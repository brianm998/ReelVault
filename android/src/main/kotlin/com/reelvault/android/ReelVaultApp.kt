// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android

import android.app.Application
import com.reelvault.android.data.AndroidTokenStorage
import com.reelvault.android.data.OkHttpChannelFactory
import com.reelvault.data.remote.PairingClient
import com.reelvault.data.repository.VideoRepository

class ReelVaultApp : Application() {
    val channelFactory by lazy { OkHttpChannelFactory() }
    val tokenStorage by lazy { AndroidTokenStorage(this) }
    val pairingClient by lazy { PairingClient() }
    val videoRepository by lazy { VideoRepository(channelFactory) }

    companion object {
        lateinit var instance: ReelVaultApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
