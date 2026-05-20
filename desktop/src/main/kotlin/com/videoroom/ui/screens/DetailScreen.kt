package com.videoroom.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoroom.ui.theme.VideoRoomSpacing
import com.videoroom.viewmodel.DetailViewModel

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onCollapse: () -> Unit = {},
    /** Current thumbnail min-width controlling adaptive grid column count. */
    thumbnailWidth: androidx.compose.ui.unit.Dp = 220.dp,
    onThumbnailWidthChange: (androidx.compose.ui.unit.Dp) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val metadata = viewModel.metadata
    val isLoading = viewModel.isLoading.collectAsState()
    val error = viewModel.error.collectAsState()
    val notes = viewModel.notes.collectAsState()
    val groupMembers = viewModel.groupMembers.collectAsState()
    val groupPreferredId = viewModel.groupPreferredId.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        // Header with collapse chevron (mirrors the LibraryPanel's collapse button)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = VideoRoomSpacing.XSmall,
                    end = VideoRoomSpacing.Medium,
                    top = VideoRoomSpacing.Medium,
                    bottom = VideoRoomSpacing.Small
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onCollapse,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Hide details panel (Tab)",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "DETAILS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }

        // Thumbnail-size slider — controls the adaptive grid's minimum card
        // width. Wider min → fewer, larger cards. Narrower min → more, smaller.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = VideoRoomSpacing.Medium,
                    vertical = VideoRoomSpacing.XSmall
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Thumbnail size",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${thumbnailWidth.value.toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = thumbnailWidth.value,
                onValueChange = { onThumbnailWidthChange(it.dp) },
                valueRange = 120f..400f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = VideoRoomSpacing.Small),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Box(modifier = Modifier.fillMaxSize()) {
        if (metadata.value == null && !isLoading.value) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(VideoRoomSpacing.Medium),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PermMedia,
                    contentDescription = "No video selected",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))
                Text(
                    text = "Select a video to view details",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (metadata.value != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(VideoRoomSpacing.Medium)
            ) {
                // Error message
                if (error.value != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = VideoRoomSpacing.Small),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = error.value!!,
                            modifier = Modifier.padding(VideoRoomSpacing.Small),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Filename
                Text(
                    text = metadata.value!!.filename,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))

                // Open in external app button
                Button(
                    onClick = {
                        val path = metadata.value!!.path
                        try {
                            val file = java.io.File(path)
                            if (file.exists()) {
                                java.awt.Desktop.getDesktop().open(file)
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = "Open",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                    Text("Open in External App")
                }

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))

                // Technical metadata
                MetadataItem("Resolution", metadata.value!!.resolution)
                MetadataItem("Duration", metadata.value!!.durationFormatted)
                MetadataItem("FPS", "%.2f".format(metadata.value!!.fps))
                MetadataItem("Video Codec", metadata.value!!.codecVideo.ifEmpty { "—" })
                if (metadata.value!!.codecAudio.isNotEmpty()) {
                    MetadataItem("Audio Codec", metadata.value!!.codecAudio)
                }
                MetadataItem("Bitrate", metadata.value!!.bitrateFormatted)
                MetadataItem("Size", metadata.value!!.sizeFormatted)

                if (metadata.value!!.colorSpace.isNotEmpty()) {
                    MetadataItem("Color Space", metadata.value!!.colorSpace)
                }

                if (metadata.value!!.hdr) {
                    MetadataItem("HDR", "Yes")
                }

                // EXIF / Camera section
                val hasExif = metadata.value!!.cameraModel.isNotEmpty() ||
                              metadata.value!!.lensModel.isNotEmpty() ||
                              metadata.value!!.gpsLatitude != 0.0 ||
                              metadata.value!!.creationDate > 0
                if (hasExif) {
                    Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))
                    Text(
                        text = "EXIF",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (metadata.value!!.cameraModel.isNotEmpty()) {
                        MetadataItem("Camera", metadata.value!!.cameraModel)
                    }
                    if (metadata.value!!.lensModel.isNotEmpty()) {
                        MetadataItem("Lens", metadata.value!!.lensModel)
                    }
                    if (metadata.value!!.creationDate > 0) {
                        MetadataItem("Captured", metadata.value!!.creationDateFormatted)
                    }
                    if (metadata.value!!.gpsLatitude != 0.0 || metadata.value!!.gpsLongitude != 0.0) {
                        MetadataItem(
                            "GPS",
                            "${"%.4f".format(metadata.value!!.gpsLatitude)}, ${"%.4f".format(metadata.value!!.gpsLongitude)}"
                        )
                    }
                }

                // Group / Stack section
                if (groupMembers.value.size > 1) {
                    Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Stack (${groupMembers.value.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { viewModel.ungroupCurrent() }) {
                            Text("Ungroup this", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        text = "Double-click opens the preferred variant. Click ⭐ to change preferred.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = MaterialTheme.shapes.small
                            )
                    ) {
                        groupMembers.value.forEachIndexed { idx, member ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(VideoRoomSpacing.Small),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = member.filename,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${if (member.height > 0) "${member.height}p" else "?"} • ${member.codecVideo.ifEmpty { "?" }} • ${formatBytes(member.sizeBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val isPreferred = member.id == groupPreferredId.value
                                IconButton(
                                    onClick = { viewModel.setGroupPreferred(member.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPreferred) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = if (isPreferred) "Preferred" else "Make preferred",
                                        modifier = Modifier.size(18.dp),
                                        tint = if (isPreferred) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        }
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        try {
                                            val file = java.io.File(member.path)
                                            if (file.exists()) java.awt.Desktop.getDesktop().open(file)
                                        } catch (_: Exception) {}
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Open",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

                // Notes section
                Text(
                    text = "Notes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextField(
                    value = notes.value,
                    onValueChange = { viewModel.updateNotes(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    placeholder = { Text("Add notes...") },
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

                // Tags section
                if (metadata.value!!.tags.isNotEmpty()) {
                    Text(
                        text = "Tags",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(VideoRoomSpacing.Small)
                    ) {
                        metadata.value!!.tags.forEach { tag ->
                            AssistChip(
                                onClick = { },
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = "Remove tag")
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // Loading
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        }  // close inner Box (the original content container)
    }  // close outer Column
}

// Tiny helper for displaying file sizes in the stack row
fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}

@Composable
fun MetadataItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = VideoRoomSpacing.Small)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit
) {
    // Simplified flow row - just use horizontal layout with wrapping
    Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}
