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
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Wraps a VLCJ EmbeddedMediaPlayerComponent inside a Compose SwingPanel.
 *
 * VLCJ requires libvlc to be present on the host system:
 *   * macOS: install VLC.app from videolan.org (https://www.videolan.org/vlc/).
 *   * Linux: `sudo apt install vlc` (or equivalent for your distro).
 *   * Windows: install VLC from videolan.org.
 *
 * If libvlc is not found, or if the native video surface fails to
 * render (which can happen in some Compose Desktop configurations),
 * [available] / [renderingHealthy] will be false and callers should
 * surface a user-visible error rather than a black rectangle.
 *
 * ## Compose Desktop + libvlc notes
 *
 * On macOS, Compose Desktop renders via Skia/Metal while VLCJ's
 * EmbeddedMediaPlayerComponent renders via AVFoundation/CoreVideo.
 * These two pipelines can conflict; if video is black after the
 * health-check timeout, treat the player as unavailable and prompt
 * the user to install VLC.
 *
 * Key correctness decisions:
 *  - The AWT heavyweight component *must* be created on the Swing EDT.
 *  - [Surface] passes `videoSurfaceComponent()` (the rendering canvas)
 *    to SwingPanel rather than the containing JPanel — this is the
 *    actual CALayer/X11 drawable and is what libvlc renders into.
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

    /**
     * Set to true once `lengthChanged` or `playing` fires, indicating that
     * libvlc is actually decoding and the native surface is functional.
     * Stays false when the component initialized but video never starts
     * (silent black-surface failure on some macOS + Compose Desktop configs).
     */
    val renderingHealthy = mutableStateOf(false)

    init {
        // The AWT heavyweight component must be created on the Swing EDT.
        // `remember { ComposeVideoPlayer() }` runs on the Compose main
        // thread, which is NOT the EDT, so we must dispatch explicitly.
        val initBlock: () -> Unit = {
            try {
                component = EmbeddedMediaPlayerComponent().also { c ->
                    c.videoSurfaceComponent().background = Color.BLACK
                    c.mediaPlayer().events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                        override fun playing(mp: MediaPlayer) {
                            isPlaying.value = true
                            renderingHealthy.value = true
                        }
                        override fun paused(mp: MediaPlayer) { isPlaying.value = false }
                        override fun stopped(mp: MediaPlayer) { isPlaying.value = false }
                        override fun finished(mp: MediaPlayer) { isPlaying.value = false }
                        override fun timeChanged(mp: MediaPlayer, newTime: Long) {
                            currentTimeMs.value = newTime
                        }
                        override fun lengthChanged(mp: MediaPlayer, newLength: Long) {
                            lengthMs.value = newLength
                            renderingHealthy.value = true   // media is being parsed
                        }
                    })
                }
            } catch (t: Throwable) {
                logger.warn("VLCJ player init failed — is libvlc installed?  " +
                    "macOS: install VLC.app from videolan.org. " +
                    "Linux: sudo apt install vlc.", t)
                initFailure = t
            }
        }

        if (SwingUtilities.isEventDispatchThread()) {
            initBlock()
        } else {
            try {
                SwingUtilities.invokeAndWait(initBlock)
            } catch (t: Throwable) {
                logger.warn("Could not initialize VLCJ on EDT", t)
                initFailure = t
            }
        }
    }

    /** True if libvlc was found and the component was created successfully. */
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
     * doesn't have a "previous frame" call, so we use time-based seeking.
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
     * Compose surface for the player.
     *
     * Passes `videoSurfaceComponent()` to SwingPanel — that is the actual
     * AWT canvas that libvlc renders into (on macOS: a CALayer-backed view;
     * on Linux: an X11 drawable). Passing the containing JPanel used to
     * produce a black surface because the native rendering context was
     * attached to the inner canvas, not the outer panel.
     *
     * When the component was not created (libvlc missing) this is a no-op;
     * callers must check [available] and show a fallback.
     */
    @Composable
    fun Surface(modifier: Modifier = Modifier) {
        val c = component ?: return
        val surfaceComponent: JComponent = c.videoSurfaceComponent() as JComponent
        SwingPanel(
            factory = { surfaceComponent },
            modifier = modifier,
            background = androidx.compose.ui.graphics.Color.Black
        )
    }
}
