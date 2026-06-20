// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.components

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
 * Shown in place of a VLCJ video surface when playback can't proceed. The
 * message distinguishes three causes so a missing file isn't mislabelled as a
 * missing VLC install:
 *   * [player] is null — libvlc never loaded → "install VLC".
 *   * the player's [ComposeVideoPlayer.mediaMissing] is set — the file isn't
 *     on disk → "file not found".
 *   * otherwise, after [healthCheckDelayMs] with no rendered frame — an
 *     unreadable file or unsupported codec → "can't play this video".
 *
 * The first two causes are known immediately; only the last waits out the
 * health-check window so genuinely slow-to-start media isn't flagged.
 */
@Composable
fun VlcUnavailableOverlay(
    player: ComposeVideoPlayer? = null,
    healthCheckDelayMs: Long = 3_000L,
    modifier: Modifier = Modifier,
) {
    // If we have a player and it's rendering, don't show anything.
    if (player?.renderingHealthy?.value == true) return

    // Only a null player means libvlc itself is unavailable. A live player that
    // simply isn't rendering is a media problem, not an install problem.
    val libvlcMissing = player == null
    val fileMissing = player?.mediaMissing?.value == true

    // libvlc-missing and file-missing are known at once; only the ambiguous
    // "loaded but no frame yet" case waits out the health-check window.
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        delay(healthCheckDelayMs)
        waited = true
    }
    if (!libvlcMissing && !fileMissing && !waited) return

    val os = System.getProperty("os.name", "").lowercase()
    val installHint = when {
        os.contains("mac") ->
            "Install VLC from videolan.org (VLC.app must be in /Applications)."
        os.contains("win") ->
            "Install VLC from videolan.org."
        else ->
            "Install VLC: sudo apt install vlc  (or your distro's equivalent)."
    }
    val (title, detail) = when {
        libvlcMissing -> "In-app playback needs VLC" to installHint
        fileMissing -> "File not found" to
            ("This video isn't where the catalog expects it — it may have been " +
                "moved or renamed, or its drive isn't mounted. Re-scan the " +
                "library to update its location.")
        else -> "Can't play this video" to
            "The file may be unreadable or in a format VLC can't decode."
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
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = detail,
                color = Color(0xFFCCCCCC),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
