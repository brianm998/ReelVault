// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoroom.data.models.LibraryLocation
import com.videoroom.ui.theme.VideoRoomSpacing

/**
 * Left-side panel that lists scanned library locations with their video counts
 * and lets the user filter the grid to a single location. An "All Videos" entry
 * at the top clears any filter.
 */
@Composable
fun LibraryPanel(
    locations: List<LibraryLocation>,
    selectedPath: String,
    totalVideosAcrossLibrary: Long,
    onSelect: (path: String) -> Unit,
    onAddLocation: () -> Unit = {},
    /** Called when the user confirms removal of a library location. */
    onRemoveLocation: ((LibraryLocation) -> Unit)? = null,
    /** Called when the user requests a rescan of a library location. */
    onRescan: ((LibraryLocation) -> Unit)? = null,
    /** Set of paths currently being rescanned; shows spinner instead of rescan button. */
    rescanningPaths: Set<String> = emptySet(),
    onCollapse: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = VideoRoomSpacing.XSmall,
                    end = VideoRoomSpacing.XSmall,
                    top = VideoRoomSpacing.Medium,
                    bottom = VideoRoomSpacing.Small
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
            // "All videos" entry — clears the location filter
            item {
                LocationRow(
                    icon = Icons.Default.VideoLibrary,
                    label = "All Videos",
                    count = totalVideosAcrossLibrary,
                    isSelected = selectedPath.isEmpty(),
                    tooltip = "Show every video in your library, across all scanned folders.",
                    onClick = { onSelect("") }
                )
            }

            if (locations.isNotEmpty()) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(
                            horizontal = VideoRoomSpacing.Small,
                            vertical = VideoRoomSpacing.Small
                        ),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }

            items(locations, key = { it.path }) { loc ->
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
                        icon = if (loc.path == selectedPath) Icons.Default.FolderOpen else Icons.Default.Folder,
                        label = displayName(loc.path),
                        sublabel = loc.path,
                        count = loc.videoCount,
                        isSelected = loc.path == selectedPath,
                        tooltip = "Show only videos from ${loc.path} (${loc.videoCount} videos). " +
                            "Right-click to remove from library.",
                        onClick = { onSelect(loc.path) },
                        onRescan = onRescan?.let { cb -> { cb(loc) } },
                        isRescanning = loc.path in rescanningPaths
                    )
                }
            }
        }
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
    onClick: () -> Unit,
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
            .clickable(onClick = onClick)
            .padding(horizontal = VideoRoomSpacing.Medium, vertical = VideoRoomSpacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = contentColor
        )
        Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
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
        Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
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
            Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))
            Icon(
                imageVector = if (expandIconLeft) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                contentDescription = tooltip,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
