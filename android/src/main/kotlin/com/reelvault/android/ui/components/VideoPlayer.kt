// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
 * Mirrors the iOS StreamPlayer approach:
 * - Accepts a nullable [streamUrl] (null = show placeholder, not an error).
 * - Streams via media3-exoplayer-hls / HlsMediaSource.
 * - Custom controls (play/pause, seek bar, volume, full-screen toggle).
 * - Buffering spinner while ExoPlayer is not ready.
 * - Error state with a retry button.
 * - [onFullScreenToggle] signals the parent to push/pop a full-screen route
 *   (the composable itself does not take over the screen).
 * - Quality/rendition picker: pass a list of [ProxyRendition] entries;
 *   selecting one swaps the stream URL and seeks back to the current position.
 *
 * The player is created once per [streamUrl]; [DisposableEffect] releases it.
 * A [LaunchedEffect] on player state drives the auto-hide timer for controls.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    streamUrl: String?,
    modifier: Modifier = Modifier,
    renditions: List<ProxyRendition> = emptyList(),
    onFullScreenToggle: (() -> Unit)? = null,
    authToken: String? = null,
) {
    val context = LocalContext.current

    // ── Player instance (recreated when streamUrl changes) ────────────────
    val exoPlayer = remember(streamUrl) {
        ExoPlayer.Builder(context).build().also { player ->
            if (streamUrl != null) {
                val dataSourceFactory = HlsTokenDataSourceFactory(authToken)
                val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(streamUrl))
                player.setMediaSource(mediaSource)
                player.prepare()
                player.playWhenReady = true
            }
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    // ── Playback state observation ────────────────────────────────────────
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<PlaybackException?>(null) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlayerError(error: PlaybackException) {
                playerError = error
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // ── Seek position ─────────────────────────────────────────────────────
    var seekPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }

    // Poll position every 500 ms while playing.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!isScrubbing) {
                seekPositionMs = exoPlayer.currentPosition
                durationMs = exoPlayer.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
        // Update once after stopping so the thumb is correct.
        if (!isScrubbing) {
            seekPositionMs = exoPlayer.currentPosition
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
        }
    }

    // ── Volume ────────────────────────────────────────────────────────────
    var volume by remember { mutableFloatStateOf(1f) }

    // ── Controls visibility (auto-hide after 3 s) ─────────────────────────
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTapTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lastTapTimeMs, isPlaying) {
        if (isPlaying) {
            delay(3_000)
            // Re-check: another tap may have bumped lastTapTimeMs.
            if (System.currentTimeMillis() - lastTapTimeMs >= 3_000) {
                controlsVisible = false
            }
        }
    }

    // ── Quality picker ────────────────────────────────────────────────────
    var showQualityPicker by remember { mutableStateOf(false) }
    var selectedRendition by remember { mutableStateOf<ProxyRendition?>(null) }

    // Swap the stream when the user picks a different rendition.
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

    // ─────────────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────────────

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

        // ── PlayerView (always present; invisible until ready) ────────────
        if (streamUrl != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        // Disable PlayerView's own controls; we draw our own overlay.
                        useController = false
                        player = exoPlayer
                    }
                },
                update = { view -> view.player = exoPlayer },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ── No-URL placeholder ────────────────────────────────────────────
        if (streamUrl == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.VideoFile,
                    contentDescription = "No stream available",
                    modifier = Modifier.size(56.dp),
                    tint = Color.White.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No stream available",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }

        // ── Buffering spinner ─────────────────────────────────────────────
        val isBuffering = streamUrl != null &&
            playerError == null &&
            playbackState == Player.STATE_BUFFERING
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }

        // ── Error overlay ─────────────────────────────────────────────────
        if (playerError != null) {
            ErrorOverlay(
                message = playerError!!.localizedMessage ?: "Playback error",
                onRetry = {
                    playerError = null
                    exoPlayer.prepare()
                    exoPlayer.play()
                },
            )
        }

        // ── Controls overlay ──────────────────────────────────────────────
        AnimatedVisibility(
            visible = controlsVisible && playerError == null && streamUrl != null,
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
                onPlayPause = {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    lastTapTimeMs = System.currentTimeMillis()
                },
                onScrubStart = { isScrubbing = true },
                onScrub = { fraction ->
                    seekPositionMs = (fraction * durationMs).toLong()
                },
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
                onFullScreen = onFullScreenToggle?.let {
                    {
                        it()
                        lastTapTimeMs = System.currentTimeMillis()
                    }
                },
            )
        }
    }

    // ── Quality picker dialog ─────────────────────────────────────────────
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
}

// ─────────────────────────────────────────────────────────────────────────────
// Public data model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A streamable proxy rendition offered by the server.
 *
 * @param label Human-readable label shown in the picker, e.g. "720p" or "Original".
 * @param hlsUrl Fully-qualified HLS master-playlist URL for this rendition.
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
    onPlayPause: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (fraction: Float) -> Unit,
    onScrubEnd: (fraction: Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onQualityClick: () -> Unit,
    onFullScreen: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // ── Seek bar ──────────────────────────────────────────────────────
        val seekFraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        Slider(
            value = seekFraction,
            onValueChange = { fraction ->
                onScrubStart()
                onScrub(fraction)
            },
            onValueChangeFinished = {
                onScrubEnd(seekFraction)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.35f),
            ),
        )

        // ── Time labels ───────────────────────────────────────────────────
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

        // ── Button row ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play / pause
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }

            // Volume
            Icon(
                imageVector = if (volume > 0f) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                contentDescription = "Volume",
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

            // Quality picker (only when renditions are provided)
            if (hasRenditions) {
                IconButton(onClick = onQualityClick) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Quality",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // Full-screen toggle
            if (onFullScreen != null) {
                IconButton(onClick = onFullScreen) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Full screen",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
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
            Text("Retry")
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
        title = { Text("Quality") },
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
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
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
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// HLS data source factory with optional bearer token
// ─────────────────────────────────────────────────────────────────────────────

/**
 * OkHttp data source factory for ExoPlayer HLS.
 *
 * Injects `Authorization: Bearer <token>` on every segment request and uses
 * the same dynamic fingerprint-pinning trust manager as the Coil image loader
 * so that the daemon's self-signed TLS cert is accepted without hardcoding it
 * at factory-creation time.
 */
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
        val fp = com.reelvault.data.remote.RemoteConnection.endpoint?.fingerprintHex ?: return
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
