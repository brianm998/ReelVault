// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.data

import android.content.Context
import com.reelvault.data.remote.DiscoveredServer

class DefaultServerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("reelvault_server", Context.MODE_PRIVATE)

    data class Entry(
        val host: String,
        val grpcPort: Int,
        val mediaPort: Int,
        val fingerprintHex: String?,
        val requiresPairing: Boolean,
    )

    fun load(): Entry? {
        val host = prefs.getString("host", null) ?: return null
        val grpcPort = prefs.getInt("grpcPort", 0).takeIf { it > 0 } ?: return null
        return Entry(
            host = host,
            grpcPort = grpcPort,
            mediaPort = prefs.getInt("mediaPort", grpcPort + 1),
            fingerprintHex = prefs.getString("fingerprintHex", null),
            requiresPairing = prefs.getBoolean("requiresPairing", false),
        )
    }

    fun save(server: DiscoveredServer) {
        prefs.edit().apply {
            putString("host", server.host)
            putInt("grpcPort", server.grpcPort)
            putInt("mediaPort", server.mediaPort)
            server.fingerprintHex?.let { putString("fingerprintHex", it) }
            putBoolean("requiresPairing", server.requiresPairing)
        }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
