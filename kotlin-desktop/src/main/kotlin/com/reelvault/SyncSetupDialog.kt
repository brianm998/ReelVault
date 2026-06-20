// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.reelvault.data.models.Tag
import com.reelvault.data.models.Collection as VideoCollection
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
@OptIn(ExperimentalMaterial3Api::class)
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
    var availableTags by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var availableCollections by remember { mutableStateOf<List<VideoCollection>>(emptyList()) }
    var selectedTagId by remember { mutableStateOf("") }
    var selectedCollectionId by remember { mutableStateOf("") }
    var filterMinRating by remember { mutableIntStateOf(0) }
    var filterColorLabel by remember { mutableStateOf("") }
    var showTagDropdown by remember { mutableStateOf(false) }
    var showCollectionDropdown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val channelFactory = NettyChannelFactory()
            val localGrpcPort = 50051
            val channel = channelFactory.createPlaintext("localhost", localGrpcPort)
            val stub = reelvault.ReelVaultGrpcKt.ReelVaultCoroutineStub(channel)
            availableTags = stub.listTags(
                reelvault.Reelvault.ListTagsRequest.newBuilder().build()
            ).tagsList.map { Tag(id = it.id, name = it.name, color = it.color, videoCount = it.videoCount) }
                .sortedBy { it.name.lowercase() }
            availableCollections = stub.listCollections(
                reelvault.Reelvault.ListCollectionsRequest.newBuilder().build()
            ).collectionsList.filter { !it.isSmart }
                .map { VideoCollection(id = it.id, name = it.name, isSmart = it.isSmart, filterJson = it.filterJson, videoCount = it.videoCount) }
                .sortedBy { it.name.lowercase() }
            channel.shutdown()
        } catch (_: Exception) { /* filter options stay empty */ }
    }

    val title = if (direction == SyncDirection.TO_REMOTE) "Sync to Remote" else "Sync from Remote"

    DialogWindow(
        onCloseRequest = { if (!isRunning) onDismiss() },
        title = title,
        resizable = false,
        state = rememberDialogState(size = DpSize(480.dp, 640.dp)),
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
                        .verticalScroll(rememberScrollState())
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

                    HorizontalDivider()

                    Text("Filter (optional)", style = MaterialTheme.typography.labelLarge)

                    // Tag dropdown
                    ExposedDropdownMenuBox(
                        expanded = showTagDropdown,
                        onExpandedChange = { showTagDropdown = it },
                    ) {
                        OutlinedTextField(
                            value = if (selectedTagId.isEmpty()) "Any tag"
                                    else availableTags.find { it.id == selectedTagId }?.name ?: "Any tag",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Tag") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showTagDropdown) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = showTagDropdown, onDismissRequest = { showTagDropdown = false }) {
                            DropdownMenuItem(text = { Text("Any tag") }, onClick = { selectedTagId = ""; showTagDropdown = false })
                            availableTags.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text(tag.name) },
                                    onClick = { selectedTagId = tag.id; showTagDropdown = false },
                                )
                            }
                        }
                    }

                    // Collection dropdown
                    ExposedDropdownMenuBox(
                        expanded = showCollectionDropdown,
                        onExpandedChange = { showCollectionDropdown = it },
                    ) {
                        OutlinedTextField(
                            value = if (selectedCollectionId.isEmpty()) "Any collection"
                                    else availableCollections.find { it.id == selectedCollectionId }?.name ?: "Any collection",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Collection") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCollectionDropdown) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = showCollectionDropdown, onDismissRequest = { showCollectionDropdown = false }) {
                            DropdownMenuItem(text = { Text("Any collection") }, onClick = { selectedCollectionId = ""; showCollectionDropdown = false })
                            availableCollections.forEach { coll ->
                                DropdownMenuItem(
                                    text = { Text(coll.name) },
                                    onClick = { selectedCollectionId = coll.id; showCollectionDropdown = false },
                                )
                            }
                        }
                    }

                    // Rating chips
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Min Rating", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0 to "Any", 1 to "★+", 2 to "★★+", 3 to "★★★+", 4 to "★★★★+", 5 to "★★★★★").forEach { (rating, label) ->
                                FilterChip(
                                    selected = filterMinRating == rating,
                                    onClick = { filterMinRating = rating },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    // Color label chips
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Color Label", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("" to "Any", "red" to "Red", "yellow" to "Yellow", "green" to "Green", "blue" to "Blue", "purple" to "Purple").forEach { (key, label) ->
                                FilterChip(
                                    selected = filterColorLabel == key,
                                    onClick = { filterColorLabel = key },
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

                    Spacer(Modifier.height(8.dp))

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
                                            filterJson = buildSyncFilterJson(selectedTagId, selectedCollectionId, filterMinRating, filterColorLabel),
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

private fun buildSyncFilterJson(
    tagId: String,
    collectionId: String,
    minRating: Int,
    colorLabel: String,
): String {
    val obj = org.json.JSONObject()
    if (tagId.isNotEmpty()) obj.put("filterTags", org.json.JSONArray().apply { put(tagId) })
    if (collectionId.isNotEmpty()) obj.put("collectionId", collectionId)
    if (minRating > 0) obj.put("filterMinRating", minRating)
    if (colorLabel.isNotEmpty()) obj.put("filterColorLabel", colorLabel)
    return if (obj.length() == 0) "" else obj.toString()
}
