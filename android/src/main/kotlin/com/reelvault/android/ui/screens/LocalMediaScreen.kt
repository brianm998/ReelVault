// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.reelvault.android.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reelvault.android.R
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.reelvault.android.data.LocalMediaRepository
import com.reelvault.android.data.LocalVideo
import com.reelvault.data.remote.RemoteConnection
import kotlinx.coroutines.launch

@Composable
fun LocalMediaScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { LocalMediaRepository(context) }

    var videos by remember { mutableStateOf<List<LocalVideo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var uploadingIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var uploadedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Permission launcher
    val permissionNeeded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasPermission = true
            scope.launch {
                isLoading = true
                videos = repo.listVideos()
                isLoading = false
            }
        } else {
            permissionDenied = true
        }
    }

    LaunchedEffect(Unit) {
        permLauncher.launch(permissionNeeded)
    }

    LaunchedEffect(errorMsg) {
        val msg = errorMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        errorMsg = null
    }

    val isConnected = RemoteConnection.isRemote

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (selectedIds.isNotEmpty()) {
                        Text(stringResource(R.string.local_selected_count, selectedIds.size))
                    } else {
                        Text(stringResource(R.string.local_title))
                    }
                },
                navigationIcon = {
                    if (selectedIds.isNotEmpty()) {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.local_clear_selection))
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    if (selectedIds.isNotEmpty() && isConnected) {
                        IconButton(
                            onClick = {
                                val toUpload = videos.filter { it.id in selectedIds && it.id !in uploadedIds }
                                scope.launch {
                                    uploadingIds = toUpload.map { it.id }.toSet()
                                    var successCount = 0
                                    toUpload.forEach { video ->
                                        try {
                                            if (repo.uploadToServer(video)) {
                                                uploadedIds = uploadedIds + video.id
                                                successCount++
                                            }
                                        } catch (e: Exception) {
                                            errorMsg = context.getString(R.string.local_upload_failed, e.message ?: "")
                                        }
                                    }
                                    uploadingIds = emptySet()
                                    if (successCount > 0) {
                                        snackbarHostState.showSnackbar(
                                            context.resources.getQuantityString(
                                                R.plurals.local_uploaded_count, successCount, successCount,
                                            )
                                        )
                                        selectedIds = emptySet()
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.local_upload_to_server))
                        }
                    }
                    if (selectedIds.isNotEmpty()) {
                        // Select all / deselect all
                        IconButton(onClick = {
                            selectedIds = if (selectedIds.size == videos.size) emptySet()
                            else videos.map { it.id }.toSet()
                        }) {
                            Icon(
                                if (selectedIds.size == videos.size) Icons.Default.Deselect
                                else Icons.Default.SelectAll,
                                contentDescription = stringResource(R.string.local_select_all)
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (isConnected && selectedIds.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.local_selected_upload_hint, selectedIds.size, selectedIds.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                permissionDenied -> {
                    PermissionDeniedMessage()
                }
                isLoading || (!hasPermission && !permissionDenied) -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                videos.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.VideoFile,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.local_no_videos_found),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                else -> {
                    LocalVideoGrid(
                        videos = videos,
                        selectedIds = selectedIds,
                        uploadingIds = uploadingIds,
                        uploadedIds = uploadedIds,
                        onTap = { video ->
                            selectedIds = if (video.id in selectedIds) {
                                selectedIds - video.id
                            } else {
                                selectedIds + video.id
                            }
                        },
                        onLongPress = { video ->
                            selectedIds = if (video.id in selectedIds) {
                                selectedIds - video.id
                            } else {
                                selectedIds + video.id
                            }
                        },
                    )
                }
            }

            if (!isConnected && hasPermission && videos.isNotEmpty()) {
                // Subtle banner: not connected, can't upload
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.local_not_connected_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalVideoGrid(
    videos: List<LocalVideo>,
    selectedIds: Set<Long>,
    uploadingIds: Set<Long>,
    uploadedIds: Set<Long>,
    onTap: (LocalVideo) -> Unit,
    onLongPress: (LocalVideo) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(videos, key = { it.id }) { video ->
            LocalVideoCell(
                video = video,
                isSelected = video.id in selectedIds,
                isUploading = video.id in uploadingIds,
                isUploaded = video.id in uploadedIds,
                onTap = { onTap(video) },
                onLongPress = { onLongPress(video) },
            )
        }
    }
}

@Composable
private fun LocalVideoCell(
    video: LocalVideo,
    isSelected: Boolean,
    isUploading: Boolean,
    isUploaded: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .border(
                if (isSelected) 2.dp else 0.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(video.uri)
                .crossfade(true)
                .build(),
            contentDescription = video.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Duration badge bottom-left
        if (video.durationMs > 0) {
            Text(
                text = formatLocalDuration(video.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }

        // Upload state overlay
        when {
            isUploading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
            }
            isUploaded -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.local_uploaded),
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            isSelected -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.common_selected),
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionDeniedMessage() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.local_video_access_denied),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.local_permission_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private fun formatLocalDuration(ms: Long): String {
    val totalSecs = ms / 1000
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}
