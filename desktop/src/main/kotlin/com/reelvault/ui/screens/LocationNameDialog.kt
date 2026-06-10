// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.reelvault.ui.theme.AccentScheme
import com.reelvault.ui.theme.ReelVaultTheme

/**
 * Name / rename a map location. Rendered as a top-level [DialogWindow] (not an
 * in-window `AlertDialog`) for the same reason as [AppearanceSettingsDialog]:
 * the map hosts JXMapViewer in a heavyweight `SwingPanel`, and Compose Desktop
 * can't paint a lightweight dialog over a heavyweight peer in the same window.
 * A separate window floats above the map natively and stays clickable.
 *
 * Opened from the map view's right-click "Name / Rename location" menu. On save
 * the caller upserts a named location (re-using [initialName]'s row when this is
 * a rename) so the spot resolves to the new name everywhere.
 */
@Composable
fun LocationNameDialog(
    /** Pre-filled name — empty for a brand-new name, the current name for a rename. */
    initialName: String,
    /** Accent scheme to re-apply (a child window doesn't inherit the parent theme). */
    accentScheme: AccentScheme,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val isRename = initialName.isNotBlank()
    var name by remember { mutableStateOf(initialName) }
    val focus = remember { FocusRequester() }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = if (isRename) "Rename Location" else "Name Location",
        resizable = false,
        state = rememberDialogState(size = DpSize(420.dp, 210.dp)),
    ) {
        // A child window doesn't inherit the parent composition's MaterialTheme.
        ReelVaultTheme(accentScheme = accentScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isRename) "Rename this location" else "Name this location",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Videos at this spot (within the place's radius) will show this name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    val commit = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) onSave(trimmed)
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Location name") },
                        singleLine = true,
                        keyboardActions = KeyboardActions(onDone = { commit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }

                    Spacer(Modifier.weight(1f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = commit, enabled = name.trim().isNotEmpty()) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
