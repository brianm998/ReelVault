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

use std::ffi::{c_char, CStr};
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

use tokio::net::TcpListener;
use tokio::runtime::{Builder, Runtime};
use tokio_stream::wrappers::TcpListenerStream;
use tonic::transport::Server;

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

        // Pairing is a LAN concept; loopback needs none. A fresh empty cell
        // satisfies `new()`'s signature without enabling anything (§7.2).
        let pairing = crate::pairing::new_state();
        let service = ReelVaultService::new(db, config, pairing, data_dir);

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
    let (db_path, data_dir, cache_dir) =
        match unsafe { (cpath(db_path), cpath(data_dir), cpath(cache_dir)) } {
            (Some(a), Some(b), Some(c)) => (a, b, c),
            _ => return 0,
        };
    match boot(db_path, data_dir, cache_dir) {
        Ok((rt, port)) => {
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
