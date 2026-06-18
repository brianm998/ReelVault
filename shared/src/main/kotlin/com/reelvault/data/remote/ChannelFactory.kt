// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.remote

import io.grpc.ManagedChannel

/** Platform-specific gRPC channel creator — Netty on desktop, OkHttp on Android. */
interface ChannelFactory {
    /** Create a plaintext (insecure) channel to the local loopback daemon. */
    fun createPlaintext(host: String, port: Int): ManagedChannel
    /** Create a TLS-pinned channel to a remote LAN daemon. */
    fun createPinned(host: String, port: Int, fingerprintHex: String): ManagedChannel
}
