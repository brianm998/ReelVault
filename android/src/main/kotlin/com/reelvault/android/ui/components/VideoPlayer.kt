// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.components

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reelvault.android.R
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// Public entry point
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Embeds an ExoPlayer for HLS streaming, with custom overlay controls.
 *
 * - Accepts a nullable [streamUrl] (null = show placeholder, not an error).
 * - Full-screen toggle: tapping the button in the controls enters a full-screen
 *   Dialog in landscape orientation with system bars hidden. Back button or the
 *   exit button returns to the embedded view. The same ExoPlayer instance is
 *   shared — only the surface (PlayerView) changes, so playback is seamless.
 * - Codec capability fallback: if ExoPlayer reports
 *   [PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES] (e.g.,
 *   a device that can only decode 1080p HEVC receives 4K), the player
 *   automatically retries with the lowest-resolution rendition in [renditions].
 *   Always provide a low-res fallback rendition (e.g., 480p) so there is
 *   something to fall back to.
 * - Quality/rendition picker: pass a list of [ProxyRendition] entries;
 *   selecting one swaps the stream URL and seeks back to the current position.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    streamUrl: String?,
    modifier: Modifier = Modifier,
    renditions: List<ProxyRendition> = emptyList(),
    authToken: String? = null,
    // Local (on-device) mode: a content:// or file:// URI played progressively
    // instead of over HLS. When set, [streamUrl] is null. Mirrors iOS
    // StreamPlayer.prepareLocal.
    localUri: String? = null,
) {
    val context = LocalContext.current
    // Either a remote HLS stream or a local progressive URI counts as "has media".
    val hasMedia = streamUrl != null || localUri != null

    var isFullscreen by remember { mutableStateOf(false) }

    // ── Player instance (recreated when the media source changes) ─────
    val exoPlayer = remember(streamUrl, localUri) {
        ExoPlayer.Builder(context).build().also { player ->
            when {
                localUri != null -> {
                    Log.i("VideoPlayer", "Playing local: $localUri")
                    // DefaultMediaSourceFactory resolves content:// / file:// and
                    // demuxes the progressive container (mp4/mov/…).
                    player.setMediaItem(MediaItem.fromUri(localUri))
                    player.prepare()
                    player.playWhenReady = false
                }
                streamUrl != null -> {
                    Log.i("VideoPlayer", "Starting HLS: $streamUrl (token=${authToken?.take(8)}…)")
                    val dataSourceFactory = HlsTokenDataSourceFactory(authToken)
                    val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(streamUrl))
                    player.setMediaSource(mediaSource)
                    player.prepare()
                    // Do NOT autoplay — buffering to STATE_READY shows the first
                    // frame as a poster behind a centred play button; playback only
                    // begins when the user taps it. Matches the iOS detail view.
                    player.playWhenReady = false
                }
            }
        }
    }

    // True once the user has tapped the big centred play button. Tied to the
    // media source so a brand-new video starts paused with the poster again.
    var userStartedPlayback by remember(streamUrl, localUri) { mutableStateOf(false) }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    // ── Playback state observation ────────────────────────────────────
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<PlaybackException?>(null) }
    var selectedRendition by remember { mutableStateOf<ProxyRendition?>(null) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { playbackState = state }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(error: PlaybackException) {
                Log.e("VideoPlayer", "Playback error: ${error.message}", error.cause)
                // Auto-fallback when the device's codec can't handle the resolution
                // (e.g., a mid-range phone served a 4K HEVC copy-mux it can't decode).
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES) {
                    val fallback = renditions
                        .filter { it.heightPx > 0 && it != selectedRendition }
                        .minByOrNull { it.heightPx }
                    if (fallback != null) {
                        Log.i("VideoPlayer", "Codec exceeds capabilities → auto-switching to ${fallback.label}")
                        selectedRendition = fallback
                        return
                    }
                }
                playerError = error
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // ── Seek position ─────────────────────────────────────────────────
    var seekPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!isScrubbing) {
                seekPositionMs = exoPlayer.currentPosition
                durationMs = exoPlayer.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
        if (!isScrubbing) {
            seekPositionMs = exoPlayer.currentPosition
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
        }
    }

    // ── Volume ────────────────────────────────────────────────────────
    var volume by remember { mutableFloatStateOf(1f) }

    // ── Controls visibility (auto-hide after 3 s) ─────────────────────
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTapTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lastTapTimeMs, isPlaying) {
        if (isPlaying) {
            delay(3_000)
            if (System.currentTimeMillis() - lastTapTimeMs >= 3_000) {
                controlsVisible = false
            }
        }
    }

    // ── Quality picker ────────────────────────────────────────────────
    var showQualityPicker by remember { mutableStateOf(false) }

    val activeUrl = selectedRendition?.hlsUrl ?: streamUrl
    LaunchedEffect(activeUrl) {
        if (activeUrl != null && activeUrl != streamUrl) {
            val savedPos = exoPlayer.currentPosition
            val wasPaused = !exoPlayer.isPlaying
            val dataSourceFactory = HlsTokenDataSourceFactory(authToken)
            val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(activeUrl))
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.seekTo(savedPos)
            if (!wasPaused) exoPlayer.play()
        }
    }

    // ── Fullscreen: orientation + system bar management ───────────────
    // Uses DisposableEffect so cleanup is guaranteed when isFullscreen flips back.
    DisposableEffect(isFullscreen) {
        if (!isFullscreen) return@DisposableEffect onDispose { }
        val activity = (context as? Activity)
            ?: return@DisposableEffect onDispose { }
        val origOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val ctrl = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        onDispose {
            activity.requestedOrientation = origOrientation
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Inline layout
    // ─────────────────────────────────────────────────────────────────

    Box(
        modifier = modifier
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                controlsVisible = !controlsVisible
                lastTapTimeMs = System.currentTimeMillis()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (hasMedia) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        useController = false
                        player = exoPlayer
                    }
                },
                // Detach the surface while the fullscreen Dialog holds it; reattach on exit.
                update = { view -> view.player = if (isFullscreen) null else exoPlayer },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!hasMedia) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.VideoFile,
                    contentDescription = stringResource(R.string.player_no_stream),
                    modifier = Modifier.size(56.dp),
                    tint = Color.White.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.player_no_stream),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }

        val isBuffering = hasMedia && playerError == null && playbackState == Player.STATE_BUFFERING
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }

        // Poster-state play button: shown over the first frame until the user
        // chooses to start playback (no autoplay).
        val showPlayButton = hasMedia && playerError == null &&
            !userStartedPlayback && playbackState != Player.STATE_BUFFERING
        if (showPlayButton) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .clickable {
                        exoPlayer.play()
                        userStartedPlayback = true
                        controlsVisible = true
                        lastTapTimeMs = System.currentTimeMillis()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.player_play),
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        if (playerError != null) {
            ErrorOverlay(
                message = playerError!!.localizedMessage ?: stringResource(R.string.player_playback_error),
                onRetry = {
                    playerError = null
                    exoPlayer.prepare()
                    exoPlayer.play()
                },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && playerError == null && hasMedia && userStartedPlayback,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ControlsOverlay(
                isPlaying = isPlaying,
                positionMs = seekPositionMs,
                durationMs = durationMs,
                volume = volume,
                hasRenditions = renditions.isNotEmpty(),
                isFullscreen = false,
                onPlayPause = {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    lastTapTimeMs = System.currentTimeMillis()
                },
                onScrubStart = { isScrubbing = true },
                onScrub = { fraction -> seekPositionMs = (fraction * durationMs).toLong() },
                onScrubEnd = { fraction ->
                    isScrubbing = false
                    exoPlayer.seekTo((fraction * durationMs).toLong())
                    lastTapTimeMs = System.currentTimeMillis()
                },
                onVolumeChange = { v ->
                    volume = v
                    exoPlayer.volume = v
                    lastTapTimeMs = System.currentTimeMillis()
                },
                onQualityClick = {
                    showQualityPicker = true
                    lastTapTimeMs = System.currentTimeMillis()
                },
                onFullScreen = {
                    isFullscreen = true
                    lastTapTimeMs = System.currentTimeMillis()
                },
            )
        }
    }

    // ── Quality picker dialog ─────────────────────────────────────────
    if (showQualityPicker) {
        RenditionPickerDialog(
            renditions = renditions,
            selectedRendition = selectedRendition,
            onSelect = { rendition ->
                selectedRendition = rendition
                showQualityPicker = false
            },
            onDismiss = { showQualityPicker = false },
        )
    }

    // ── Fullscreen dialog ─────────────────────────────────────────────
    // Shares `exoPlayer` — the inline AndroidView has already nulled its surface
    // via the `update` callback above, so only this Dialog's PlayerView is active.
    if (isFullscreen && hasMedia) {
        var fsControlsVisible by remember { mutableStateOf(true) }
        var fsLastTapMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var fsShowQuality by remember { mutableStateOf(false) }

        LaunchedEffect(fsLastTapMs, isPlaying) {
            if (isPlaying) {
                delay(3_000)
                if (System.currentTimeMillis() - fsLastTapMs >= 3_000) fsControlsVisible = false
            }
        }

        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = true,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        fsControlsVisible = !fsControlsVisible
                        fsLastTapMs = System.currentTimeMillis()
                    },
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                            useController = false
                            player = exoPlayer
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                val fsBuffering = playerError == null && playbackState == Player.STATE_BUFFERING
                if (fsBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }

                AnimatedVisibility(
                    visible = fsControlsVisible && playerError == null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    ControlsOverlay(
                        isPlaying = isPlaying,
                        positionMs = seekPositionMs,
                        durationMs = durationMs,
                        volume = volume,
                        hasRenditions = renditions.isNotEmpty(),
                        isFullscreen = true,
                        onPlayPause = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            fsLastTapMs = System.currentTimeMillis()
                        },
                        onScrubStart = { isScrubbing = true },
                        onScrub = { fraction -> seekPositionMs = (fraction * durationMs).toLong() },
                        onScrubEnd = { fraction ->
                            isScrubbing = false
                            exoPlayer.seekTo((fraction * durationMs).toLong())
                            fsLastTapMs = System.currentTimeMillis()
                        },
                        onVolumeChange = { v ->
                            volume = v
                            exoPlayer.volume = v
                            fsLastTapMs = System.currentTimeMillis()
                        },
                        onQualityClick = {
                            fsShowQuality = true
                            fsLastTapMs = System.currentTimeMillis()
                        },
                        onFullScreen = { isFullscreen = false },
                    )
                }
            }

            if (fsShowQuality) {
                RenditionPickerDialog(
                    renditions = renditions,
                    selectedRendition = selectedRendition,
                    onSelect = { rendition ->
                        selectedRendition = rendition
                        fsShowQuality = false
                    },
                    onDismiss = { fsShowQuality = false },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Public data model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A streamable proxy rendition offered by the server.
 *
 * @param label Human-readable label shown in the picker, e.g. "720p" or "Original".
 * @param hlsUrl Fully-qualified HLS playlist URL for this rendition.
 * @param heightPx Pixel height, 0 = unknown / "Original".
 */
data class ProxyRendition(
    val label: String,
    val hlsUrl: String,
    val heightPx: Int = 0,
)

// ─────────────────────────────────────────────────────────────────────────────
// Controls overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ControlsOverlay(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    volume: Float,
    hasRenditions: Boolean,
    isFullscreen: Boolean,
    onPlayPause: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (fraction: Float) -> Unit,
    onScrubEnd: (fraction: Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onQualityClick: () -> Unit,
    onFullScreen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        val seekFraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        Slider(
            value = seekFraction,
            onValueChange = { fraction ->
                onScrubStart()
                onScrub(fraction)
            },
            onValueChangeFinished = { onScrubEnd(seekFraction) },
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.35f),
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatMs(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
            Text(
                text = formatMs(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }

            Icon(
                imageVector = if (volume > 0f) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                contentDescription = stringResource(R.string.player_volume),
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp),
            )
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                modifier = Modifier.width(80.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                ),
            )

            Spacer(modifier = Modifier.weight(1f))

            if (hasRenditions) {
                IconButton(onClick = onQualityClick) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = stringResource(R.string.player_quality),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            IconButton(onClick = onFullScreen) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = if (isFullscreen) stringResource(R.string.player_exit_full_screen) else stringResource(R.string.player_full_screen),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .wrapContentSize()
            .background(
                color = Color.Black.copy(alpha = 0.72f),
                shape = MaterialTheme.shapes.medium,
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
        )
        Button(onClick = onRetry) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.player_retry))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rendition picker dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RenditionPickerDialog(
    renditions: List<ProxyRendition>,
    selectedRendition: ProxyRendition?,
    onSelect: (ProxyRendition) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_quality)) },
        text = {
            Column {
                renditions.forEach { rendition ->
                    val isSelected = rendition == selectedRendition ||
                        (selectedRendition == null && rendition == renditions.firstOrNull())
                    TextButton(
                        onClick = { onSelect(rendition) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = rendition.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.player_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_cancel)) }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// HLS data source factory with optional bearer token
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
private fun HlsTokenDataSourceFactory(token: String?): androidx.media3.datasource.DataSource.Factory {
    val trustManager = HlsDynamicTrustManager()
    val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }
    val okHttpClient = okhttp3.OkHttpClient.Builder()
        .sslSocketFactory(sslCtx.socketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        .apply {
            if (token != null) {
                addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                    )
                }
            }
        }
        .build()
    return androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
}

/** Pins to the current [RemoteConnection] fingerprint at handshake time. */
private class HlsDynamicTrustManager : javax.net.ssl.X509TrustManager {
    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {
        val fp = com.reelvault.data.remote.RemoteConnection.endpoint?.fingerprintHex
            ?.takeIf { it.isNotEmpty() } ?: return
        com.reelvault.data.remote.PinnedTls.PinningTrustManager(fp).checkServerTrusted(chain, authType)
    }
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
}

// ─────────────────────────────────────────────────────────────────────────────
// Formatting helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSecs = ms / 1000
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}
