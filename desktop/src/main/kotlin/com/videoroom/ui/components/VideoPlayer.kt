// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.Color
import javax.swing.JPanel
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
 *
 * ## Debug logging
 *
 * Every step of init / discovery / load / play is logged at DEBUG
 * (see `src/main/resources/logback.xml`). When playback fails in the
 * wild, the log line *immediately preceding* the silent failure is
 * usually enough to localise the cause — typically one of:
 *
 *   - NativeDiscovery returned false (libvlc not on the discovery path)
 *   - EmbeddedMediaPlayerComponent constructor threw (JNA / linkage)
 *   - media().play(path) returned false (file not found, codec unsupported)
 *   - Player events never fire (surface not attached to a real window)
 */
class ComposeVideoPlayer {
    private val logger = LoggerFactory.getLogger(ComposeVideoPlayer::class.java)
    private var component: EmbeddedMediaPlayerComponent? = null
    private var initFailure: Throwable? = null

    /**
     * JPanel wrapper that hosts the AWT Canvas returned by
     * `videoSurfaceComponent()`. SwingPanel requires a JComponent; Canvas
     * extends java.awt.Component (not JComponent), so wrapping it in a
     * BorderLayout JPanel is the correct bridge.
     */
    private var surfacePanel: JPanel? = null

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
        logger.info("ComposeVideoPlayer init: jvm={} ({}), os={} {} ({}), thread={}, edt={}",
            System.getProperty("java.version"),
            System.getProperty("java.vendor"),
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            System.getProperty("os.arch"),
            Thread.currentThread().name,
            SwingUtilities.isEventDispatchThread()
        )
        // The AWT heavyweight component must be created on the Swing EDT.
        // `remember { ComposeVideoPlayer() }` runs on the Compose main
        // thread, which is NOT the EDT, so we must dispatch explicitly.
        val initBlock: () -> Unit = {
            try {
                logger.debug("Constructing EmbeddedMediaPlayerComponent on EDT={}",
                    SwingUtilities.isEventDispatchThread())
                val c = EmbeddedMediaPlayerComponent()
                logger.debug("EmbeddedMediaPlayerComponent constructed: {} (isJComponent={})",
                    c.javaClass.name, c is javax.swing.JComponent)
                val canvas = c.videoSurfaceComponent()
                logger.debug("videoSurfaceComponent: class={} displayable={} size={}x{} parent={}",
                    canvas?.javaClass?.name, canvas?.isDisplayable,
                    canvas?.width, canvas?.height, canvas?.parent?.javaClass?.simpleName)
                canvas?.background = Color.BLACK
                // EmbeddedMediaPlayerComponent extends JPanel and already
                // hosts the AWT Canvas in its BorderLayout. Use it directly
                // as the SwingPanel host rather than ripping the Canvas
                // out and reparenting it — reparenting was destroying the
                // Canvas-to-libvlc native binding on macOS, which is why
                // playback never started.
                c.background = Color.BLACK
                surfacePanel = c
                attachEventListeners(c)
                component = c
                logger.info("ComposeVideoPlayer ready (libvlc native init succeeded)")
            } catch (t: Throwable) {
                logger.error("VLCJ player init failed — is libvlc installed?  " +
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
                logger.error("Could not initialize VLCJ on EDT", t)
                initFailure = t
            }
        }
    }

    /**
     * Attach a verbose set of listeners so we can see which events fire and
     * which never do. On a healthy playback the expected order is:
     *   mediaChanged → opening → buffering → playing → timeChanged*
     */
    private fun attachEventListeners(c: EmbeddedMediaPlayerComponent) {
        c.mediaPlayer().events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun opening(mp: MediaPlayer) {
                logger.debug("event: opening")
            }
            override fun buffering(mp: MediaPlayer, newCache: Float) {
                logger.debug("event: buffering {}%", newCache)
            }
            override fun playing(mp: MediaPlayer) {
                logger.info("event: playing")
                isPlaying.value = true
                renderingHealthy.value = true
            }
            override fun paused(mp: MediaPlayer) {
                logger.debug("event: paused")
                isPlaying.value = false
            }
            override fun stopped(mp: MediaPlayer) {
                logger.debug("event: stopped")
                isPlaying.value = false
            }
            override fun finished(mp: MediaPlayer) {
                logger.debug("event: finished")
                isPlaying.value = false
            }
            override fun timeChanged(mp: MediaPlayer, newTime: Long) {
                currentTimeMs.value = newTime
            }
            override fun lengthChanged(mp: MediaPlayer, newLength: Long) {
                logger.info("event: lengthChanged {}ms", newLength)
                lengthMs.value = newLength
                renderingHealthy.value = true
            }
            override fun error(mp: MediaPlayer) {
                // *This* is the critical event we want surfaced. libvlc
                // reports a wide variety of failure modes through `error`,
                // including "couldn't open the file", codec-not-found, etc.
                logger.error("event: error — libvlc failed to play media")
                isPlaying.value = false
            }
            override fun videoOutput(mp: MediaPlayer, newCount: Int) {
                logger.info("event: videoOutput count={} (surface attached and rendering frames)",
                    newCount)
            }
            override fun corked(mp: MediaPlayer, corked: Boolean) {
                logger.debug("event: corked={}", corked)
            }
        })
    }

    /** True if libvlc was found and the component was created successfully. */
    val available: Boolean get() = component != null
    val initError: Throwable? get() = initFailure

    fun load(path: String, playImmediately: Boolean) {
        val c = component
        if (c == null) {
            logger.warn("load({}) ignored: component is null (init failed)", path)
            return
        }
        val file = java.io.File(path)
        logger.info("load(path={}, playImmediately={}): exists={} readable={} size={}",
            path, playImmediately, file.exists(), file.canRead(),
            if (file.exists()) file.length() else -1)
        SwingUtilities.invokeLater {
            try {
                val ok = if (playImmediately) {
                    c.mediaPlayer().media().play(path)
                } else {
                    c.mediaPlayer().media().startPaused(path)
                }
                logger.info("media().{} returned {}",
                    if (playImmediately) "play" else "startPaused", ok)
                if (!ok) {
                    logger.warn("libvlc rejected the media — usually means the path " +
                        "doesn't exist, the codec isn't supported, or libvlc plugins " +
                        "weren't found (set VLC_PLUGIN_PATH or check NativeDiscovery)")
                }
            } catch (t: Throwable) {
                logger.error("Exception while loading media", t)
            }
        }
    }

    fun togglePause() {
        val c = component ?: run {
            logger.warn("togglePause ignored: component is null")
            return
        }
        logger.debug("togglePause")
        SwingUtilities.invokeLater { c.mediaPlayer().controls().pause() }
    }

    fun play() {
        val c = component ?: run {
            logger.warn("play ignored: component is null")
            return
        }
        logger.debug("play")
        SwingUtilities.invokeLater { c.mediaPlayer().controls().play() }
    }

    /**
     * Stop playback entirely (resets the playhead to 0). VLCJ's `stop()`
     * doesn't always emit a paused/stopped event on every platform, so we
     * also nudge our own state flags to keep the UI in sync.
     */
    fun stop() {
        val c = component ?: run {
            logger.warn("stop ignored: component is null")
            return
        }
        logger.debug("stop")
        SwingUtilities.invokeLater {
            try { c.mediaPlayer().controls().stop() } catch (t: Throwable) {
                logger.warn("Exception during stop", t)
            }
        }
        isPlaying.value = false
        currentTimeMs.value = 0L
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
        logger.debug("release")
        SwingUtilities.invokeLater {
            try { c.release() } catch (t: Throwable) {
                logger.warn("Exception during release", t)
            }
        }
        component = null
    }

    /**
     * Compose surface for the player.
     *
     * Uses `surfacePanel` — a JPanel that wraps the AWT Canvas returned by
     * `videoSurfaceComponent()`. We cannot pass the Canvas directly because
     * SwingPanel requires a JComponent and Canvas only extends Component.
     * The panel is created once on the EDT during init and reused here, so
     * repeated recompositions don't add/remove the canvas from its parent.
     *
     * When the component was not created (libvlc missing) this is a no-op;
     * callers must check [available] and show a fallback.
     */
    @Composable
    fun Surface(modifier: Modifier = Modifier) {
        val panel = surfacePanel ?: return
        SwingPanel(
            factory = {
                logger.debug("SwingPanel factory: panel={} parent={} attached-now",
                    panel.javaClass.simpleName, panel.parent?.javaClass?.simpleName)
                panel
            },
            modifier = modifier,
            background = androidx.compose.ui.graphics.Color.Black
        )
    }

    companion object {
        private val companionLogger = LoggerFactory.getLogger(ComposeVideoPlayer::class.java)

        /**
         * Fast startup check: calls [NativeDiscovery.discover] (path-search
         * only, no heavy initialisation) on first access and caches the result.
         *
         * This runs synchronously on whichever thread first reads the property.
         * In practice that's the Compose main thread during the very first
         * composition of [GridScreen], which is safe because [NativeDiscovery]
         * only inspects filesystem paths (no JNI, no player init).
         *
         * Callers use this to decide whether to show a disabled play button
         * and an immediate error dialog, without waiting for a
         * [ComposeVideoPlayer] instance to be constructed and fail.
         */
        val isLibVlcAvailable: Boolean by lazy {
            companionLogger.info("Running NativeDiscovery to locate libvlc…")
            val nd = NativeDiscovery()
            val result = try {
                nd.discover()
            } catch (t: Throwable) {
                companionLogger.error("NativeDiscovery threw", t)
                false
            }
            // After a successful discover() vlcj has set VLC_PLUGIN_PATH and
            // added the libvlc directory to JNA's library search path. Log
            // both so we can spot path mismatches in the wild.
            companionLogger.info(
                "NativeDiscovery: result={} discoveredPath={} VLC_PLUGIN_PATH={} jna.library.path={}",
                result,
                try { nd.discoveredPath() } catch (_: Throwable) { "<unavailable>" },
                System.getenv("VLC_PLUGIN_PATH"),
                System.getProperty("jna.library.path")
            )
            if (!result) {
                // Most common failure on macOS: VLC isn't in /Applications.
                val os = System.getProperty("os.name", "").lowercase()
                val hint = when {
                    os.contains("mac") ->
                        "Expected /Applications/VLC.app/Contents/MacOS/lib/libvlc.dylib"
                    os.contains("win") ->
                        "Expected libvlc.dll in the VLC install directory on PATH"
                    else ->
                        "Expected libvlc.so via your distro's vlc package (sudo apt install vlc)"
                }
                companionLogger.warn("libvlc not found via NativeDiscovery. {}", hint)
            }
            result
        }
    }
}
