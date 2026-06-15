// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! In-process embed entry point for the iOS app (docs/IOS_CORE_PORT.md §7).
//!
//! On iOS the core cannot run as a separate daemon (no `fork`/`exec`, the app is
//! suspended at the OS's discretion), so instead of *spawning* a process the way
//! macOS does (`macos/.../ServerLauncher.swift`), the app links the core as a
//! static library and boots the **same** `ReelVaultService::into_server()` on an
//! in-process loopback gRPC listener. `VideoRepository` then connects to
//! `127.0.0.1:<port>` exactly as it connects to a remote daemon — local mode is
//! just a different endpoint, so the whole client (`kit/`) is reused unchanged.
//!
//! This module is compiled **only** for `target_os = "ios"` (gated in `lib.rs`),
//! so the desktop build never sees it and there is zero behavior change there.
//!
//! Phase 0 scaffold: this boots the server over the app-container catalog and
//! returns the bound port. The media pipeline still routes through the CLI
//! backend, which doesn't work in the iOS sandbox — that's replaced by the
//! native `MediaBackend` in later phases. So the embedded server answers catalog
//! RPCs (`GetStatus`, `ListVideos`, …) today; on-device metadata/thumbnail/proxy
//! generation lands with Phases 1–2.

use std::ffi::{c_char, CStr, CString};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use tokio::net::TcpListener;
use tokio::runtime::{Builder, Runtime};
use tokio_stream::wrappers::TcpListenerStream;
use tonic::transport::Server;

use crate::error::{ReelVaultError, Result};
use crate::media_backend::{set_backend, MediaBackend, MediaSource};
use crate::metadata::FFProbeOutput;
use crate::thumbnails::ColorInfo;

use crate::config::Config;
use crate::db::Database;
use crate::service::ReelVaultService;

/// The live embedded server: its tokio runtime (kept alive so the spawned gRPC
/// server keeps running) and the loopback port the app connects to.
struct Embedded {
    rt: Runtime,
    port: u16,
}

/// One embedded server per process. `OnceLock<Mutex<…>>` so `start`/`stop` from
/// the Swift side are serialized and `start` is idempotent.
static EMBEDDED: OnceLock<Mutex<Option<Embedded>>> = OnceLock::new();

/// Handles the photo-ingest FFI needs, captured in `boot` before `db`/`config`
/// move into the gRPC service.
struct IngestCtx {
    db: Arc<Database>,
    cache: PathBuf,
    events: tokio::sync::broadcast::Sender<crate::watcher::CatalogChange>,
}
static INGEST: OnceLock<IngestCtx> = OnceLock::new();

/// Borrow a C string as an owned `String`, or `None` if null / not UTF-8.
///
/// # Safety
/// `p` must be null or a valid NUL-terminated C string for the call's duration.
unsafe fn cstring(p: *const c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    CStr::from_ptr(p).to_str().ok().map(str::to_owned)
}

/// Borrow a C string as an owned `PathBuf`, or `None` if null / not UTF-8.
///
/// # Safety
/// `p` must be null or a valid NUL-terminated C string for the duration of the
/// call (it is — Swift passes container paths that outlive the call).
unsafe fn cpath(p: *const c_char) -> Option<PathBuf> {
    if p.is_null() {
        return None;
    }
    CStr::from_ptr(p).to_str().ok().map(PathBuf::from)
}

/// Initialize a tracing subscriber writing to stderr, once. On iOS stderr is
/// captured by `xcrun simctl launch --console` and the device log, so the
/// embedded core's `tracing` output is visible during simulator/device testing.
fn init_logging() {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        let _ = tracing_subscriber::fmt()
            .with_writer(std::io::stderr)
            .with_ansi(false)
            .with_target(false)
            .try_init();
    });
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
        // On iOS, trust the Swift-supplied container path over `dirs`
        // (docs/IOS_CORE_PORT.md §6.10 / §11.4).
        config.thumbnail_cache_path = cache_dir;
        // The `notify` recursive watcher doesn't map to the Photos/Files sandbox;
        // live updates come from PHPhotoLibraryChangeObserver in a later phase
        // (§6.8). Keep it off so `new()` doesn't boot the desktop watcher.
        config.watch_enabled = false;
        let config = std::sync::Arc::new(config);

        // Capture handles the photo-ingest FFI needs before `db`/`config` move
        // into the service.
        let ingest_db = Arc::clone(&db);
        let ingest_cache = config.thumbnail_cache_path.clone();

        // Pairing is a LAN concept; loopback needs none. A fresh empty cell
        // satisfies `new()`'s signature without enabling anything (§7.2).
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
/// port. Swift then calls `VideoRepository.shared.connect(to: .loopback(port:))`.
///
/// All three arguments are app-container paths supplied by Swift (don't trust
/// `dirs` on iOS): the SQLite catalog file, the data dir (TLS/pairing state —
/// unused on loopback but required by `new()`), and the thumbnail cache dir.
///
/// # Safety
/// The three pointers must be null or valid NUL-terminated C strings.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)] // FFI boundary; pointers validated via `cpath`
pub extern "C" fn reelvault_start_embedded(
    db_path: *const c_char,
    data_dir: *const c_char,
    cache_dir: *const c_char,
) -> u16 {
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
    // panics (both aws-lc-rs and ring are compiled in). See
    // `crate::install_crypto_provider`.
    crate::install_crypto_provider();
    tracing::info!("reelvault_start_embedded: booting embedded core");
    let (db_path, data_dir, cache_dir) =
        match unsafe { (cpath(db_path), cpath(data_dir), cpath(cache_dir)) } {
            (Some(a), Some(b), Some(c)) => (a, b, c),
            _ => return 0,
        };
    match boot(db_path, data_dir, cache_dir) {
        Ok((rt, port)) => {
            tracing::info!("reelvault_start_embedded: serving on 127.0.0.1:{port}");
            *guard = Some(Embedded { rt, port });
            port
        }
        Err(e) => {
            tracing::error!("reelvault_start_embedded failed: {e}");
            0
        }
    }
}

/// Park the embedded server when the app is suspended. Shuts the runtime down in
/// the background (non-blocking) so the next foreground `reelvault_start_embedded`
/// boots a fresh one. Safe to call when nothing is running.
#[no_mangle]
pub extern "C" fn reelvault_stop_embedded() {
    if let Some(cell) = EMBEDDED.get() {
        if let Ok(mut guard) = cell.lock() {
            if let Some(embedded) = guard.take() {
                embedded.rt.shutdown_background();
            }
        }
    }
}

// ===========================================================================
// Phase 2 — native media backend (AVFoundation via Swift callbacks)
//
// Rust can't call Swift directly, so the app registers a table of C function
// pointers (`reelvault_register_media_backend`) that `NativeMediaBackend`
// invokes for the media work the desktop core shells out to ffmpeg for
// (docs/IOS_CORE_PORT.md §6.2). Swift implements them with AVFoundation /
// VideoToolbox.
// ===========================================================================

/// C-ABI callbacks the Swift side provides. `kind`: 0 = filesystem path,
/// 1 = Photos asset (PHAsset localIdentifier), 2 = security-scoped bookmark
/// (hex). All strings are NUL-terminated UTF-8.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct NativeMediaCallbacks {
    /// Return ffprobe-shaped JSON (a `FFProbeOutput`) as a heap C string, or
    /// NULL on failure. Rust hands the pointer back to `free_string`.
    pub probe: extern "C" fn(kind: i32, src_id: *const c_char) -> *mut c_char,
    /// Decode one frame at `time_secs`, longest side <= `max_px`, and write a
    /// JPEG to `out_path`. Returns 0 on success.
    pub extract_frame: extern "C" fn(
        kind: i32,
        src_id: *const c_char,
        time_secs: f64,
        max_px: i32,
        out_path: *const c_char,
    ) -> i32,
    /// Transcode an H.264/AAC proxy at `target_height` to `out_path`. 0 = ok.
    pub transcode_proxy:
        extern "C" fn(kind: i32, src_id: *const c_char, out_path: *const c_char, target_height: i32) -> i32,
    /// Free a string previously returned by `probe`.
    pub free_string: extern "C" fn(*mut c_char),
}

impl MediaSource {
    /// `(kind, id)` for the FFI callbacks (see [`NativeMediaCallbacks`]).
    fn ffi_parts(&self) -> (i32, CString) {
        let (kind, s) = match self {
            MediaSource::Path(p) => (0, p.to_string_lossy().into_owned()),
            MediaSource::PhotoAsset(id) => (1, id.clone()),
            MediaSource::Bookmark(b) => (2, b.iter().map(|x| format!("{x:02x}")).collect()),
        };
        (kind, CString::new(s).unwrap_or_default())
    }
}

/// `MediaBackend` backed by the Swift AVFoundation callbacks. Function pointers
/// are `Send + Sync`, so this is safe to share as `Arc<dyn MediaBackend>`.
struct NativeMediaBackend {
    cb: NativeMediaCallbacks,
}

impl MediaBackend for NativeMediaBackend {
    fn probe(&self, src: &MediaSource) -> Result<FFProbeOutput> {
        let (kind, id) = src.ffi_parts();
        // Share the global throttle with the CLI backend so concurrent native
        // decode/encode sessions stay within the (mobile-tuned) cap (§6.11).
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        let ptr = (self.cb.probe)(kind, id.as_ptr());
        if ptr.is_null() {
            return Err(ReelVaultError::MetadataExtractionFailed(
                "native probe returned null".into(),
            ));
        }
        // SAFETY: Swift returns a valid NUL-terminated string or NULL (checked).
        let json = unsafe { CStr::from_ptr(ptr) }.to_string_lossy().into_owned();
        (self.cb.free_string)(ptr);
        serde_json::from_str(&json)
            .map_err(|e| ReelVaultError::MetadataExtractionFailed(format!("native probe JSON: {e}")))
    }

    fn probe_color(&self, _src: &MediaSource) -> ColorInfo {
        // The native extract_frame handles color/HDR itself, so the CLI-style
        // ColorInfo (used only to build an ffmpeg -vf chain) isn't needed.
        ColorInfo::default()
    }

    fn extract_loudness(&self, _src: &MediaSource) -> Vec<f32> {
        // Audio-loudness waveform via AVAssetReader is a later refinement
        // (docs/IOS_CORE_PORT.md §11.3); empty = no waveform for now.
        Vec::new()
    }

    fn extract_frame(
        &self,
        src: &MediaSource,
        _color: &ColorInfo,
        time_secs: f64,
        max_px: i32,
        _quality: u8,
        out: &std::path::Path,
    ) -> Result<()> {
        let (kind, id) = src.ffi_parts();
        let out_c = CString::new(out.to_string_lossy().as_bytes())
            .map_err(|_| ReelVaultError::InvalidPath("out path has interior NUL".into()))?;
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        let rc = (self.cb.extract_frame)(kind, id.as_ptr(), time_secs, max_px, out_c.as_ptr());
        if rc == 0 {
            Ok(())
        } else {
            Err(ReelVaultError::ThumbnailGenerationFailed(format!(
                "native extract_frame rc={rc}"
            )))
        }
    }

    fn transcode_proxy(
        &self,
        src: &MediaSource,
        out: &std::path::Path,
        target_height: i32,
        _total_frames: i64,
        progress: &mut dyn FnMut(f64),
    ) -> Result<()> {
        let (kind, id) = src.ffi_parts();
        let out_c = CString::new(out.to_string_lossy().as_bytes())
            .map_err(|_| ReelVaultError::InvalidPath("out path has interior NUL".into()))?;
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        let rc = (self.cb.transcode_proxy)(kind, id.as_ptr(), out_c.as_ptr(), target_height);
        // AVAssetExportSession doesn't surface granular progress here yet; jump
        // to the top of the encode band on completion.
        progress(85.0);
        if rc == 0 {
            Ok(())
        } else {
            Err(ReelVaultError::FfmpegError(format!(
                "native transcode_proxy rc={rc}"
            )))
        }
    }

    // Catalog-only on iOS (D4): you can't rewrite a Photos original in place.
    fn write_creation_time(&self, _src: &MediaSource, _timestamp_ms: i64) -> Result<()> {
        Ok(())
    }
    fn write_location(&self, _src: &MediaSource, _lat: f64, _lon: f64, _alt: f64) -> Result<()> {
        Ok(())
    }
}

/// Install the native (AVFoundation) media backend. Swift calls this once at
/// startup, BEFORE the core does any media work, so it wins the `set_backend`
/// race over the default CLI backend (which can't run in the iOS sandbox).
#[no_mangle]
pub extern "C" fn reelvault_register_media_backend(callbacks: NativeMediaCallbacks) {
    init_logging();
    // Swift may register the backend (and trigger media work) before
    // `reelvault_start_embedded`; install the rustls provider here too. Idempotent.
    crate::install_crypto_provider();
    set_backend(Arc::new(NativeMediaBackend { cb: callbacks }));
    tracing::info!("native media backend registered");
}

/// Ingest one Photos video into the on-device catalog (docs/IOS_CORE_PORT.md
/// §6.9). Swift's PhotoKit enumerator calls this once per `PHAsset`
/// (`local_id` = its localIdentifier, `filename` = the original filename) after
/// the embedded server is up. Probes + thumbnails the asset through the native
/// backend, upserts a `photos://<local_id>` row, and publishes a live
/// `VideoAdded` so the grid refreshes. Returns 0 on success, negative on error.
///
/// Runs synchronously on the caller's (background) thread and re-enters Swift
/// via the media callbacks — so call it off the main thread.
///
/// # Safety
/// `local_id` / `filename` must be NUL-terminated C strings (filename may be null).
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)] // FFI boundary; pointers validated via `cstring`
pub extern "C" fn reelvault_ingest_photo(
    local_id: *const c_char,
    filename: *const c_char,
) -> i32 {
    let ctx = match INGEST.get() {
        Some(c) => c,
        None => return -1, // server not booted yet
    };
    let local_id = match unsafe { cstring(local_id) } {
        Some(s) if !s.is_empty() => s,
        _ => return -2,
    };
    let filename =
        unsafe { cstring(filename) }.unwrap_or_else(|| format!("{local_id}.mov"));
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
            tracing::warn!("reelvault_ingest_photo({local_id}) failed: {e}");
            -3
        }
    }
}

/// Ingest one filesystem video into the on-device catalog — the Files-app /
/// security-scoped-bookmark and container path (docs/IOS_CORE_PORT.md D7
/// "Files second"). Probes + thumbnails through the native backend (AVURLAsset)
/// and adds a row keyed on the path. Returns 0 on success, negative on error.
///
/// # Safety
/// `path` / `filename` must be NUL-terminated C strings (filename may be null).
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)] // FFI boundary; pointers validated via `cstring`
pub extern "C" fn reelvault_ingest_path(path: *const c_char, filename: *const c_char) -> i32 {
    let ctx = match INGEST.get() {
        Some(c) => c,
        None => return -1,
    };
    let path = match unsafe { cstring(path) } {
        Some(s) if !s.is_empty() => s,
        _ => return -2,
    };
    let filename = unsafe { cstring(filename) }.unwrap_or_else(|| {
        std::path::Path::new(&path)
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
            // filesystem sources (Photos rows stay NULL — no stable path).
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
            tracing::warn!("reelvault_ingest_path({path}) failed: {e}");
            -3
        }
    }
}

/// Has `display_path` already been cataloged? Lets the Swift enumerators skip
/// re-probing assets that are already indexed, so a relaunch over an unchanged
/// library is near-instant instead of re-running the (expensive) AVFoundation
/// probe + thumbnail for every asset. `display_path` is the same string the
/// matching `reelvault_ingest_*` would use (`photos://<localId>` or a file
/// path). Returns 1 if a row exists, 0 if not, negative on error. A missing
/// thumbnail for an already-indexed row is regenerated on demand by
/// `GetThumbnail`, so skipping here can't strand a row without a preview.
///
/// # Safety
/// `display_path` must be a NUL-terminated C string.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)] // FFI boundary; pointer validated via `cstring`
pub extern "C" fn reelvault_is_video_indexed(display_path: *const c_char) -> i32 {
    let ctx = match INGEST.get() {
        Some(c) => c,
        None => return -1,
    };
    let path = match unsafe { cstring(display_path) } {
        Some(s) if !s.is_empty() => s,
        _ => return -2,
    };
    match ctx.db.get_video_by_path(&path) {
        Ok(Some(_)) => 1,
        Ok(None) => 0,
        Err(_) => -3,
    }
}

/// Ingest one Files-app video via a security-scoped bookmark (D7 "Files
/// second"). Swift resolves the document-picker URL, captures a bookmark
/// (`bookmark`/`len` raw bytes) and the display `filename` (may be NULL). The
/// row is keyed on `bookmark://<hex>` (mirrors `photos://<id>`: the filename is
/// what the grid shows, while the path carries the bookmark so the native
/// backend and on-device playback can both re-resolve the scoped URL).
/// source_kind="bookmark", source_id=hex(bookmark). 0 on success, negative on
/// error.
///
/// Note: bookmark bytes aren't stable for a given file, so re-importing the
/// same file makes a new row (no path-based dedup) — acceptable for v1.
///
/// # Safety
/// `bookmark` points to `len` readable bytes; `filename` is a NUL-terminated C
/// string or NULL.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)] // FFI boundary; pointers validated below
pub extern "C" fn reelvault_ingest_bookmark(
    bookmark: *const u8,
    len: usize,
    filename: *const c_char,
) -> i32 {
    let ctx = match INGEST.get() {
        Some(c) => c,
        None => return -1,
    };
    if bookmark.is_null() || len == 0 {
        return -2;
    }
    // SAFETY: caller guarantees `bookmark` points to `len` readable bytes.
    let bytes = unsafe { std::slice::from_raw_parts(bookmark, len) }.to_vec();
    let hex_id: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    let display_path = format!("bookmark://{hex_id}");
    let filename = unsafe { cstring(filename) }.unwrap_or_else(|| "video".to_string());
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
            tracing::warn!("reelvault_ingest_bookmark failed: {e}");
            -3
        }
    }
}
