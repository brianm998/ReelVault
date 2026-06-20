// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelvault.data.models.WatchSettings
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.launch

/**
 * Sheet that surfaces the daemon's real-time file-watcher knobs.
 *
 * Three controls:
 *   - "Enable live updates" — master switch. When off the daemon doesn't
 *     run a watcher; the user must `Scan Library` manually.
 *   - "Wait for writes to settle" — the size-stability gate that keeps
 *     the indexer from grabbing a half-written recording. Most users
 *     never touch this; default 5 s is a safe baseline.
 *   - "Poll fallback" — interval (seconds) at which the daemon does a
 *     manual readdir on paths that FSEvents/inotify can't see (NFS /
 *     SMB / SAN). Set to 0 to disable.
 *
 * Values are clamped on the server too; the UI just clamps to nice
 * ranges so the sliders feel right.
 */
@Composable
fun WatchSettingsDialog(
    repository: VideoRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Local mirror of the server's current settings. Reloaded on open
    // so we never show stale values from a prior session.
    var settings by remember { mutableStateOf(WatchSettings.Default) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        try {
            settings = repository.getWatchSettings()
        } catch (e: Exception) {
            // getWatchSettings failed — continue with defaults so the
            // buttons are enabled and the user can at least save/cancel.
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SettingsRemote,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Live Updates", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Watch your library locations for new and changed files",
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
                    Text(
                        "Loading current settings…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(modifier = Modifier.width(440.dp)) {
                    // Master switch.
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable live updates")
                            Text(
                                "When off, ReelVault only sees new files after you run \"Scan Library\".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.enabled,
                            onCheckedChange = { settings = settings.copy(enabled = it) },
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Settle slider.
                    val settleSeconds = (settings.writeSettleMs / 1000L).coerceAtLeast(1L)
                    Text(
                        "Wait $settleSeconds s for writes to finish  •  ${settleHelp(settleSeconds)}",
                        fontWeight = FontWeight.Medium,
                    )
                    Slider(
                        value = (settings.writeSettleMs / 1000L).toFloat(),
                        onValueChange = {
                            settings = settings.copy(writeSettleMs = (it.toLong() * 1000L).coerceIn(1_000L, 60_000L))
                        },
                        valueRange = 1f..60f,
                        steps = 58,
                        enabled = settings.enabled,
                    )
                    Text(
                        "Prevents indexing a recording while the camera or upload tool is still writing it. " +
                            "Higher = safer; lower = files appear sooner. 5 s is fine for most workflows.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Poll fallback.
                    val pollSecs = settings.pollIntervalMs / 1000L
                    Text(
                        if (pollSecs == 0L) "Poll fallback: off"
                        else "Poll fallback every $pollSecs s",
                        fontWeight = FontWeight.Medium,
                    )
                    Slider(
                        value = (settings.pollIntervalMs / 1000L).toFloat(),
                        onValueChange = {
                            // 5 s step so the slider feels right; 0 disables.
                            val rounded = ((it / 5f).toInt() * 5).toLong()
                            settings = settings.copy(pollIntervalMs = (rounded * 1000L).coerceIn(0L, 300_000L))
                        },
                        valueRange = 0f..300f,
                        steps = 59,
                        enabled = settings.enabled,
                    )
                    Text(
                        "Used for paths where the OS doesn't deliver file events — typically network mounts " +
                            "(SMB, NFS, SAN). 0 disables the fallback entirely. 30 s is a reasonable default; " +
                            "raise it for very large remote trees.",
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
                        try {
                            repository.updateWatchSettings(settings)
                            onDismiss()
                        } catch (_: Exception) {
                            // Save failed — leave dialog open so the user
                            // can try again or cancel.
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !loading && !saving,
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
    )
}

private fun settleHelp(seconds: Long): String = when {
    seconds <= 2 -> "fastest"
    seconds <= 5 -> "balanced"
    seconds <= 15 -> "safer"
    else -> "very conservative"
}
