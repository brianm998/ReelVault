// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.remote

/**
 * A ReelVault daemon reachable over the LAN — found via mDNS (`_reelvault._tcp`)
 * or entered manually. The client pins the connection by [fingerprintHex] and
 * authorizes gRPC + media calls with a paired bearer token (kept in a [TokenStorage]
 * implementation, keyed by fingerprint). [mediaPort] hosts the HTTPS media server
 * (HLS + pairing).
 */
data class DiscoveredServer(
    val name: String,
    val host: String,
    val grpcPort: Int,
    val mediaPort: Int,
    val fingerprintHex: String?,
    val catalogName: String?,
    val requiresPairing: Boolean,
) {
    /** Stable identity for de-duping discovery hits / picker rows. */
    val id: String get() = "$host:$grpcPort"
    val displayName: String get() = catalogName?.takeIf { it.isNotBlank() } ?: name
}

/**
 * A startup connection option offered when more than one daemon is reachable:
 * the local loopback daemon vs. one or more remote daemons.
 */
sealed class ServerChoice {
    abstract val title: String
    abstract val subtitle: String

    data class Local(val port: Int) : ServerChoice() {
        override val title: String get() = "This Computer"
        override val subtitle: String get() = "Local library · localhost:$port"
    }

    data class Remote(val server: DiscoveredServer) : ServerChoice() {
        override val title: String get() = server.displayName
        override val subtitle: String get() =
            "${server.host}:${server.grpcPort}" +
                (if (server.requiresPairing) " · pairing required" else "")
    }
}

/**
 * The media endpoint of the currently-connected REMOTE daemon, or `null` in
 * local mode. The detail player reads this: when set, it streams video over HLS
 * (via a fingerprint-pinned loopback proxy) instead of opening a local file path
 * (which only exists for a daemon on this machine). Set on every connect.
 */
object RemoteConnection {
    data class Endpoint(
        val host: String,
        val mediaPort: Int,
        val fingerprintHex: String,
        val token: String,
    )

    @Volatile
    var endpoint: Endpoint? = null

    val isRemote: Boolean get() = endpoint != null
}
