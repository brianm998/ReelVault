// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.remote

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder

class NettyChannelFactory : ChannelFactory {
    override fun createPlaintext(host: String, port: Int): ManagedChannel =
        ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()

    override fun createPinned(host: String, port: Int, fingerprintHex: String): ManagedChannel =
        NettyChannelBuilder.forAddress(host, port)
            .overrideAuthority("localhost")
            .sslContext(
                GrpcSslContexts.forClient()
                    .trustManager(PinnedTls.PinningTrustManager(fingerprintHex))
                    .build()
            )
            .build()
}
