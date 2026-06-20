// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.awt.SwingPanel
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
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import javax.swing.SwingUtilities
import org.jetbrains.skia.Image as SkiaImage

/**
 * VLCJ-backed video player. Renders one of two ways depending on the platform
 * (see [useEmbedded]); the public API is identical either way.
 *
 * ## macOS — callback rendering (CPU)
 *
 * The straightforward integration would use VLCJ's `EmbeddedMediaPlayerComponent`,
 * which hosts an AWT `Canvas` that libvlc's native `vout` module renders into
 * directly. On macOS the vout reads an `NSObject` (NSView or CALayer) pointer
 * via JNA's `Native.getComponentPointer(canvas)` — which delegates to JAWT.
 * Compose Desktop's `SwingPanel` does host the Canvas, but it lives inside a
 * popup-window hierarchy that JAWT can't extract a usable layer pointer from:
 * `getComponentPointer` returns 0, libvlc's vout init logs "No drawable-nsobject
 * found!" and the surface stays blank forever (audio plays fine).
 *
 * So macOS uses `CallbackMediaPlayerComponent`: libvlc decodes into a memory
 * buffer (BGRA via `RV32BufferFormat`) and calls `RenderCallback.display` on
 * each frame. We copy the buffer into a `ByteArray`, wrap it as a Skia `Image`,
 * convert to a Compose `ImageBitmap`, and a `mutableStateOf<ImageBitmap>` drives
 * a Compose `Image`. That per-frame CPU copy + Skia build (~250 MB/s for 1080p30)
 * is what makes this path skip frames above ~480p — unavoidable on macOS.
 *
 * ## Linux / Windows — embedded rendering (native, GPU)
 *
 * There JAWT *can* hand libvlc the window handle (X11 XID / HWND) from a
 * `SwingPanel`-hosted component, so we use `EmbeddedMediaPlayerComponent`:
 * libvlc renders straight into the component's native surface with no per-frame
 * CPU copy, and no >480p frame-skipping. [Surface] then hosts that component in
 * a `SwingPanel` instead of drawing a Compose `Image`. (Inline grid/list cards
 * still force the callback path — a heavyweight surface inside a scrolling grid
 * clips/z-orders badly, and card-sized playback never skips anyway.)
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
class ComposeVideoPlayer(
    /** When false, always use the software callback path regardless of OS.
     *  The inline grid/list player passes false: a heavyweight native surface
     *  inside a scrolling LazyGrid clips and z-orders badly, and card-sized
     *  playback doesn't skip frames anyway. Detail playback leaves it true. */
    private val allowEmbedded: Boolean = true,
    /** Request accurate (non-keyframe) seeking for this player's media. libvlc
     *  defaults to "fast seek", which snaps `setTime`/`setPosition` to the
     *  nearest keyframe — so a back-one-frame seek lands a whole GOP (20–60
     *  frames) early and successive small back-seeks stay pinned to that
     *  keyframe. The detail player passes true so the ← key (and the on-screen
     *  step / scrub controls) land on the exact requested frame. vlcj 4.8.2 has
     *  no per-call precise-seek overload, so we set it once per media via the
     *  `:no-input-fast-seek` input option in [load]. The inline grid/list
     *  players leave it false — they don't frame-step, and fast seek is cheaper. */
    private val preciseSeek: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(ComposeVideoPlayer::class.java)
    /** Separate logger so libvlc's own diagnostics are easy to filter. */
    private val libvlcLogger = LoggerFactory.getLogger("libvlc")
    /** Callback (CPU) component — used on macOS and for inline grid/list cards. */
    private var component: CallbackMediaPlayerComponent? = null
    /** Embedded (native GPU) component — used for detail playback on Linux/Windows. */
    private var embeddedComponent: EmbeddedMediaPlayerComponent? = null
    private var factory: MediaPlayerFactory? = null
    private var nativeLog: NativeLog? = null
    private var initFailure: Throwable? = null

    /**
     * Which rendering path to build (see the class doc):
     *  - false → callback (CPU copy + Skia) — macOS, or any inline grid player.
     *  - true  → embedded native surface in a SwingPanel — Linux/Windows detail.
     * Overridable with `-Dreelvault.video.renderer=callback|embedded` for
     * testing or to work around a platform quirk without a rebuild.
     */
    private val useEmbedded: Boolean = run {
        if (!allowEmbedded) return@run false
        when (System.getProperty("reelvault.video.renderer", "").trim().lowercase()) {
            "embedded" -> true
            "callback" -> false
            else -> {
                val os = System.getProperty("os.name", "").lowercase()
                !(os.contains("mac") || os.contains("darwin"))
            }
        }
    }

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
     * True when the most recent [load] was handed a path that doesn't exist on
     * disk — a moved/renamed file, or an unmounted drive. Lets the unavailable
     * overlay say "file not found" instead of mislabelling a missing file as
     * "libvlc isn't installed".
     */
    val mediaMissing = mutableStateOf(false)

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

    /**
     * Counts frames so we can log the first one (proves the callback is
     * actually firing) without spamming on every subsequent frame.
     */
    @Volatile private var frameCount = 0L

    // ─────────────────────────────────────────────────────────────────────
    // CALLBACK ORDERING IS LOAD-BEARING.
    //
    // Both callbacks MUST be declared *before* the `init` block. Kotlin
    // initialises fields and runs init blocks in source order. The init
    // block constructs CallbackMediaPlayerComponent and passes these two
    // callbacks in. If they're declared after init, they're null at the
    // point of construction — and vlcj silently substitutes its own
    // defaults (DefaultBufferFormatCallback returning RV32, and
    // DefaultRenderCallback that paints into a hidden Swing component),
    // so our callbacks never fire and the user sees a black surface even
    // though libvlc reports videoOutput count=1 and runs to completion.
    //
    // Keep these two declarations above `init`.
    // ─────────────────────────────────────────────────────────────────────

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
            logger.info("BufferFormatCallback.allocatedBuffers: {} buffer(s)", buffers.size)
        }
    }

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
                if (useEmbedded) {
                    // Native embedded surface (Linux/Windows): libvlc renders
                    // directly into the component's Canvas — no per-frame CPU
                    // copy, so no >480p frame-skipping. Constructor args are
                    //   (factory, videoSurfaceComponent, fullScreenStrategy,
                    //    inputEvents, overlay); all but the factory left default.
                    val e = EmbeddedMediaPlayerComponent(f, null, null, null, null)
                    attachEventListeners(e.mediaPlayer())
                    embeddedComponent = e
                    logger.info("ComposeVideoPlayer ready (libvlc {}, embedded rendering)",
                        try { f.application().version() } catch (_: Throwable) { "<unknown>" })
                } else {
                    // CallbackMediaPlayerComponent(factory, fullScreenStrategy,
                    //   inputEvents, lockBuffers, renderCallback,
                    //   bufferFormatCallback, videoSurfaceComponent).
                    //
                    // lockBuffers=true: vlcj acquires a JNA lock around buffer
                    // access during the libvlc lock/unlock callbacks. Doesn't
                    // affect whether callbacks fire, just synchronisation.
                    //
                    // CRITICAL: renderCallback and bufferFormatCallback are
                    // initialised *above* this init block; passing nulls here
                    // makes vlcj silently fall back to its own defaults and
                    // our callbacks never fire. See the big comment block
                    // above their declarations.
                    val c = CallbackMediaPlayerComponent(
                        f, null, null, true,
                        renderCallback, bufferFormatCallback, null
                    )
                    attachEventListeners(c.mediaPlayer())
                    component = c
                    logger.info("ComposeVideoPlayer ready (libvlc {}, callback rendering)",
                        try { f.application().version() } catch (_: Throwable) { "<unknown>" })
                }
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
     *   opening → buffering → playing → timeChanged*
     */
    private fun attachEventListeners(target: EmbeddedMediaPlayer) {
        target.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
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

    /** The active media player, whichever rendering path was built. */
    private fun activeMediaPlayer(): EmbeddedMediaPlayer? =
        component?.mediaPlayer() ?: embeddedComponent?.mediaPlayer()

    /** True if libvlc was found and a component was created successfully. */
    val available: Boolean get() = component != null || embeddedComponent != null
    val initError: Throwable? get() = initFailure

    fun load(path: String, playImmediately: Boolean) {
        val mp = activeMediaPlayer()
        if (mp == null) {
            logger.warn("load({}) ignored: no media player (init failed)", path)
            return
        }
        // Clear stale frame synchronously so callers that show the thumbnail
        // beneath the player surface see the thumbnail while the new video
        // buffers — not the last frame of the previous video.
        frame.value = null
        renderingHealthy.value = false
        frameCount = 0L
        // A remote stream is an http(s) MRL (the loopback HLS proxy), not a local
        // file — skip the filesystem existence probe so it isn't flagged "missing".
        val isUrl = path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
        val file = if (isUrl) null else java.io.File(path)
        val exists = isUrl || (file?.exists() == true)
        // Record up-front whether the file is even there so the unavailable
        // overlay can distinguish a moved/unmounted file from a missing libvlc.
        mediaMissing.value = !exists
        logger.info("load(path={}, playImmediately={}): url={} exists={} readable={} size={}",
            path, playImmediately, isUrl, exists, file?.canRead() ?: false,
            if (!isUrl && exists) file?.length() ?: -1 else -1)
        // Per-media input options. With preciseSeek, disable libvlc's default
        // fast (keyframe) seeking so setTime/setPosition land on the exact
        // requested frame rather than snapping to the nearest keyframe — this
        // is what makes back-one-frame stepping work (see the constructor doc).
        val mediaOptions: Array<String> =
            if (preciseSeek) arrayOf(":no-input-fast-seek") else emptyArray()
        SwingUtilities.invokeLater {
            try {
                val ok = if (playImmediately) {
                    mp.media().play(path, *mediaOptions)
                } else {
                    mp.media().startPaused(path, *mediaOptions)
                }
                logger.info("media().{} returned {} (options={})",
                    if (playImmediately) "play" else "startPaused", ok,
                    mediaOptions.joinToString(" "))
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
        val mp = activeMediaPlayer() ?: run {
            logger.warn("togglePause ignored: no media player")
            return
        }
        logger.debug("togglePause")
        SwingUtilities.invokeLater { mp.controls().pause() }
    }

    fun play() {
        val mp = activeMediaPlayer() ?: run {
            logger.warn("play ignored: no media player")
            return
        }
        logger.debug("play")
        SwingUtilities.invokeLater { mp.controls().play() }
    }

    /**
     * Stop playback entirely (resets the playhead to 0). VLCJ's `stop()`
     * doesn't always emit a paused/stopped event on every platform, so we
     * also nudge our own state flags to keep the UI in sync.
     */
    fun stop() {
        val mp = activeMediaPlayer() ?: run {
            logger.warn("stop ignored: no media player")
            return
        }
        logger.debug("stop")
        SwingUtilities.invokeLater {
            try { mp.controls().stop() } catch (t: Throwable) {
                logger.warn("Exception during stop", t)
            }
        }
        isPlaying.value = false
        currentTimeMs.value = 0L
    }

    /** Step forward exactly one frame (libvlc supports this natively). */
    fun stepForwardOneFrame() {
        val mp = activeMediaPlayer() ?: return
        SwingUtilities.invokeLater { mp.controls().nextFrame() }
    }

    /**
     * Move by [frames] frames (positive = forward, negative = back) from the
     * current position. libvlc has no native "previous frame" call, so we map
     * the current time to a frame index, offset it by [frames], and seek to the
     * CENTRE of the target frame.
     *
     * Centring is what makes single-frame stepping exact. Seeking to a frame's
     * leading edge (current − frameDuration) is fragile: the frame duration in
     * whole milliseconds is truncated (33 ms for a 33.33 ms frame), and libvlc
     * snaps the reported time to the decoded frame's PTS, so the rounded target
     * can fall just inside an adjacent frame and skip one. Aiming at the middle
     * of the target frame leaves a half-frame (~16 ms @30) margin on both sides,
     * so the rounding can't cross a frame boundary.
     *
     * Requires accurate seeking — the detail player's preciseSeek
     * (`:no-input-fast-seek`). With libvlc's default fast seek this still snaps
     * to the nearest keyframe regardless of the target.
     */
    fun skipFrames(frames: Int, fps: Double) {
        if (fps <= 0.0 || frames == 0) return
        val mp = activeMediaPlayer() ?: return
        SwingUtilities.invokeLater {
            val frameMs = 1000.0 / fps
            val currentFrame = Math.round(mp.status().time() / frameMs)
            val targetFrame = (currentFrame + frames).coerceAtLeast(0L)
            val total = mp.status().length().takeIf { it > 0 } ?: 0L
            // Middle of the target frame, not its leading edge (see above).
            var targetMs = ((targetFrame + 0.5) * frameMs).toLong()
            if (total > 0L) targetMs = targetMs.coerceIn(0L, total)
            mp.controls().setTime(targetMs)
        }
    }

    /** Set output volume, 0–100. libvlc resets a new media's volume to 100, so
     *  callers re-apply the user's level after each load. */
    fun setVolume(percent: Int) {
        val mp = activeMediaPlayer() ?: return
        val v = percent.coerceIn(0, 100)
        SwingUtilities.invokeLater {
            try { mp.audio().setVolume(v) } catch (_: Throwable) {}
        }
    }

    fun seek(timeMs: Long) {
        val mp = activeMediaPlayer() ?: return
        SwingUtilities.invokeLater {
            mp.controls().setTime(timeMs)
            // While paused, libvlc performs the seek but never pushes the
            // target frame to the (callback) video surface, so the loupe would
            // keep showing the pre-seek frame — exactly the "bar moves but the
            // frame doesn't change" report. nextFrame() forces a decode+display
            // of the seeked frame (≈1 frame past the request, imperceptible
            // while scrubbing), firing the render callback so the displayed
            // frame tracks the scrubber — matching the macOS client.
            if (!mp.status().isPlaying) {
                mp.controls().nextFrame()
            }
        }
    }

    fun release() {
        val cb = component
        val emb = embeddedComponent
        if (cb == null && emb == null) return
        logger.debug("release")
        SwingUtilities.invokeLater {
            try { cb?.release() } catch (t: Throwable) {
                logger.warn("Exception during release (callback component)", t)
            }
            try { emb?.release() } catch (t: Throwable) {
                logger.warn("Exception during release (embedded component)", t)
            }
            try { nativeLog?.release() } catch (t: Throwable) {
                logger.warn("Exception during release (nativeLog)", t)
            }
            // The component only releases the factory when it created it; we
            // passed one in, so we own the release.
            try { factory?.release() } catch (t: Throwable) {
                logger.warn("Exception during release (factory)", t)
            }
        }
        component = null
        embeddedComponent = null
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

            // --- Smooth playback on the software callback path ---
            //
            // Our vmem/callback renderer (the only path that works under
            // Compose Desktop's SwingPanel on macOS) copies every decoded
            // frame on the CPU and rebuilds a Skia image. Above ~480p that
            // work can't always hit the display deadline.
            //
            // We used to force late pictures to be displayed
            // (`--no-drop-late-frames`) so 4K HEVC wouldn't start black. But
            // that makes libvlc queue the late frames and then flush them in
            // a burst, which the user sees as a periodic "freeze, then jump
            // ahead" every few seconds.
            //
            // Let the video output DROP pictures that miss their deadline
            // instead, so the playback clock never stalls — a few frames are
            // skipped rather than the whole image freezing and catching up.
            // Paired with the generous pre-roll cache below, enough frames
            // are decoded ahead that the first display isn't late, so we keep
            // the no-black startup behavior without the mid-stream judder.
            "--drop-late-frames",

            // The DECODER still decodes every frame (only the on-screen
            // display of already-late frames is dropped, above), so we never
            // lose reference frames or the first I-frame on a slow-start file.
            "--no-skip-frames",

            // Larger file cache so libvlc decodes a healthy pre-roll before
            // the playback clock starts: headroom to absorb decode + filter
            // latency, and (with --drop-late-frames) frames ready in time for
            // the first display. Default is 300 ms.
            "--file-caching=1500",

            // verbose=2 enables libvlc's DEBUG-level messages; NativeLog
            // then routes them through the "libvlc" SLF4J logger. Keep
            // this on while we're hunting playback issues; tune the
            // SLF4J logger in logback.xml to control what actually prints.
            "--verbose=2",
        )
        // System property escape hatch: -Dreelvault.libvlc.args=... appends
        // extra args. Useful for switching vouts (`--vout=caopengllayer`)
        // or tightening verbosity (`--verbose=3`) without rebuilding.
        val extra = System.getProperty("reelvault.libvlc.args", "").trim()
        if (extra.isNotEmpty()) {
            base += extra.split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        return base.toTypedArray()
    }

    /**
     * Compose surface for the player. Reads the most-recent rendered frame
     * from [frame] and draws it in a Compose `Image` composable. No Swing
     * heavyweight component, no Canvas, no NSView wrangling.
     *
     * Returns without drawing anything when no frame has been decoded yet so
     * callers that layer this surface on top of a thumbnail see the thumbnail
     * until the first frame arrives — no black flash, no stale frame from a
     * previous video.
     */
    @Composable
    fun Surface(modifier: Modifier = Modifier, targetAspectRatio: Float? = null) {
        // Embedded (native) path: host libvlc's own video component in a
        // SwingPanel. Force the original aspect ratio so a mismatched-ratio
        // proxy is stretched to the original's shape (parity with the callback
        // path); the native surface is opaque, so even if that no-ops a mismatch
        // just black-letterboxes — still fine, never revealing anything behind.
        val emb = embeddedComponent
        if (emb != null) {
            LaunchedEffect(targetAspectRatio, renderingHealthy.value) {
                if (renderingHealthy.value) applyEmbeddedAspectRatio(targetAspectRatio)
            }
            val swingFactory = remember(emb) { { emb } }
            // Black SwingPanel background (defaults to white) so any letterbox
            // or pre-first-frame gap matches libvlc's own black vout, no flash.
            SwingPanel(
                background = androidx.compose.ui.graphics.Color.Black,
                modifier = modifier,
                factory = swingFactory,
            )
            return
        }
        val bitmap = frame.value ?: return
        if (targetAspectRatio != null && targetAspectRatio > 0f) {
            // Stretch the decoded frame to a box of the given aspect ratio (the
            // ORIGINAL video's), centered with black letterbox around it. A
            // proxy encoded at a different ratio is therefore squished to the
            // original's shape instead of being aspect-fit to its own — keeping
            // playback shape consistent and never revealing anything behind it.
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.aspectRatio(targetAspectRatio),
                    contentScale = ContentScale.FillBounds,
                )
            }
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.Fit,
            )
        }
    }

    /**
     * Force libvlc's display aspect ratio to [targetAspectRatio] (the original
     * video's) so the embedded surface stretches a mismatched-ratio proxy to
     * the original's shape; null restores the source ratio. No-op on the
     * callback path (which sizes the frame in Compose instead).
     */
    private fun applyEmbeddedAspectRatio(targetAspectRatio: Float?) {
        val mp = embeddedComponent?.mediaPlayer() ?: return
        SwingUtilities.invokeLater {
            try {
                if (targetAspectRatio != null && targetAspectRatio > 0f) {
                    // Express the ratio as W:H integers; libvlc then scales the
                    // (possibly mismatched-AR) source to that display ratio.
                    val w = kotlin.math.round(targetAspectRatio * 10_000f).toInt()
                    mp.video().setAspectRatio("$w:10000")
                } else {
                    mp.video().setAspectRatio(null)
                }
            } catch (t: Throwable) {
                logger.warn("setAspectRatio({}) failed", targetAspectRatio, t)
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
         * composition of [com.reelvault.ui.screens.GridScreen], which is safe
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
