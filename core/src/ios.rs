// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! C-ABI embed surface for the iOS app (docs/IOS_CORE_PORT.md §7).
//!
//! The platform-agnostic embed logic (booting the in-process loopback gRPC
//! server, ingest, prune, is-indexed) lives in [`crate::embed`], shared with the
//! Android JNI surface ([`crate::android`]). This module is the iOS-specific
//! half: it decodes the C strings Swift passes, exposes the `#[no_mangle] extern
//! "C"` entry points, and implements the native [`MediaBackend`] over a table of
//! Swift function pointers (`NativeMediaCallbacks`) — Rust can't call Swift
//! directly, so the app registers callbacks the core invokes for the media work
//! the desktop core shells out to ffmpeg for (docs/IOS_CORE_PORT.md §6.2).
//!
//! Compiled **only** for `target_os = "ios"` (gated in `lib.rs`).

use std::ffi::{c_char, CStr, CString};
use std::path::PathBuf;
use std::sync::Arc;

use crate::embed;
use crate::error::{ReelVaultError, Result};
use crate::media_backend::{set_backend, MediaBackend, MediaSource};
use crate::metadata::FFProbeOutput;
use crate::thumbnails::ColorInfo;

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
    embed::ffi_guard("start_embedded", 0, || {
        let (db_path, data_dir, cache_dir) =
            match unsafe { (cpath(db_path), cpath(data_dir), cpath(cache_dir)) } {
                (Some(a), Some(b), Some(c)) => (a, b, c),
                _ => return 0,
            };
        embed::embed_start(db_path, data_dir, cache_dir)
    })
}

/// Park the embedded server when the app is suspended. Shuts the runtime down in
/// the background (non-blocking) so the next foreground `reelvault_start_embedded`
/// boots a fresh one. Safe to call when nothing is running.
#[no_mangle]
pub extern "C" fn reelvault_stop_embedded() {
    embed::ffi_guard("stop_embedded", (), embed::embed_stop)
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
    /// Compute an audio loudness-over-time envelope (one normalized 0..1 value
    /// per time slice). Writes up to `max_samples` little-endian f32 into `out`
    /// and returns the count written, or -1 on failure / no audio track. `out`
    /// has capacity `max_samples`.
    pub extract_loudness:
        extern "C" fn(kind: i32, src_id: *const c_char, out: *mut f32, max_samples: i32) -> i32,
    /// Free a string previously returned by `probe`.
    pub free_string: extern "C" fn(*mut c_char),
}

impl MediaSource {
    /// `(kind, id)` for the FFI callbacks (see [`NativeMediaCallbacks`]).
    fn ffi_parts(&self) -> (i32, CString) {
        let (kind, s) = embed::source_kind_and_id(self);
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

    fn extract_loudness(&self, src: &MediaSource) -> Vec<f32> {
        // Native loudness via AVAssetReader (the Swift side reads the audio
        // track's PCM, computes a per-window RMS envelope, normalizes to 0..1 —
        // the same shape the desktop ffmpeg path produces for the detail graph).
        const MAX: usize = 480;
        let (kind, id) = src.ffi_parts();
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        let mut buf = vec![0f32; MAX];
        let n = (self.cb.extract_loudness)(kind, id.as_ptr(), buf.as_mut_ptr(), MAX as i32);
        if n <= 0 {
            return Vec::new();
        }
        buf.truncate((n as usize).min(MAX));
        buf
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
    embed::ffi_guard("register_media_backend", (), || {
        embed::init_logging();
        // Swift may register the backend (and trigger media work) before
        // `reelvault_start_embedded`; install the rustls provider here too. Idempotent.
        crate::install_crypto_provider();
        set_backend(Arc::new(NativeMediaBackend { cb: callbacks }));
        tracing::info!("native media backend registered");
    })
}

/// Ingest one Photos video into the on-device catalog (docs/IOS_CORE_PORT.md
/// §6.9). Swift's PhotoKit enumerator calls this once per `PHAsset`
/// (`local_id` = its localIdentifier, `filename` = the original filename) after
/// the embedded server is up. Returns 0 on success, negative on error.
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
    embed::ffi_guard("ingest_photo", -99, || {
        let local_id = match unsafe { cstring(local_id) } {
            Some(s) if !s.is_empty() => s,
            _ => return -2,
        };
        embed::ingest_asset(local_id, unsafe { cstring(filename) })
    })
}

/// Ingest one filesystem video into the on-device catalog — the Files-app
/// container path (docs/IOS_CORE_PORT.md D7 "Files second"). Returns 0 on
/// success, negative on error.
///
/// # Safety
/// `path` / `filename` must be NUL-terminated C strings (filename may be null).
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)] // FFI boundary; pointers validated via `cstring`
pub extern "C" fn reelvault_ingest_path(path: *const c_char, filename: *const c_char) -> i32 {
    embed::ffi_guard("ingest_path", -99, || {
        let path = match unsafe { cstring(path) } {
            Some(s) if !s.is_empty() => s,
            _ => return -2,
        };
        embed::ingest_path(path, unsafe { cstring(filename) })
    })
}

/// Has `display_path` already been cataloged (row + metadata)? Lets the Swift
/// enumerators skip re-probing assets that are already indexed. `display_path` is
/// the same string the matching `reelvault_ingest_*` would use
/// (`photos://<localId>` or a file path). Returns 1 if fully indexed, 0 if not,
/// negative on error.
///
/// # Safety
/// `display_path` must be a NUL-terminated C string.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)] // FFI boundary; pointer validated via `cstring`
pub extern "C" fn reelvault_is_video_indexed(display_path: *const c_char) -> i32 {
    embed::ffi_guard("is_video_indexed", -99, || {
        let path = match unsafe { cstring(display_path) } {
            Some(s) if !s.is_empty() => s,
            _ => return -2,
        };
        embed::is_indexed(path)
    })
}

/// Ingest one Files-app video via a security-scoped bookmark (D7 "Files
/// second"). The row is keyed on `bookmark://<hex>`. 0 on success, negative on
/// error.
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
    embed::ffi_guard("ingest_bookmark", -99, || {
        if bookmark.is_null() || len == 0 {
            return -2;
        }
        // SAFETY: caller guarantees `bookmark` points to `len` readable bytes.
        let bytes = unsafe { std::slice::from_raw_parts(bookmark, len) }.to_vec();
        embed::ingest_bookmark(bytes, unsafe { cstring(filename) })
    })
}

/// Ingest a synced (derived/downscaled) video into the on-device catalog,
/// stamping its provenance. `path` is the local file path (must already be
/// downloaded); `filename` is its display name (may be NULL); `origin_hash`
/// is the blake3 hash of the peer original (may be NULL); `derived_height`
/// is the height of this copy (0 if it is the original). Returns 0 on
/// success, negative on error.
///
/// # Safety
/// `path`, `filename`, and `origin_hash` must be NUL-terminated C strings
/// (filename and origin_hash may be null).
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn reelvault_ingest_synced(
    path: *const std::os::raw::c_char,
    filename: *const std::os::raw::c_char,
    origin_hash: *const std::os::raw::c_char,
    derived_height: i32,
) -> i32 {
    embed::ffi_guard("reelvault_ingest_synced", -99, || {
        let path = match unsafe { cstring(path) } {
            Some(s) if !s.is_empty() => s,
            _ => return -2,
        };
        let filename = unsafe { cstring(filename) }.unwrap_or_default();
        let origin_hash = unsafe { cstring(origin_hash) }.unwrap_or_default();
        embed::ingest_synced(&path, &filename, &origin_hash, derived_height)
    })
}

/// Reconcile the on-device catalog against the Photos library: remove every
/// `source_kind = 'photo'` row whose `source_id` (PHAsset.localIdentifier) is NOT
/// in `present_ids_json` (a JSON array). Returns the number removed, or negative
/// on error. The caller MUST pass a COMPLETE present-set.
///
/// # Safety
/// `present_ids_json` must be a NUL-terminated UTF-8 C string (a JSON array).
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)] // FFI boundary; pointer validated via `cstring`
pub extern "C" fn reelvault_prune_photos(present_ids_json: *const c_char) -> i32 {
    embed::ffi_guard("prune_photos", -1, || {
        let json = match unsafe { cstring(present_ids_json) } {
            Some(s) => s,
            None => return -2,
        };
        embed::prune_photos(json)
    })
}
