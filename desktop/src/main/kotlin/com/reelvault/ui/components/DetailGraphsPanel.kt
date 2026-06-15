// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.reelvault.ui.theme.ReelVaultSpacing
import com.reelvault.viewmodel.GridViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image as SkiaImage

/** Per-scrub-frame averages used to plot how a clip's light and colour move
 *  over its length. [luma] is Rec.601 luminance (0..1); [r]/[g]/[b] are the
 *  mean channel values (0..1). */
private data class FrameStat(val luma: Float, val r: Float, val g: Float, val b: Float) {
    val color: Color get() = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}

/**
 * Detail-mode left panel: brightness and colour graphs derived from the
 * video's scrub thumbnails. Gives a quick read on how exposure and colour
 * shift across the clip without scrubbing through it frame by frame.
 *
 * Stats are sampled from whatever scrub frames are loaded (the higher-res
 * detail set when present, else the base set), off the UI thread, and
 * recomputed as more frames arrive.
 */
@Composable
fun DetailGraphsPanel(
    viewModel: GridViewModel,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedId by viewModel.selectedVideoId.collectAsState()
    val scrubMap by viewModel.scrubFrames.collectAsState()
    val hiResMap by viewModel.hiResScrubFrames.collectAsState()
    val loudnessMap by viewModel.audioLoudness.collectAsState()

    // Ensure the frames and loudness series are loading even if the centre view
    // hasn't asked yet.
    LaunchedEffect(selectedId) {
        selectedId?.let {
            viewModel.loadScrubFrames(it)
            viewModel.loadAudioLoudness(it)
        }
    }
    val loudness: List<Float> = selectedId?.let { loudnessMap[it] } ?: emptyList()
    // Whether the loudness series has finished loading (the map entry exists,
    // empty or not). Distinguishes "still analysing" (absent) from "loaded, no
    // audio" (present but empty) so a slow first decode isn't a silent blank.
    // The daemon caches the decoded series, so later opens are instant.
    val loudnessLoaded: Boolean = selectedId?.let { loudnessMap.containsKey(it) } ?: false

    // Prefer the detail-resolution frames per index, falling back to the base
    // set — more pixels make for steadier averages, and this tracks whatever
    // the scrubber is already showing.
    val frames: List<ByteArray?> = run {
        val base = selectedId?.let { scrubMap[it] } ?: emptyList()
        val hi = selectedId?.let { hiResMap[it] } ?: emptyList()
        if (base.isEmpty()) hi
        else base.mapIndexed { i, b -> hi.getOrNull(i) ?: b }
    }

    // Key the off-thread computation on a cheap signature so it only re-runs
    // when the actual frame bytes change (not on every recomposition).
    val signature = frames.joinToString(",") { (it?.size ?: 0).toString() }
    val stats by produceState(initialValue = emptyList<FrameStat>(), signature) {
        value = withContext(Dispatchers.Default) {
            frames.mapNotNull { it?.let(::computeFrameStat) }
        }
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        // Header — mirrors the right inspector's collapse affordance.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ReelVaultSpacing.Medium, end = ReelVaultSpacing.XSmall, top = ReelVaultSpacing.Medium, bottom = ReelVaultSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "VISUALS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Tooltip(text = "Hide this panel. Press Tab to toggle both side panels.") {
                IconButton(onClick = onCollapse, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "Hide visuals panel",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = ReelVaultSpacing.Small),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        // if/else (not an early `return@Column`) so the Column's child-group
        // structure stays balanced when `stats` flips from empty to populated —
        // an early return there corrupts Compose's group stack and crashes
        // recomposition.
        val hasStats = stats.size >= 2
        val hasLoudness = loudness.size >= 2
        // if/else (not an early `return@Column`) so the Column's child-group
        // structure stays balanced when the content flips from empty to
        // populated — an early return there corrupts Compose's group stack.
        if (!hasStats && !hasLoudness) {
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (selectedId == null) "Select a video" else "Analyzing frames…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(ReelVaultSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(ReelVaultSpacing.Medium),
            ) {
                if (hasStats) {
                    SectionLabel("Brightness")
                    BrightnessChart(stats)

                    SectionLabel("Color over time")
                    ColorTimeline(stats)

                    SectionLabel("RGB channels")
                    RgbChart(stats)
                }
                // Loudness comes from the daemon (ffmpeg), independent of the
                // scrub frames, so it can appear before/without the others. Three
                // states: ready → chart; still decoding → a hint (the first decode
                // can be slow over a networked library); loaded-but-empty →
                // nothing (the clip has no audio).
                if (hasLoudness) {
                    SectionLabel("Loudness")
                    LoudnessChart(loudness)
                } else if (!loudnessLoaded) {
                    SectionLabel("Loudness")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Analyzing audio…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Filled luminance curve across the clip (left = start, right = end). */
@Composable
private fun BrightnessChart(stats: List<FrameStat>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF1E1E1E), RoundedCornerShape(4.dp)),
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

/** Filled loudness curve across the clip (left = start, right = end). Values are
 *  normalized momentary loudness in 0..1 (silence ≈ 0, full scale ≈ 1). */
@Composable
private fun LoudnessChart(loudness: List<Float>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF1E1E1E), RoundedCornerShape(4.dp)),
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
            drawPath(area, color = Color(0xFF4DD0E1).copy(alpha = 0.20f))
            val line = Path().apply {
                loudness.forEachIndexed { i, v ->
                    if (i == 0) moveTo(px(i), py(v)) else lineTo(px(i), py(v))
                }
            }
            drawPath(line, color = Color(0xFF4DD0E1), style = Stroke(width = 2f))
        }
    }
}

/** A strip of each frame's average colour, in clip order. */
@Composable
private fun ColorTimeline(stats: List<FrameStat>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color(0xFF1E1E1E), RoundedCornerShape(4.dp)),
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
            .background(Color(0xFF1E1E1E), RoundedCornerShape(4.dp)),
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
            channel({ it.r }, Color(0xFFE57373))
            channel({ it.g }, Color(0xFF81C784))
            channel({ it.b }, Color(0xFF64B5F6))
        }
    }
}

/** Sample a grid of pixels from an encoded JPEG and average them. Returns null
 *  if the bytes don't decode. Runs off the UI thread (see [produceState]). */
private fun computeFrameStat(bytes: ByteArray): FrameStat? = try {
    val img = SkiaImage.makeFromEncoded(bytes)
    val w = img.width
    val h = img.height
    if (w <= 0 || h <= 0) {
        null
    } else {
        val bmp = Bitmap().apply { allocN32Pixels(w, h, false) }
        img.readPixels(bmp)
        var rs = 0L
        var gs = 0L
        var bs = 0L
        var count = 0
        val steps = 12
        for (iy in 0 until steps) {
            val y = (iy * h) / steps
            for (ix in 0 until steps) {
                val x = (ix * w) / steps
                val c = bmp.getColor(x, y)
                rs += (c ushr 16) and 0xFF
                gs += (c ushr 8) and 0xFF
                bs += c and 0xFF
                count++
            }
        }
        bmp.close()
        if (count == 0) {
            null
        } else {
            val r = rs.toFloat() / count / 255f
            val g = gs.toFloat() / count / 255f
            val b = bs.toFloat() / count / 255f
            FrameStat(0.299f * r + 0.587f * g + 0.114f * b, r, g, b)
        }
    }
} catch (_: Exception) {
    null
}
