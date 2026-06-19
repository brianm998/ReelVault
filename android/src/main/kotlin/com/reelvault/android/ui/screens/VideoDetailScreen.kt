// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.screens

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reelvault.android.ui.components.ProxyRendition
import com.reelvault.android.ui.components.VideoPlayer
import com.reelvault.android.ui.theme.swatch
import com.reelvault.android.ui.theme.dimmed
import com.reelvault.android.R
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
    /** "Show on Map" — focus the in-app map on (lat, lon). No-op host hides the button. */
    onShowOnMap: ((Double, Double) -> Unit)? = null,
) {
    val context = LocalContext.current
    val vm: DetailViewModel = viewModel(
        key = "detail_$videoId",
        factory = DetailViewModel.Factory(repository, context),
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
                        text = metadata?.filename ?: stringResource(R.string.detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    if (metadata != null) {
                        IconButton(onClick = { shareVideo(context, metadata!!) }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.detail_share),
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
                            text = error ?: stringResource(R.string.detail_video_not_found),
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
                    onShowOnMap = onShowOnMap,
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
    onShowOnMap: ((Double, Double) -> Unit)?,
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
            MetadataSection(title = stringResource(R.string.detail_section_file_info)) {
                MetadataRow(stringResource(R.string.detail_field_filename), metadata.filename)
                MetadataRow(stringResource(R.string.detail_field_path), metadata.path, monospace = true)
                MetadataRow(stringResource(R.string.detail_field_size), metadata.sizeFormatted)
                MetadataRow(stringResource(R.string.detail_field_indexed), epochMsToDisplay(metadata.indexedAt, LocalContext.current))
                MetadataRow(stringResource(R.string.detail_field_online), if (metadata.isOnline) stringResource(R.string.detail_value_online_yes) else stringResource(R.string.detail_value_online_no))
            }
        }

        // ── Video ─────────────────────────────────────────────────────────
        item {
            MetadataSection(title = stringResource(R.string.detail_section_video)) {
                MetadataRow(stringResource(R.string.detail_field_resolution), metadata.resolution)
                MetadataRow(stringResource(R.string.detail_field_codec), metadata.codecVideo.ifEmpty { stringResource(R.string.detail_em_dash) })
                if (metadata.fps > 0) MetadataRow(stringResource(R.string.detail_field_fps), "%.3f".format(metadata.fps))
                MetadataRow(stringResource(R.string.detail_field_bitrate), metadata.bitrateFormatted)
                metadata.frameCountFormatted?.let { MetadataRow(stringResource(R.string.detail_field_frames), it) }
                if (metadata.hdr) MetadataRow(stringResource(R.string.detail_field_hdr), stringResource(R.string.detail_value_yes))
                if (metadata.colorSpace.isNotEmpty()) MetadataRow(stringResource(R.string.detail_field_color_space), metadata.colorSpace)
                when (metadata.fullResolution) {
                    FullResolutionStatus.Full ->
                        MetadataRow(stringResource(R.string.detail_field_resolution_status), stringResource(R.string.detail_value_full_resolution))
                    FullResolutionStatus.NotFull ->
                        MetadataRow(stringResource(R.string.detail_field_resolution_status), stringResource(R.string.detail_value_not_full_resolution))
                    FullResolutionStatus.Unspecified -> Unit
                }
            }
        }

        // ── Audio ─────────────────────────────────────────────────────────
        if (metadata.codecAudio.isNotEmpty()) {
            item {
                MetadataSection(title = stringResource(R.string.detail_section_audio)) {
                    MetadataRow(stringResource(R.string.detail_field_codec), metadata.codecAudio)
                    if (metadata.audioChannels > 0) {
                        MetadataRow(stringResource(R.string.detail_field_channels), audioChannelLabel(metadata.audioChannels, LocalContext.current))
                    }
                    if (metadata.audioSampleRate > 0) {
                        MetadataRow(stringResource(R.string.detail_field_sample_rate), stringResource(R.string.detail_value_sample_rate, metadata.audioSampleRate))
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
                MetadataSection(title = stringResource(R.string.detail_section_capture)) {
                    if (metadata.creationDate > 0) {
                        MetadataRow(stringResource(R.string.detail_field_date), metadata.creationDateFormatted)
                    }
                    if (metadata.cameraModel.isNotEmpty()) {
                        val displayName = metadata.cameraDisplayName.ifEmpty { metadata.cameraModel }
                        MetadataRow(stringResource(R.string.detail_field_camera), displayName)
                        // Show internal name when it differs from the marketing name.
                        if (metadata.cameraDisplayName.isNotEmpty() &&
                            metadata.cameraDisplayName != metadata.cameraModel
                        ) {
                            MetadataRow(stringResource(R.string.detail_field_camera_internal), metadata.cameraModel)
                        }
                    }
                    if (metadata.lensModel.isNotEmpty()) {
                        MetadataRow(stringResource(R.string.detail_field_lens), metadata.lensModel)
                    }
                    if (metadata.iso > 0) MetadataRow(stringResource(R.string.detail_field_iso), stringResource(R.string.detail_value_iso, metadata.iso))
                    if (metadata.aperture > 0.0) {
                        MetadataRow(stringResource(R.string.detail_field_aperture), "f/%.1f".format(metadata.aperture))
                    }
                    if (metadata.exposureTimeS > 0.0) {
                        MetadataRow(stringResource(R.string.detail_field_exposure), GridStatKey.formatExposureTime(metadata.exposureTimeS))
                    }
                    if (metadata.focalLengthMm > 0.0) {
                        MetadataRow(stringResource(R.string.detail_field_focal_length), "%.0f mm".format(metadata.focalLengthMm))
                    }
                    if (metadata.exposureMode.isNotEmpty()) {
                        MetadataRow(stringResource(R.string.detail_field_exposure_mode), metadata.exposureMode)
                    }
                    if (metadata.exposureProgram.isNotEmpty()) {
                        MetadataRow(stringResource(R.string.detail_field_exposure_program), metadata.exposureProgram)
                    }
                    if (metadata.whiteBalance.isNotEmpty()) {
                        MetadataRow(stringResource(R.string.detail_field_white_balance), metadata.whiteBalance)
                    }
                }
            }
        }

        // ── Location ──────────────────────────────────────────────────────
        item {
            LocationSection(
                metadata = metadata,
                onSetLocation = { lat, lon, writeToFile ->
                    vm.setVideoLocation(lat, lon, writeToFile)
                },
                onRemoveLocation = { vm.clearVideoLocation() },
                onShowOnMap = onShowOnMap,
            )
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
        "https://${ep.host}:${ep.mediaPort}/hls/$videoId/$h/index.m3u8"
    }

    VideoPlayer(
        streamUrl = hlsUrl,
        authToken = remoteEndpoint?.token,
        renditions = proxiesToRenditions(proxies, videoId, remoteEndpoint),
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
    val ep = remoteEndpoint
    val proxyRenditions = proxies.map { proxy ->
        // height=0 means "original/best" — request 2160 so the server picks the highest proxy.
        val h = if (proxy.height > 0) proxy.height else 2160
        ProxyRendition(
            label = if (proxy.height == 0) "Original" else "${proxy.height}p",
            hlsUrl = "https://${ep.host}:${ep.mediaPort}/hls/$videoId/$h/index.m3u8",
            heightPx = proxy.height,
        )
    }
    // Always include a 480p fallback so the codec auto-fallback in VideoPlayer has a
    // guaranteed low-res option. The server re-encodes to H.264/yuv420p if no 480p
    // proxy exists — decodable on any Android device.
    val has480 = proxyRenditions.any { it.heightPx in 460..520 }
    return if (has480) proxyRenditions
    else proxyRenditions + ProxyRendition(
        label = "480p",
        hlsUrl = "https://${ep.host}:${ep.mediaPort}/hls/$videoId/480/index.m3u8",
        heightPx = 480,
    )
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
private fun LocationSection(
    metadata: VideoMetadata,
    onSetLocation: (lat: Double, lon: Double, writeToFile: Boolean) -> Unit,
    onRemoveLocation: () -> Unit,
    onShowOnMap: ((Double, Double) -> Unit)?,
) {
    val hasLocation = metadata.gpsLatitude != 0.0 || metadata.gpsLongitude != 0.0
    var showPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.detail_section_location),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (hasLocation) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.detail_field_gps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.4f),
                    )
                    Text(
                        text = "%.6f, %.6f".format(metadata.gpsLatitude, metadata.gpsLongitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.6f),
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
                            text = stringResource(R.string.detail_field_altitude),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.4f),
                        )
                        Text(
                            text = stringResource(R.string.detail_value_altitude, metadata.gpsAltitude),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(0.6f),
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.detail_no_location),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons mirror the iOS LocationButtonsSection.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LocationActionButton(
                    text = if (hasLocation) stringResource(R.string.detail_change_location) else stringResource(R.string.detail_set_location),
                    icon = Icons.Default.EditLocationAlt,
                    onClick = { showPicker = true },
                )
                if (hasLocation) {
                    LocationActionButton(
                        text = stringResource(R.string.detail_remove_location),
                        icon = Icons.Default.WrongLocation,
                        destructive = true,
                        onClick = onRemoveLocation,
                    )
                    if (onShowOnMap != null) {
                        LocationActionButton(
                            text = stringResource(R.string.detail_show_on_map),
                            icon = Icons.Default.Map,
                            onClick = { onShowOnMap(metadata.gpsLatitude, metadata.gpsLongitude) },
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        LocationPickerSheet(
            initial = if (hasLocation) metadata.gpsLatitude to metadata.gpsLongitude else null,
            onApply = { lat, lon, writeToFile ->
                onSetLocation(lat, lon, writeToFile)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun LocationActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OutlinedButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, color = tint)
    }
}

/**
 * Map-based location picker: drag the map under a fixed centre pin to choose a
 * point, optionally write it into the file, Save. The Android counterpart of the
 * iOS [LocationPickerSheet] (OSMDroid instead of MapKit).
 */
@Composable
private fun LocationPickerSheet(
    initial: Pair<Double, Double>?,
    onApply: (lat: Double, lon: Double, writeToFile: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = initial ?: (20.0 to 0.0)
    var center by remember { mutableStateOf(start) }
    var writeToFile by remember { mutableStateOf(false) }
    var mapViewRef by remember { mutableStateOf<org.osmdroid.views.MapView?>(null) }

    DisposableEffect(Unit) { onDispose { mapViewRef?.onDetach() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    Text(stringResource(R.string.detail_set_location_title), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { onApply(center.first, center.second, writeToFile) }) {
                        Text(stringResource(R.string.common_save))
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            // Configured once at app startup; this guard is cheap.
                            // (Avoids load()'s main-thread storage probe — see OsmConfig.)
                            com.reelvault.android.util.OsmConfig.ensureInitialized(ctx)
                            org.osmdroid.views.MapView(ctx).apply {
                                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                                setUseDataConnection(true)
                                setMultiTouchControls(true)
                                isTilesScaledToDpi = true
                                controller.setZoom(if (initial != null) 14.0 else 2.0)
                                controller.setCenter(org.osmdroid.util.GeoPoint(start.first, start.second))
                                addMapListener(object : org.osmdroid.events.MapListener {
                                    override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                                        val c = mapCenter
                                        center = c.latitude to c.longitude
                                        return false
                                    }
                                    override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean = false
                                })
                                mapViewRef = this
                            }
                        },
                    )
                    // Fixed crosshair pin: the map moves under it; the tip marks
                    // the chosen point (offset so the tip, not the glyph centre,
                    // sits on the map centre). Non-interactive so drags reach the map.
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(40.dp)
                            .offset(y = (-20).dp),
                    )
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "%.5f, %.5f".format(center.first, center.second),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = stringResource(R.string.detail_drag_to_position),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = writeToFile, onCheckedChange = { writeToFile = it })
                        Column {
                            Text(
                                text = stringResource(R.string.detail_write_location_to_file),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.detail_write_location_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
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
                    text = stringResource(R.string.detail_section_tags),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { showTagPicker = true },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.detail_add_tag),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (metadata.tags.isEmpty()) {
                Text(
                    text = stringResource(R.string.detail_no_tags),
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
                contentDescription = stringResource(R.string.detail_remove_tag, label),
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
        title = { Text(stringResource(R.string.detail_add_tag_title)) },
        text = {
            if (available.isEmpty()) {
                Text(stringResource(R.string.detail_all_tags_applied))
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
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
                    text = stringResource(R.string.detail_section_notes),
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
                    Text(if (editing) stringResource(R.string.common_save) else stringResource(R.string.detail_edit))
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
                    text = notes.ifEmpty { stringResource(R.string.detail_no_notes) },
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
                text = stringResource(R.string.detail_section_rating),
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
                        contentDescription = pluralStringResource(R.plurals.detail_star_count, star, star),
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
                        text = stringResource(R.string.detail_rating_out_of_five, rating),
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
                text = stringResource(R.string.detail_section_color_label),
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
                text = stringResource(R.string.detail_color_label_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.detail_color_label_selected, label.displayName),
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
                text = stringResource(R.string.detail_section_proxies),
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
                        text = stringResource(R.string.detail_proxy_auto),
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
    context.startActivity(
        Intent.createChooser(
            shareIntent,
            context.getString(R.string.detail_share_subject, metadata.filename),
        )
    )
}

private fun epochMsToDisplay(epochMs: Long, context: Context): String {
    if (epochMs <= 0L) return context.getString(R.string.detail_em_dash)
    val instant = java.time.Instant.ofEpochMilli(epochMs)
    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}

private fun audioChannelLabel(channels: Int, context: Context): String = when (channels) {
    1 -> context.getString(R.string.detail_audio_channels_mono)
    2 -> context.getString(R.string.detail_audio_channels_stereo)
    6 -> context.getString(R.string.detail_audio_channels_51)
    8 -> context.getString(R.string.detail_audio_channels_71)
    else -> channels.toString()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024        -> "%.2f MB".format(bytes / (1024.0 * 1024))
    bytes > 0                    -> "%.2f KB".format(bytes / 1024.0)
    else                         -> ""
}
