// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.data

import com.reelvault.data.remote.ChannelFactory
import com.reelvault.data.remote.PinnedTls
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.okhttp.OkHttpChannelBuilder
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

class OkHttpChannelFactory : ChannelFactory {
    override fun createPlaintext(host: String, port: Int): ManagedChannel =
        ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build()

    override fun createPinned(host: String, port: Int, fingerprintHex: String): ManagedChannel {
        val trustManager = PinnedTls.PinningTrustManager(fingerprintHex)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        return OkHttpChannelBuilder.forAddress(host, port)
            .overrideAuthority("localhost")
            .sslSocketFactory(sslContext.socketFactory)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
