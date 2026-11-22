// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.Coil
import coil.ImageLoader
import com.reelvault.android.core.NativeMediaBridge
import com.reelvault.android.core.ReelVaultCore
import com.reelvault.android.data.AndroidTokenStorage
import com.reelvault.android.data.OkHttpChannelFactory
import com.reelvault.android.data.OfflineLibrary
import com.reelvault.android.sync.SyncWorker
import com.reelvault.android.util.MainThreadWatchdog
import com.reelvault.android.util.OsmConfig
import com.reelvault.data.remote.PairingClient
import com.reelvault.data.remote.PinnedTls
import com.reelvault.data.remote.RemoteConnection
import com.reelvault.data.repository.VideoRepository
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class ReelVaultApp : Application() {
    val channelFactory by lazy { OkHttpChannelFactory() }
    val tokenStorage by lazy { AndroidTokenStorage(this) }
    val pairingClient by lazy { PairingClient() }
    val videoRepository by lazy { VideoRepository(channelFactory) }

    companion object {
        lateinit var instance: ReelVaultApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Configure OSMDroid once, up front, with an internal-storage cache so
        // the map/picker views never run load()'s slow volume probe on the UI
        // thread (which froze the screen — see OsmConfig).
        OsmConfig.ensureInitialized(this)
        // Initialise the offline download library (loads the persisted index).
        OfflineLibrary.init(this)
        // Register the native media backend for the embedded core BEFORE it does
        // any media work (mirrors iOS NativeMedia.register()). Loads
        // libreelvault_core.so; if it's missing (e.g. a build without the native
        // step) the app still runs in remote-only mode.
        try {
            ReelVaultCore.nativeRegisterMediaBackend(NativeMediaBridge(applicationContext))
        } catch (t: Throwable) {
            android.util.Log.e("ReelVault", "embedded core native lib unavailable: $t")
        }
        // Debug-only: log the main thread's stack whenever it stalls, so a
        // future freeze / black screen is diagnosable from logcat.
        if (BuildConfig.DEBUG) MainThreadWatchdog.start()
        setupCoil()
        scheduleAutoSync()
    }

    /**
     * Register a periodic WorkManager task that runs auto-sync profiles every 15 minutes
     * (WorkManager's minimum interval), subject to network connectivity. Uses KEEP policy
     * so repeated app restarts don't reset the countdown.
     */
    private fun scheduleAutoSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "reelvault_auto_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Installs a Coil image loader whose OkHttpClient reads the current
     * [RemoteConnection.endpoint] fingerprint at handshake time. Call once at
     * startup; the dynamic trust manager picks up new connections automatically.
     */
    private fun setupCoil() {
        val trustManager = DynamicPinnedTrustManager()
        val sslCtx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(okHttpClient)
                .build()
        )
    }
}

/**
 * Trust manager that delegates to [PinnedTls.PinningTrustManager] when a
 * fingerprint is stored in [RemoteConnection.endpoint], or accepts any cert
 * during the transitional state (before pairing).
 *
 * Using a dynamic delegate means we never need to reinstall the Coil image
 * loader after connecting to a new server.
 */
private class DynamicPinnedTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        val fp = RemoteConnection.endpoint?.fingerprintHex?.takeIf { it.isNotEmpty() } ?: return
        PinnedTls.PinningTrustManager(fp).checkServerTrusted(chain, authType)
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
