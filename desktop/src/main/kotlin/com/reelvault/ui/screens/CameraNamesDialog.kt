// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelvault.data.models.CameraNameMapping
import com.reelvault.data.repository.VideoRepository
import com.reelvault.trackTextEntryFocus
import kotlinx.coroutines.launch
import com.reelvault.util.Strings

/**
 * Editor for the catalog's camera marketing-name mappings.
 *
 * Shows the full table — every built-in entry plus any custom
 * overrides the user has added — and provides a small form for
 * adding new entries or overriding built-in ones. Setting a row's
 * marketing name to blank removes the override and reveals the
 * built-in entry again.
 *
 * Overrides are stored per-catalog (in the `custom_camera_names`
 * config key) and apply everywhere the daemon resolves a camera
 * name: the right-panel detail row, the camera filter dropdown,
 * and any grid stat slot configured to show "Camera model".
 */
@Composable
fun CameraNamesDialog(
    repository: VideoRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var mappings by remember { mutableStateOf<List<CameraNameMapping>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var newInternal by remember { mutableStateOf("") }
    var newMarketing by remember { mutableStateOf("") }

    // Pending delete confirmation row (null = no confirmation shown).
    var pendingDelete by remember { mutableStateOf<CameraNameMapping?>(null) }

    suspend fun reload() {
        loading = true
        errorMessage = null
        try {
            mappings = repository.listCameraNameMappings()
        } catch (e: Exception) {
            errorMessage = Strings.format("err_failed_load_mappings", e.message ?: "")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Camera Names", fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "ReelVault maps internal camera codes (the strings " +
                        "recorded in the file's metadata) to marketing-friendly " +
                        "names that the rest of the UI displays. Built-in " +
                        "entries cover the most common bodies; add custom rows " +
                        "below for anything that's missing, or override a " +
                        "built-in entry you'd prefer named differently. Set a " +
                        "row's marketing name to blank to remove a custom " +
                        "override.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable mapping table.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 360.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        )
                ) {
                    if (loading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (mappings.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No mappings yet.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(mappings, key = { it.internalName }) { row ->
                                MappingRow(
                                    mapping = row,
                                    onDelete = { pendingDelete = row }
                                )
                                HorizontalDivider(modifier = Modifier.alpha(0.4f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Add-new form.
                Text(
                    text = "Add or override a mapping",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newInternal,
                        onValueChange = { newInternal = it },
                        label = { Text(Strings["ui_internal_name"]) },
                        placeholder = { Text(Strings["ui_e_g_sony_ilce_7rm3a"]) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).trackTextEntryFocus(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = newMarketing,
                        onValueChange = { newMarketing = it },
                        label = { Text(Strings["ui_marketing_name"]) },
                        placeholder = { Text(Strings["ui_e_g_sony_a7r_iiia"]) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).trackTextEntryFocus(),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tip: copy the internal name from the ⓘ button next " +
                        "to the camera field in the right details panel.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            val canSave = newInternal.trim().isNotEmpty() && newMarketing.trim().isNotEmpty()
            TextButton(
                onClick = {
                    scope.launch {
                        try {
                            repository.setCameraNameMapping(
                                internalName = newInternal.trim(),
                                marketingName = newMarketing.trim(),
                            )
                            newInternal = ""
                            newMarketing = ""
                            reload()
                        } catch (e: Exception) {
                            errorMessage = Strings.format("err_failed_save_mapping", e.message ?: "")
                        }
                    }
                },
                enabled = canSave && !loading
            ) {
                Text(Strings["ui_add_override"])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Strings["ui_done"]) }
        },
        modifier = Modifier.widthIn(min = 640.dp, max = 720.dp)
    )

    // Confirmation dialog for deleting a custom override.
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(Strings["ui_remove_this_custom_mapping"]) },
            text = {
                Text(
                    if (row.isBuiltin) {
                        "\"${row.internalName}\" will fall back to the built-in mapping after removal."
                    } else {
                        "\"${row.internalName}\" will fall back to its raw model name after removal."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = row
                        pendingDelete = null
                        scope.launch {
                            try {
                                repository.setCameraNameMapping(
                                    internalName = target.internalName,
                                    marketingName = "",
                                )
                                reload()
                            } catch (e: Exception) {
                                errorMessage = Strings.format("err_failed_remove_mapping", e.message ?: "")
                            }
                        }
                    }
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(Strings["ui_cancel"]) }
            }
        )
    }
}

@Composable
private fun MappingRow(
    mapping: CameraNameMapping,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Internal name — monospaced so part numbers read cleanly.
        Text(
            text = mapping.internalName,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(220.dp),
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = mapping.marketingName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(8.dp))
        SourceBadge(mapping)
        Spacer(modifier = Modifier.width(4.dp))
        if (mapping.isCustom) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = if (mapping.isBuiltin)
                        "Remove this custom override and fall back to the built-in mapping"
                    else
                        "Remove this custom mapping",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            // Reserve the same trailing slot so the table's columns
            // stay aligned for built-in rows.
            Spacer(modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SourceBadge(mapping: CameraNameMapping) {
    val (label, bg, fg) = when {
        mapping.isCustom && mapping.isBuiltin ->
            Triple("Custom (override)", Color(0xFFF59E0B).copy(alpha = 0.25f), Color(0xFFF59E0B))
        mapping.isCustom ->
            Triple(
                "Custom",
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                MaterialTheme.colorScheme.primary
            )
        else ->
            Triple(
                "Built-in",
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                MaterialTheme.colorScheme.onSurfaceVariant
            )
    }
    Surface(
        color = bg,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

