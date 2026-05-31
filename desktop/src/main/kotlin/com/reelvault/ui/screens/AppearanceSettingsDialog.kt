// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelvault.ui.theme.AccentScheme
import java.util.prefs.Preferences

/**
 * Appearance & Browse settings: accent color scheme and scrub-frame count.
 *
 * The accent scheme is applied immediately (via [onSchemeChange]) and also
 * persisted to Java Preferences by the caller. The scrub-frame count is
 * persisted directly here to the shared `com/reelvault/ui` prefs node;
 * [GridViewModel.loadScrubFrames] reads from the same node so the new
 * value is picked up on the next card hover without a restart.
 */
@Composable
fun AppearanceSettingsDialog(
    currentScheme: AccentScheme,
    onSchemeChange: (AccentScheme) -> Unit,
    onDismiss: () -> Unit,
) {
    val uiPrefs = remember { Preferences.userRoot().node("com/reelvault/ui") }
    // Scrub-frame count: how many timeline samples are fetched per video for
    // the hover scrub preview. Loaded from prefs; changes written back immediately.
    val scrubOptions = remember { listOf(4, 6, 8, 10, 15, 20) }
    var scrubCount by remember {
        mutableStateOf(uiPrefs.getInt("scrubFrameCount", 10).let { saved ->
            // Snap to the nearest valid option in case a future version
            // writes a value outside this set.
            scrubOptions.minByOrNull { kotlin.math.abs(it - saved) } ?: 10
        })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Appearance & Browse", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Color scheme and scrub-preview settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.width(440.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // ── Accent color ──────────────────────────────────────────
                Text("Accent color", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                AccentScheme.entries.forEach { scheme ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = currentScheme == scheme,
                            onClick = { onSchemeChange(scheme) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = when (scheme) {
                                    AccentScheme.Purple -> "Purple"
                                    AccentScheme.Blue   -> "Blue"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = when (scheme) {
                                    AccentScheme.Purple -> "Classic ReelVault palette — deep purple accents"
                                    AccentScheme.Blue   -> "Blue accents matching the macOS system style"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                // ── Scrub preview ─────────────────────────────────────────
                Text("Scrub frames per video", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    scrubOptions.forEachIndexed { index, count ->
                        SegmentedButton(
                            selected = scrubCount == count,
                            onClick = {
                                scrubCount = count
                                uiPrefs.putInt("scrubFrameCount", count)
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = scrubOptions.size,
                            ),
                        ) {
                            Text("$count")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "How many still frames ReelVault samples from each video for the " +
                    "hover scrub preview. More frames = smoother scrubbing but more " +
                    "memory and network traffic to the daemon. Changes take effect " +
                    "the next time you hover a card that hasn't been sampled yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                // ── Thumbnail grid size ───────────────────────────────────
                Text("Thumbnail size", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Drag the size slider in the bottom bar to adjust how large card " +
                    "thumbnails appear in Grid and List modes. The slider range is " +
                    "120 – 400 dp; the default is 220 dp. Your last-used size is " +
                    "remembered across sessions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                // ── Proxy & playback gate ────────────────────────────────
                Text("Proxy & playback settings", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "The maximum inline-playback resolution and the default proxy " +
                    "height are configured in the Playback & Proxies sheet " +
                    "(toolbar → Playback & Proxies…). ReelVault uses these to decide " +
                    "which videos show a \"Too large to play here\" badge and what " +
                    "resolution newly-created proxies target.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
