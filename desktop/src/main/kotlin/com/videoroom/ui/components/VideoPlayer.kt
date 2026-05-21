// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.Color
import javax.swing.SwingUtilities

/**
 * Wraps a VLCJ EmbeddedMediaPlayerComponent inside a Compose SwingPanel.
 *
 * VLCJ requires libvlc to be present on the host system:
 *   * macOS: install VLC.app from the App Store or videolan.org.
 *   * Linux: install the `libvlc` package (e.g. `apt install vlc` includes it).
 *   * Windows: install VLC from videolan.org.
 *
 * If libvlc isn't available the component creation throws — we catch that and
 * the parent screen falls back to the hover-scrub thumbnail.
 */
class ComposeVideoPlayer {
    private val logger = LoggerFactory.getLogger(ComposeVideoPlayer::class.java)
    private var component: EmbeddedMediaPlayerComponent? = null
    private var initFailure: Throwable? = null

    /** Most-recent currentTime in ms, updated by the time-changed event. */
    val currentTimeMs = mutableStateOf(0L)
    /** Total length in ms (0 until the media is parsed). */
    val lengthMs = mutableStateOf(0L)
    val isPlaying = mutableStateOf(false)

    init {
        try {
            component = EmbeddedMediaPlayerComponent().also { c ->
                c.videoSurfaceComponent().background = Color.BLACK
                c.mediaPlayer().events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                    override fun playing(mp: MediaPlayer) { isPlaying.value = true }
                    override fun paused(mp: MediaPlayer) { isPlaying.value = false }
                    override fun stopped(mp: MediaPlayer) { isPlaying.value = false }
                    override fun finished(mp: MediaPlayer) { isPlaying.value = false }
                    override fun timeChanged(mp: MediaPlayer, newTime: Long) {
                        currentTimeMs.value = newTime
                    }
                    override fun lengthChanged(mp: MediaPlayer, newLength: Long) {
                        lengthMs.value = newLength
                    }
                })
            }
        } catch (t: Throwable) {
            logger.warn("VLCJ player init failed — is libvlc installed?", t)
            initFailure = t
        }
    }

    val available: Boolean get() = component != null
    val initError: Throwable? get() = initFailure

    fun load(path: String, playImmediately: Boolean) {
        val c = component ?: return
        SwingUtilities.invokeLater {
            if (playImmediately) {
                c.mediaPlayer().media().play(path)
            } else {
                c.mediaPlayer().media().startPaused(path)
            }
        }
    }

    fun togglePause() {
        val c = component ?: return
        SwingUtilities.invokeLater { c.mediaPlayer().controls().pause() }
    }

    fun play() {
        val c = component ?: return
        SwingUtilities.invokeLater { c.mediaPlayer().controls().play() }
    }

    /** Step forward exactly one frame (libvlc supports this natively). */
    fun stepForwardOneFrame() {
        val c = component ?: return
        SwingUtilities.invokeLater { c.mediaPlayer().controls().nextFrame() }
    }

    /**
     * Move by [frames] frames (positive or negative) at the given fps. libvlc
     * doesn't have a "previous frame" call, so we use time-based seeking. The
     * caller passes fps because we don't reliably know it from libvlc alone.
     */
    fun skipFrames(frames: Int, fps: Double) {
        if (fps <= 0.0 || frames == 0) return
        val c = component ?: return
        SwingUtilities.invokeLater {
            val mp = c.mediaPlayer()
            val deltaMs = ((frames.toDouble() / fps) * 1000.0).toLong()
            val current = mp.status().time()
            val total = mp.status().length().takeIf { it > 0 } ?: 0L
            val target = (current + deltaMs).coerceIn(0L, if (total > 0) total else Long.MAX_VALUE)
            mp.controls().setTime(target)
        }
    }

    fun seek(timeMs: Long) {
        val c = component ?: return
        SwingUtilities.invokeLater { c.mediaPlayer().controls().setTime(timeMs) }
    }

    fun release() {
        val c = component ?: return
        SwingUtilities.invokeLater {
            try { c.release() } catch (_: Throwable) {}
        }
        component = null
    }

    /**
     * Compose surface for the player. When VLCJ failed to initialize the
     * caller is responsible for showing a fallback — calling this still works
     * (it just renders nothing).
     */
    @Composable
    fun Surface(modifier: Modifier = Modifier) {
        val c = component
        if (c != null) {
            SwingPanel(
                factory = { c },
                modifier = modifier,
                background = androidx.compose.ui.graphics.Color.Black
            )
        }
    }
}
