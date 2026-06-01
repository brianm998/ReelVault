// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.launch

/**
 * Library-wide behaviors that aren't about appearance, playback, or
 * the file watcher. Currently a single switch — auto-tag timelapses —
 * but the dialog is the home for any future library-scoped knobs
 * (auto-tag faces, auto-tag locations, etc.).
 *
 * Backs onto the daemon's `auto_tag_timelapses` config key. When on,
 * the post-index pipeline applies a "timelapse" tag to videos whose
 * recorded resolution exceeds their camera's max in-camera video
 * resolution. The catalog remembers which videos were auto-tagged —
 * removing the tag manually is permanent, even on subsequent scans.
 */
@Composable
fun LibrarySettingsDialog(
    repository: VideoRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var autoTagTimelapses by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        repository.getConfig()?.let { cfg ->
            autoTagTimelapses = cfg.autoTagTimelapses
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Library", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Catalog-wide auto-tagging and detection settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Loading current settings…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Column(modifier = Modifier.width(460.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Auto-tag timelapses",
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "When a video's recorded resolution exceeds the camera's " +
                                    "max in-camera video resolution, ReelVault tags it as " +
                                    "\"timelapse\" — by definition such files can only be " +
                                    "assembled from stills. Removing the tag manually is " +
                                    "permanent: the auto-tagger won't re-apply it on " +
                                    "future scans, even if the same heuristic fires again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = autoTagTimelapses,
                            onCheckedChange = { autoTagTimelapses = it },
                            enabled = !saving,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        repository.updateConfig(
                            autoTagTimelapses = autoTagTimelapses,
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
