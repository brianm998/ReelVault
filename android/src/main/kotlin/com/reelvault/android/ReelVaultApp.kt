// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.reelvault.android.data.AndroidTokenStorage
import com.reelvault.android.data.OkHttpChannelFactory
import com.reelvault.android.util.MainThreadWatchdog
import com.reelvault.android.util.OsmConfig
import com.reelvault.data.remote.PairingClient
import com.reelvault.data.remote.PinnedTls
import com.reelvault.data.remote.RemoteConnection
import com.reelvault.data.repository.VideoRepository
import java.security.cert.X509Certificate
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
        // Debug-only: log the main thread's stack whenever it stalls, so a
        // future freeze / black screen is diagnosable from logcat.
        if (BuildConfig.DEBUG) MainThreadWatchdog.start()
        setupCoil()
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
