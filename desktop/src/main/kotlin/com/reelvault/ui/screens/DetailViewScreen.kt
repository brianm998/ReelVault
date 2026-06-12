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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.reelvault.LocalAppWindow
import com.reelvault.util.FileDragSource
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
    /**
     * Monotonically-incrementing tokens from App.kt: each increment of
     * [stepBackToggle] / [stepForwardToggle] is a ← / → press in detail mode,
     * stepping the playhead one frame back / forward. The handlers below act
     * only while the clip is paused (not in the initial scrub-preview and not
     * playing). 0 on first composition → no action taken on initial render.
     */
    stepBackToggle: Int = 0,
    stepForwardToggle: Int = 0,
    modifier: Modifier = Modifier
) {
    val selectedVideoId by gridViewModel.selectedVideoId.collectAsState()
    val videos by gridViewModel.videos.collectAsState()
    // Cached summary of the global selection — written by every selection path
    // (grid, list, and the map's right panel). Used as the fallback below.
    val selectedSummary by gridViewModel.selectedVideo
    val scrubFramesMap by gridViewModel.scrubFrames.collectAsState()
    val thumbnailsMap by gridViewModel.thumbnails.collectAsState()
    val hiResScrubMap by gridViewModel.hiResScrubFrames.collectAsState()
    val hiResPosterMap by gridViewModel.hiResPoster.collectAsState()
    val metadata = detailViewModel.metadata.value

    // Resolve the selected video from the loaded grid page when possible, else
    // fall back to the global selection's cached summary. The grid paginates,
    // so a video chosen from the map (whose full geotagged set is loaded
    // separately) usually isn't in `videos` — without the fallback the loupe
    // would show its empty "select a video" placeholder even though one is
    // selected.
    val video: VideoSummary? = remember(selectedVideoId, videos, selectedSummary) {
        videos.firstOrNull { it.id == selectedVideoId }
            ?: selectedSummary?.takeIf { it.id == selectedVideoId }
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
    val player = remember(video.id) { ComposeVideoPlayer(preciseSeek = true) }
    DisposableEffect(video.id) {
        onDispose {
            player.release()
            // Leaving this video (or the detail view) cancels any in-flight
            // hi-res thumbnail generation; returning resumes it.
            gridViewModel.cancelHiResDetail(video.id)
        }
    }
    // Hide the top-bar proxy indicator when the loupe leaves the screen
    // (switching to grid/list, or clearing the selection).
    DisposableEffect(Unit) {
        onDispose { detailViewModel.setProxyBanner(null) }
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

    // ← / → frame stepping (App.kt forwards each press as a token bump). These
    // act ONLY in the "paused" state — playback has been started (so we're past
    // the initial scrub-thumbnail preview) but the clip isn't currently playing
    // because the user paused it or it reached its end. No-op in the preview and
    // while playing. Each press does exactly what the on-screen single-frame
    // step buttons do (← = skipFrames(-1); → = the native nextFrame), leaving the
    // clip paused on the new frame. The `playbackStarted && !isPlaying` gate also
    // makes a stale token harmless on a fresh composition (the new player hasn't
    // started). Skip the initial composition (token 0) so mounting never seeks.
    val frameStepFps = video.fps.takeIf { it > 0.0 } ?: 30.0
    val canFrameStep = { playbackStarted && player.available && !player.isPlaying.value }
    LaunchedEffect(stepBackToggle) {
        if (stepBackToggle == 0) return@LaunchedEffect
        if (canFrameStep()) player.skipFrames(-1, frameStepFps)
    }
    LaunchedEffect(stepForwardToggle) {
        if (stepForwardToggle == 0) return@LaunchedEffect
        if (canFrameStep()) player.stepForwardOneFrame()
    }

    // Drag-out support: drag the master clip from the detail view into an
    // external editor / file manager. Mirrors the grid card's gesture (detect
    // motion in Compose, hand off to AWT via FileDragSource). Note: while a
    // video is playing the render surface is a heavyweight AWT component, so
    // the gesture fires over the scrub-preview / letterbox margins; the macOS
    // client (layer-backed AVPlayer) can drag from anywhere on the frame.
    val awtWindow = LocalAppWindow.current
    val fileDragSource = remember { FileDragSource() }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // Measure the player render area so effectivePath can default to
                // the proxy whose resolution best matches it.
                .onSizeChanged { areaSize = it }
                .pointerInput(video.openPath) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPos = down.position
                        fileDragSource.setPendingFiles(listOf(video.openPath))
                        var handled = false
                        while (!handled) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                fileDragSource.clearPending()
                                handled = true
                            } else {
                                val delta = change.position - startPos
                                val dist = kotlin.math.sqrt(
                                    (delta.x * delta.x + delta.y * delta.y).toDouble()
                                ).toFloat()
                                if (dist >= 8f && awtWindow != null) {
                                    val screenX = (awtWindow.x + change.position.x).toInt()
                                    val screenY = (awtWindow.y + change.position.y).toInt()
                                    fileDragSource.startDragIfPending(awtWindow, screenX, screenY)
                                    handled = true
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (playbackStarted && player.available) {
                // Stretch the proxy/master to the ORIGINAL video's aspect ratio
                // so a mismatched-ratio proxy fills the frame (black letterbox)
                // rather than being aspect-fit to its own shape.
                val originalAspect = if (video.width > 0 && video.height > 0)
                    video.width.toFloat() / video.height.toFloat() else null
                player.Surface(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    targetAspectRatio = originalAspect
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

            // Proxy-playback indicator. Shown whenever the player is loading
            // a proxy instead of the master — either because the master is
            // too large to play inline (auto fallback) or because the user
            // explicitly picked a proxy in the right panel. The badge itself
            // now lives in the top bar (so it never covers the frame); here
            // we just publish its content. The right panel remains where the
            // user picks a different proxy or reverts to the master.
            LaunchedEffect(effectivePath, video.id, video.path, selectedProxyId, proxies) {
                detailViewModel.setProxyBanner(
                    if (effectivePath != video.path) {
                        val activeProxy = proxies.firstOrNull { it.path == effectivePath }
                        DetailViewModel.ProxyBanner(
                            selected = selectedProxyId != null,
                            detail = activeProxy?.let { "${it.filename} • ${it.height}p" },
                        )
                    } else null
                )
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

    // Scrub state. While the user drags the slider we show their finger
    // position (dragMs); after they release we hold that position
    // (pendingSeekMs) until libvlc's clock catches up. Without this the Slider
    // value is bound straight to the event-driven currentTimeMs, so the thumb
    // snaps back to the last reported time and never appears to move after a
    // seek — and not at all while paused, when no timeChanged events fire.
    var dragMs by remember(video.id) { mutableStateOf<Float?>(null) }
    var pendingSeekMs by remember(video.id) { mutableStateOf<Long?>(null) }
    // Release the held position once playback's reported time reaches it.
    LaunchedEffect(currentMs) {
        val p = pendingSeekMs
        if (p != null && kotlin.math.abs(currentMs - p) <= 400L) pendingSeekMs = null
    }

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
        // Finger position while dragging, the held seek target just after
        // release, otherwise the live playback position.
        val displayedMs: Long = dragMs?.toLong() ?: pendingSeekMs
            ?: if (lengthMs > 0) currentMs else 0L
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(displayedMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp)
            )
            Slider(
                value = displayedMs.toFloat().coerceIn(0f, maxMs.toFloat()),
                onValueChange = { v ->
                    if (playbackStarted && player.available) {
                        // Move the thumb with the finger and scrub live.
                        dragMs = v
                        player.seek(v.toLong())
                    }
                },
                onValueChangeFinished = {
                    // Hold the thumb where the user dropped it until the
                    // player's reported time catches up (cleared above).
                    dragMs?.let { pendingSeekMs = it.toLong().coerceIn(0L, maxMs) }
                    dragMs = null
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
