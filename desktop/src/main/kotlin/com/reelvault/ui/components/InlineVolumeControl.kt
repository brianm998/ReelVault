// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A compact playback-volume slider overlaid on the currently-playing inline
 * card (grid / list). Shown only for clips that carry an audio track; the
 * caller is responsible for that gate and for placing/aligning this control
 * inside the card's Box via [modifier] (e.g. `Modifier.align(BottomCenter)`).
 *
 * It mirrors the detail-view loupe's volume control but is smaller so it fits
 * over a thumbnail. The Material3 Slider normally claims a 48 dp interactive
 * height; [requiredHeight] overrides that so the pill stays low-profile.
 *
 * Because the Slider consumes its own pointer gestures, an overlaid instance
 * does not trip the card's selection handler (which only fires on an
 * *unconsumed* pointer-down) — the same basis the play/stop buttons rely on.
 */
@Composable
fun InlineVolumeControl(
    volume: Int,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Slider(
                value = volume.toFloat(),
                onValueChange = { onVolumeChange(it.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier
                    .width(76.dp)
                    .requiredHeight(24.dp),
            )
        }
    }
}
