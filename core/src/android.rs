// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! JNI embed surface for the Android app — the Android mirror of [`crate::ios`].
//!
//! The platform-agnostic embed logic (booting the in-process loopback gRPC
//! server, ingest, prune, is-indexed) lives in [`crate::embed`]. This module is
//! the Android-specific half: it decodes the strings Kotlin passes over JNI,
//! exposes the `Java_…` entry points the Kotlin `ReelVaultCore` object binds via
//! `external fun`, and implements the native [`MediaBackend`] over a Kotlin
//! callback object ([`JniMediaBackend`]) — Rust can't call Kotlin directly, so
//! the app registers an object whose methods the core invokes (via JNI) for the
//! media work the desktop core shells out to ffmpeg for. Kotlin implements them
//! with `MediaMetadataRetriever` / `MediaExtractor`.
//!
//! Compiled **only** for `target_os = "android"` (gated in `lib.rs`).
//!
//! ## Threading
//!
//! [`MediaBackend`] methods run on the core's `spawn_blocking` worker threads,
//! not the JVM thread, so every call attaches the current thread to the JVM
//! ([`JavaVM::attach_current_thread`], a scoped guard that detaches on drop)
//! before touching the Kotlin object. Every `Java_…` export is wrapped in
//! [`embed::ffi_guard`] because a Rust panic unwinding into JNI is undefined
//! behavior (same hazard as the C boundary on iOS).

use std::path::PathBuf;
use std::sync::{Arc, OnceLock};

use jni::objects::{GlobalRef, JClass, JFloatArray, JObject, JString, JValue};
use jni::sys::{jint, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};

use crate::embed;
use crate::error::{ReelVaultError, Result};
use crate::media_backend::{set_backend, MediaBackend, MediaSource};
use crate::metadata::FFProbeOutput;
use crate::thumbnails::ColorInfo;

/// The JVM handle, cached in `JNI_OnLoad` so worker threads can attach later.
static JVM: OnceLock<JavaVM> = OnceLock::new();

/// Called by the runtime when `System.loadLibrary("reelvault_core")` loads the
/// `.so`. Caches the `JavaVM` so [`JniMediaBackend`] can attach worker threads.
///
/// # Safety
/// Invoked by the JVM with a valid `JavaVM`; the raw reserved pointer is unused.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    let _ = JVM.set(vm);
    JNI_VERSION_1_6 as jint
}

/// Read a (possibly null) `JString` into an owned Rust `String`, `None` on
/// null / decode error.
fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(|js| js.into())
}

/// Boot the embedded server over the app-container catalog; returns the bound
/// loopback port, or 0 on error. Idempotent. Mirrors `reelvault_start_embedded`.
#[no_mangle]
pub extern "system" fn Java_com_reelvault_android_core_ReelVaultCore_nativeStartEmbedded<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    db_path: JString<'local>,
    data_dir: JString<'local>,
    cache_dir: JString<'local>,
) -> jint {
    embed::ffi_guard("nativeStartEmbedded", 0, || {
        let (a, b, c) = match (
            jstr(&mut env, &db_path),
            jstr(&mut env, &data_dir),
            jstr(&mut env, &cache_dir),
        ) {
            (Some(a), Some(b), Some(c)) => (a, b, c),
            _ => return 0,
        };
        i32::from(embed::embed_start(
            PathBuf::from(a),
            PathBuf::from(b),
            PathBuf::from(c),
        ))
    })
}

/// Park the embedded server when the app is suspended. Safe if nothing runs.
#[no_mangle]
pub extern "system" fn Java_com_reelvault_android_core_ReelVaultCore_nativeStopEmbedded(
    _env: JNIEnv,
    _class: JClass,
) {
    embed::ffi_guard("nativeStopEmbedded", (), embed::embed_stop)
}

/// Install the native (JNI/MediaMetadataRetriever) media backend. Kotlin calls
/// this once at startup, BEFORE any media work, so it wins the `set_backend` race
/// over the default CLI backend (which can't run in the Android sandbox).
/// `callback` is the Kotlin object implementing `probe`/`extractFrame`/
/// `transcodeProxy`/`extractLoudness`.
#[no_mangle]
pub extern "system" fn Java_com_reelvault_android_core_ReelVaultCore_nativeRegisterMediaBackend<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    callback: JObject<'local>,
) {
    embed::ffi_guard("nativeRegisterMediaBackend", (), || {
        embed::init_logging();
        // Kotlin may register the backend (and trigger media work) before
        // nativeStartEmbedded; install the rustls provider here too. Idempotent.
        crate::install_crypto_provider();
        match env.new_global_ref(&callback) {
            Ok(global) => {
                set_backend(Arc::new(JniMediaBackend { cb: global }));
                tracing::info!("native (JNI) media backend registered");
            }
            Err(e) => tracing::error!("nativeRegisterMediaBackend: global ref failed: {e}"),
        }
    })
}

/// Ingest one MediaStore video (`local_id` = the MediaStore `_ID`, `filename` =
/// its display name) into the on-device catalog. Returns 0 on success, negative
/// on error. Call off the main thread (re-enters the Kotlin media callbacks).
#[no_mangle]
pub extern "system" fn Java_com_reelvault_android_core_ReelVaultCore_nativeIngestMediaStore<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    local_id: JString<'local>,
    filename: JString<'local>,
) -> jint {
    embed::ffi_guard("nativeIngestMediaStore", -99, || {
        let id = match jstr(&mut env, &local_id) {
            Some(s) if !s.is_empty() => s,
            _ => return -2,
        };
        embed::ingest_asset(id, jstr(&mut env, &filename))
    })
}

/// Ingest one filesystem video (`path`, `filename`) into the on-device catalog.
/// Returns 0 on success, negative on error.
#[no_mangle]
pub extern "system" fn Java_com_reelvault_android_core_ReelVaultCore_nativeIngestPath<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    filename: JString<'local>,
) -> jint {
    embed::ffi_guard("nativeIngestPath", -99, || {
        let p = match jstr(&mut env, &path) {
            Some(s) if !s.is_empty() => s,
            _ => return -2,
        };
        embed::ingest_path(p, jstr(&mut env, &filename))
    })
}

/// Has `display_path` already been cataloged (row + metadata)? Returns 1 if
/// fully indexed, 0 if not, negative on error.
#[no_mangle]
pub extern "system" fn Java_com_reelvault_android_core_ReelVaultCore_nativeIsVideoIndexed<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    display_path: JString<'local>,
) -> jint {
    embed::ffi_guard("nativeIsVideoIndexed", -99, || {
        let p = match jstr(&mut env, &display_path) {
            Some(s) if !s.is_empty() => s,
            _ => return -2,
        };
        embed::is_indexed(p)
    })
}

/// Reconcile the catalog against the MediaStore: remove every `source_kind =
/// 'photo'` row whose id is NOT in `present_ids_json` (a JSON array of the
/// MediaStore ids currently present). Returns the number removed, negative on
/// error. The caller MUST pass a COMPLETE present-set.
#[no_mangle]
pub extern "system" fn Java_com_reelvault_android_core_ReelVaultCore_nativePrunePhotos<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    present_ids_json: JString<'local>,
) -> jint {
    embed::ffi_guard("nativePrunePhotos", -1, || match jstr(&mut env, &present_ids_json) {
        Some(json) => embed::prune_photos(json),
        None => -2,
    })
}

// ===========================================================================
// Native media backend (MediaMetadataRetriever via Kotlin callbacks)
// ===========================================================================

/// `MediaBackend` backed by a Kotlin callback object. The `GlobalRef` keeps the
/// object alive across calls and threads; each method attaches the current
/// (worker) thread to the JVM before invoking it.
struct JniMediaBackend {
    cb: GlobalRef,
}

/// Build a backend error with a uniform prefix.
fn jni_err(what: &str) -> ReelVaultError {
    ReelVaultError::MetadataExtractionFailed(format!("jni media backend: {what}"))
}

impl JniMediaBackend {
    /// Call `String probe(int kind, String srcId)` → the ffprobe-shaped JSON.
    fn call_probe(&self, kind: i32, id: &str) -> Result<String> {
        let vm = JVM.get().ok_or_else(|| jni_err("JVM not set"))?;
        let mut env = vm
            .attach_current_thread()
            .map_err(|e| jni_err(&format!("attach: {e}")))?;
        let jid: JObject = env
            .new_string(id)
            .map_err(|e| jni_err(&format!("new_string: {e}")))?
            .into();
        let res = env.call_method(
            self.cb.as_obj(),
            "probe",
            "(ILjava/lang/String;)Ljava/lang/String;",
            &[JValue::Int(kind), JValue::Object(&jid)],
        );
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
            return Err(jni_err("probe threw"));
        }
        let obj = res
            .and_then(|v| v.l())
            .map_err(|e| jni_err(&format!("probe call: {e}")))?;
        if obj.is_null() {
            return Err(jni_err("probe returned null"));
        }
        let js = JString::from(obj);
        let s: String = env
            .get_string(&js)
            .map_err(|e| jni_err(&format!("probe str: {e}")))?
            .into();
        Ok(s)
    }

    /// Call `int extractFrame(int kind, String srcId, double timeSecs, int maxPx,
    /// String outPath)` → 0 on success.
    fn call_extract_frame(
        &self,
        kind: i32,
        id: &str,
        time_secs: f64,
        max_px: i32,
        out: &str,
    ) -> Result<()> {
        let vm = JVM.get().ok_or_else(|| jni_err("JVM not set"))?;
        let mut env = vm
            .attach_current_thread()
            .map_err(|e| jni_err(&format!("attach: {e}")))?;
        let jid: JObject = env
            .new_string(id)
            .map_err(|e| jni_err(&format!("new_string id: {e}")))?
            .into();
        let jout: JObject = env
            .new_string(out)
            .map_err(|e| jni_err(&format!("new_string out: {e}")))?
            .into();
        let res = env.call_method(
            self.cb.as_obj(),
            "extractFrame",
            "(ILjava/lang/String;DILjava/lang/String;)I",
            &[
                JValue::Int(kind),
                JValue::Object(&jid),
                JValue::Double(time_secs),
                JValue::Int(max_px),
                JValue::Object(&jout),
            ],
        );
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
            return Err(ReelVaultError::ThumbnailGenerationFailed(
                "extractFrame threw".into(),
            ));
        }
        let rc = res
            .and_then(|v| v.i())
            .map_err(|e| jni_err(&format!("extractFrame call: {e}")))?;
        if rc == 0 {
            Ok(())
        } else {
            Err(ReelVaultError::ThumbnailGenerationFailed(format!(
                "native extractFrame rc={rc}"
            )))
        }
    }

    /// Call `int transcodeProxy(int kind, String srcId, String outPath, int
    /// targetHeight)` → 0 on success.
    fn call_transcode_proxy(
        &self,
        kind: i32,
        id: &str,
        out: &str,
        target_height: i32,
    ) -> Result<()> {
        let vm = JVM.get().ok_or_else(|| jni_err("JVM not set"))?;
        let mut env = vm
            .attach_current_thread()
            .map_err(|e| jni_err(&format!("attach: {e}")))?;
        let jid: JObject = env
            .new_string(id)
            .map_err(|e| jni_err(&format!("new_string id: {e}")))?
            .into();
        let jout: JObject = env
            .new_string(out)
            .map_err(|e| jni_err(&format!("new_string out: {e}")))?
            .into();
        let res = env.call_method(
            self.cb.as_obj(),
            "transcodeProxy",
            "(ILjava/lang/String;Ljava/lang/String;I)I",
            &[
                JValue::Int(kind),
                JValue::Object(&jid),
                JValue::Object(&jout),
                JValue::Int(target_height),
            ],
        );
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
            return Err(ReelVaultError::FfmpegError("transcodeProxy threw".into()));
        }
        let rc = res
            .and_then(|v| v.i())
            .map_err(|e| jni_err(&format!("transcodeProxy call: {e}")))?;
        if rc == 0 {
            Ok(())
        } else {
            Err(ReelVaultError::FfmpegError(format!(
                "native transcodeProxy rc={rc}"
            )))
        }
    }

    /// Call `float[] extractLoudness(int kind, String srcId, int maxSamples)` →
    /// the per-window envelope (empty on null / failure).
    fn call_extract_loudness(&self, kind: i32, id: &str, max_samples: i32) -> Vec<f32> {
        let Some(vm) = JVM.get() else {
            return Vec::new();
        };
        let Ok(mut env) = vm.attach_current_thread() else {
            return Vec::new();
        };
        let Ok(jid_str) = env.new_string(id) else {
            return Vec::new();
        };
        let jid: JObject = jid_str.into();
        let res = env.call_method(
            self.cb.as_obj(),
            "extractLoudness",
            "(ILjava/lang/String;I)[F",
            &[
                JValue::Int(kind),
                JValue::Object(&jid),
                JValue::Int(max_samples),
            ],
        );
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
            return Vec::new();
        }
        let obj = match res.and_then(|v| v.l()) {
            Ok(o) => o,
            Err(_) => return Vec::new(),
        };
        if obj.is_null() {
            return Vec::new();
        }
        let arr = JFloatArray::from(obj);
        let len = env.get_array_length(&arr).unwrap_or(0);
        if len <= 0 {
            return Vec::new();
        }
        let mut buf = vec![0f32; len as usize];
        if env.get_float_array_region(&arr, 0, &mut buf).is_err() {
            return Vec::new();
        }
        buf
    }
}

impl MediaBackend for JniMediaBackend {
    fn probe(&self, src: &MediaSource) -> Result<FFProbeOutput> {
        let (kind, id) = embed::source_kind_and_id(src);
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        let json = self.call_probe(kind, &id)?;
        serde_json::from_str(&json)
            .map_err(|e| jni_err(&format!("probe JSON: {e}")))
    }

    fn probe_color(&self, _src: &MediaSource) -> ColorInfo {
        // The native extractFrame handles color/HDR itself.
        ColorInfo::default()
    }

    fn extract_loudness(&self, src: &MediaSource) -> Vec<f32> {
        const MAX: i32 = 480;
        let (kind, id) = embed::source_kind_and_id(src);
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        self.call_extract_loudness(kind, &id, MAX)
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
        let (kind, id) = embed::source_kind_and_id(src);
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        self.call_extract_frame(kind, &id, time_secs, max_px, &out.to_string_lossy())
    }

    fn transcode_proxy(
        &self,
        src: &MediaSource,
        out: &std::path::Path,
        target_height: i32,
        _total_frames: i64,
        progress: &mut dyn FnMut(f64),
    ) -> Result<()> {
        let (kind, id) = embed::source_kind_and_id(src);
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        let r = self.call_transcode_proxy(kind, &id, &out.to_string_lossy(), target_height);
        // No granular progress from the Kotlin export yet; jump to the top of the
        // encode band on completion (mirrors the iOS native backend).
        progress(85.0);
        r
    }

    // Catalog-only on Android (mirrors iOS D4): MediaStore originals aren't
    // rewritten in place.
    fn write_creation_time(&self, _src: &MediaSource, _timestamp_ms: i64) -> Result<()> {
        Ok(())
    }
    fn write_location(&self, _src: &MediaSource, _lat: f64, _lon: f64, _alt: f64) -> Result<()> {
        Ok(())
    }
}
