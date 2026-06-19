// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(ExperimentalMaterial3Api::class)

package com.reelvault.android.ui.screens

import android.graphics.Bitmap
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.OptIn as MediaOptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.reelvault.android.R
import com.reelvault.android.data.OfflineLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Offline library: the downloaded-video grid, reached from the no-server screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Displays the user's offline downloads as a scrollable grid.  Tapping a card
 * pushes [OfflineDetailScreen].  Reached from the connection-flow screen when
 * no daemon is available.
 *
 * Mirrors iOS OfflineLibraryView.
 */
@Composable
fun OfflineLibraryScreen(
    onBack: () -> Unit,
) {
    val entries by OfflineLibrary.entries.collectAsStateWithLifecycle()
    val totalBytes = OfflineLibrary.totalBytes
    val snackbarHostState = remember { SnackbarHostState() }

    val lastError by OfflineLibrary.lastError.collectAsStateWithLifecycle()
    LaunchedEffect(lastError) {
        val msg = lastError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        OfflineLibrary.clearError()
    }

    // Navigation state: which entry is being viewed in the detail screen.
    var selectedEntry by remember { mutableStateOf<OfflineLibrary.Entry?>(null) }

    if (selectedEntry != null) {
        OfflineDetailScreen(
            entry = selectedEntry!!,
            onBack = { selectedEntry = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.offline_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        Text(
                            text = formatBytes(totalBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (entries.isEmpty()) {
            OfflineEmptyState(modifier = Modifier.padding(innerPadding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(
                    start = 8.dp, end = 8.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.id }) { entry ->
                    OfflineCard(
                        entry = entry,
                        onClick = { selectedEntry = entry },
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.DownloadForOffline,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.offline_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.offline_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OfflineCard(
    entry: OfflineLibrary.Entry,
    onClick: () -> Unit,
) {
    var bitmap by remember(entry.id) { mutableStateOf<Bitmap?>(null) }

    // Load thumbnail off the main thread.
    LaunchedEffect(entry.id) {
        bitmap = withContext(Dispatchers.IO) {
            OfflineLibrary.thumbnailBitmap(entry.id)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .clickable { onClick() }
                .fillMaxWidth(),
        ) {
            // Thumbnail area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // Filename + metadata
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = entry.filename,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val secondary = buildSecondaryLabel(entry)
                if (secondary.isNotEmpty()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun buildSecondaryLabel(entry: OfflineLibrary.Entry): String {
    val parts = buildList {
        if (entry.pixelHeight > 0) add("${entry.pixelHeight}p")
        if (entry.durationMs > 0) add(formatDuration(entry.durationMs))
        add(formatBytes(entry.sizeBytes))
    }
    return parts.joinToString(" · ")
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000L
    return "%d:%02d".format(s / 60, s % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes > 0 -> "%.1f KB".format(bytes / 1024.0)
    else -> ""
}

// ─────────────────────────────────────────────────────────────────────────────
// Offline detail view: plays a downloaded file with ExoPlayer (no streaming)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Plays a downloaded video from its local file, with a metadata inspector below.
 * No daemon required — ExoPlayer opens the local File URI directly.
 *
 * Mirrors iOS OfflineDetailView.
 */
@MediaOptIn(UnstableApi::class)
@Composable
fun OfflineDetailScreen(
    entry: OfflineLibrary.Entry,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }

    val file = remember(entry.id) { OfflineLibrary.videoFile(entry.id) }
    val fileExists = file?.exists() == true

    val exoPlayer = remember(entry.id) {
        ExoPlayer.Builder(context).build().also { player ->
            if (fileExists && file != null) {
                val uri = android.net.Uri.fromFile(file)
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.playWhenReady = false
            }
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.pause()
            exoPlayer.release()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = entry.filename,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.offline_remove_download),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // Player
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            if (entry.pixelWidth > 0 && entry.pixelHeight > 0) {
                                entry.pixelWidth.toFloat() / entry.pixelHeight.toFloat()
                            } else {
                                16f / 9f
                            }
                        )
                        .background(Color.Black),
                ) {
                    if (fileExists) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                                    useController = true
                                    player = exoPlayer
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.offline_file_missing),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // Metadata card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.detail_section_file_info).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        OfflineMetaRow(stringResource(R.string.detail_field_filename), entry.filename)
                        if (entry.pixelWidth > 0 && entry.pixelHeight > 0) {
                            OfflineMetaRow(
                                stringResource(R.string.detail_field_resolution),
                                "${entry.pixelWidth}×${entry.pixelHeight}",
                            )
                        }
                        if (entry.codecVideo.isNotEmpty()) {
                            OfflineMetaRow(stringResource(R.string.detail_field_codec), entry.codecVideo)
                        }
                        OfflineMetaRow(
                            stringResource(R.string.offline_downloaded_quality),
                            if (entry.renditionHeight == 0) {
                                stringResource(R.string.offline_quality_original)
                            } else {
                                "${entry.renditionHeight}p"
                            },
                        )
                        OfflineMetaRow(
                            stringResource(R.string.detail_field_size),
                            formatBytes(entry.sizeBytes),
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.offline_remove_title)) },
            text = { Text(stringResource(R.string.offline_remove_body, entry.filename)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            OfflineLibrary.remove(entry.id)
                        }
                        showDeleteDialog = false
                        onBack()
                    },
                ) {
                    Text(
                        stringResource(R.string.offline_remove_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun OfflineMetaRow(label: String, value: String) {
    if (value.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Offline download button — shown in VideoDetailScreen when remote-connected
// ─────────────────────────────────────────────────────────────────────────────

/**
 * "Download for Offline" control for the video detail view.  Reflects
 * downloaded / downloading / not-downloaded state and lets the user choose
 * between Original and 720p quality.
 *
 * Only rendered when a remote endpoint is active ([RemoteConnection.endpoint]
 * is non-null) — callers should gate on that condition.
 *
 * Mirrors iOS OfflineDownloadButton.
 */
@Composable
fun OfflineDownloadSection(
    video: com.reelvault.data.models.VideoSummary,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val entries by OfflineLibrary.entries.collectAsStateWithLifecycle()
    val downloading by OfflineLibrary.downloading.collectAsStateWithLifecycle()
    val lastError by OfflineLibrary.lastError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showQualityDialog by remember { mutableStateOf(false) }

    LaunchedEffect(lastError) {
        val msg = lastError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        OfflineLibrary.clearError()
    }

    val isDownloaded = entries.any { it.id == video.id }
    val isDownloading = downloading.contains(video.id)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.offline_section_title).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            when {
                isDownloaded -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.offline_available_offline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch { OfflineLibrary.remove(video.id) }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.offline_remove_download))
                    }
                }

                isDownloading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.offline_downloading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    OutlinedButton(onClick = { showQualityDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.DownloadForOffline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.offline_download_button))
                    }
                }
            }
        }
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text(stringResource(R.string.offline_quality_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showQualityDialog = false
                            scope.launch { OfflineLibrary.download(video, height = 0) }
                        },
                    ) {
                        Text(
                            stringResource(R.string.offline_quality_original),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showQualityDialog = false
                            scope.launch { OfflineLibrary.download(video, height = 720) }
                        },
                    ) {
                        Text(
                            stringResource(R.string.offline_quality_720p),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
