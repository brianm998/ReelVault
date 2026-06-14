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

#ifdef __cplusplus
}
#endif

#endif /* REELVAULT_CORE_H */
