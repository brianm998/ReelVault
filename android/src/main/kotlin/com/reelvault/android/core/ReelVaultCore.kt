// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.core

/**
 * JNI bindings to the embedded Rust core (`core/src/android.rs`), loaded from
 * `libreelvault_core.so` in jniLibs (built by `android/build-core.sh`).
 *
 * The Android mirror of iOS's `LocalCore` + `reelvault_core.h`: the app boots the
 * core in-process, which serves the **same** gRPC API on a loopback port, and the
 * client connects to `127.0.0.1:<port>` exactly as it connects to a remote daemon
 * — so the whole grid/detail UI is reused for the on-device library.
 *
 * The `external fun` names + signatures must match the `Java_com_reelvault_android
 * _core_ReelVaultCore_*` exports in `core/src/android.rs`.
 */
object ReelVaultCore {
    init {
        // Triggers JNI_OnLoad, which caches the JavaVM for the media backend.
        System.loadLibrary("reelvault_core")
    }

    /** Boot the embedded gRPC server over the app-container catalog; returns the
     *  bound 127.0.0.1 port, or 0 on error. Idempotent. */
    external fun nativeStartEmbedded(dbPath: String, dataDir: String, cacheDir: String): Int

    /** Park the embedded server (call on app suspend). Safe if nothing runs. */
    external fun nativeStopEmbedded()

    /** Install the native media backend (probe/extractFrame/…) before any media
     *  work, so it wins over the default CLI backend (which can't run here). */
    external fun nativeRegisterMediaBackend(callback: NativeMediaBridge)

    /** Ingest one MediaStore video (`localId` = MediaStore `_ID`). 0 = ok. */
    external fun nativeIngestMediaStore(localId: String, filename: String?): Int

    /** Ingest one filesystem video. 0 = ok. */
    external fun nativeIngestPath(path: String, filename: String?): Int

    /** 1 if `displayPath` is fully cataloged (row + metadata), 0 if not, <0 on error. */
    external fun nativeIsVideoIndexed(displayPath: String): Int

    /** Remove every photo-source row whose id is NOT in `presentIdsJson` (a JSON
     *  array). Returns the count removed. The caller MUST pass a COMPLETE set. */
    external fun nativePrunePhotos(presentIdsJson: String): Int
}
