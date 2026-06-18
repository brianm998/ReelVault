// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reelvault.android.ui.components.ProxyRendition
import com.reelvault.android.ui.components.VideoPlayer
import com.reelvault.android.ui.theme.swatch
import com.reelvault.android.ui.theme.dimmed
import com.reelvault.android.viewmodel.DetailViewModel
import com.reelvault.data.models.ColorLabel
import com.reelvault.data.models.FullResolutionStatus
import com.reelvault.data.models.GridStatKey
import com.reelvault.data.models.VideoMetadata
import com.reelvault.data.remote.RemoteConnection
import com.reelvault.data.repository.VideoRepository

// ─────────────────────────────────────────────────────────────────────────────
// Screen entry point
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen detail / inspector view for a single video.
 *
 * Mirrors iOS VideoDetailView.swift — metadata sections presented in a
 * scrollable list below an inline video player (HLS or thumbnail).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailScreen(
    videoId: String,
    repository: VideoRepository,
    onBack: () -> Unit,
) {
    val vm: DetailViewModel = viewModel(
        key = "detail_$videoId",
        factory = DetailViewModel.Factory(repository),
    )

    val metadata by vm.metadata.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val thumbnail by vm.thumbnail.collectAsStateWithLifecycle()

    // Load on first composition.
    LaunchedEffect(videoId) {
        vm.loadMetadata(videoId)
    }

    // Proxies (fetched separately).
    var proxies by remember { mutableStateOf<List<VideoRepository.ProxyInfo>>(emptyList()) }
    LaunchedEffect(videoId) {
        proxies = repository.listProxies(videoId)
    }

    // All tags (needed for the "add tag" picker).
    var allTags by remember { mutableStateOf<List<com.reelvault.data.models.Tag>>(emptyList()) }
    LaunchedEffect(Unit) {
        allTags = repository.listTags()
    }

    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = metadata?.filename ?: "Detail",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    if (metadata != null) {
                        IconButton(onClick = { shareVideo(context, metadata!!) }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            metadata == null && !isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error ?: "Video not found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            metadata != null -> {
                DetailContent(
                    videoId = videoId,
                    metadata = metadata!!,
                    notes = notes,
                    thumbnail = thumbnail,
                    proxies = proxies,
                    allTags = allTags,
                    repository = repository,
                    vm = vm,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main content list
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DetailContent(
    videoId: String,
    metadata: VideoMetadata,
    notes: String,
    thumbnail: ByteArray?,
    proxies: List<VideoRepository.ProxyInfo>,
    allTags: List<com.reelvault.data.models.Tag>,
    repository: VideoRepository,
    vm: DetailViewModel,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // ── Video player / thumbnail ──────────────────────────────────────
        item {
            VideoPlayerSection(
                videoId = videoId,
                metadata = metadata,
                proxies = proxies,
            )
        }

        // ── File Info ─────────────────────────────────────────────────────
        item {
            MetadataSection(title = "File Info") {
                MetadataRow("Filename", metadata.filename)
                MetadataRow("Path", metadata.path, monospace = true)
                MetadataRow("Size", metadata.sizeFormatted)
                MetadataRow("Indexed", epochMsToDisplay(metadata.indexedAt))
                MetadataRow("Online", if (metadata.isOnline) "Yes" else "No (offline)")
            }
        }

        // ── Video ─────────────────────────────────────────────────────────
        item {
            MetadataSection(title = "Video") {
                MetadataRow("Resolution", metadata.resolution)
                MetadataRow("Codec", metadata.codecVideo.ifEmpty { "—" })
                if (metadata.fps > 0) MetadataRow("FPS", "%.3f".format(metadata.fps))
                MetadataRow("Bitrate", metadata.bitrateFormatted)
                metadata.frameCountFormatted?.let { MetadataRow("Frames", it) }
                if (metadata.hdr) MetadataRow("HDR", "Yes")
                if (metadata.colorSpace.isNotEmpty()) MetadataRow("Color Space", metadata.colorSpace)
                when (metadata.fullResolution) {
                    FullResolutionStatus.Full ->
                        MetadataRow("Resolution Status", "Full resolution")
                    FullResolutionStatus.NotFull ->
                        MetadataRow("Resolution Status", "Not full resolution")
                    FullResolutionStatus.Unspecified -> Unit
                }
            }
        }

        // ── Audio ─────────────────────────────────────────────────────────
        if (metadata.codecAudio.isNotEmpty()) {
            item {
                MetadataSection(title = "Audio") {
                    MetadataRow("Codec", metadata.codecAudio)
                    if (metadata.audioChannels > 0) {
                        MetadataRow("Channels", audioChannelLabel(metadata.audioChannels))
                    }
                    if (metadata.audioSampleRate > 0) {
                        MetadataRow("Sample Rate", "${metadata.audioSampleRate} Hz")
                    }
                }
            }
        }

        // ── Capture (EXIF) ────────────────────────────────────────────────
        val hasCapture = metadata.creationDate > 0 ||
            metadata.cameraModel.isNotEmpty() ||
            metadata.lensModel.isNotEmpty() ||
            metadata.iso > 0 ||
            metadata.aperture > 0.0 ||
            metadata.exposureTimeS > 0.0 ||
            metadata.focalLengthMm > 0.0 ||
            metadata.exposureMode.isNotEmpty() ||
            metadata.exposureProgram.isNotEmpty() ||
            metadata.whiteBalance.isNotEmpty()
        if (hasCapture) {
            item {
                MetadataSection(title = "Capture") {
                    if (metadata.creationDate > 0) {
                        MetadataRow("Date", metadata.creationDateFormatted)
                    }
                    if (metadata.cameraModel.isNotEmpty()) {
                        val displayName = metadata.cameraDisplayName.ifEmpty { metadata.cameraModel }
                        MetadataRow("Camera", displayName)
                        // Show internal name when it differs from the marketing name.
                        if (metadata.cameraDisplayName.isNotEmpty() &&
                            metadata.cameraDisplayName != metadata.cameraModel
                        ) {
                            MetadataRow("Camera (internal)", metadata.cameraModel)
                        }
                    }
                    if (metadata.lensModel.isNotEmpty()) {
                        MetadataRow("Lens", metadata.lensModel)
                    }
                    if (metadata.iso > 0) MetadataRow("ISO", "ISO ${metadata.iso}")
                    if (metadata.aperture > 0.0) {
                        MetadataRow("Aperture", "f/%.1f".format(metadata.aperture))
                    }
                    if (metadata.exposureTimeS > 0.0) {
                        MetadataRow("Exposure", GridStatKey.formatExposureTime(metadata.exposureTimeS))
                    }
                    if (metadata.focalLengthMm > 0.0) {
                        MetadataRow("Focal Length", "%.0f mm".format(metadata.focalLengthMm))
                    }
                    if (metadata.exposureMode.isNotEmpty()) {
                        MetadataRow("Exposure Mode", metadata.exposureMode)
                    }
                    if (metadata.exposureProgram.isNotEmpty()) {
                        MetadataRow("Exposure Program", metadata.exposureProgram)
                    }
                    if (metadata.whiteBalance.isNotEmpty()) {
                        MetadataRow("White Balance", metadata.whiteBalance)
                    }
                }
            }
        }

        // ── Location ──────────────────────────────────────────────────────
        if (metadata.gpsLatitude != 0.0 || metadata.gpsLongitude != 0.0) {
            item {
                LocationSection(metadata = metadata)
            }
        }

        // ── Tags / Keywords ───────────────────────────────────────────────
        item {
            TagsSection(
                metadata = metadata,
                allTags = allTags,
                onAddTag = { tagId -> vm.addTag(tagId) },
                onRemoveTag = { tagId -> vm.removeTag(tagId) },
            )
        }

        // ── Notes ─────────────────────────────────────────────────────────
        item {
            NotesSection(
                notes = notes,
                onNotesChange = { vm.updateNotes(it) },
            )
        }

        // ── Rating ────────────────────────────────────────────────────────
        item {
            RatingSection(
                rating = metadata.rating,
                onSetRating = { vm.setRating(it) },
            )
        }

        // ── Color Label ───────────────────────────────────────────────────
        item {
            ColorLabelSection(
                colorLabel = ColorLabel.from(metadata.colorLabel),
                onSetColorLabel = { vm.setColorLabel(it.raw) },
            )
        }

        // ── Proxies ───────────────────────────────────────────────────────
        if (proxies.isNotEmpty()) {
            item {
                ProxiesSection(proxies = proxies)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Video player section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VideoPlayerSection(
    videoId: String,
    metadata: VideoMetadata,
    proxies: List<VideoRepository.ProxyInfo>,
) {
    val remoteEndpoint = RemoteConnection.endpoint
    val hlsUrl: String? = remoteEndpoint?.let { ep ->
        // Server route: /hls/:id/:height/*file — height must be a separate path segment.
        // Cap at 1080 for mobile; server clamps to [144,2160] and picks the nearest proxy.
        val h = if (metadata.height > 0) metadata.height.coerceAtMost(1080) else 1080
        "https://${ep.host}:${ep.mediaPort}/hls/$videoId/$h/master.m3u8"
    }

    VideoPlayer(
        streamUrl = hlsUrl,
        authToken = remoteEndpoint?.token,
        renditions = proxiesToRenditions(proxies, videoId, remoteEndpoint),
        onFullScreenToggle = null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(
                ratio = if (metadata.height > 0 && metadata.width > 0) {
                    metadata.width.toFloat() / metadata.height.toFloat()
                } else {
                    16f / 9f
                },
            ),
    )
}

private fun proxiesToRenditions(
    proxies: List<VideoRepository.ProxyInfo>,
    videoId: String,
    remoteEndpoint: RemoteConnection.Endpoint?,
): List<ProxyRendition> {
    if (remoteEndpoint == null) return emptyList()
    return proxies.map { proxy ->
        // height=0 means "original/best" — request 2160 so the server picks the highest proxy.
        val h = if (proxy.height > 0) proxy.height else 2160
        ProxyRendition(
            label = if (proxy.height == 0) "Original" else "${proxy.height}p",
            hlsUrl = "https://${remoteEndpoint.host}:${remoteEndpoint.mediaPort}/hls/$videoId/$h/master.m3u8",
            heightPx = proxy.height,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MetadataSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Metadata row (label + value)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MetadataRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (value.isEmpty()) return
    Row(
        modifier = modifier
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
            style = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Location section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LocationSection(metadata: VideoMetadata) {
    val context = LocalContext.current
    val coordString = "%.6f, %.6f".format(metadata.gpsLatitude, metadata.gpsLongitude)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "LOCATION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "GPS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.4f),
                )
                // Tappable coordinates — open in Maps.
                Text(
                    text = coordString,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDecoration = TextDecoration.Underline,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(0.6f)
                        .clickable {
                            openInMaps(context, metadata.gpsLatitude, metadata.gpsLongitude)
                        },
                )
            }
            if (metadata.gpsAltitude != 0.0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Altitude",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.4f),
                    )
                    Text(
                        text = "%.1f m".format(metadata.gpsAltitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }
        }
    }
}

private fun openInMaps(context: Context, lat: Double, lon: Double) {
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tags section
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    metadata: VideoMetadata,
    allTags: List<com.reelvault.data.models.Tag>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    var showTagPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TAGS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { showTagPicker = true },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add tag",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (metadata.tags.isEmpty()) {
                Text(
                    text = "No tags",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    metadata.tags.forEach { tagId ->
                        val tagName = allTags.firstOrNull { it.id == tagId }?.name ?: tagId
                        TagChip(
                            label = tagName,
                            onRemove = { onRemoveTag(tagId) },
                        )
                    }
                }
            }
        }
    }

    if (showTagPicker) {
        TagPickerDialog(
            allTags = allTags,
            currentTagIds = metadata.tags,
            onAddTag = { tagId ->
                onAddTag(tagId)
                showTagPicker = false
            },
            onDismiss = { showTagPicker = false },
        )
    }
}

@Composable
private fun TagChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = false,
        onClick = {},
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove $label",
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onRemove() },
            )
        },
    )
}

@Composable
private fun TagPickerDialog(
    allTags: List<com.reelvault.data.models.Tag>,
    currentTagIds: List<String>,
    onAddTag: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val available = allTags.filter { it.id !in currentTagIds }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Tag") },
        text = {
            if (available.isEmpty()) {
                Text("All tags are already applied.")
            } else {
                Column {
                    available.forEach { tag ->
                        TextButton(onClick = { onAddTag(tag.id) }) {
                            Text(tag.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Notes section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NotesSection(
    notes: String,
    onNotesChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(notes) { mutableStateOf(notes) }
    val focusManager = LocalFocusManager.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "NOTES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        if (editing) {
                            onNotesChange(draft)
                            focusManager.clearFocus()
                        }
                        editing = !editing
                    },
                ) {
                    Text(if (editing) "Save" else "Edit")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 3,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            onNotesChange(draft)
                            editing = false
                            focusManager.clearFocus()
                        },
                    ),
                )
            } else {
                Text(
                    text = notes.ifEmpty { "No notes" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notes.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.clickable { editing = true },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rating section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RatingSection(
    rating: Int,
    onSetRating: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "RATING",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 0 = clear rating (tap the current first star to toggle off)
                (1..5).forEach { star ->
                    Icon(
                        imageVector = if (star <= rating) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "$star star${if (star == 1) "" else "s"}",
                        modifier = Modifier
                            .size(28.dp)
                            .clickable {
                                // Tapping the same star a second time clears.
                                onSetRating(if (rating == star) 0 else star)
                            },
                        tint = if (star <= rating) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (rating > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$rating / 5",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Color label section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColorLabelSection(
    colorLabel: ColorLabel,
    onSetColorLabel: (ColorLabel) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "COLOR LABEL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorLabel.values().forEach { label ->
                    val isSelected = label == colorLabel
                    ColorLabelChip(
                        label = label,
                        isSelected = isSelected,
                        onClick = {
                            // Tapping the active label clears it.
                            onSetColorLabel(if (isSelected) ColorLabel.None else label)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorLabelChip(
    label: ColorLabel,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val swatchColor = label.swatch
    val bgColor = if (label == ColorLabel.None) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        if (isSelected) swatchColor else label.dimmed
    }

    Box(
        modifier = Modifier
            .size(if (isSelected) 32.dp else 28.dp)
            .background(bgColor, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (label == ColorLabel.None) {
            Text(
                text = "N",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "${label.displayName} selected",
                modifier = Modifier.size(16.dp),
                tint = Color.White,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Proxies section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProxiesSection(proxies: List<VideoRepository.ProxyInfo>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "PROXIES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            proxies.forEach { proxy ->
                ProxyRow(proxy)
                if (proxy != proxies.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyRow(proxy: VideoRepository.ProxyInfo) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = proxy.filename,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (proxy.autoDetected) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = "Auto",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
        val details = buildList {
            if (proxy.width > 0 && proxy.height > 0) add("${proxy.width}x${proxy.height}")
            if (proxy.sizeBytes > 0) add(formatBytes(proxy.sizeBytes))
        }.joinToString(" · ")
        if (details.isNotEmpty()) {
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun shareVideo(context: Context, metadata: VideoMetadata) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "video/*"
        putExtra(Intent.EXTRA_SUBJECT, metadata.filename)
        putExtra(Intent.EXTRA_TEXT, metadata.path)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share ${metadata.filename}"))
}

private fun epochMsToDisplay(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    val instant = java.time.Instant.ofEpochMilli(epochMs)
    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}

private fun audioChannelLabel(channels: Int): String = when (channels) {
    1 -> "1 (Mono)"
    2 -> "2 (Stereo)"
    6 -> "5.1 Surround"
    8 -> "7.1 Surround"
    else -> channels.toString()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024        -> "%.2f MB".format(bytes / (1024.0 * 1024))
    bytes > 0                    -> "%.2f KB".format(bytes / 1024.0)
    else                         -> ""
}
