// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reelvault.android.ReelVaultApp
import com.reelvault.android.core.LocalCore
import com.reelvault.android.core.ReelVaultCore
import com.reelvault.android.data.lastPairedUploadEndpoint
import com.reelvault.sync.SyncManager
import com.reelvault.sync.SyncProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager background worker that runs all auto-sync profiles once.
 *
 * Scheduled as a periodic task from [ReelVaultApp] via [scheduleAutoSync].
 * Only executes when the embedded core is running ([LocalCore.port] > 0) and
 * at least one paired remote endpoint is available (stored in SharedPreferences
 * by the pairing flow via [lastPairedUploadEndpoint]).
 */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val tag = "SyncWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            runAutoSyncProfiles()
            Result.success()
        } catch (e: Exception) {
            Log.e(tag, "auto-sync failed: $e")
            Result.retry()
        }
    }

    private suspend fun runAutoSyncProfiles() {
        // Load persisted sync profiles.
        val prefs = applicationContext.getSharedPreferences("reelvault_sync", Context.MODE_PRIVATE)
        val json = prefs.getString("profiles", null) ?: return

        val profiles: List<SyncProfile> = try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { SyncProfile.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) {
            Log.w(tag, "failed to parse sync profiles: $e")
            return
        }

        val autoProfiles = profiles.filter { it.autoSync }
        if (autoProfiles.isEmpty()) return

        // Require the embedded local core to be running.
        val localPort = LocalCore.port.takeIf { it > 0 } ?: run {
            Log.d(tag, "local core not running — skipping auto-sync")
            return
        }

        // Resolve the last-paired remote endpoint (same source as the upload sheet).
        val endpoint = lastPairedUploadEndpoint(applicationContext) ?: run {
            Log.d(tag, "no paired remote endpoint — skipping auto-sync")
            return
        }

        val app = applicationContext as? ReelVaultApp ?: return
        val remoteGrpcPort = endpoint.mediaPort - 1  // same convention as SyncSetupSheet

        val mgr = SyncManager(
            localChannelFactory = app.channelFactory,
            localGrpcPort = localPort,
            remoteChannelFactory = app.channelFactory,
            remoteHost = endpoint.host,
            remoteGrpcPort = remoteGrpcPort,
            remoteMediaPort = endpoint.mediaPort,
            token = endpoint.token,
            fingerprint = endpoint.fingerprintHex,
            onIngestSynced = { path, filename, originHash, derivedHeight ->
                ReelVaultCore.nativeIngestSynced(path, filename, originHash, derivedHeight)
            },
        )

        for (profile in autoProfiles) {
            if (isStopped) break
            try {
                mgr.startSync(profile)
                Log.i(tag, "auto-sync completed for profile '${profile.name}'")
            } catch (e: Exception) {
                Log.e(tag, "auto-sync error for profile '${profile.name}': $e")
            }
        }
    }
}
