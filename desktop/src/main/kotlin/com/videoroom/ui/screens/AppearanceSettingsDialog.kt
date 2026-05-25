// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.videoroom.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videoroom.ui.theme.AccentScheme

/**
 * Simple dialog that lets the user choose between the Purple and Blue
 * accent color schemes. The selection is persisted by the caller via
 * Java `Preferences` so it survives restarts.
 */
@Composable
fun AppearanceSettingsDialog(
    currentScheme: AccentScheme,
    onSchemeChange: (AccentScheme) -> Unit,
    onDismiss: () -> Unit,
) {
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
                    Text("Appearance", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Choose the accent color scheme for the interface",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                    AccentScheme.Purple -> "Classic VideoRoom palette — deep purple accents"
                                    AccentScheme.Blue   -> "Blue accents matching the macOS system style"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
