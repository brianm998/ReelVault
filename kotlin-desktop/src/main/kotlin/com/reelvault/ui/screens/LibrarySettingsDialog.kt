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
import com.reelvault.ui.components.FilenameDateInferenceControls
import com.reelvault.util.FilenameDateInference
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
 *
 * The dialog seeds its initial state from
 * [VideoRepository.cachedConfig], which is populated by any earlier
 * `getConfig` call (typically the first time the user opens any
 * settings dialog after launch). After that the dialog opens
 * instantly — no spinner — and only the very first open of any
 * settings dialog pays a network round-trip. A background refresh
 * still runs so a setting changed via CLI or another client lands
 * within a second or two.
 */
@Composable
fun LibrarySettingsDialog(
    repository: VideoRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Seed from the repository's in-memory cache. If we've never fetched
    // (very first dialog open after a cold start), the cache is null and
    // we fall back to the same default the daemon uses — on. The
    // background LaunchedEffect below replaces this with the real
    // server value as soon as it arrives.
    val cached = remember { repository.cachedConfig() }
    var autoTagTimelapses by remember {
        mutableStateOf(cached?.autoTagTimelapses ?: true)
    }
    // Only show the "loading…" affordance when we genuinely don't have
    // any value to render yet. Cached opens skip it entirely.
    var loading by remember { mutableStateOf(cached == null) }
    var saving by remember { mutableStateOf(false) }

    // Default filename → capture-date inference. Stored locally (Java
    // Preferences), not in the catalog config, so it's seeded directly rather
    // than from `cached`. Saved alongside the catalog config on Save.
    var inferEnabled by remember { mutableStateOf(FilenameDateInference.defaultEnabled()) }
    var inferFormat by remember { mutableStateOf(FilenameDateInference.defaultFormat()) }
    var inferPosition by remember { mutableStateOf(FilenameDateInference.defaultPosition()) }

    LaunchedEffect(Unit) {
        // Always refresh in the background so the cache is current —
        // catches the case where the value changed via CLI or another
        // client since the cache was last filled. When the cache was
        // already populated this is essentially free UX-wise: the
        // toggle re-snaps to the freshly-fetched value (almost always
        // identical to the cached one).
        val fresh = repository.getConfig()
        if (fresh != null) {
            autoTagTimelapses = fresh.autoTagTimelapses
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
                // The "loading" affordance only renders on a true cold
                // start (no cached config yet). After the first fetch
                // it never reappears within this app session.
                if (loading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Refreshing…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ── Default capture-date inference from filenames ──────────
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Infer capture date from filename", fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "When a video has no embedded capture date, read one from its " +
                                "filename with this method — applied to new library scans, and " +
                                "offered as the default in the Set Capture Date dialog.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = inferEnabled,
                        onCheckedChange = { inferEnabled = it },
                        enabled = !saving,
                    )
                }
                if (inferEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FilenameDateInferenceControls(
                        format = inferFormat,
                        position = inferPosition,
                        onFormatChange = { inferFormat = it },
                        onPositionChange = { inferPosition = it },
                        enabled = !saving,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        // Local pref — persist the default inference method.
                        FilenameDateInference.saveDefault(inferEnabled, inferFormat, inferPosition)
                        repository.updateConfig(
                            autoTagTimelapses = autoTagTimelapses,
                        )
                        saving = false
                        onDismiss()
                    }
                },
                enabled = !saving,
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
