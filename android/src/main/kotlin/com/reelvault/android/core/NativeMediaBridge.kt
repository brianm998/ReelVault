// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.core

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The native media backend for the embedded core — the Android mirror of iOS's
 * `NativeMediaShim`. The Rust core (`core/src/android.rs` → `JniMediaBackend`)
 * calls these methods over JNI for the media work the desktop core shells out to
 * ffmpeg for. Implemented with `MediaMetadataRetriever` + `MediaExtractor` (probe
 * + frame extraction); proxy transcode and loudness are deferred for v1 (local
 * mode plays the original via ExoPlayer and never streams, so a proxy is never
 * needed, and the loudness graph self-heals when empty).
 *
 * Registered once at startup via `ReelVaultCore.nativeRegisterMediaBackend(this)`.
 * Methods run on the core's worker threads (off the main thread). Method names +
 * signatures are bound by name from Rust — keep them in sync with the
 * `env.call_method(...)` descriptors in `core/src/android.rs`.
 *
 * `kind`: 1 = MediaStore asset (`srcId` = MediaStore `_ID`), 0 = filesystem path.
 */
class NativeMediaBridge(private val context: Context) {
    private companion object {
        const val TAG = "ReelVault"
    }

    /**
     * Return ffprobe-shaped JSON (deserializes to the core's `FFProbeOutput`), or
     * null on failure. The shape mirrors NativeMediaShim.probeJSON: `streams[]`
     * (video: width/height/codec_name/`r_frame_rate` as a millihertz rational +
     * a `rotate` tag; audio: codec_name/channels/sample_rate) and `format`
     * (duration secs, bit_rate, tags{creation_time, location}).
     */
    fun probe(kind: Int, srcId: String): String? {
        val mmr = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        var extractorReady = false
        try {
            setDataSource(mmr, kind, srcId)
            try {
                setDataSource(extractor, kind, srcId)
                extractorReady = true
            } catch (e: Exception) {
                Log.w(TAG, "probe: MediaExtractor unavailable, falling back to MMR: $e")
            }

            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
            val streams = JSONArray()
            var index = 0

            if (extractorReady) {
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    when {
                        mime.startsWith("video/") -> {
                            streams.put(videoStream(index++, f, mmr, mime, rotation))
                        }
                        mime.startsWith("audio/") -> {
                            val s = JSONObject()
                                .put("index", index++)
                                .put("codec_type", "audio")
                                .put("codec_name", audioCodecName(mime))
                            f.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.let { s.put("channels", it) }
                            f.intOrNull(MediaFormat.KEY_SAMPLE_RATE)?.let {
                                s.put("sample_rate", it.toString())
                            }
                            streams.put(s)
                        }
                    }
                }
            }

            // Fallback: no usable video track from the extractor but MMR sees one.
            if (streams.length() == 0 &&
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            ) {
                streams.put(videoStream(index, null, mmr, null, rotation))
            }

            val format = JSONObject()
            val durationMs = mmr
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            format.put("duration", if (durationMs != null) durationMs / 1000.0 else JSONObject.NULL)
            val bitrate = mmr
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
            format.put("bit_rate", bitrate ?: JSONObject.NULL)

            val tags = JSONObject()
            normalizedCreationTime(mmr)?.let { tags.put("creation_time", it) }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                ?.takeIf { it.isNotBlank() }
                ?.let { tags.put("com.apple.quicktime.location.ISO6709", it) }
            if (tags.length() > 0) format.put("tags", tags)

            return JSONObject().put("streams", streams).put("format", format).toString()
        } catch (e: Exception) {
            Log.w(TAG, "probe($kind,$srcId) failed: $e")
            return null
        } finally {
            runCatching { mmr.release() }
            runCatching { extractor.release() }
        }
    }

    private fun videoStream(
        index: Int,
        f: MediaFormat?,
        mmr: MediaMetadataRetriever,
        mime: String?,
        rotation: Int?,
    ): JSONObject {
        val s = JSONObject().put("index", index).put("codec_type", "video")
        mime?.let { s.put("codec_name", videoCodecName(it)) }
        val width = f?.intOrNull(MediaFormat.KEY_WIDTH)
            ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = f?.intOrNull(MediaFormat.KEY_HEIGHT)
            ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        width?.let { s.put("width", it) }
        height?.let { s.put("height", it) }
        // Millihertz rational so fractional NTSC rates survive (29.97 → 29970/1000).
        val fps = f?.numberOrNull(MediaFormat.KEY_FRAME_RATE)
        if (fps != null && fps > 0.0) {
            s.put("r_frame_rate", "${(fps * 1000).roundToInt()}/1000")
        }
        // The core swaps width↔height on |rotation| == 90/270 (it reads abs), via
        // either the "rotate" stream tag or a Display-Matrix side-data entry. Emit
        // the simpler tag form.
        if (rotation != null && rotation != 0) {
            s.put("tags", JSONObject().put("rotate", rotation.toString()))
        }
        return s
    }

    /**
     * Decode one frame at [timeSecs] (longest side <= [maxPx]) and write a JPEG to
     * [outPath] atomically. Returns 0 on success, non-zero on failure.
     */
    fun extractFrame(kind: Int, srcId: String, timeSecs: Double, maxPx: Int, outPath: String): Int {
        val mmr = MediaMetadataRetriever()
        try {
            setDataSource(mmr, kind, srcId)
            val timeUs = (timeSecs * 1_000_000.0).toLong().coerceAtLeast(0L)
            // OPTION_CLOSEST gives the frame nearest the timestamp (not just the
            // preceding keyframe), matching the iOS exact-seek behavior. MMR
            // returns the bitmap already rotated per the display matrix.
            val frame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return 1
            val scaled = scaleToLongestSide(frame, maxPx)
            val tmp = File("$outPath.tmp")
            FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
            return if (tmp.renameTo(File(outPath))) 0 else { tmp.delete(); 2 }
        } catch (e: Exception) {
            Log.w(TAG, "extractFrame($srcId) failed: $e")
            return 3
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * Transcode an H.264/AAC proxy. Deferred for v1 — local mode plays the
     * original directly via ExoPlayer and never streams a proxy. Returning
     * non-zero makes the core treat proxy generation as best-effort/failed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun transcodeProxy(kind: Int, srcId: String, outPath: String, targetHeight: Int): Int = -1

    /**
     * Audio loudness envelope. Deferred for v1 (the detail "Visuals" graph shows
     * empty and self-heals); a MediaExtractor+MediaCodec PCM→RMS implementation
     * can land later without any Rust change.
     */
    @Suppress("UNUSED_PARAMETER")
    fun extractLoudness(kind: Int, srcId: String, maxSamples: Int): FloatArray? = null

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun videoUri(srcId: String): Uri =
        ContentUris.withAppendedId(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            srcId.toLong(),
        )

    private fun setDataSource(mmr: MediaMetadataRetriever, kind: Int, srcId: String) {
        if (kind == 1) mmr.setDataSource(context, videoUri(srcId)) else mmr.setDataSource(srcId)
    }

    private fun setDataSource(extractor: MediaExtractor, kind: Int, srcId: String) {
        if (kind == 1) extractor.setDataSource(context, videoUri(srcId), null)
        else extractor.setDataSource(srcId)
    }

    private fun scaleToLongestSide(b: Bitmap, maxPx: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (maxPx <= 0 || longest <= maxPx) return b
        val scale = maxPx.toDouble() / longest
        val nw = max(1, (b.width * scale).roundToInt())
        val nh = max(1, (b.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(b, nw, nh, true)
    }

    /** MMR's date (e.g. "20231225T133000.000Z") → RFC3339 UTC the core parses. */
    private fun normalizedCreationTime(mmr: MediaMetadataRetriever): String? {
        val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val out = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        for (pattern in DATE_PATTERNS) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = sdf.parse(raw) ?: continue
                return out.format(date)
            } catch (_: Exception) { /* try next */ }
        }
        return raw // pass through; the core may still parse it
    }

    private val DATE_PATTERNS = listOf(
        "yyyyMMdd'T'HHmmss.SSS'Z'",
        "yyyyMMdd'T'HHmmss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss",
    )

    private fun videoCodecName(mime: String): String = when (mime.lowercase()) {
        "video/avc" -> "h264"
        "video/hevc", "video/dolby-vision" -> "hevc"
        "video/x-vnd.on2.vp9" -> "vp9"
        "video/x-vnd.on2.vp8" -> "vp8"
        "video/av01" -> "av1"
        "video/mp4v-es" -> "mpeg4"
        "video/3gpp" -> "h263"
        "video/mpeg2" -> "mpeg2video"
        else -> mime.substringAfter("video/")
    }

    private fun audioCodecName(mime: String): String = when (mime.lowercase()) {
        "audio/mp4a-latm" -> "aac"
        "audio/raw" -> "pcm"
        "audio/opus" -> "opus"
        "audio/vorbis" -> "vorbis"
        "audio/ac3" -> "ac3"
        "audio/eac3" -> "eac3"
        "audio/flac" -> "flac"
        "audio/mpeg" -> "mp3"
        "audio/3gpp", "audio/amr" -> "amr_nb"
        else -> mime.substringAfter("audio/")
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        try { getInteger(key) } catch (_: Exception) { null }

    private fun MediaFormat.numberOrNull(key: String): Double? =
        try {
            getInteger(key).toDouble()
        } catch (_: Exception) {
            try { getFloat(key).toDouble() } catch (_: Exception) { null }
        }
}
