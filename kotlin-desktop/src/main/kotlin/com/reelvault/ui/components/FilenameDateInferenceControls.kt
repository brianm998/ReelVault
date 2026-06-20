// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reelvault.util.FilenameDateInference

/**
 * The shared "infer a capture date from the filename" picker: a Format dropdown
 * (component ordering) and a Position dropdown (where in the name the date
 * sits). Used by the Add-Library dialog, the Library settings dialog, and the
 * Set-Capture-Date dialog so all three offer the identical control.
 */
@Composable
fun FilenameDateInferenceControls(
    format: String,
    position: String,
    onFormatChange: (String) -> Unit,
    onPositionChange: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InferenceDropdown(
            label = "Format",
            options = FilenameDateInference.FORMATS,
            selected = format,
            enabled = enabled,
            onSelect = onFormatChange,
            modifier = Modifier.weight(1f),
        )
        InferenceDropdown(
            label = "Position",
            options = FilenameDateInference.POSITIONS,
            selected = position,
            enabled = enabled,
            onSelect = onPositionChange,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InferenceDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == selected }?.second ?: selected
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                        leadingIcon = if (value == selected) {
                            { Icon(Icons.Default.Check, contentDescription = "Selected") }
                        } else null,
                    )
                }
            }
        }
    }
}
