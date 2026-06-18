// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.reelvault.data.remote.TokenStorage

class AndroidTokenStorage(context: Context) : TokenStorage {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "reelvault_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun get(fingerprintHex: String): String? =
        prefs.getString(fingerprintHex.lowercase(), null)?.takeIf { it.isNotBlank() }

    override fun set(fingerprintHex: String, token: String) {
        prefs.edit().putString(fingerprintHex.lowercase(), token).apply()
    }

    override fun clear(fingerprintHex: String) {
        prefs.edit().remove(fingerprintHex.lowercase()).apply()
    }
}
