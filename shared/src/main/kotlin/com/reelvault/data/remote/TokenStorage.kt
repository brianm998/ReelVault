// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.remote

interface TokenStorage {
    fun get(fingerprintHex: String): String?
    fun set(fingerprintHex: String, token: String)
    fun clear(fingerprintHex: String)
}
