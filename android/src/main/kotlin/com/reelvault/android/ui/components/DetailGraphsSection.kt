// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reelvault.android.R
import com.reelvault.android.viewmodel.GridViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** Per-scrub-frame pixel averages. [luma] is Rec.601 luminance (0..1); [r]/[g]/[b]
 *  are the mean channel values (0..1). Computed off the UI thread from the JPEG
 *  bytes in the scrub-frame cache. */
private data class FrameStat(val luma: Float, val r: Float, val g: Float, val b: Float) {
    val color: Color get() = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}

private val graphBackground = Color(0xFF1E1E1E)
private val tealColor = Color(0xFF4DD0E1)
private val redChannel = Color(0xFFE57373)
private val greenChannel = Color(0xFF81C784)
private val blueChannel = Color(0xFF64B5F6)

/**
 * "Visuals" graphs section for the Android detail view. Shows:
 *  - Brightness curve across the clip (derived from scrub thumbnails)
 *  - Color-over-time strip (average colour of each scrub frame)
 *  - RGB channel curves
 *  - Audio loudness curve (from the daemon, normalized to 0..1)
 *
 * Mirrors iOS [DetailGraphsView] and desktop [DetailGraphsPanel], adapted for
 * Android: pixel sampling uses [BitmapFactory] + [android.graphics.Bitmap] instead
 * of Skia (a desktop-only dep) or CGContext (iOS).
 *
 * The section is self-loading: it calls [GridViewModel.loadScrubFrames] and
 * [GridViewModel.loadAudioLoudness] on first composition for [videoId], and
 * recomputes stats whenever new frames arrive. It renders nothing while both
 * series are absent, so an all-empty clip (no audio, no thumbnails) contributes
 * no blank space to the inspector.
 */
@Composable
fun DetailGraphsSection(
    videoId: String,
    viewModel: GridViewModel,
    modifier: Modifier = Modifier,
) {
    // Kick off the fetches (idempotent — GridViewModel guards against duplicates).
    LaunchedEffect(videoId) {
        viewModel.loadScrubFrames(videoId)
        viewModel.loadAudioLoudness(videoId)
    }

    // Collect the frame bytes as a snapshot keyed on a cheap size-signature so
    // the expensive off-thread pixel sampling only re-runs when the actual bytes
    // change, not on every recomposition.
    val scrubBytesSignature by produceState(initialValue = "" to emptyList<ByteArray?>(), videoId) {
        viewModel.scrubFrames.collectLatest { map ->
            val frames = map[videoId] ?: emptyList()
            value = frames.joinToString(",") { (it?.size ?: 0).toString() } to frames
        }
    }
    val (signature, frameBytes) = scrubBytesSignature

    val stats by produceState(initialValue = emptyList<FrameStat>(), signature) {
        value = withContext(Dispatchers.Default) {
            frameBytes.mapNotNull { it?.let(::computeFrameStat) }
        }
    }

    val loudness by produceState(initialValue = null as List<Float>?, videoId) {
        viewModel.audioLoudness.collectLatest { map ->
            value = map[videoId]  // null = still loading; emptyList = no audio
        }
    }

    val hasStats = stats.size >= 2
    val hasLoudness = (loudness?.size ?: 0) >= 2
    val loudnessLoaded = loudness != null

    // Only render the card when there is something to show (or the loudness
    // series hasn't finished loading yet).
    if (!hasStats && !hasLoudness && loudnessLoaded) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Section header — same uppercase style as the other detail sections.
        Text(
            text = stringResource(R.string.detail_section_visuals),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))

        if (hasStats) {
            GraphLabel(stringResource(R.string.detail_graphs_brightness))
            BrightnessChart(stats)
            GraphLabel(stringResource(R.string.detail_graphs_color_over_time))
            ColorTimeline(stats)
            GraphLabel(stringResource(R.string.detail_graphs_rgb_channels))
            RgbChart(stats)
        }

        // Loudness: three states — still loading (spinner), loaded+data (chart),
        // loaded+empty (silently absent — the clip has no audio).
        if (hasLoudness && loudness != null) {
            GraphLabel(stringResource(R.string.detail_graphs_loudness))
            LoudnessChart(loudness!!)
        } else if (!loudnessLoaded) {
            GraphLabel(stringResource(R.string.detail_graphs_loudness))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(graphBackground, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.detail_graphs_analyzing_audio),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GraphLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Filled luminance curve across the clip (left = clip start, right = clip end). */
@Composable
private fun BrightnessChart(stats: List<FrameStat>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(graphBackground, RoundedCornerShape(4.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(4.dp)) {
            val n = stats.size
            if (n < 2) return@Canvas
            fun px(i: Int) = size.width * i / (n - 1)
            fun py(v: Float) = size.height * (1f - v.coerceIn(0f, 1f))
            val area = Path().apply {
                moveTo(0f, size.height)
                stats.forEachIndexed { i, s -> lineTo(px(i), py(s.luma)) }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(area, color = Color.White.copy(alpha = 0.15f))
            val line = Path().apply {
                stats.forEachIndexed { i, s ->
                    if (i == 0) moveTo(px(i), py(s.luma)) else lineTo(px(i), py(s.luma))
                }
            }
            drawPath(line, color = Color.White, style = Stroke(width = 2f))
        }
    }
}

/** Strip of each scrub frame's average colour, in clip order. */
@Composable
private fun ColorTimeline(stats: List<FrameStat>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(graphBackground, RoundedCornerShape(4.dp)),
    ) {
        stats.forEach { s ->
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(s.color))
        }
    }
}

/** Three overlaid channel curves (red / green / blue) across the clip. */
@Composable
private fun RgbChart(stats: List<FrameStat>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(graphBackground, RoundedCornerShape(4.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(4.dp)) {
            val n = stats.size
            if (n < 2) return@Canvas
            fun px(i: Int) = size.width * i / (n - 1)
            fun py(v: Float) = size.height * (1f - v.coerceIn(0f, 1f))
            fun channel(sel: (FrameStat) -> Float, color: Color) {
                val p = Path().apply {
                    stats.forEachIndexed { i, s ->
                        if (i == 0) moveTo(px(i), py(sel(s))) else lineTo(px(i), py(sel(s)))
                    }
                }
                drawPath(p, color = color, style = Stroke(width = 2f))
            }
            channel({ it.r }, redChannel)
            channel({ it.g }, greenChannel)
            channel({ it.b }, blueChannel)
        }
    }
}

/** Filled loudness curve across the clip. Values are normalized momentary
 *  loudness in 0..1 (silence ≈ 0, full scale ≈ 1). */
@Composable
private fun LoudnessChart(loudness: List<Float>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(graphBackground, RoundedCornerShape(4.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(4.dp)) {
            val n = loudness.size
            if (n < 2) return@Canvas
            fun px(i: Int) = size.width * i / (n - 1)
            fun py(v: Float) = size.height * (1f - v.coerceIn(0f, 1f))
            val area = Path().apply {
                moveTo(0f, size.height)
                loudness.forEachIndexed { i, v -> lineTo(px(i), py(v)) }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(area, color = tealColor.copy(alpha = 0.20f))
            val line = Path().apply {
                loudness.forEachIndexed { i, v ->
                    if (i == 0) moveTo(px(i), py(v)) else lineTo(px(i), py(v))
                }
            }
            drawPath(line, color = tealColor, style = Stroke(width = 2f))
        }
    }
}

/** Sample a grid of pixels from encoded JPEG bytes and average R/G/B.
 *  Returns null if the bytes don't decode or produce an empty image.
 *  Runs off the UI thread (see [produceState] call site). */
private fun computeFrameStat(bytes: ByteArray): FrameStat? {
    val bmp: Bitmap = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
        null
    } ?: return null

    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) {
        bmp.recycle()
        return null
    }

    val steps = 12
    var rs = 0L; var gs = 0L; var bs = 0L; var count = 0
    for (iy in 0 until steps) {
        val y = (iy * h) / steps
        for (ix in 0 until steps) {
            val x = (ix * w) / steps
            val c = bmp.getPixel(x, y)
            rs += (c ushr 16) and 0xFF
            gs += (c ushr 8) and 0xFF
            bs += c and 0xFF
            count++
        }
    }
    bmp.recycle()

    if (count == 0) return null
    val r = rs.toFloat() / count / 255f
    val g = gs.toFloat() / count / 255f
    val b = bs.toFloat() / count / 255f
    return FrameStat(luma = 0.299f * r + 0.587f * g + 0.114f * b, r = r, g = g, b = b)
}
