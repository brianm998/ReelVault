// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Discovers ReelVault daemons advertised over mDNS as `_reelvault._tcp`. Reads
 * the TXT record the daemon publishes (`fp`, `grpc`, `media`, `cat`, `auth`) to
 * build a [DiscoveredServer]. Pure-Java (jmdns); one code path on every OS.
 *
 * jmdns binds per network interface, so we create one [JmDNS] per non-loopback
 * IPv4 address and merge the results — a single default instance misses servers
 * on secondary interfaces (Wi-Fi vs. wired).
 */
class ServerDiscovery {
    private val logger = LoggerFactory.getLogger(ServerDiscovery::class.java)

    /** Browse for the given [timeoutMs], then return the de-duped server set.
     *  `JmDNS.list` blocks up to the timeout doing the query + resolution, so we
     *  run one per interface concurrently and merge (wall-clock ≈ one timeout). */
    suspend fun discover(timeoutMs: Long = 2500): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val instances = createInstances()
        if (instances.isEmpty()) return@withContext emptyList()
        try {
            val infoLists: List<Array<ServiceInfo>> = coroutineScope {
                instances.map { jm ->
                    async(Dispatchers.IO) {
                        try { jm.list(SERVICE_TYPE, timeoutMs) } catch (e: Exception) {
                            logger.warn("JmDNS.list failed: ${e.message}"); emptyArray<ServiceInfo>()
                        }
                    }
                }.awaitAll()
            }
            val found = LinkedHashMap<String, DiscoveredServer>()
            for (infos in infoLists) for (info in infos) toServer(info)?.let { found[it.id] = it }
            found.values.toList()
        } finally {
            instances.forEach { jm -> try { jm.close() } catch (_: Exception) {} }
        }
    }

    private fun toServer(info: ServiceInfo?): DiscoveredServer? {
        if (info == null) return null
        val ip = info.inet4Addresses.firstOrNull()?.hostAddress
        val host = ip ?: info.server?.removeSuffix(".")?.takeIf { it.isNotBlank() } ?: return null
        val grpc = info.getPropertyString("grpc")?.toIntOrNull() ?: info.port.takeIf { it > 0 } ?: return null
        val media = info.getPropertyString("media")?.toIntOrNull() ?: 50052
        val cat = info.getPropertyString("cat")
        val auth = info.getPropertyString("auth")
        return DiscoveredServer(
            name = info.name,
            host = host,
            grpcPort = grpc,
            mediaPort = media,
            fingerprintHex = info.getPropertyString("fp")?.takeIf { it.isNotBlank() },
            catalogName = cat,
            requiresPairing = auth == "pin",
        )
    }

    private fun createInstances(): List<JmDNS> {
        val addrs = siteLocalIPv4InetAddresses()
        val list = mutableListOf<JmDNS>()
        if (addrs.isEmpty()) {
            try { list += JmDNS.create() } catch (e: Exception) { logger.warn("JmDNS.create() failed: ${e.message}") }
        } else {
            for (a in addrs) {
                try { list += JmDNS.create(a) } catch (e: Exception) { logger.warn("JmDNS.create($a) failed: ${e.message}") }
            }
        }
        return list
    }

    companion object {
        const val SERVICE_TYPE = "_reelvault._tcp.local."
    }
}

/** This machine's up, non-loopback IPv4 addresses as strings. */
fun localIpv4Addresses(): Set<String> {
    val out = mutableSetOf<String>()
    try {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    addr.hostAddress?.let { out += it }
                }
            }
        }
    } catch (_: Exception) {}
    return out
}

/** Same set, as [InetAddress] for binding jmdns to each interface. */
fun siteLocalIPv4InetAddresses(): List<InetAddress> {
    val out = mutableListOf<InetAddress>()
    try {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) out += addr
            }
        }
    } catch (_: Exception) {}
    return out
}

/**
 * Whether [host] is this machine — a literal local IP, or a name (e.g.
 * `<box>.local` advertised over mDNS) that resolves to one of our interface IPs.
 * Used to drop a same-machine `--remote` daemon from the remote list (we'd reach
 * the same process on loopback). Performs blocking DNS — call off the UI thread.
 */
fun hostIsLocalMachine(host: String, localIps: Set<String>): Boolean {
    if (host in localIps) return true
    return try {
        InetAddress.getAllByName(host).any { it is Inet4Address && it.hostAddress in localIps }
    } catch (_: Exception) {
        false
    }
}
