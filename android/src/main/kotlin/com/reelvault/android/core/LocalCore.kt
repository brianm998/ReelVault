// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Lifecycle of the embedded core for on-device (Local) mode — the Android mirror
 * of iOS's `LocalCore.swift`. Computes the app-container paths and starts/stops
 * the loopback gRPC server via [ReelVaultCore].
 */
object LocalCore {
    private const val TAG = "ReelVault"

    /** The loopback port the embedded core is serving on (0 when not running). */
    @Volatile
    var port: Int = 0
        private set

    /**
     * Boot the embedded core over the app-private catalog. Returns the loopback
     * port, or null on failure. Idempotent (a second call returns the same port).
     * Call off the main thread.
     */
    fun start(context: Context): Int? {
        val support = File(context.filesDir, "ReelVault").apply { mkdirs() }
        val catalog = File(support, "catalog.db")
        val cache = File(context.cacheDir, "ReelVault").apply { mkdirs() }
        val p = ReelVaultCore.nativeStartEmbedded(
            catalog.absolutePath,
            support.absolutePath,
            cache.absolutePath,
        )
        port = p
        if (p > 0) {
            Log.i(TAG, "LocalCore: embedded core on 127.0.0.1:$p")
        } else {
            Log.e(TAG, "LocalCore: nativeStartEmbedded failed (rc=$p)")
        }
        return p.takeIf { it > 0 }
    }

    /** Park the embedded core (on app suspend / switch to a server). */
    fun stop() {
        ReelVaultCore.nativeStopEmbedded()
        port = 0
    }
}
