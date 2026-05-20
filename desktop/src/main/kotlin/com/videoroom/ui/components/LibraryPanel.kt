package com.videoroom.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Text(
            text = "LIBRARY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = VideoRoomSpacing.Medium,
                end = VideoRoomSpacing.Medium,
                top = VideoRoomSpacing.Medium,
                bottom = VideoRoomSpacing.Small
            )
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // "All videos" entry — clears the location filter
            item {
                LocationRow(
                    icon = Icons.Default.VideoLibrary,
                    label = "All Videos",
                    count = totalVideosAcrossLibrary,
                    isSelected = selectedPath.isEmpty(),
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
                LocationRow(
                    icon = if (loc.path == selectedPath) Icons.Default.FolderOpen else Icons.Default.Folder,
                    label = displayName(loc.path),
                    sublabel = loc.path,
                    count = loc.videoCount,
                    isSelected = loc.path == selectedPath,
                    onClick = { onSelect(loc.path) }
                )
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
    onClick: () -> Unit
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
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
    }
}

/** Display name = last path segment (e.g. "/Users/me/Videos" → "Videos"). */
private fun displayName(path: String): String {
    val trimmed = path.trimEnd('/')
    return trimmed.substringAfterLast('/', missingDelimiterValue = trimmed).ifEmpty { "/" }
}
