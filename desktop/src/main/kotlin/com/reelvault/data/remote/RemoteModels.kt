// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.remote

import java.io.File
import java.util.Properties

/**
 * A ReelVault daemon reachable over the LAN — found via mDNS (`_reelvault._tcp`)
 * or entered manually. The client pins the connection by [fingerprintHex] and
 * authorizes gRPC + media calls with a paired bearer token (kept in [TokenStore],
 * keyed by fingerprint). [mediaPort] hosts the HTTPS media server (HLS + pairing).
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

/** Common base for the tiny properties stores under `~/.config/reelvault`. */
private fun configDir(): File =
    File(File(System.getProperty("user.home") ?: "."), ".config/reelvault")

private fun loadProps(file: File): Properties = Properties().also { p ->
    if (file.exists()) try { file.inputStream().use { p.load(it) } } catch (_: Exception) {}
}

private fun storeProps(file: File, props: Properties, comment: String) {
    try {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, comment) }
    } catch (_: Exception) { /* best-effort */ }
}

/**
 * Long-lived pairing bearer tokens, keyed by server certificate fingerprint, in
 * `~/.config/reelvault/tokens.properties`. The desktop has no system Keychain
 * the way macOS/iOS do; for a LAN bearer token this file (in the user's private
 * config dir) matches the desktop's existing posture (see [RecentCatalogs]).
 */
class TokenStore(private val configFile: File = File(configDir(), "tokens.properties")) {
    private val props = loadProps(configFile)

    fun get(fingerprintHex: String): String? =
        props.getProperty(fingerprintHex.lowercase())?.takeIf { it.isNotBlank() }

    fun set(fingerprintHex: String, token: String) {
        props.setProperty(fingerprintHex.lowercase(), token)
        storeProps(configFile, props, "ReelVault pairing tokens")
    }

    fun clear(fingerprintHex: String) {
        props.remove(fingerprintHex.lowercase())
        storeProps(configFile, props, "ReelVault pairing tokens")
    }
}

/**
 * The persisted startup default so the next launch connects straight to the
 * chosen source instead of re-arbitrating, in
 * `~/.config/reelvault/default-server.properties`. The bearer token lives in
 * [TokenStore] (keyed by fingerprint); only the address + pin are stored here.
 */
class DefaultServerStore(
    private val configFile: File = File(configDir(), "default-server.properties")
) {
    data class Entry(
        val kind: String,            // "local" | "remote"
        val host: String,
        val grpcPort: Int,
        val mediaPort: Int,
        val fingerprintHex: String?,
        val requiresPairing: Boolean,
    ) {
        val isRemote: Boolean get() = kind == "remote"
        fun asDiscoveredServer(): DiscoveredServer = DiscoveredServer(
            name = host, host = host, grpcPort = grpcPort, mediaPort = mediaPort,
            fingerprintHex = fingerprintHex, catalogName = null,
            requiresPairing = requiresPairing,
        )
    }

    fun load(): Entry? {
        val p = loadProps(configFile)
        val kind = p.getProperty("kind")?.takeIf { it == "local" || it == "remote" } ?: return null
        val host = p.getProperty("host") ?: return null
        val grpc = p.getProperty("grpcPort")?.toIntOrNull() ?: return null
        return Entry(
            kind = kind,
            host = host,
            grpcPort = grpc,
            mediaPort = p.getProperty("mediaPort")?.toIntOrNull() ?: 50052,
            fingerprintHex = p.getProperty("fingerprintHex")?.takeIf { it.isNotBlank() },
            requiresPairing = p.getProperty("requiresPairing")?.toBoolean() ?: false,
        )
    }

    fun saveLocal(port: Int) = save(Entry("local", "127.0.0.1", port, 50052, null, false))

    fun saveRemote(s: DiscoveredServer) = save(
        Entry("remote", s.host, s.grpcPort, s.mediaPort, s.fingerprintHex, s.requiresPairing)
    )

    private fun save(e: Entry) {
        val p = Properties()
        p.setProperty("kind", e.kind)
        p.setProperty("host", e.host)
        p.setProperty("grpcPort", e.grpcPort.toString())
        p.setProperty("mediaPort", e.mediaPort.toString())
        e.fingerprintHex?.let { p.setProperty("fingerprintHex", it) }
        p.setProperty("requiresPairing", e.requiresPairing.toString())
        storeProps(configFile, p, "ReelVault default server")
    }

    fun clear() { try { configFile.delete() } catch (_: Exception) {} }
}
