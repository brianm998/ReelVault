// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

/// Boots the embedded Rust core in-process (docs/IOS_CORE_PORT.md §7) and exposes
/// the loopback gRPC port. This is the on-device "Local Library" mode: instead of
/// connecting to a daemon over the LAN, the app links the core as a static
/// library (ReelVaultCore.xcframework) and runs the *same* gRPC server on
/// `127.0.0.1`, then points `VideoRepository` at it — local vs. remote is just a
/// different endpoint.
///
/// Phase 0 scaffold: this opens an (initially empty) app-container catalog. The
/// media pipeline still uses the CLI backend, which can't run in the iOS sandbox,
/// so on-device metadata/thumbnail/proxy generation lands with the native
/// `MediaBackend` (Phase 2) and Photos/Files ingest (Phase 3).
enum LocalCore {
    /// True when the Rust core framework is linked into the binary. Always true
    /// in a standard iOS build; apps that omit the xcframework compile this to
    /// false via the REELVAULT_NO_LOCAL_CORE build flag.
    static var isAvailable: Bool {
        #if REELVAULT_NO_LOCAL_CORE
        return false
        #else
        return true
        #endif
    }

    /// The loopback port the embedded core is bound to, set when `start()` first
    /// succeeds and kept for the lifetime of the process. Used by SyncManager
    /// to open a second loopback client alongside the one VideoRepository holds.
    private(set) static var port: Int? = nil
    /// App-container paths handed to the core explicitly (don't trust `dirs` on
    /// iOS — see docs/IOS_CORE_PORT.md §6.10 / §11.4).
    private static func paths() throws -> (db: String, data: String, cache: String) {
        let fm = FileManager.default
        let appSupport = try fm.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        )
        let caches = try fm.url(
            for: .cachesDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        )
        let dataDir = appSupport.appendingPathComponent("ReelVault", isDirectory: true)
        let cacheDir = caches.appendingPathComponent("ReelVault", isDirectory: true)
        try fm.createDirectory(at: dataDir, withIntermediateDirectories: true)
        try fm.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        let dbURL = dataDir.appendingPathComponent("catalog.db")
        return (dbURL.path, dataDir.path, cacheDir.path)
    }

    /// Boot the embedded server and return the bound loopback port, or nil on
    /// failure. Idempotent (the core returns the same port on a repeat call).
    static func start() -> Int? {
        do {
            let p = try paths()
            let rawPort = reelvault_start_embedded(p.db, p.data, p.cache)
            guard rawPort != 0 else { return nil }
            let resolved = Int(rawPort)
            port = resolved
            return resolved
        } catch {
            NSLog("LocalCore: failed to prepare container paths: \(error.localizedDescription)")
            return nil
        }
    }

    /// Park the embedded server (call on app suspension).
    static func stop() {
        reelvault_stop_embedded()
    }
}
