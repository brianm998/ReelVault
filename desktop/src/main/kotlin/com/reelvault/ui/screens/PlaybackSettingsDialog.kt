// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.launch

/**
 * Preferences sheet for the playback resolution gate and the default
 * proxy target height. Backs onto the daemon's `max_native_playback_height`
 * and `proxy_target_height` config keys.
 *
 * Behavior surfaced to the user:
 *   - Videos taller than the playback ceiling get a "Too large to play
 *     here" marker on their grid card and offer a one-click proxy.
 *   - Newly-generated proxies default to the proxy height.
 */
@Composable
fun PlaybackSettingsDialog(
    repository: VideoRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val playbackOptions = listOf(720, 1080, 1440, 2160, 4320)
    val proxyOptions = listOf(540, 720, 1080, 1440)

    var maxNative by remember { mutableStateOf(2160) }
    var proxyTarget by remember { mutableStateOf(720) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        repository.getConfig()?.let { cfg ->
            maxNative = cfg.maxNativePlaybackHeight.takeIf { it > 0 } ?: 2160
            proxyTarget = cfg.proxyTargetHeight.takeIf { it > 0 } ?: 720
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Playback & Proxies", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Gate inline playback; pick a default proxy size",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Loading current settings…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Column(modifier = Modifier.width(440.dp)) {
                    Text("Maximum inline-playback height", fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    SegmentedHeightPicker(
                        options = playbackOptions,
                        selected = maxNative,
                        onSelect = { maxNative = it },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Videos taller than this show \"Too large to play here\" and offer a proxy. Higher = more inline playback but slower scrolling on a busy grid.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Default proxy height", fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    SegmentedHeightPicker(
                        options = proxyOptions,
                        selected = proxyTarget,
                        onSelect = { proxyTarget = it },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Newly-created proxies default to this height. Smaller = faster + smaller files; larger = closer to the original quality.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        repository.updateConfig(
                            maxNativePlaybackHeight = maxNative,
                            proxyTargetHeight = proxyTarget,
                        )
                        saving = false
                        onDismiss()
                    }
                },
                enabled = !loading && !saving,
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SegmentedHeightPicker(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, h ->
            SegmentedButton(
                selected = selected == h,
                onClick = { onSelect(h) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text("${h}p")
            }
        }
    }
}
