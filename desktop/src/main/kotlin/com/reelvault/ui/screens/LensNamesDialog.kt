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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelvault.data.models.LensNameMapping
import com.reelvault.data.repository.VideoRepository
import com.reelvault.trackTextEntryFocus
import kotlinx.coroutines.launch

/**
 * Editor for the catalog's lens display-name aliases.
 *
 * Unlike cameras there's no built-in table: the rows are the distinct
 * lens strings actually present in the catalog (the values ReelVault
 * extracts from `exifEX:LensModel`), each shown with the name the UI
 * displays for it. Renaming a row stores a custom alias; clearing the
 * alias reverts to the raw string. Use this to tidy up verbose
 * third-party names (e.g. "14mm F1.8 DG HSM | Art 018" → "Sigma 14mm
 * F1.8 Art") or to fold a leftover variant onto a canonical name.
 *
 * Aliases are stored per-catalog (in the `custom_lens_names` config
 * key) and apply wherever the daemon resolves a lens name: the
 * right-panel detail row, the lens filter column, and any grid stat
 * slot configured to show "Lens".
 */
@Composable
fun LensNamesDialog(
    repository: VideoRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var mappings by remember { mutableStateOf<List<LensNameMapping>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var newRaw by remember { mutableStateOf("") }
    var newAlias by remember { mutableStateOf("") }

    // Pending delete confirmation row (null = no confirmation shown).
    var pendingDelete by remember { mutableStateOf<LensNameMapping?>(null) }

    suspend fun reload() {
        loading = true
        errorMessage = null
        try {
            mappings = repository.listLensNameMappings()
        } catch (e: Exception) {
            errorMessage = "Failed to load lens names: ${e.message ?: "unknown error"}"
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
                    imageVector = Icons.Default.Lens,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Lens Names", fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "These are the lens names recorded in your catalog. " +
                        "ReelVault reads the most specific name each file " +
                        "provides, but you can rename any of them here — handy " +
                        "for shortening verbose third-party names or folding a " +
                        "stray variant onto a canonical name. Clear an alias to " +
                        "go back to the raw recorded name.",
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
                                "No lenses in the catalog yet.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(mappings, key = { it.rawName }) { row ->
                                MappingRow(
                                    mapping = row,
                                    onRename = { newRaw = row.rawName; newAlias = row.alias },
                                    onDelete = { pendingDelete = row }
                                )
                                HorizontalDivider(modifier = Modifier.alpha(0.4f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Add-new / rename form.
                Text(
                    text = "Rename a lens",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newRaw,
                        onValueChange = { newRaw = it },
                        label = { Text("Recorded name") },
                        placeholder = { Text("e.g. 14mm F1.8 DG HSM | Art 018") },
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
                        value = newAlias,
                        onValueChange = { newAlias = it },
                        label = { Text("Display name") },
                        placeholder = { Text("e.g. Sigma 14mm F1.8 Art") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).trackTextEntryFocus(),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tip: click a row above to load its recorded name here, " +
                        "then edit the display name.",
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
            val canSave = newRaw.trim().isNotEmpty() && newAlias.trim().isNotEmpty()
            TextButton(
                onClick = {
                    scope.launch {
                        try {
                            repository.setLensNameMapping(
                                rawName = newRaw.trim(),
                                alias = newAlias.trim(),
                            )
                            newRaw = ""
                            newAlias = ""
                            reload()
                        } catch (e: Exception) {
                            errorMessage = "Failed to save alias: ${e.message ?: "unknown error"}"
                        }
                    }
                },
                enabled = canSave && !loading
            ) {
                Text("Save alias")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        modifier = Modifier.widthIn(min = 640.dp, max = 720.dp)
    )

    // Confirmation dialog for clearing a custom alias.
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Clear this lens alias?") },
            text = {
                Text("\"${row.rawName}\" will go back to showing its recorded name after removal.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = row
                        pendingDelete = null
                        scope.launch {
                            try {
                                repository.setLensNameMapping(
                                    rawName = target.rawName,
                                    alias = "",
                                )
                                reload()
                            } catch (e: Exception) {
                                errorMessage = "Failed to remove alias: ${e.message ?: "unknown error"}"
                            }
                        }
                    }
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MappingRow(
    mapping: LensNameMapping,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Recorded name — monospaced so part numbers read cleanly.
        Text(
            text = mapping.rawName,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(240.dp),
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (mapping.isCustom) mapping.alias else "—",
            style = MaterialTheme.typography.bodySmall,
            color = if (mapping.isCustom)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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
                    contentDescription = "Remove this custom alias",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            // Rename affordance for an un-aliased catalog lens. Keeps the
            // trailing slot filled so columns stay aligned.
            IconButton(
                onClick = onRename,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Rename this lens",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SourceBadge(mapping: LensNameMapping) {
    val (label, bg, fg) = when {
        mapping.isCustom && !mapping.inCatalog ->
            Triple("Custom (unused)", Color(0xFFF59E0B).copy(alpha = 0.25f), Color(0xFFF59E0B))
        mapping.isCustom ->
            Triple(
                "Custom",
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                MaterialTheme.colorScheme.primary
            )
        else ->
            Triple(
                "From catalog",
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
