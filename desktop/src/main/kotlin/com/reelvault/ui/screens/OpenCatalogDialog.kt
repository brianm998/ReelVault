// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.reelvault.data.server.RecentCatalogs
import com.reelvault.ui.components.Tooltip
import com.reelvault.ui.theme.ReelVaultSpacing
import java.io.File

/**
 * Dialog shown when the client can't find a catalog to open — at startup
 * (after a fresh launch) or after the user picks File → Open.
 *
 * Lists recent catalogs, lets the user browse for an existing `.db` file,
 * or pick a directory in which a new catalog will be created.
 *
 * The dialog never closes itself; the caller must collapse it by setting
 * the binding to `false` when [onPick] returns.
 */
@Composable
fun OpenCatalogDialog(
    onDismiss: () -> Unit,
    onPick: (path: String) -> Unit,
    /** When `true`, the dialog has no Cancel button — only "Quit". Used for
     *  the startup-time required-pick scenario. */
    isStartup: Boolean = false,
    recents: RecentCatalogs = RecentCatalogs.Default,
) {
    val recentList = remember { recents.list() }

    AlertDialog(
        onDismissRequest = { if (!isStartup) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.LibraryBooks,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                Text(if (isStartup) "Open a Catalog" else "Open Catalog")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 480.dp)
            ) {
                Text(
                    text = "A catalog is the SQLite file ReelVault uses to remember " +
                        "your library — locations, tags, notes, thumbnails. Each catalog " +
                        "is independent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(ReelVaultSpacing.Medium))

                Row(horizontalArrangement = Arrangement.spacedBy(ReelVaultSpacing.Small)) {
                    Tooltip(text = "Pick an existing .db file from disk. ReelVault will mount it and load its library.") {
                        OutlinedButton(onClick = {
                            pickCatalogFile(forCreate = false)?.let { onPick(it) }
                        }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open File…")
                        }
                    }
                    Tooltip(text = "Pick a folder and create a fresh catalog.db inside it. Use this for a brand-new library.") {
                        OutlinedButton(onClick = {
                            pickCatalogFile(forCreate = true)?.let { onPick(it) }
                        }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New Catalog…")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Medium))
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (recentList.isEmpty()) {
                    Text(
                        text = "No recent catalogs yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = ReelVaultSpacing.Small)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(recentList) { path ->
                            RecentRow(
                                path = path,
                                onOpen = { onPick(path) },
                                onForget = { recents.remove(path) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isStartup) {
                Tooltip(text = "Quit ReelVault without opening a catalog.") {
                    TextButton(onClick = {
                        // ExitApplication isn't available here; fall back to System.exit.
                        // The app harness uses a windowed `application { }` block so
                        // closing the JVM is fine.
                        System.exit(0)
                    }) {
                        Text("Quit")
                    }
                }
            } else {
                Tooltip(text = "Close this dialog without changing the currently open catalog.") {
                    Button(onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(560.dp)
    )
}

@Composable
private fun RecentRow(path: String, onOpen: () -> Unit, onForget: () -> Unit) {
    val file = remember(path) { File(path) }
    val display = remember(path) { file.nameWithoutExtension.ifEmpty { file.name } }
    val exists = remember(path) { file.exists() }
    val dirty = remember(path) { file.parentFile?.absolutePath ?: "" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = exists, onClick = onOpen)
            .padding(vertical = ReelVaultSpacing.XSmall, horizontal = ReelVaultSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (exists) Icons.Default.LibraryBooks else Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (exists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = display,
                style = MaterialTheme.typography.bodyMedium,
                color = if (exists) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (exists) dirty else "$dirty (missing)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Tooltip(text = "Remove this catalog from the recent list. The file isn't deleted.") {
            IconButton(onClick = onForget, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Forget",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * Native open/save dialog for picking (or creating) a `.db` catalog file.
 * Returns the chosen absolute path, or `null` if the user cancelled.
 */
private fun pickCatalogFile(forCreate: Boolean): String? {
    val mode = if (forCreate) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD
    val dialog = java.awt.FileDialog(null as java.awt.Frame?,
        if (forCreate) "Create New Catalog" else "Open Catalog", mode)
    if (forCreate) {
        dialog.file = "catalog.db"
    } else {
        // AWT's FileDialog filter API is limited; just accept anything.
    }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return java.io.File(dir, file).absolutePath
}
