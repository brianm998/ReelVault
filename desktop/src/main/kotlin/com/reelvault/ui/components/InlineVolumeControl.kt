// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A compact playback-volume slider overlaid on the currently-playing inline
 * card (grid / list). Shown only for clips that carry an audio track; the
 * caller gates that and places/aligns this control inside the card's Box via
 * [modifier] (e.g. `Modifier.align(BottomCenter)`).
 *
 * Deliberately NOT a Material3 `Slider`: a Slider carries a `FocusTargetNode`,
 * and this overlay is mounted/unmounted every time inline playback starts and
 * stops. On Compose-Desktop 1.6.1, removing a focusable node from a subtree
 * that is being detached crashes with "visitAncestors called on an unattached
 * node" (which then corrupts the composition's SlotTable) — and the EDT focus
 * guard can't safely swallow it. A hand-drawn track + thumb driven by
 * `pointerInput` has no focus target, so it sidesteps the bug entirely. The
 * detail loupe keeps its Material Slider — its control bar is a stable subtree.
 *
 * The drag consumes its pointer events, so it doesn't trip the card's selection
 * handler or the file drag-out (which yields to a consumed child drag).
 */
@Composable
fun InlineVolumeControl(
    volume: Int,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val frac = (volume / 100f).coerceIn(0f, 1f)
    Tooltip(text = "Playback volume") {
        Row(
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (volume == 0) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                contentDescription = "Volume",
                modifier = Modifier.size(14.dp),
                tint = Color.White,
            )
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .height(16.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onVolumeChange(((offset.x / size.width) * 100f).roundToInt().coerceIn(0, 100))
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                onVolumeChange(((offset.x / size.width) * 100f).roundToInt().coerceIn(0, 100))
                            },
                        ) { change, _ ->
                            change.consume()
                            onVolumeChange(((change.position.x / size.width) * 100f).roundToInt().coerceIn(0, 100))
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                // Unfilled track.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.35f)),
                )
                // Filled portion (0..frac of the width).
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White),
                )
                // Thumb — sits at the right edge of the filled portion.
                Box(
                    modifier = Modifier.fillMaxWidth(frac),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
            }
        }
    }
}
