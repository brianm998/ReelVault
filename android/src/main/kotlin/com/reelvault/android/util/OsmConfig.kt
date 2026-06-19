// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.util

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.osmdroid.config.Configuration
import java.io.File

/**
 * One-time, process-global OSMDroid configuration.
 *
 * The map and location-picker views previously called
 * `Configuration.getInstance().load(ctx, prefs)` inside their AndroidView
 * `factory` — i.e. on the MAIN THREAD, every time the view was created.
 * OSMDroid's `load()` resolves the tile-cache directory by probing every
 * mounted storage volume via `StorageUtils` (including slow removable SD
 * cards). On a real device this blocked the UI thread long enough to skip
 * ~100 frames — a frozen / black screen — as seen in logcat:
 *
 *     StorageUtils: /storage/18EF-2A08/.../files is writable
 *     Choreographer: Skipped 97 frames! ... too much work on its main thread.
 *
 * We instead configure OSMDroid ONCE and pin its cache to app-internal storage,
 * which is always present and fast and skips the volume probe entirely.
 */
object OsmConfig {
    private const val TAG = "RVOsm"

    @Volatile private var initialized = false

    /**
     * Configure OSMDroid if it hasn't been already. Idempotent and cheap after
     * the first call, so it is safe to invoke from the main thread (e.g. an
     * AndroidView factory) as a guard.
     */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val started = SystemClock.uptimeMillis()
            val appContext = context.applicationContext
            val cfg = Configuration.getInstance()
            cfg.userAgentValue = "ReelVault/1.0 (android)"
            // Pin the cache to internal storage so OSMDroid never probes mounted
            // volumes (the slow, main-thread-blocking part of load()).
            val base = File(appContext.filesDir, "osmdroid").apply { mkdirs() }
            cfg.osmdroidBasePath = base
            cfg.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
            initialized = true
            Log.i(TAG, "OSMDroid configured in ${SystemClock.uptimeMillis() - started}ms (cache=$base)")
        }
    }
}
