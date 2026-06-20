// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Shared in-process embed logic for the mobile apps (iOS + Android).
//!
//! On iOS and Android the core cannot run as a separate daemon (no `fork`/`exec`
//! the app is suspended at the OS's discretion), so instead of *spawning* a
//! process the way the desktop does, the app links the core as a native library
//! and boots the **same** [`ReelVaultService::into_server()`] on an in-process
//! loopback gRPC listener. `VideoRepository` then connects to `127.0.0.1:<port>`
//! exactly as it connects to a remote daemon — local mode is just a different
//! endpoint, so the whole client is reused unchanged.
//!
//! This module holds everything that is platform-agnostic: the embedded-server
//! runtime holder, the catalog boot, and the ingest/prune/is-indexed bodies. The
//! per-platform FFI shims live in [`crate::ios`] (a C-ABI surface driven by Swift
//! function pointers) and [`crate::android`] (a JNI surface driven by Kotlin) —
//! both decode their platform's strings and then delegate here, so the on-device
//! catalog behaves identically on both Apple and Android.
//!
//! Compiled **only** for `target_os = "ios"` / `"android"` (gated in `lib.rs`),
//! so the desktop build never sees it and there is zero behavior change there.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

use tokio::net::TcpListener;
use tokio::runtime::{Builder, Runtime};
use tokio_stream::wrappers::TcpListenerStream;
use tonic::transport::Server;

use crate::config::Config;
use crate::db::Database;
use crate::media_backend::MediaSource;
use crate::service::ReelVaultService;

/// The live embedded server: its tokio runtime (kept alive so the spawned gRPC
/// server keeps running) and the loopback port the app connects to.
struct Embedded {
    rt: Runtime,
    port: u16,
}

/// One embedded server per process. `OnceLock<Mutex<…>>` so `start`/`stop` from
/// the app side are serialized and `start` is idempotent.
static EMBEDDED: OnceLock<Mutex<Option<Embedded>>> = OnceLock::new();

/// Handles the ingest needs (DB, thumbnail cache, catalog-event bus), captured in
/// `boot` before `db`/`config` move into the gRPC service.
struct IngestCtx {
    db: Arc<Database>,
    cache: PathBuf,
    events: tokio::sync::broadcast::Sender<crate::watcher::CatalogChange>,
}
static INGEST: OnceLock<IngestCtx> = OnceLock::new();

/// `(kind, id)` for the native media backend. `kind`: 0 = filesystem path,
/// 1 = platform media asset (iOS Photos `localIdentifier` / Android MediaStore
/// id), 2 = security-scoped bookmark (hex). The id is what the native backend
/// resolves back to a decodable source.
pub(crate) fn source_kind_and_id(src: &MediaSource) -> (i32, String) {
    match src {
        MediaSource::Path(p) => (0, p.to_string_lossy().into_owned()),
        MediaSource::PhotoAsset(id) => (1, id.clone()),
        MediaSource::Bookmark(b) => (2, b.iter().map(|x| format!("{x:02x}")).collect()),
    }
}

/// Initialize a tracing subscriber once. On iOS this writes to stderr (captured
/// by `xcrun simctl launch --console` and the device log); on Android it writes
/// to logcat (tag `ReelVault`) so the embedded core's output is visible via
/// `adb logcat` during testing.
pub(crate) fn init_logging() {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        #[cfg(target_os = "android")]
        let _ = tracing_subscriber::fmt()
            .with_writer(android_log::MakeLogcat)
            .with_ansi(false)
            .with_target(false)
            .try_init();
        #[cfg(not(target_os = "android"))]
        let _ = tracing_subscriber::fmt()
            .with_writer(std::io::stderr)
            .with_ansi(false)
            .with_target(false)
            .try_init();
    });
}

/// Minimal logcat sink for `tracing` on Android — routes the formatted log line
/// to `__android_log_write` (liblog). Avoids an extra logging dependency; the
/// `log` library is part of the NDK sysroot (`-llog`).
#[cfg(target_os = "android")]
mod android_log {
    use std::ffi::CString;
    use std::io::Write;
    use std::os::raw::c_char;

    // ANDROID_LOG_INFO = 4 (android/log.h priority levels).
    const ANDROID_LOG_INFO: i32 = 4;

    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(prio: i32, tag: *const c_char, text: *const c_char) -> i32;
    }

    /// A `Write` that emits each chunk as one logcat line under the `ReelVault` tag.
    pub struct LogcatWriter;

    impl Write for LogcatWriter {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            let len = buf.len();
            let text = String::from_utf8_lossy(buf);
            // Interior NULs would truncate the C string; replace them defensively.
            let cleaned = text.trim_end_matches('\n').replace('\0', " ");
            if let (Ok(tag), Ok(msg)) = (CString::new("ReelVault"), CString::new(cleaned)) {
                // SAFETY: both pointers are valid NUL-terminated strings for the call.
                unsafe { __android_log_write(ANDROID_LOG_INFO, tag.as_ptr(), msg.as_ptr()) };
            }
            Ok(len)
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    /// `MakeWriter` factory producing a fresh [`LogcatWriter`] per record.
    #[derive(Clone)]
    pub struct MakeLogcat;

    impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for MakeLogcat {
        type Writer = LogcatWriter;
        fn make_writer(&'a self) -> Self::Writer {
            LogcatWriter
        }
    }
}

/// Run an FFI/JNI entry-point body, catching any Rust panic so it never unwinds
/// across the native boundary — a panic that reaches C/JNI is an immediate
/// process abort (the `panic_cannot_unwind` crash on iOS; undefined behavior in
/// JNI). Logs the panic message to `tracing` AND stderr and returns `fallback`.
/// Both mobile builds use `panic = "unwind"`, so this actually catches.
pub(crate) fn ffi_guard<T>(name: &str, fallback: T, body: impl FnOnce() -> T) -> T {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(body)) {
        Ok(value) => value,
        Err(payload) => {
            let msg = payload
                .downcast_ref::<&str>()
                .map(|s| (*s).to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "panic with non-string payload".to_string());
            tracing::error!("ffi: {name} panicked (caught — app NOT aborted): {msg}");
            eprintln!("ReelVault FFI: {name} panicked (caught): {msg}");
            fallback
        }
    }
}

/// Build the runtime, open the catalog, and spawn the in-process gRPC server.
/// Returns the runtime (to park in the static) and the bound loopback port.
fn boot(db_path: PathBuf, data_dir: PathBuf, cache_dir: PathBuf) -> anyhow::Result<(Runtime, u16)> {
    let rt = Builder::new_multi_thread().enable_all().build()?;
    // Setup runs on the runtime so the `tokio::spawn`s inside `new()` and the
    // server have a reactor; the server task keeps running after `block_on`.
    let port = rt.block_on(async move {
        let db = std::sync::Arc::new(Database::new(&db_path)?);
        db.initialize().await?;

        let mut config = Config::load(db.as_ref()).await?;
        // On mobile, trust the app-supplied container path over `dirs`.
        config.thumbnail_cache_path = cache_dir;
        // The `notify` recursive watcher doesn't map to the Photos/MediaStore
        // sandbox; live updates come from the platform change observer. Keep it
        // off so `new()` doesn't boot the desktop watcher.
        config.watch_enabled = false;
        let config = std::sync::Arc::new(config);

        // Capture handles the ingest needs before `db`/`config` move into the
        // service.
        let ingest_db = Arc::clone(&db);
        let ingest_cache = config.thumbnail_cache_path.clone();

        // Pairing is a LAN concept; loopback needs none. A fresh empty cell
        // satisfies `new()`'s signature without enabling anything.
        let pairing = crate::pairing::new_state();
        let service = ReelVaultService::new(db, config, pairing, data_dir);

        let _ = INGEST.set(IngestCtx {
            db: ingest_db,
            cache: ingest_cache,
            events: service.catalog_events(),
        });

        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let port = listener.local_addr()?.port();
        tokio::spawn(async move {
            if let Err(e) = Server::builder()
                .add_service(service.into_server())
                .serve_with_incoming(TcpListenerStream::new(listener))
                .await
            {
                tracing::error!("embedded gRPC server exited: {e}");
            }
        });
        Ok::<u16, anyhow::Error>(port)
    })?;
    Ok((rt, port))
}

/// Boot the embedded server over the app-container catalog and return the bound
/// loopback port, or `0` on error. Idempotent: a second call returns the same
/// port. The three arguments are app-container paths supplied by the app (the
/// SQLite catalog file, the data dir, and the thumbnail cache dir).
pub(crate) fn embed_start(db_path: PathBuf, data_dir: PathBuf, cache_dir: PathBuf) -> u16 {
    let cell = EMBEDDED.get_or_init(|| Mutex::new(None));
    let mut guard = match cell.lock() {
        Ok(g) => g,
        Err(_) => return 0,
    };
    if let Some(existing) = guard.as_ref() {
        return existing.port; // already booted
    }
    init_logging();
    // Select the rustls crypto provider before any TLS work — the post-index
    // sensor fetch (reqwest) builds a rustls 0.23 ClientConfig that otherwise
    // panics (both aws-lc-rs and ring are compiled in).
    crate::install_crypto_provider();
    // Android has no $HOME in the app sandbox, so `dirs` can't resolve a cache
    // dir during Config::load — point it at the app-supplied cache dir. (Config
    // then overrides thumbnail_cache_path with this same path right after load.)
    #[cfg(target_os = "android")]
    std::env::set_var("XDG_CACHE_HOME", &cache_dir);
    tracing::info!("embed_start: booting embedded core");
    match boot(db_path, data_dir, cache_dir) {
        Ok((rt, port)) => {
            tracing::info!("embed_start: serving on 127.0.0.1:{port}");
            *guard = Some(Embedded { rt, port });
            port
        }
        Err(e) => {
            tracing::error!("embed_start failed: {e}");
            0
        }
    }
}

/// Park the embedded server when the app is suspended. Shuts the runtime down in
/// the background (non-blocking) so the next foreground `embed_start` boots a
/// fresh one. Safe to call when nothing is running.
pub(crate) fn embed_stop() {
    if let Some(cell) = EMBEDDED.get() {
        if let Ok(mut guard) = cell.lock() {
            if let Some(embedded) = guard.take() {
                embedded.rt.shutdown_background();
            }
        }
    }
}

/// Ingest one platform media asset (iOS Photos asset / Android MediaStore video)
/// into the on-device catalog. `local_id` is the platform asset id; `filename` is
/// its display name (None → `{local_id}.mov`). Probes + thumbnails through the
/// native backend, upserts a `photos://<local_id>` row, and publishes a live
/// `VideoAdded`. Returns 0 on success, negative on error (-1 server not booted,
/// -2 invalid id, -3 indexing failed).
pub(crate) fn ingest_asset(local_id: String, filename: Option<String>) -> i32 {
    let ctx = match INGEST.get() {
        Some(c) => c,
        None => return -1,
    };
    if local_id.is_empty() {
        return -2;
    }
    let filename = filename.unwrap_or_else(|| format!("{local_id}.mov"));
    let source = MediaSource::PhotoAsset(local_id.clone());
    let display_path = format!("photos://{local_id}");

    match crate::indexing::IndexingEngine::index_media_source(
        ctx.db.as_ref(),
        &source,
        &display_path,
        &filename,
        "photo",
        &local_id,
        &ctx.cache,
    ) {
        Ok(video_id) => {
            let _ = ctx.events.send(crate::watcher::CatalogChange::VideoAdded {
                video_id,
                path: PathBuf::from(display_path),
            });
            0
        }
        Err(e) => {
            tracing::warn!("ingest_asset({local_id}) failed: {e}");
            -3
        }
    }
}

/// Ingest one filesystem video (container / SAF-copied path) into the on-device
/// catalog. `path` is the file path; `filename` its display name (None →
/// basename). Returns 0 on success, negative on error.
pub(crate) fn ingest_path(path: String, filename: Option<String>) -> i32 {
    let ctx = match INGEST.get() {
        Some(c) => c,
        None => return -1,
    };
    if path.is_empty() {
        return -2;
    }
    let filename = filename.unwrap_or_else(|| {
        Path::new(&path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("video")
            .to_string()
    });
    let source = MediaSource::Path(PathBuf::from(&path));
    let file_size = std::fs::metadata(&path).map(|m| m.len() as i64).ok();

    match crate::indexing::IndexingEngine::index_media_source(
        ctx.db.as_ref(),
        &source,
        &path,
        &filename,
        "path",
        &path,
        &ctx.cache,
    ) {
        Ok(video_id) => {
            // index_media_source doesn't know the on-disk size; record it for
            // filesystem sources (asset rows stay NULL — no stable path).
            if let Some(sz) = file_size {
                let _ = ctx.db.update_video_file_size(&video_id, sz);
            }
            let _ = ctx.events.send(crate::watcher::CatalogChange::VideoAdded {
                video_id,
                path: PathBuf::from(path),
            });
            0
        }
        Err(e) => {
            tracing::warn!("ingest_path({path}) failed: {e}");
            -3
        }
    }
}

/// Ingest one video via a security-scoped bookmark (iOS Files-app). `bytes` are
/// the raw bookmark bytes; the row is keyed on `bookmark://<hex>`. Returns 0 on
/// success, negative on error.
///
/// Only the iOS surface uses this (Android has no bookmark source), so it reads
/// as dead code on the Android target — allow it.
#[allow(dead_code)]
pub(crate) fn ingest_bookmark(bytes: Vec<u8>, filename: Option<String>) -> i32 {
    let ctx = match INGEST.get() {
        Some(c) => c,
        None => return -1,
    };
    if bytes.is_empty() {
        return -2;
    }
    let hex_id: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    let display_path = format!("bookmark://{hex_id}");
    let filename = filename.unwrap_or_else(|| "video".to_string());
    let source = MediaSource::Bookmark(bytes);

    match crate::indexing::IndexingEngine::index_media_source(
        ctx.db.as_ref(),
        &source,
        &display_path,
        &filename,
        "bookmark",
        &hex_id,
        &ctx.cache,
    ) {
        Ok(video_id) => {
            let _ = ctx.events.send(crate::watcher::CatalogChange::VideoAdded {
                video_id,
                path: PathBuf::from(display_path),
            });
            0
        }
        Err(e) => {
            tracing::warn!("ingest_bookmark failed: {e}");
            -3
        }
    }
}

/// Ingest a synced (derived / downscaled) video from a peer into the on-device
/// catalog, stamping its provenance. `path` is the local file path (the file
/// must already be downloaded); `filename` is its display name; `origin_hash`
/// is the sparse blake3 hash of the peer's original; `derived_height` is the
/// height of this downscaled copy (0 if it is the original).
/// Returns 0 on success, negative on error (-1 not booted, -2 bad args, -3 failed).
pub(crate) fn ingest_synced(path: &str, filename: &str, origin_hash: &str, derived_height: i32) -> i32 {
    let ctx = match INGEST.get() {
        Some(c) => c,
        None => return -1,
    };
    if path.is_empty() {
        return -2;
    }
    let filename_owned = if filename.is_empty() {
        Path::new(path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("video")
            .to_string()
    } else {
        filename.to_string()
    };
    let source = crate::media_backend::MediaSource::Path(PathBuf::from(path));

    match crate::indexing::IndexingEngine::index_media_source(
        ctx.db.as_ref(),
        &source,
        path,
        &filename_owned,
        "path",
        path,
        &ctx.cache,
    ) {
        Ok(video_id) => {
            // Stamp provenance: mark as derived and record the origin hash.
            let db = &ctx.db;
            let oh = origin_hash.to_string();
            let dh: Option<i32> = if derived_height > 0 { Some(derived_height) } else { None };
            if let Ok(conn) = db.get_connection() {
                if let Err(e) = conn.execute(
                    "UPDATE videos SET is_derived = 1, origin_hash = ?1 WHERE id = ?2",
                    rusqlite::params![oh, &video_id],
                ) {
                    tracing::warn!("ingest_synced: could not stamp provenance for {video_id}: {e}");
                }
                // Also update video_locations if it exists.
                let _ = conn.execute(
                    "UPDATE video_locations SET file_size_bytes = COALESCE((SELECT length_via_stat), file_size_bytes) WHERE video_id = ?1",
                    rusqlite::params![&video_id],
                );
                let _ = dh; // suppress unused warning when no derived_height
            }
            let _ = ctx.events.send(crate::watcher::CatalogChange::VideoAdded {
                video_id,
                path: PathBuf::from(path),
            });
            0
        }
        Err(e) => {
            tracing::warn!("ingest_synced({path}) failed: {e}");
            -3
        }
    }
}

/// Has `display_path` already been fully cataloged (row + metadata)? Lets the app
/// enumerators skip re-probing already-indexed assets so a relaunch over an
/// unchanged library is near-instant. Returns 1 if fully indexed, 0 if not,
/// negative on error (-1 server not booted, -2 invalid path, -3 database error).
pub(crate) fn is_indexed(display_path: String) -> i32 {
    let ctx = match INGEST.get() {
        Some(c) => c,
        None => return -1,
    };
    if display_path.is_empty() {
        return -2;
    }
    // Require metadata, not just a row: a row added by a probe-then-failed
    // ingest must be re-ingested, not skipped.
    match ctx.db.is_fully_indexed(&display_path) {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(_) => -3,
    }
}

/// Reconcile the on-device catalog against the platform media library: remove
/// every `source_kind = 'photo'` row whose `source_id` is NOT in
/// `present_ids_json` (a JSON array of the asset ids currently present). Each
/// removal publishes a `VideoRemoved`. The caller MUST pass a COMPLETE present
/// set — a partial set would delete everything outside it. Returns the number
/// removed, or negative on error (-1 not booted, -2 invalid JSON, -3 db error).
pub(crate) fn prune_photos(present_ids_json: String) -> i32 {
    let ctx = match INGEST.get() {
        Some(c) => c,
        None => return -1,
    };
    let present: std::collections::HashSet<String> =
        match serde_json::from_str::<Vec<String>>(&present_ids_json) {
            Ok(v) => v.into_iter().collect(),
            Err(_) => return -2,
        };
    let rows = match ctx.db.list_videos_by_source_kind("photo") {
        Ok(r) => r,
        Err(e) => {
            tracing::warn!("prune_photos: list failed: {e}");
            return -3;
        }
    };
    let mut removed = 0;
    for (video_id, source_id) in rows {
        if present.contains(&source_id) {
            continue;
        }
        if ctx.db.delete_video(&video_id).is_ok() {
            let _ = ctx.events.send(crate::watcher::CatalogChange::VideoRemoved {
                video_id: Some(video_id),
                path: PathBuf::from(format!("photos://{source_id}")),
            });
            removed += 1;
        }
    }
    if removed > 0 {
        tracing::info!("prune_photos: removed {removed} deleted asset(s)");
    }
    removed
}
