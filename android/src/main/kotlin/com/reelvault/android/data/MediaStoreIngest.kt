// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.reelvault.android.core.ReelVaultCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

/**
 * Enumerates the device's MediaStore videos and ingests them into the embedded
 * core's on-device catalog — the Android mirror of iOS's `PhotoLibraryIngest`.
 *
 * Incremental: each asset is skipped if already fully indexed
 * (`nativeIsVideoIndexed`), so a relaunch over an unchanged library is near
 * instant. New assets are ingested under bounded concurrency, and assets removed
 * from MediaStore are pruned (`nativePrunePhotos`). A [ContentObserver] re-runs
 * the cheap incremental pass when the library changes; the foreground catch-up
 * (call [run] on resume) keeps it fresh without background tasks.
 *
 * Each `nativeIngestMediaStore` upserts a `photos://<id>` row and publishes a
 * live `VideoAdded`, so the grid (subscribed to the catalog-event stream) streams
 * rows in as they're cataloged.
 */
object MediaStoreIngest {
    private const val TAG = "ReelVault"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isIngesting = MutableStateFlow(false)
    /** True while an ingest pass is running — drives the grid's "importing" banner. */
    val isIngesting: StateFlow<Boolean> = _isIngesting.asStateFlow()

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var inFlight = false

    private var observer: ContentObserver? = null

    /** Stop an in-flight pass (e.g. when switching to a server). Cooperative. */
    fun requestCancel() {
        cancelRequested = true
    }

    /** Fire-and-forget an ingest pass on the ingest scope (outlives the caller's
     *  composition). Used for the initial boot pass and foreground catch-up. */
    fun kickoff(context: Context) {
        val appCtx = context.applicationContext
        scope.launch { run(appCtx) }
    }

    /**
     * Run one full incremental ingest pass: enumerate MediaStore, ingest the
     * not-yet-indexed videos, then prune videos that disappeared. Single-flight:
     * a concurrent call returns immediately. Safe to call repeatedly (initial
     * boot, ContentObserver change, foreground catch-up).
     */
    suspend fun run(context: Context) {
        if (inFlight) return
        inFlight = true
        cancelRequested = false
        _isIngesting.value = true
        try {
            runPass(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStoreIngest pass failed: $e")
        } finally {
            inFlight = false
            _isIngesting.value = false
        }
    }

    private suspend fun runPass(context: Context) = coroutineScope {
        val all = LocalMediaRepository(context).listVideos()
        val present = all.map { it.id.toString() }

        val todo = all.filter {
            !cancelRequested &&
                ReelVaultCore.nativeIsVideoIndexed("photos://${it.id}") != 1
        }
        Log.i(TAG, "MediaStoreIngest: ${all.size} videos, ${todo.size} to ingest")

        val permits = min(4, max(2, Runtime.getRuntime().availableProcessors() - 1))
        val sem = Semaphore(permits)
        todo.map { v ->
            async {
                if (cancelRequested) return@async
                sem.acquire()
                try {
                    if (cancelRequested) return@async
                    val rc = ReelVaultCore.nativeIngestMediaStore(v.id.toString(), v.displayName)
                    if (rc != 0) Log.w(TAG, "ingest ${v.id} (${v.displayName}) rc=$rc")
                } finally {
                    sem.release()
                }
            }
        }.awaitAll()

        // Prune only after a COMPLETE, non-cancelled enumeration — a partial set
        // would delete everything outside it.
        if (!cancelRequested) {
            val removed = ReelVaultCore.nativePrunePhotos(JSONArray(present).toString())
            if (removed > 0) Log.i(TAG, "MediaStoreIngest: pruned $removed removed video(s)")
        }
    }

    /** Watch MediaStore for adds/removes and re-run the incremental pass. */
    fun startObserver(context: Context) {
        if (observer != null) return
        val appCtx = context.applicationContext
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scope.launch { run(appCtx) }
            }
        }
        appCtx.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants = */ true,
            obs,
        )
        observer = obs
    }

    fun stopObserver(context: Context) {
        observer?.let { context.applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
    }
}
