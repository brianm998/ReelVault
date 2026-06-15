/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Copyright (C) 2026 ReelVault Contributors */

/*
 * C ABI for the embedded ReelVault core (docs/IOS_CORE_PORT.md §7.2).
 *
 * Implemented in core/src/ios.rs (compiled only for target_os = "ios") and
 * linked into the iOS app via ReelVaultCore.xcframework, which
 * ios/build-core-xcframework.sh assembles from the Rust staticlib.
 */
#ifndef REELVAULT_CORE_H
#define REELVAULT_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Boot the embedded gRPC server over the app-container catalog and return the
 * bound 127.0.0.1 port (0 on error). Idempotent: a second call returns the same
 * port. All three arguments are NUL-terminated UTF-8 container paths supplied by
 * Swift: the SQLite catalog file, the data dir, and the thumbnail cache dir.
 */
uint16_t reelvault_start_embedded(const char *db_path,
                                  const char *data_dir,
                                  const char *cache_dir);

/* Park the embedded server when the app is suspended. Safe if nothing runs. */
void reelvault_stop_embedded(void);

/*
 * Native media backend (Phase 2, docs/IOS_CORE_PORT.md §6.2). Swift provides
 * these callbacks so the Rust core can decode/probe/transcode via AVFoundation
 * instead of shelling out to ffmpeg. `kind`: 0 = filesystem path, 1 = Photos
 * asset (PHAsset.localIdentifier), 2 = security-scoped bookmark (hex). All
 * `src_id`/path strings are NUL-terminated UTF-8. The field order MUST match
 * the Rust #[repr(C)] NativeMediaCallbacks in core/src/ios.rs.
 */
typedef struct {
    /* ffprobe-shaped JSON (FFProbeOutput) as a heap C string, NULL on error.
       The core frees it via free_string. */
    char *(*probe)(int32_t kind, const char *src_id);
    /* Decode one frame at time_secs (longest side <= max_px) to a JPEG at
       out_path. Returns 0 on success. */
    int32_t (*extract_frame)(int32_t kind, const char *src_id, double time_secs,
                             int32_t max_px, const char *out_path);
    /* Transcode an H.264/AAC proxy at target_height to out_path. 0 = ok. */
    int32_t (*transcode_proxy)(int32_t kind, const char *src_id,
                               const char *out_path, int32_t target_height);
    /* Free a string returned by probe. */
    void (*free_string)(char *s);
} ReelVaultMediaCallbacks;

/* Install the native media backend. Call once at startup, before any media
   work, so it wins over the default (CLI) backend. */
void reelvault_register_media_backend(ReelVaultMediaCallbacks callbacks);

/*
 * Ingest one Photos video into the on-device catalog (Phase 3). `local_id` is
 * the PHAsset.localIdentifier; `filename` is its original filename (may be
 * NULL). Returns 0 on success, negative on error. Call off the main thread
 * (it re-enters the media callbacks synchronously).
 */
int32_t reelvault_ingest_photo(const char *local_id, const char *filename);

/*
 * Ingest one filesystem video (Files-app / container path) into the on-device
 * catalog. `path` is the file path; `filename` its display name (may be NULL).
 * Returns 0 on success, negative on error. Call off the main thread.
 */
int32_t reelvault_ingest_path(const char *path, const char *filename);

#ifdef __cplusplus
}
#endif

#endif /* REELVAULT_CORE_H */
