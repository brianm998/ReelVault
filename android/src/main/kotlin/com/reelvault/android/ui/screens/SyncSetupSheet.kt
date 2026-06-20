// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reelvault.android.ReelVaultApp
import com.reelvault.android.core.LocalCore
import com.reelvault.android.core.ReelVaultCore
import com.reelvault.android.data.UploadEndpoint
import com.reelvault.sync.SyncDirection
import com.reelvault.sync.SyncManager
import com.reelvault.sync.SyncProfile
import com.reelvault.sync.SyncRunResult
import kotlinx.coroutines.launch

/**
 * Bottom sheet for starting a catalog sync run.  Shown from [LibraryGridScreen]
 * when the user taps one of the sync toolbar buttons (Local mode + paired server).
 *
 * The sheet is NOT a nav route — it lives entirely in the Compose composition so
 * it doesn't pollute the Android back-stack / nav history.
 *
 * [direction] is pre-set to either [SyncDirection.TO_REMOTE] or
 * [SyncDirection.FROM_REMOTE]; [endpoint] carries the remote server credentials
 * rebuilt from SharedPreferences + the Keychain token (same source as the upload
 * sheet).
 */
@Composable
fun SyncSetupSheet(
    direction: SyncDirection,
    endpoint: UploadEndpoint,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var targetHeight by remember { mutableIntStateOf(1080) }
    var isRunning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SyncRunResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = if (direction == SyncDirection.TO_REMOTE) "Sync to Remote" else "Sync from Remote",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (direction == SyncDirection.TO_REMOTE)
                    "Push local videos and catalog data to the paired server"
                else
                    "Pull remote videos to this device in a playable format",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Quality selector — only relevant for FROM_REMOTE pulls.
            if (direction == SyncDirection.FROM_REMOTE) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Target Quality", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(720 to "720p", 1080 to "1080p", 0 to "Original").forEach { (h, label) ->
                        FilterChip(
                            selected = targetHeight == h,
                            onClick = { targetHeight = h },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // Result summary after a completed run.
            result?.let { r ->
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${r.completed} synced, ${r.failed} failed",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (r.errors.isNotEmpty()) {
                    Text(
                        r.errors.first(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = {
                        scope.launch {
                            isRunning = true
                            errorMessage = null
                            result = null
                            try {
                                val localPort = LocalCore.port
                                if (localPort <= 0) {
                                    errorMessage = "Local core is not running"
                                    return@launch
                                }
                                val app = ReelVaultApp.instance
                                val profile = SyncProfile(
                                    name = "Quick Sync",
                                    peerKey = endpoint.fingerprintHex,
                                    direction = direction,
                                    targetHeight = targetHeight,
                                )
                                val manager = SyncManager(
                                    localChannelFactory = app.channelFactory,
                                    localGrpcPort = localPort,
                                    remoteChannelFactory = app.channelFactory,
                                    remoteHost = endpoint.host,
                                    remoteGrpcPort = endpoint.mediaPort - 1,
                                    remoteMediaPort = endpoint.mediaPort,
                                    token = endpoint.token,
                                    fingerprint = endpoint.fingerprintHex,
                                    onIngestSynced = { path, filename, contentHash, derivedHeight ->
                                        ReelVaultCore.nativeIngestSynced(
                                            path, filename, contentHash, derivedHeight,
                                        )
                                    },
                                )
                                manager.startSync(profile)
                                result = manager.lastResult.value
                            } catch (e: Exception) {
                                errorMessage = e.message ?: "Sync failed"
                            } finally {
                                isRunning = false
                            }
                        }
                    },
                    enabled = !isRunning,
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Start Sync")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
