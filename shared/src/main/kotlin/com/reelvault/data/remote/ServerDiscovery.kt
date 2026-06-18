// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.remote

interface ServerDiscovery {
    fun start(onFound: (DiscoveredServer) -> Unit, onLost: (String) -> Unit)
    fun stop()
}
