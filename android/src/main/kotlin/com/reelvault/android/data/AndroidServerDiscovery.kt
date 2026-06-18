// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.reelvault.data.remote.DiscoveredServer
import com.reelvault.data.remote.ServerDiscovery

class AndroidServerDiscovery(private val context: Context) : ServerDiscovery {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var onFoundCallback: ((DiscoveredServer) -> Unit)? = null
    private var onLostCallback: ((String) -> Unit)? = null

    override fun start(onFound: (DiscoveredServer) -> Unit, onLost: (String) -> Unit) {
        onFoundCallback = onFound
        onLostCallback = onLost

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {}
            override fun onServiceResolved(info: NsdServiceInfo) {
                val attrs = info.attributes
                fun attr(key: String) = attrs[key]?.let { String(it) }

                val grpcPort = attr("grpc_port")?.toIntOrNull() ?: info.port
                val mediaPort = attr("media_port")?.toIntOrNull() ?: (grpcPort + 1)
                val fingerprint = attr("fp")
                val catalogName = attr("catalog")

                val server = DiscoveredServer(
                    name = info.serviceName,
                    host = info.host.hostAddress ?: return,
                    grpcPort = grpcPort,
                    mediaPort = mediaPort,
                    fingerprintHex = fingerprint,
                    catalogName = catalogName,
                    requiresPairing = fingerprint != null,
                )
                onFoundCallback?.invoke(server)
            }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, error: Int) {}
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType == "_reelvault._tcp.") {
                    try { nsdManager.resolveService(info, resolveListener) } catch (_: Exception) {}
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                onLostCallback?.invoke(info.serviceName)
            }
        }

        try {
            nsdManager.discoverServices("_reelvault._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (_: Exception) {}
    }

    override fun stop() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
    }
}
