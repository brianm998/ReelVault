// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Shown in place of a VLCJ video surface when libvlc is unavailable or
 * when the rendering health-check times out (black surface).
 *
 * Accepts an optional [player] whose [ComposeVideoPlayer.renderingHealthy]
 * is monitored: the overlay stays hidden for [healthCheckDelayMs] so
 * genuine slow-to-start media isn't flagged, then if [renderingHealthy]
 * is still false the instructions appear.
 *
 * When [player] is null (libvlc never loaded) the instructions appear
 * immediately.
 */
@Composable
fun VlcUnavailableOverlay(
    player: ComposeVideoPlayer? = null,
    healthCheckDelayMs: Long = 3_000L,
    modifier: Modifier = Modifier,
) {
    // If we have a player but it's healthy, don't show anything.
    val healthy = player?.renderingHealthy?.value ?: false
    if (healthy) return

    // Give the player a moment to start before we declare failure.
    var timedOut by remember { mutableStateOf(player == null) }
    if (!timedOut) {
        LaunchedEffect(Unit) {
            delay(healthCheckDelayMs)
            timedOut = true
        }
    }
    if (!timedOut) return

    val os = System.getProperty("os.name", "").lowercase()
    val installHint = when {
        os.contains("mac") ->
            "Install VLC from videolan.org (VLC.app must be in /Applications)."
        os.contains("win") ->
            "Install VLC from videolan.org."
        else ->
            "Install VLC: sudo apt install vlc  (or your distro's equivalent)."
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "In-app playback needs VLC",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = installHint,
                color = Color(0xFFCCCCCC),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
