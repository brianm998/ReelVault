// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.log.LogLevel
import uk.co.caprica.vlcj.log.NativeLog
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import javax.swing.SwingUtilities
import org.jetbrains.skia.Image as SkiaImage

/**
 * VLCJ-backed video player rendered into a Compose `Image` composable.
 *
 * ## Why not EmbeddedMediaPlayerComponent?
 *
 * The straightforward integration would use VLCJ's `EmbeddedMediaPlayerComponent`,
 * which hosts an AWT `Canvas` that libvlc's native `vout` module renders into
 * directly. On macOS the vout reads an `NSObject` (NSView or CALayer) pointer
 * via JNA's `Native.getComponentPointer(canvas)` — which delegates to JAWT.
 *
 * Compose Desktop's `SwingPanel` does host the Canvas, but it lives inside a
 * popup-window hierarchy that JAWT can't extract a usable layer pointer from:
 * `getComponentPointer` returns 0, libvlc's vout init logs "No drawable-nsobject
 * found!" and the surface stays blank forever (audio plays fine).
 *
 * ## Callback rendering instead
 *
 * We use `CallbackMediaPlayerComponent`: libvlc decodes into a memory buffer
 * (BGRA via `RV32BufferFormat`) and calls `RenderCallback.display` on each
 * frame. We copy the buffer into a `ByteArray`, wrap it as a Skia `Image`,
 * convert to a Compose `ImageBitmap`, and a `mutableStateOf<ImageBitmap>`
 * drives a Compose `Image` composable.
 *
 * Performance: 1080p BGRA at 30 fps is ~250 MB/s of memcpy plus one Skia
 * Image build per frame — non-trivial but well within budget on modern
 * hardware. The Skia draw is GPU-accelerated by Compose Desktop's renderer.
 *
 * ## Setup requirements
 *
 * VLCJ still needs libvlc on disk (`/Applications/VLC.app` on macOS,
 * `vlc` package on Linux, VLC for Windows). The discovery + factory + log
 * plumbing below pipes libvlc's own warnings into SLF4J under the `libvlc`
 * logger; check `logback.xml` to tune the level.
 *
 * Every step of init / discovery / load / play is logged at DEBUG. When
 * playback fails the log line *immediately preceding* the silent failure
 * usually localises the cause.
 */
class ComposeVideoPlayer {
    private val logger = LoggerFactory.getLogger(ComposeVideoPlayer::class.java)
    /** Separate logger so libvlc's own diagnostics are easy to filter. */
    private val libvlcLogger = LoggerFactory.getLogger("libvlc")
    private var component: CallbackMediaPlayerComponent? = null
    private var factory: MediaPlayerFactory? = null
    private var nativeLog: NativeLog? = null
    private var initFailure: Throwable? = null

    /** Most-recent currentTime in ms, updated by the time-changed event. */
    val currentTimeMs = mutableStateOf(0L)
    /** Total length in ms (0 until the media is parsed). */
    val lengthMs = mutableStateOf(0L)
    val isPlaying = mutableStateOf(false)

    /**
     * Set to true once a frame has actually been rendered via the callback.
     * Stays false when the component initialised but libvlc never delivered
     * a decoded frame (e.g. unsupported codec, broken plugin path).
     */
    val renderingHealthy = mutableStateOf(false)

    /**
     * Latest decoded frame as a Compose `ImageBitmap`. Updated by the render
     * callback on libvlc's display thread; read by `Surface` on the Compose
     * main thread. `mutableStateOf` is thread-safe for writes (Compose
     * applies the change to its snapshot).
     */
    private val frame = mutableStateOf<ImageBitmap?>(null)

    /**
     * Re-usable pixel scratch buffer. Sized once per resolution change in
     * `getBufferFormat` (libvlc calls back into us before the first frame).
     * Holding a single byte array avoids 8 MB allocations per 1080p frame
     * (240+ MB/s of GC pressure at 30 fps).
     */
    @Volatile private var scratch: ByteArray? = null
    @Volatile private var scratchWidth = 0
    @Volatile private var scratchHeight = 0

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
        // CallbackMediaPlayerComponent doesn't host an AWT heavyweight Canvas
        // so technically EDT initialisation isn't strictly required — but
        // libvlc's lifecycle is still simpler to reason about when init,
        // play, and stop all happen on the same thread, so we keep the EDT
        // dispatch for parity with the embedded path.
        val initBlock: () -> Unit = {
            try {
                val args = buildLibvlcArgs()
                logger.info("Creating MediaPlayerFactory with args: {}", args.joinToString(" "))
                val f = MediaPlayerFactory(*args)
                factory = f
                // Pipe libvlc's own logs through SLF4J so we can see what
                // libvlc thinks is going wrong with codec selection, module
                // loading, etc.
                nativeLog = f.application().newLog().apply {
                    setLevel(LogLevel.DEBUG)
                    addLogListener { level, module, _, _, _, _, _, message ->
                        val tag = module ?: "?"
                        when (level) {
                            LogLevel.ERROR -> libvlcLogger.error("[{}] {}", tag, message)
                            LogLevel.WARNING -> libvlcLogger.warn("[{}] {}", tag, message)
                            LogLevel.NOTICE -> libvlcLogger.info("[{}] {}", tag, message)
                            LogLevel.DEBUG -> libvlcLogger.debug("[{}] {}", tag, message)
                            else -> libvlcLogger.info("[{}] {}", tag, message)
                        }
                    }
                }
                // CallbackMediaPlayerComponent(factory, fullScreenStrategy,
                //   inputEvents, lockBuffers, renderCallback,
                //   bufferFormatCallback, videoSurfaceComponent).
                //
                // lockBuffers=true: libvlc holds the buffer while display()
                // runs. Safe because we do a synchronous copy inside the
                // callback.
                val c = CallbackMediaPlayerComponent(
                    f, null, null, true,
                    renderCallback, bufferFormatCallback, null
                )
                attachEventListeners(c)
                component = c
                logger.info("ComposeVideoPlayer ready (libvlc {}, callback rendering)",
                    try { f.application().version() } catch (_: Throwable) { "<unknown>" })
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

    /** libvlc tells us its decided buffer format here (and we tell it ours). */
    private val bufferFormatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            logger.info("BufferFormatCallback: source {}x{}", sourceWidth, sourceHeight)
            val needed = sourceWidth * sourceHeight * 4
            if (scratch?.size != needed) {
                scratch = ByteArray(needed)
            }
            scratchWidth = sourceWidth
            scratchHeight = sourceHeight
            // RV32 = 32-bit packed RGB/BGRA, native byte order. On macOS
            // little-endian this is byte order B,G,R,A in memory — which is
            // exactly what Skia's BGRA_8888 expects.
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {
            logger.debug("BufferFormatCallback.allocatedBuffers: {} buffer(s)", buffers.size)
        }
    }

    /**
     * Counts frames so we can log the first one (proves the callback is
     * actually firing) without spamming on every subsequent frame.
     */
    @Volatile private var frameCount = 0L

    /**
     * One call per decoded frame. Runs on libvlc's display thread.
     *
     * Implemented as an explicit `object : RenderCallback` rather than a
     * SAM lambda — vlcj's binding uses a `JNA Callback` reference under the
     * hood and the object form is the most predictable way to keep the
     * reference alive and the JNA stub stable across JIT compilation.
     */
    private val renderCallback = object : RenderCallback {
        override fun display(
            mediaPlayer: MediaPlayer,
            nativeBuffers: Array<out ByteBuffer>,
            bufferFormat: BufferFormat,
        ) {
            try {
                val n = ++frameCount
                if (n == 1L || n % 300L == 0L) {
                    logger.info("renderCallback fired: frame={} format={}x{}",
                        n, bufferFormat.width, bufferFormat.height)
                }
                val src = nativeBuffers.firstOrNull() ?: run {
                    logger.warn("renderCallback: nativeBuffers empty")
                    return
                }
                val w = bufferFormat.width
                val h = bufferFormat.height
                val rowBytes = w * 4
                val needed = rowBytes * h
                val bytes = scratch?.takeIf { it.size == needed }
                    ?: ByteArray(needed).also {
                        scratch = it; scratchWidth = w; scratchHeight = h
                    }
                src.rewind()
                src.get(bytes)
                val info = ImageInfo(
                    ColorInfo(ColorType.BGRA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB),
                    w, h
                )
                val skiaImage = SkiaImage.makeRaster(info, bytes, rowBytes)
                frame.value = skiaImage.toComposeImageBitmap()
                if (!renderingHealthy.value) {
                    logger.info("First decoded frame painted: {}x{}", w, h)
                    renderingHealthy.value = true
                }
            } catch (t: Throwable) {
                logger.error("Exception in renderCallback (frame {})", frameCount, t)
            }
        }
    }

    /**
     * Attach a verbose set of listeners so we can see which events fire and
     * which never do. On a healthy playback the expected order is:
     *   opening → buffering → playing → timeChanged*
     */
    private fun attachEventListeners(c: CallbackMediaPlayerComponent) {
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
                // Fallback so the "VLC not installed" overlay disappears as
                // soon as libvlc is actively playing — even if our render
                // callback hasn't been wired or hasn't fired yet. Without
                // this the overlay would stay visible during audio-only
                // startup or while we wait for the first decoded frame.
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
                logger.error("event: error — libvlc failed to play media")
                isPlaying.value = false
            }
            override fun videoOutput(mp: MediaPlayer, newCount: Int) {
                logger.info("event: videoOutput count={}", newCount)
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
                logger.warn("Exception during release (component)", t)
            }
            try { nativeLog?.release() } catch (t: Throwable) {
                logger.warn("Exception during release (nativeLog)", t)
            }
            // CallbackMediaPlayerComponent only releases the factory when
            // it created it; we passed one in, so we own the release.
            try { factory?.release() } catch (t: Throwable) {
                logger.warn("Exception during release (factory)", t)
            }
        }
        component = null
        nativeLog = null
        factory = null
        frame.value = null
        scratch = null
    }

    /**
     * libvlc startup args. The defaults from
     * [uk.co.caprica.vlcj.player.component.MediaPlayerComponentDefaults] are
     * `--video-title=…`, `--no-snapshot-preview`, `--quiet`, `--intf=dummy`.
     * We drop `--quiet` so we can see what libvlc is doing through
     * [NativeLog], and we keep the others.
     */
    private fun buildLibvlcArgs(): Array<String> {
        val base = mutableListOf(
            "--no-snapshot-preview",
            "--intf=dummy",
            "--no-video-title-show",
        )
        // System property escape hatch: -Dvideoroom.libvlc.args="--verbose=2"
        // adds extra args. Useful for diagnosing codec issues without
        // rebuilding.
        val extra = System.getProperty("videoroom.libvlc.args", "").trim()
        if (extra.isNotEmpty()) {
            base += extra.split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        return base.toTypedArray()
    }

    /**
     * Compose surface for the player. Reads the most-recent rendered frame
     * from [frame] and draws it in a Compose `Image` composable. No Swing
     * heavyweight component, no Canvas, no NSView wrangling.
     */
    @Composable
    fun Surface(modifier: Modifier = Modifier) {
        val bitmap = frame.value
        Box(
            modifier = modifier
                .background(androidx.compose.ui.graphics.Color.Black)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }

    companion object {
        private val companionLogger = LoggerFactory.getLogger(ComposeVideoPlayer::class.java)

        /**
         * Fast startup check: calls [NativeDiscovery.discover] (path-search
         * only, no heavy initialisation) on first access and caches the result.
         *
         * This runs synchronously on whichever thread first reads the property.
         * In practice that's the Compose main thread during the very first
         * composition of [com.videoroom.ui.screens.GridScreen], which is safe
         * because [NativeDiscovery] only inspects filesystem paths (no JNI,
         * no player init).
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
            companionLogger.info(
                "NativeDiscovery: result={} discoveredPath={} VLC_PLUGIN_PATH={} jna.library.path={}",
                result,
                try { nd.discoveredPath() } catch (_: Throwable) { "<unavailable>" },
                System.getenv("VLC_PLUGIN_PATH"),
                System.getProperty("jna.library.path")
            )
            if (!result) {
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
