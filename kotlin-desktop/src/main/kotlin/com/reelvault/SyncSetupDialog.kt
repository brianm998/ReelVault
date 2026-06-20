// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.reelvault.data.remote.NettyChannelFactory
import com.reelvault.data.remote.RemoteConnection
import com.reelvault.sync.SyncDirection
import com.reelvault.sync.SyncManager
import com.reelvault.sync.SyncProfile
import com.reelvault.sync.SyncRunResult
import com.reelvault.ui.theme.ReelVaultTheme
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Top-level [DialogWindow] for starting a catalog sync run on the desktop.
 *
 * Enabled only when [RemoteConnection.endpoint] is set (the client is currently
 * paired with a remote daemon).  The actual [SyncManager] wires the loopback
 * gRPC port of the local daemon (localhost:50051 default) against the remote
 * endpoint; a full local-core embed on desktop is deferred to a later phase.
 *
 * [direction] is pre-set by the caller (Sync to Remote / Sync from Remote menu
 * items in [ReelVaultTopBar]).
 */
@Composable
fun SyncSetupDialog(
    direction: SyncDirection,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var targetHeight by remember { mutableStateOf(1080) }
    var isRunning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SyncRunResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val title = if (direction == SyncDirection.TO_REMOTE) "Sync to Remote" else "Sync from Remote"

    DialogWindow(
        onCloseRequest = { if (!isRunning) onDismiss() },
        title = title,
        resizable = false,
        state = rememberDialogState(size = DpSize(440.dp, 380.dp)),
    ) {
        // A DialogWindow is a separate top-level ComposeWindow and does NOT
        // inherit the parent composition's MaterialTheme — re-apply it here.
        ReelVaultTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                    )

                    Text(
                        text = if (direction == SyncDirection.TO_REMOTE)
                            "Push local catalog data and video files to the paired remote device."
                        else
                            "Pull catalog data and video files from the paired remote device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Quality selector — only relevant for FROM_REMOTE pulls.
                    if (direction == SyncDirection.FROM_REMOTE) {
                        Text("Target quality (max height):", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(720 to "720p", 1080 to "1080p", 2160 to "4K", 0 to "Original")
                                .forEach { (h, label) ->
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
                        HorizontalDivider()
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
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = { if (!isRunning) onDismiss() }) { Text("Cancel") }
                        Button(
                            onClick = {
                                val ep = RemoteConnection.endpoint ?: return@Button
                                isRunning = true
                                errorMessage = null
                                result = null
                                scope.launch {
                                    try {
                                        val channelFactory = NettyChannelFactory()
                                        // Desktop: the local daemon runs on loopback:50051 by default.
                                        // A future phase will read the actual port from ServerLauncher.
                                        val localGrpcPort = 50051
                                        val remoteGrpcPort = ep.mediaPort - 1  // same convention as Android
                                        val profile = SyncProfile(
                                            id = UUID.randomUUID().toString(),
                                            name = "Desktop sync",
                                            peerKey = ep.fingerprintHex,
                                            direction = direction,
                                            filterJson = "",
                                            targetHeight = targetHeight,
                                            deviceLabel = "Desktop",
                                        )
                                        val mgr = SyncManager(
                                            localChannelFactory = channelFactory,
                                            localGrpcPort = localGrpcPort,
                                            remoteChannelFactory = channelFactory,
                                            remoteHost = ep.host,
                                            remoteGrpcPort = remoteGrpcPort,
                                            remoteMediaPort = ep.mediaPort,
                                            token = ep.token,
                                            fingerprint = ep.fingerprintHex,
                                            onIngestSynced = null, // desktop ingests via local daemon gRPC
                                        )
                                        mgr.startSync(profile)
                                        result = mgr.lastResult.value
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
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Start")
                        }
                    }
                }
            }
        }
    }
}
