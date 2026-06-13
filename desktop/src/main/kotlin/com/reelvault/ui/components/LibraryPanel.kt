// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reelvault.data.models.Collection
import com.reelvault.data.models.LibraryLocation
import com.reelvault.data.models.LibraryRow
import com.reelvault.trackTextEntryFocus
import com.reelvault.ui.theme.ReelVaultSpacing

/**
 * Left-side panel that lists scanned library locations with their video counts
 * and lets the user filter the grid to a single location. An "All Videos" entry
 * at the top clears any filter. A Collections section below lets the user browse
 * and manage named (and smart) collections.
 *
 * Recursive locations that contain videos in subfolders are expandable into a
 * disclosure tree: [rows] is the flattened, display-ordered list of locations
 * and their currently-expanded subdirectories (each carrying its nesting
 * depth). [locations] still supplies the per-location data (rescan / remove /
 * full-path sublabel) for the top-level rows.
 */
@Composable
fun LibraryPanel(
    rows: List<LibraryRow>,
    locations: List<LibraryLocation>,
    selectedPaths: List<String>,
    totalVideosAcrossLibrary: Long,
    /** Called when a location row is clicked. [additive] = Cmd/Ctrl-click
     *  (toggle), [range] = Shift-click (range select). */
    onSelect: (path: String, additive: Boolean, range: Boolean) -> Unit,
    /** Toggle a directory's expanded/collapsed state (clicked disclosure chevron). */
    onToggleExpand: (path: String) -> Unit = {},
    onAddLocation: () -> Unit = {},
    /** Called when the user confirms removal of a library location. */
    onRemoveLocation: ((LibraryLocation) -> Unit)? = null,
    /** Called when the user requests a rescan of a library location. */
    onRescan: ((LibraryLocation) -> Unit)? = null,
    /** Set of paths currently being rescanned; shows spinner instead of rescan button. */
    rescanningPaths: Set<String> = emptySet(),
    onCollapse: () -> Unit = {},
    collections: List<Collection> = emptyList(),
    /** Match counts for smart collections, keyed by id. Smart collections have
     *  no members, so their `videoCount` is always 0 — the count badge uses
     *  this instead (computed by the view model from each collection's filter). */
    smartCollectionCounts: Map<String, Long> = emptyMap(),
    selectedCollectionId: String? = null,
    onSelectCollection: ((String?) -> Unit)? = null,
    onCreateCollection: ((name: String) -> Unit)? = null,
    onCreateSmartCollection: (() -> Unit)? = null,
    onDeleteCollection: ((Collection) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showNewCollectionDialog by remember { mutableStateOf(false) }
    var newCollectionName by remember { mutableStateOf("") }

    // Top-level rows look up their LibraryLocation here for the rescan/remove
    // affordances and the full-path sublabel. Subdirectory rows don't need it.
    val locationByPath = remember(locations) { locations.associateBy { it.path } }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ReelVaultSpacing.XSmall,
                    end = ReelVaultSpacing.XSmall,
                    top = ReelVaultSpacing.Medium,
                    bottom = ReelVaultSpacing.Small
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tooltip(text = "Add a folder to your library. Same as the \"add folder\" button in the top bar.") {
                IconButton(
                    onClick = onAddLocation,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add library location",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "LIBRARY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Tooltip(text = "Hide the library panel. Press Tab to toggle both side panels.") {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "Hide library panel (Tab)",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // "All videos" entry — clears both location and collection filters
            item {
                LocationRow(
                    icon = Icons.Default.VideoLibrary,
                    label = "All Videos",
                    count = totalVideosAcrossLibrary,
                    isSelected = selectedPaths.isEmpty() && selectedCollectionId == null,
                    tooltip = "Show every video in your library, across all scanned folders.",
                    onClick = { _, _ ->
                        onSelect("", false, false)
                        onSelectCollection?.invoke(null)
                    }
                )
            }

            if (rows.isNotEmpty()) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(
                            horizontal = ReelVaultSpacing.Small,
                            vertical = ReelVaultSpacing.Small
                        ),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }

            // The flattened location tree: top-level locations and their
            // expanded subdirectories. Key by depth+path so a directory that is
            // also a registered location (e.g. a nested library root) can appear
            // at two depths without a duplicate-key clash.
            items(rows, key = { "${it.depth} ${it.path}" }) { row ->
                val isSelected = row.path in selectedPaths
                val icon = if (isSelected) Icons.Default.FolderOpen else Icons.Default.Folder
                val onRowClick: (Boolean, Boolean) -> Unit = { additive, range ->
                    onSelect(row.path, additive, range)
                    onSelectCollection?.invoke(null)
                }
                val loc = if (row.isTopLevel) locationByPath[row.path] else null

                if (loc != null) {
                    // Top-level library location: rescan / remove + full-path sublabel.
                    ContextMenuArea(
                        items = {
                            buildList {
                                if (onRescan != null) {
                                    add(ContextMenuItem("Rescan folder…") { onRescan(loc) })
                                }
                                if (onRemoveLocation != null) {
                                    add(ContextMenuItem("Remove from library…") {
                                        onRemoveLocation(loc)
                                    })
                                }
                            }
                        }
                    ) {
                        LocationRow(
                            icon = icon,
                            label = displayName(row.path),
                            sublabel = row.path,
                            count = row.videoCount,
                            isSelected = isSelected,
                            tooltip = "Show only videos from ${row.path} (${row.videoCount} videos), " +
                                "including subfolders. Shift-click for a range, Cmd/Ctrl-click to add or remove. " +
                                "Right-click to remove from library.",
                            depth = row.depth,
                            isExpandable = row.isExpandable,
                            isExpanded = row.isExpanded,
                            onToggleExpand = { onToggleExpand(row.path) },
                            onClick = onRowClick,
                            onRescan = onRescan?.let { cb -> { cb(loc) } },
                            isRescanning = row.path in rescanningPaths
                        )
                    }
                } else {
                    // Subdirectory row: select + expand/collapse only.
                    LocationRow(
                        icon = icon,
                        label = displayName(row.path),
                        sublabel = null,
                        count = row.videoCount,
                        isSelected = isSelected,
                        tooltip = "Show only videos in ${row.path} and its subfolders " +
                            "(${row.videoCount} videos). Shift-click for a range, " +
                            "Cmd/Ctrl-click to add or remove.",
                        depth = row.depth,
                        isExpandable = row.isExpandable,
                        isExpanded = row.isExpanded,
                        onToggleExpand = { onToggleExpand(row.path) },
                        onClick = onRowClick
                    )
                }
            }

            // ---- Collections section ----
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(
                        horizontal = ReelVaultSpacing.Small,
                        vertical = ReelVaultSpacing.Small
                    ),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = ReelVaultSpacing.XSmall,
                            end = ReelVaultSpacing.XSmall,
                            top = ReelVaultSpacing.XSmall,
                            bottom = 2.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onCreateCollection != null) {
                        Tooltip(text = "Create a new empty collection.") {
                            IconButton(
                                onClick = {
                                    newCollectionName = ""
                                    showNewCollectionDialog = true
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "New collection",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        text = "COLLECTIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (onCreateSmartCollection != null) {
                        Tooltip(text = "Save the current active filters as a smart collection.") {
                            IconButton(
                                onClick = onCreateSmartCollection,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Save as smart collection",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (collections.isEmpty()) {
                item {
                    Text(
                        text = "No collections yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(
                            horizontal = ReelVaultSpacing.Medium + 18.dp,
                            vertical = ReelVaultSpacing.XSmall
                        )
                    )
                }
            } else {
                items(collections, key = { it.id }) { col ->
                    ContextMenuArea(
                        items = {
                            buildList {
                                if (onDeleteCollection != null) {
                                    add(ContextMenuItem("Delete collection…") { onDeleteCollection(col) })
                                }
                            }
                        }
                    ) {
                        val effectiveCount = if (col.isSmart)
                            (smartCollectionCounts[col.id] ?: 0L) else col.videoCount
                        LocationRow(
                            icon = if (col.isSmart) Icons.Default.AutoAwesome else Icons.Outlined.FolderSpecial,
                            label = col.name,
                            count = effectiveCount,
                            isSelected = col.id == selectedCollectionId,
                            tooltip = if (col.isSmart)
                                "Smart collection — automatically gathers the $effectiveCount " +
                                    "video${if (effectiveCount == 1L) "" else "s"} matching its filter. Right-click to delete."
                            else
                                "$effectiveCount video${if (effectiveCount == 1L) "" else "s"}. Right-click to delete.",
                            onClick = { _, _ ->
                                onSelect("", false, false)
                                onSelectCollection?.invoke(col.id)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showNewCollectionDialog) {
        AlertDialog(
            onDismissRequest = { showNewCollectionDialog = false },
            title = { Text("New Collection") },
            text = {
                TextField(
                    value = newCollectionName,
                    onValueChange = { newCollectionName = it },
                    placeholder = { Text("Collection name") },
                    singleLine = true,
                    modifier = Modifier.trackTextEntryFocus(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newCollectionName.trim()
                        if (name.isNotEmpty()) {
                            onCreateCollection?.invoke(name)
                        }
                        showNewCollectionDialog = false
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewCollectionDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LocationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sublabel: String? = null,
    count: Long,
    isSelected: Boolean,
    tooltip: String = "",
    /** Nesting depth in the location tree (0 = top level); indents the row. */
    depth: Int = 0,
    /** Show a disclosure chevron at the left of the row. */
    isExpandable: Boolean = false,
    isExpanded: Boolean = false,
    /** Invoked when the disclosure chevron is clicked (not the row body). */
    onToggleExpand: (() -> Unit)? = null,
    onClick: (additive: Boolean, range: Boolean) -> Unit,
    onRescan: (() -> Unit)? = null,
    isRescanning: Boolean = false
) {
    val bg = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Tooltip(text = tooltip) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .hoverable(interactionSource)
            .selectionAwareClickable(onClick)
            .padding(horizontal = ReelVaultSpacing.Medium, vertical = ReelVaultSpacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Indent by depth so nested subdirectories sit under their parent.
        if (depth > 0) {
            Spacer(modifier = Modifier.width((depth * 14).dp))
        }
        // Disclosure-chevron slot — always reserved (18dp) so folder icons stay
        // aligned whether or not a row is expandable. The chevron points right
        // when collapsed and rotates 90° clockwise to point down when expanded.
        val rotation by animateFloatAsState(
            targetValue = if (isExpanded) 90f else 0f,
            label = "disclosureRotation"
        )
        val chevronInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(18.dp)
                .then(
                    if (isExpandable && onToggleExpand != null) {
                        Modifier.clickable(
                            interactionSource = chevronInteraction,
                            indication = null,
                            onClick = onToggleExpand
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isExpandable) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "Collapse subfolders" else "Expand subfolders",
                    modifier = Modifier.size(16.dp).rotate(rotation),
                    tint = contentColor
                )
            }
        }
        Spacer(modifier = Modifier.width(2.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = contentColor
        )
        Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
        // Right-aligned count badge
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Rescan button / spinner (only shown for library-location rows)
        if (isRescanning) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp).padding(start = 4.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (onRescan != null && isHovered) {
            Tooltip(text = "Rescan this folder and reconnect any proxies") {
                IconButton(
                    onClick = { onRescan() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Rescan",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    }
}

/**
 * Click handler that reports the keyboard modifiers held at press time, so a
 * library row can support Shift-click (range) and Cmd/Ctrl-click (toggle) the
 * same way the video grid does. `range` = Shift, `additive` = Cmd (macOS) /
 * Ctrl (Windows/Linux).
 */
private fun Modifier.selectionAwareClickable(
    onClick: (additive: Boolean, range: Boolean) -> Unit
): Modifier = this.pointerInput(onClick) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = true)
        val mods = currentEvent.keyboardModifiers
        val range = mods.isShiftPressed
        val additive = mods.isMetaPressed || mods.isCtrlPressed
        waitForUpOrCancellation() ?: return@awaitEachGesture
        onClick(additive, range)
    }
}

/** Display name = last path segment (e.g. "/Users/me/Videos" → "Videos"). */
private fun displayName(path: String): String {
    val trimmed = path.trimEnd('/')
    return trimmed.substringAfterLast('/', missingDelimiterValue = trimmed).ifEmpty { "/" }
}

/**
 * Thin vertical strip used to represent a collapsed side panel. Clicking
 * anywhere on it expands the panel.
 *
 * @param expandIconLeft If true, the chevron points left (used on the right
 * panel's collapsed strip, where expanding pushes the panel leftward). If
 * false, the chevron points right (left panel's collapsed strip).
 */
@Composable
fun CollapsedPanelStrip(
    expandIconLeft: Boolean,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Tooltip(text = tooltip) {
        Column(
            modifier = modifier
                .width(20.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .clickable(onClick = onClick),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(ReelVaultSpacing.Medium))
            Icon(
                imageVector = if (expandIconLeft) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                contentDescription = tooltip,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
