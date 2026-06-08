// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.reelvault.data.models.VideoMetadata
import com.reelvault.data.models.VideoSummary
import com.reelvault.ui.components.ComposeVideoPlayer
import com.reelvault.ui.components.VlcUnavailableOverlay
import com.reelvault.ui.theme.ReelVaultSpacing
import com.reelvault.viewmodel.DetailViewModel
import com.reelvault.viewmodel.GridViewModel
import org.jetbrains.skia.Image as SkiaImage
import org.slf4j.LoggerFactory

private val detailLogger = LoggerFactory.getLogger("com.reelvault.ui.screens.DetailViewScreen")

/** Three-state info overlay cycle, advanced by the 'i' key (Lightroom-style). */
enum class InfoOverlayState { NONE, CAMERA, FILE }

/**
 * Single-video loupe view. Shows the selected video full-size with hover-scrub
 * preview, a bottom playback/control bar, and a cycling info overlay.
 *
 * When the user hits "play" the screen loads VLCJ and switches from the static
 * scrub-frame preview to live in-app playback. The scrubber and control bar
 * stay visible.
 */
@Composable
fun DetailViewScreen(
    gridViewModel: GridViewModel,
    detailViewModel: DetailViewModel,
    /** Externally-controlled info overlay state, advanced by the 'i' shortcut in App.kt. */
    infoOverlay: InfoOverlayState,
    /**
     * Monotonically-incrementing token from App.kt. Each increment (fired by
     * the space bar) triggers a play/pause toggle — start playback if not yet
     * started, or toggle pause if already playing. 0 on first composition →
     * no action taken on initial render.
     */
    playToggle: Int = 0,
    modifier: Modifier = Modifier
) {
    val selectedVideoId by gridViewModel.selectedVideoId.collectAsState()
    val videos by gridViewModel.videos.collectAsState()
    val scrubFramesMap by gridViewModel.scrubFrames.collectAsState()
    val thumbnailsMap by gridViewModel.thumbnails.collectAsState()
    val hiResScrubMap by gridViewModel.hiResScrubFrames.collectAsState()
    val hiResPosterMap by gridViewModel.hiResPoster.collectAsState()
    val metadata = detailViewModel.metadata.value

    val video: VideoSummary? = remember(selectedVideoId, videos) {
        videos.firstOrNull { it.id == selectedVideoId }
    }

    // Configurable step size for ±N-frame buttons. Default 20.
    var stepFrames by remember { mutableStateOf(20) }

    if (video == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(ReelVaultSpacing.Medium))
                Text(
                    text = "Select a video in the grid to view it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                Text(
                    text = "Press G to return to the grid.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // Lazily load scrub frames for the current video (parity with grid hover).
    LaunchedEffect(video.id) {
        gridViewModel.loadScrubFrames(video.id)
        if (video.hasThumbnail) {
            gridViewModel.loadThumbnail(video.id)
        }
    }

    // Player lifecycle: one player per selected video. Releasing on key change
    // ensures we don't leak libvlc handles when the user pages through videos.
    val player = remember(video.id) { ComposeVideoPlayer() }
    DisposableEffect(video.id) {
        onDispose {
            player.release()
            // Leaving this video (or the detail view) cancels any in-flight
            // hi-res thumbnail generation; returning resumes it.
            gridViewModel.cancelHiResDetail(video.id)
        }
    }

    // Mode flag: "play" hasn't been pressed yet → show scrub thumbnail preview.
    // After "play", VLCJ takes over the preview area.
    var playbackStarted by remember(video.id) { mutableStateOf(false) }

    // Resolve which path the player should load right now: explicit proxy
    // pick from the right panel takes precedence, then the
    // unplayable-master → smallest-proxy fallback, then the master path.
    // We re-derive on every recomposition (cheap — just two state reads
    // plus a list lookup) so picking a different proxy mid-session
    // immediately changes what gets loaded.
    val selectedProxyId by detailViewModel.selectedProxyId.collectAsState()
    val proxies by detailViewModel.proxies.collectAsState()
    // Pixel size of the player render area, measured by the layout below. The
    // detail player defaults to the proxy whose resolution best matches this
    // area's height (re-derived when the area is first measured / resized).
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    val effectivePath: String = remember(video.id, selectedProxyId, proxies, areaSize.height) {
        detailViewModel.playbackPathFor(video, areaSize.height) ?: video.path
    }

    // After a 2 s dwell on this video's detail view, upgrade its thumbnails to
    // the render resolution so scrubbing shows higher-res frames. The delay is
    // cancelled if the user leaves (key change), so a quick glance never
    // triggers generation; cancellation of the work itself is handled in the
    // DisposableEffect above.
    LaunchedEffect(video.id) {
        kotlinx.coroutines.delay(2000)
        val w = areaSize.width
        if (w > 0) gridViewModel.startHiResDetail(video.id, w)
    }
    // If the user picks a different proxy (or reverts to master) while
    // a video is already playing, swap the URL in place. Skip when the
    // player isn't active so we don't auto-start playback on selection.
    LaunchedEffect(effectivePath, playbackStarted) {
        if (playbackStarted && player.available) {
            player.load(effectivePath, playImmediately = true)
        }
    }

    // Space bar handler: each increment of playToggle fires a play/pause.
    // Skip the initial composition (playToggle == 0) to avoid auto-playing
    // when the screen first mounts.
    LaunchedEffect(playToggle) {
        if (playToggle == 0) return@LaunchedEffect
        detailLogger.info(
            "Space-bar toggle: video={} playbackStarted={} player.available={} initError={}",
            video.id, playbackStarted, player.available,
            player.initError?.javaClass?.simpleName
        )
        if (!player.available) return@LaunchedEffect
        if (playbackStarted) {
            player.togglePause()
        } else {
            player.load(effectivePath, playImmediately = true)
            playbackStarted = true
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // Measure the player render area so effectivePath can default to
                // the proxy whose resolution best matches it.
                .onSizeChanged { areaSize = it },
            contentAlignment = Alignment.Center
        ) {
            if (playbackStarted && player.available) {
                player.Surface(
                    modifier = Modifier.fillMaxSize().background(Color.Black)
                )
            } else {
                ScrubPreview(
                    video = video,
                    thumbnailBytes = thumbnailsMap[video.id],
                    scrubFrames = scrubFramesMap[video.id] ?: emptyList(),
                    hiResScrubFrames = hiResScrubMap[video.id] ?: emptyList(),
                    hiResPosterBytes = hiResPosterMap[video.id],
                    modifier = Modifier.fillMaxSize().background(Color.Black)
                )
            }

            // Cycling info overlay (top-left).
            if (infoOverlay != InfoOverlayState.NONE) {
                InfoOverlay(
                    state = infoOverlay,
                    video = video,
                    metadata = metadata,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(ReelVaultSpacing.Medium)
                )
            }

            // Proxy-playback banner (top-right). Visible whenever the
            // player is loading a proxy instead of the master — either
            // because the master is too large to play inline (auto
            // fallback) or because the user explicitly picked a proxy
            // in the right panel. Reassures the user that yes, this is
            // playable, and tells them which file they're seeing.
            //
            // The badge is informational only; the right panel is where
            // the user picks a different proxy or reverts to master.
            if (effectivePath != video.path) {
                val activeProxy = remember(effectivePath, proxies) {
                    proxies.firstOrNull { it.path == effectivePath }
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(ReelVaultSpacing.Medium),
                    color = Color(0xFF408888).copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(modifier = Modifier.padding(ReelVaultSpacing.Small)) {
                        Text(
                            text = if (selectedProxyId != null) "Playing selected proxy"
                                else "Playing proxy",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (activeProxy != null) {
                            Text(
                                text = "${activeProxy.filename} • ${activeProxy.height}p",
                                color = Color.White.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            // "VLC not installed" or "VLC found but video surface is black"
            // overlay. VlcUnavailableOverlay handles both cases: immediate
            // display when player.available is false (libvlc not found), and
            // a 3-second health-check timer when available is true but
            // renderingHealthy stays false (black-surface failure on macOS
            // Compose Desktop + CoreVideo conflict).
            if (playbackStarted) {
                VlcUnavailableOverlay(player = player.takeIf { it.available })
            }
        }

        ControlBar(
            video = video,
            player = player,
            playbackStarted = playbackStarted,
            stepFrames = stepFrames,
            onStartPlayback = {
                detailLogger.info(
                    "Detail play button: video={} playbackStarted={} player.available={} initError={}",
                    video.id, playbackStarted, player.available,
                    player.initError?.javaClass?.simpleName
                )
                if (!playbackStarted) {
                    playbackStarted = true
                    if (player.available) {
                        player.load(effectivePath, playImmediately = true)
                    }
                    // If libvlc isn't available, playbackStarted is still set
                    // so the "VLCJ unavailable" overlay shows; the Stop button
                    // remains enabled so the user can return to the preview.
                } else {
                    player.togglePause()
                }
            },
            onStopPlayback = {
                player.stop()
                playbackStarted = false
            },
            onStepFramesChange = { stepFrames = it.coerceIn(1, 600) }
        )
    }
}

@Composable
private fun ScrubPreview(
    video: VideoSummary,
    thumbnailBytes: ByteArray?,
    scrubFrames: List<ByteArray?>,
    /** Detail-resolution scrub frames, filled in incrementally while the user
     *  dwells (see GridViewModel.startHiResDetail). Preferred over the base
     *  [scrubFrames] per location when present. */
    hiResScrubFrames: List<ByteArray?> = emptyList(),
    /** Detail-resolution static frame, shown when not scrubbing. */
    hiResPosterBytes: ByteArray? = null,
    modifier: Modifier = Modifier
) {
    fun decode(bytes: ByteArray?) =
        bytes?.let { try { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() } catch (_: Exception) { null } }

    val thumbnailImage = remember(thumbnailBytes) { decode(thumbnailBytes) }
    val hiResPosterImage = remember(hiResPosterBytes) { decode(hiResPosterBytes) }
    val scrubImages = remember(scrubFrames) { scrubFrames.map { decode(it) } }
    val hiResScrubImages = remember(hiResScrubFrames) { hiResScrubFrames.map { decode(it) } }
    // Best static (non-hover) frame: the hi-res poster once it's fetched.
    val posterImage = hiResPosterImage ?: thumbnailImage

    var hoverX by remember { mutableStateOf<Float?>(null) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }

    val displayed = run {
        val x = hoverX
        val w = areaSize.width
        if (x != null && w > 0 && scrubImages.any { it != null }) {
            val frac = (x / w).coerceIn(0f, 1f)
            val idx = (frac * scrubImages.size).toInt().coerceIn(0, scrubImages.size - 1)
            // Highest resolution available for this scrub location.
            hiResScrubImages.getOrNull(idx) ?: scrubImages[idx] ?: posterImage
        } else posterImage
    }

    Box(
        modifier = modifier
            .onSizeChanged { areaSize = it }
            .onPointerEvent(PointerEventType.Enter) {
                it.changes.firstOrNull()?.position?.let { p -> hoverX = p.x }
            }
            .onPointerEvent(PointerEventType.Move) {
                it.changes.firstOrNull()?.position?.let { p -> hoverX = p.x }
            }
            .onPointerEvent(PointerEventType.Exit) { hoverX = null },
        contentAlignment = Alignment.Center
    ) {
        if (displayed != null) {
            Image(
                bitmap = displayed,
                contentDescription = video.filename,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ControlBar(
    video: VideoSummary,
    player: ComposeVideoPlayer,
    playbackStarted: Boolean,
    stepFrames: Int,
    onStartPlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onStepFramesChange: (Int) -> Unit
) {
    val currentMs by player.currentTimeMs
    val lengthMs by player.lengthMs
    val isPlaying by player.isPlaying
    val fps = video.fps.takeIf { it > 0.0 } ?: 30.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = ReelVaultSpacing.Medium, vertical = ReelVaultSpacing.Small)
    ) {
        // Scrubber. Uses the player's length when known, otherwise falls back
        // to the video's reported duration so the bar still renders before
        // playback has been started.
        val maxMs = if (lengthMs > 0) lengthMs else video.durationMs.coerceAtLeast(1L)
        val displayedMs = if (lengthMs > 0) currentMs else 0L
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(displayedMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp)
            )
            Slider(
                value = displayedMs.toFloat(),
                onValueChange = { v ->
                    if (playbackStarted && player.available) {
                        player.seek(v.toLong())
                    }
                },
                valueRange = 0f..maxMs.toFloat(),
                enabled = playbackStarted && player.available,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatTime(maxMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(ReelVaultSpacing.XSmall))

        // Control buttons.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.reelvault.ui.components.Tooltip(text = "Step back $stepFrames frames") {
                IconButton(
                    onClick = { player.skipFrames(-stepFrames, fps) },
                    enabled = playbackStarted && player.available
                ) {
                    Icon(Icons.Default.FastRewind, contentDescription = "Step back $stepFrames frames")
                }
            }
            com.reelvault.ui.components.Tooltip(text = "Step back 1 frame") {
                IconButton(
                    onClick = { player.skipFrames(-1, fps) },
                    enabled = playbackStarted && player.available
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Step back 1 frame")
                }
            }
            com.reelvault.ui.components.Tooltip(
                text = if (isPlaying) "Pause" else "Play in-app"
            ) {
                IconButton(onClick = onStartPlayback) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            com.reelvault.ui.components.Tooltip(
                text = "Stop and return to the hover-scrub thumbnail preview"
            ) {
                IconButton(
                    onClick = onStopPlayback,
                    enabled = playbackStarted
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop"
                    )
                }
            }
            com.reelvault.ui.components.Tooltip(text = "Step forward 1 frame") {
                IconButton(
                    onClick = { player.stepForwardOneFrame() },
                    enabled = playbackStarted && player.available
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Step forward 1 frame")
                }
            }
            com.reelvault.ui.components.Tooltip(text = "Step forward $stepFrames frames") {
                IconButton(
                    onClick = { player.skipFrames(stepFrames, fps) },
                    enabled = playbackStarted && player.available
                ) {
                    Icon(Icons.Default.FastForward, contentDescription = "Step forward $stepFrames frames")
                }
            }

            Spacer(modifier = Modifier.width(ReelVaultSpacing.Large))

            // Configurable step size (default 20).
            com.reelvault.ui.components.Tooltip(
                text = "How many frames the \"step ±N\" buttons skip. Default 20."
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Step:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(ReelVaultSpacing.XSmall))
                    OutlinedButton(
                        onClick = { onStepFramesChange(stepFrames - 5) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("-5", style = MaterialTheme.typography.labelSmall) }
                    Text(
                        text = "$stepFrames",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = ReelVaultSpacing.Small)
                    )
                    OutlinedButton(
                        onClick = { onStepFramesChange(stepFrames + 5) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("+5", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun InfoOverlay(
    state: InfoOverlayState,
    video: VideoSummary,
    metadata: VideoMetadata?,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(ReelVaultSpacing.Medium)) {
            when (state) {
                InfoOverlayState.NONE -> Unit
                InfoOverlayState.CAMERA -> {
                    val camera = metadata?.cameraModel?.ifEmpty { null } ?: "Camera: unknown"
                    val lens = metadata?.lensModel?.ifEmpty { null } ?: "Lens: unknown"
                    Text(
                        text = camera,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = lens,
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                InfoOverlayState.FILE -> {
                    val mp = if (video.width > 0 && video.height > 0) {
                        String.format("%.1f MP", (video.width.toLong() * video.height.toLong()) / 1_000_000.0)
                    } else "—"
                    val captured = metadata?.creationDateFormatted?.takeIf { it != "Unknown" }
                        ?: if (video.creationDate > 0) {
                            java.time.format.DateTimeFormatter
                                .ofPattern("yyyy-MM-dd")
                                .withZone(java.time.ZoneId.systemDefault())
                                .format(java.time.Instant.ofEpochMilli(video.creationDate))
                        } else "Unknown"
                    Text(
                        text = video.filename,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Captured: $captured",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "${video.width} × ${video.height} ($mp)",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}
