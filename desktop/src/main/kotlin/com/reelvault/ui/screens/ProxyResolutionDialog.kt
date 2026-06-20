// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reelvault.data.models.VideoSummary
import com.reelvault.util.Strings

/**
 * Resolution picker shown when the user clicks "Create proxy…". Five
 * preset heights with the entries ≥ the source video's height greyed
 * out (a proxy can't be larger than its source). Default selection is
 * 720p unless the source is itself ≤720p, in which case we pick the
 * largest preset below the source.
 *
 * The "Always create proxies at this resolution" toggle is a
 * placeholder — the underlying server config flag doesn't exist yet,
 * so it's disabled and surfaces only as a hint of the planned UX.
 */
@Composable
fun ProxyResolutionDialog(
    sourceVideo: VideoSummary,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val presets = remember { listOf(2160, 1440, 1080, 720, 540) }
    val defaultPick = remember(sourceVideo) {
        if (sourceVideo.height > 720) 720
        else presets.firstOrNull { it < sourceVideo.height } ?: 720
    }
    var selectedHeight by remember(sourceVideo.id) { mutableStateOf(defaultPick) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PictureInPicture,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Create a proxy of this video", fontWeight = FontWeight.SemiBold)
                    Text(
                        sourceVideo.filename,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.width(400.dp)) {
                Text(
                    "Target resolution",
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                presets.forEach { h ->
                    val disabled = sourceVideo.height > 0 && h >= sourceVideo.height
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedHeight == h,
                                enabled = !disabled,
                                onClick = { selectedHeight = h },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedHeight == h,
                            onClick = { if (!disabled) selectedHeight = h },
                            enabled = !disabled,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            presetLabel(h),
                            modifier = Modifier.weight(1f),
                            color = if (disabled)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            presetDetail(h, sourceVideo.height),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Placeholder for "remember this choice" — the server
                // config flag doesn't exist yet, but the placement
                // shows the user where it will live once it does.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = false, onCheckedChange = {}, enabled = false)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Always create proxies at this resolution",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedHeight) }) {
                Text(Strings.format("ui_create_proxy_height", selectedHeight))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(Strings["ui_cancel"]) }
        },
    )
}

private fun presetLabel(h: Int): String = when (h) {
    2160 -> "2160p — 4K UHD"
    1440 -> "1440p — QHD"
    1080 -> "1080p — Full HD"
    720  -> "720p — HD"
    540  -> "540p — qHD"
    else -> "${h}p"
}

private fun presetDetail(h: Int, sourceHeight: Int): String {
    if (sourceHeight > 0 && h >= sourceHeight) return "≥ source (${sourceHeight}p)"
    val pixels = (h * h * 16.0) / 9.0
    val baseline = (1080 * 1080 * 16.0) / 9.0
    val ratio = (pixels / baseline) * 100.0
    return "${ratio.toInt()}% of 1080p"
}
