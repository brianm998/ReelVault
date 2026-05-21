// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.videoroom.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Compose-Desktop tooltip wrapper. Hovering [content] for ~500 ms shows a small
 * popup with [text]. Pass an empty string to render the content with no
 * tooltip (handy when the caller wants to conditionally enable a help string).
 *
 * Uses [TooltipArea], the Compose Multiplatform desktop-specific API for
 * hover popups. The popup is styled with the inverse-surface color so it has
 * good contrast in both light and dark themes.
 */
@Composable
fun Tooltip(
    text: String,
    modifier: Modifier = Modifier,
    /** Cursor-relative offset of the tooltip popup. */
    offsetY: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    if (text.isBlank()) {
        content()
        return
    }
    TooltipArea(
        tooltip = {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
                shadowElevation = 4.dp
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        },
        modifier = modifier,
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, offsetY))
    ) {
        content()
    }
}
