// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

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
    gridViewModel: com.videoroom.viewmodel.GridViewModel,
    onCollapse: () -> Unit = {},
    /** Current thumbnail min-width controlling adaptive grid column count. */
    thumbnailWidth: androidx.compose.ui.unit.Dp = 220.dp,
    onThumbnailWidthChange: (androidx.compose.ui.unit.Dp) -> Unit = {},
    /** Opens the LocationPickerDialog for the given video IDs. `initial` is
     *  pre-filled GPS coordinate (lat, lon) when one is already set. */
    onEditLocation: (videoIds: List<String>, initial: Pair<Double, Double>?) -> Unit = { _, _ -> },
    /** Opens the CaptureDateDialog for the given video IDs. `initialTs` is
     *  the existing Unix-ms capture timestamp when one is set, else null. */
    onEditCaptureDate: (videoIds: List<String>, initialTs: Long?) -> Unit = { _, _ -> },
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
            com.videoroom.ui.components.Tooltip(
                text = "Hide the details panel. Press Tab to toggle both side panels."
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
            com.videoroom.ui.components.Tooltip(
                text = "Drag to resize thumbnails. The grid automatically adjusts " +
                    "how many columns fit at this size."
            ) {
                Slider(
                    value = thumbnailWidth.value,
                    onValueChange = { onThumbnailWidthChange(it.dp) },
                    valueRange = 120f..400f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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

                // (Right-click any video card in the grid to open it with the
                // default player or a configured external editor.)

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
                val hasGps = metadata.value!!.gpsLatitude != 0.0 || metadata.value!!.gpsLongitude != 0.0
                val hasExif = metadata.value!!.cameraModel.isNotEmpty() ||
                              metadata.value!!.lensModel.isNotEmpty() ||
                              hasGps ||
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
                    if (hasGps) {
                        // Prefer a user-defined name when one is registered
                        // within 250 m of these coordinates; the raw
                        // lat/long collapses behind a disclosure so the
                        // named view stays uncluttered. Mirrors the
                        // LocationPickerView's "name-first" treatment.
                        val matchedName = gridViewModel.nameForLocation(
                            metadata.value!!.gpsLatitude,
                            metadata.value!!.gpsLongitude,
                        )
                        if (matchedName != null) {
                            NamedGpsRow(
                                name = matchedName.name,
                                latitude = metadata.value!!.gpsLatitude,
                                longitude = metadata.value!!.gpsLongitude,
                            )
                        } else {
                            MetadataItem(
                                "GPS",
                                "${"%.4f".format(metadata.value!!.gpsLatitude)}, ${"%.4f".format(metadata.value!!.gpsLongitude)}"
                            )
                        }
                    }
                }

                // "Set / Edit location" button. Surfaced even when no EXIF
                // exists at all so users can geotag a video that lacks GPS.
                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
                com.videoroom.ui.components.Tooltip(
                    text = if (hasGps) {
                        "Replace the existing GPS coordinate via an interactive map."
                    } else {
                        "Open a map and pin where this video was captured. Applies " +
                            "to every video currently selected, so you can geotag a " +
                            "batch in one go."
                    }
                ) {
                    OutlinedButton(
                        onClick = {
                            val selected = gridViewModel.selectedVideoIds.value
                            val targets = if (selected.size > 1 && metadata.value!!.id in selected) {
                                selected
                            } else {
                                listOf(metadata.value!!.id)
                            }
                            val initial = if (hasGps) {
                                metadata.value!!.gpsLatitude to metadata.value!!.gpsLongitude
                            } else null
                            onEditLocation(targets, initial)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                        Text(
                            if (hasGps) "Change location…"
                            else "Set location…",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // "Set / Change capture date" button. Same pattern as the
                // location button: works on the multi-selection when the
                // current video is part of it.
                Spacer(modifier = Modifier.height(VideoRoomSpacing.XSmall))
                val hasDate = metadata.value!!.creationDate > 0
                com.videoroom.ui.components.Tooltip(
                    text = if (hasDate) {
                        "Replace this video's recorded date and time with a calendar pick."
                    } else {
                        "Pick the day (and optionally time) this video was captured. " +
                            "Applies to every video currently selected, so you can " +
                            "stamp a batch in one go."
                    }
                ) {
                    OutlinedButton(
                        onClick = {
                            val selected = gridViewModel.selectedVideoIds.value
                            val targets = if (selected.size > 1 && metadata.value!!.id in selected) {
                                selected
                            } else {
                                listOf(metadata.value!!.id)
                            }
                            val initialTs = if (hasDate) metadata.value!!.creationDate else null
                            onEditCaptureDate(targets, initialTs)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                        Text(
                            if (hasDate) "Change capture date…"
                            else "Set capture date…",
                            style = MaterialTheme.typography.labelMedium
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
                        com.videoroom.ui.components.Tooltip(
                            text = "Remove this video from the stack. The other members stay grouped."
                        ) {
                            TextButton(onClick = {
                                viewModel.ungroupCurrent { oldGroupId ->
                                    // Refresh the grid's expanded-stack
                                    // caches + representative
                                    // groupId/groupSize so the card
                                    // stops claiming to be a stack
                                    // member. Without this hop the
                                    // right panel updates but the grid
                                    // keeps treating it as expandable.
                                    gridViewModel.refreshAfterStackChange(oldGroupId)
                                }
                            }) {
                                Text("Ungroup this", style = MaterialTheme.typography.labelSmall)
                            }
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
                                com.videoroom.ui.components.Tooltip(
                                    text = if (isPreferred) {
                                        "This is the preferred variant. It's the thumbnail shown " +
                                            "in the grid and the file opened on double-click."
                                    } else {
                                        "Make this the preferred variant of the stack. " +
                                            "The grid thumbnail and double-click action will switch to this file."
                                    }
                                ) {
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
                                }
                                com.videoroom.ui.components.Tooltip(
                                    text = "Open ${member.filename} in your system's default video player."
                                ) {
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
                }

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

                // Notes section
                Text(
                    text = "Notes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                com.videoroom.ui.components.Tooltip(
                    text = "Free-form notes about this video. Saved automatically and " +
                        "searchable from the top-bar search field."
                ) {
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
                }

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

                // Keywords / Tags section ---------------------------------
                KeywordsSection(
                    primaryVideoTags = metadata.value!!.tags,
                    allTags = gridViewModel.tags.collectAsState().value,
                    selectedVideoIds = gridViewModel.selectedVideoIds.collectAsState().value
                        .ifEmpty { listOf(metadata.value!!.id) },
                    activeFilterTagId = gridViewModel.filterTagId.collectAsState().value,
                    onApplyKeyword = { name, ids ->
                        gridViewModel.applyKeyword(name, ids) {
                            // After tagging, reload the primary video's metadata
                            // so its "Currently applied" chips refresh.
                            metadata.value?.id?.let { id -> viewModel.loadMetadata(id) }
                        }
                    },
                    onRemoveKeywordByName = { name, ids ->
                        val tagId = gridViewModel.tags.value.firstOrNull { it.name == name }?.id
                        if (tagId != null) {
                            gridViewModel.removeKeyword(tagId, ids) {
                                metadata.value?.id?.let { id -> viewModel.loadMetadata(id) }
                            }
                        }
                    },
                    onFilterByTag = { gridViewModel.setTagFilter(it) }
                )
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

/**
 * GPS field with a user-defined name resolved from the catalog's
 * named-locations table. Shows the name as the primary identity; the raw
 * lat/long sits inside a collapsible disclosure so it's accessible but not
 * visually noisy. Mirrors the [LocationPickerDialog]'s "name-first"
 * treatment so the two surfaces feel consistent.
 */
@Composable
fun NamedGpsRow(name: String, latitude: Double, longitude: Double) {
    var expanded by remember { mutableStateOf(false) }
    com.videoroom.ui.components.Tooltip(
        text = "Named place at $latitude, $longitude. " +
            "Click 'Show coordinates' to see the exact values."
    ) {
        Column(modifier = Modifier.padding(vertical = VideoRoomSpacing.Small)) {
            Text(
                text = "Location",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text(
                    text = if (expanded) "Hide coordinates" else "Show coordinates",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (expanded) {
                Text(
                    text = "%.6f, %.6f".format(latitude, longitude),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun MetadataItem(label: String, value: String, tooltip: String = "") {
    val help = if (tooltip.isNotEmpty()) tooltip else defaultMetadataTooltip(label, value)
    com.videoroom.ui.components.Tooltip(text = help) {
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
}

/**
 * Default help string for a metadata row, keyed off the label. Keeps the call
 * sites tidy — most fields can just rely on the default phrasing.
 */
private fun defaultMetadataTooltip(label: String, value: String): String = when (label) {
    "Resolution" -> "Image dimensions in pixels. Larger numbers = sharper picture."
    "Duration" -> "Total playback length of this clip."
    "FPS" -> "Frames per second — higher values mean smoother motion."
    "Video Codec" -> "Compression format used to encode the video stream (e.g. h264, hevc, prores)."
    "Audio Codec" -> "Compression format used for the audio track."
    "Bitrate" -> "Average data rate. Higher generally means better quality at a given resolution."
    "Size" -> "File size on disk."
    "Color Space" -> "Color encoding standard (e.g. bt709 for HD, bt2020 for 4K HDR)."
    "HDR" -> "High Dynamic Range content with extended brightness and color range."
    "Camera" -> "Camera model recorded in the file's metadata (when available)."
    "Lens" -> "Lens model recorded in the file's metadata."
    "Captured" -> "Original recording date and time from the file's metadata."
    "GPS" -> "Latitude and longitude where the video was recorded (when present)."
    else -> "$label: $value"
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

/**
 * Right-panel Keywords block:
 *   * Text field at top — type a new keyword and press Enter to apply it to
 *     every selected video (or just the primary video if none multi-selected).
 *   * List of all known keywords with usage counts. Each row has:
 *       - A `>` button on the left → set the grid filter to this tag.
 *       - The keyword text → click to add this tag to the selected videos.
 *         If the tag is already on the primary video it's shown with a check
 *         and clicking it removes it from the selection instead.
 */
@Composable
fun KeywordsSection(
    primaryVideoTags: List<String>,
    allTags: List<com.videoroom.data.models.Tag>,
    selectedVideoIds: List<String>,
    activeFilterTagId: String,
    onApplyKeyword: (name: String, videoIds: List<String>) -> Unit,
    onRemoveKeywordByName: (name: String, videoIds: List<String>) -> Unit,
    onFilterByTag: (tagId: String) -> Unit
) {
    var newKeyword by remember { mutableStateOf("") }
    val primaryTagSet = remember(primaryVideoTags) { primaryVideoTags.toSet() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Keywords",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (activeFilterTagId.isNotEmpty()) {
                com.videoroom.ui.components.Tooltip(
                    text = "Stop filtering the grid by the currently selected keyword."
                ) {
                    TextButton(onClick = { onFilterByTag("") }) {
                        Text("Clear filter", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text(
            text = "Applies to ${selectedVideoIds.size} selected video" +
                if (selectedVideoIds.size == 1) "" else "s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))

        com.videoroom.ui.components.Tooltip(
            text = "Type a new keyword and press Enter to apply it to all selected videos. " +
                "If the keyword doesn't exist yet, it will be created."
        ) {
            TextField(
                value = newKeyword,
                onValueChange = { newKeyword = it },
                placeholder = { Text("Add a keyword…", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        if (newKeyword.isNotBlank()) {
                            onApplyKeyword(newKeyword.trim(), selectedVideoIds)
                            newKeyword = ""
                        }
                    }
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }

        Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))

        if (allTags.isEmpty()) {
            Text(
                text = "No keywords yet. Type one above and press Enter.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                allTags.forEach { tag ->
                    val isOnVideo = tag.name in primaryTagSet
                    val isActiveFilter = tag.id == activeFilterTagId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ">" filter button on the left
                        com.videoroom.ui.components.Tooltip(
                            text = if (isActiveFilter) {
                                "Currently filtering the grid by '${tag.name}'. Click again to clear."
                            } else {
                                "Filter the grid to show only videos tagged '${tag.name}'."
                            }
                        ) {
                            IconButton(
                                onClick = {
                                    // Toggle: click an active filter to clear it.
                                    onFilterByTag(if (isActiveFilter) "" else tag.id)
                                },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Filter by ${tag.name}",
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isActiveFilter) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        // Tag name + count, clickable to toggle on selection
                        val rowTooltip = when {
                            selectedVideoIds.isEmpty() -> {
                                val plural = if (tag.videoCount == 1L) "" else "s"
                                "'${tag.name}' is used on ${tag.videoCount} video$plural. " +
                                    "Select a video to add or remove this keyword."
                            }
                            isOnVideo -> {
                                val plural = if (selectedVideoIds.size == 1) "" else "s"
                                "'${tag.name}' is on the current video. " +
                                    "Click to remove it from the ${selectedVideoIds.size} selected video$plural."
                            }
                            else -> {
                                val plural = if (selectedVideoIds.size == 1) "" else "s"
                                "Click to apply '${tag.name}' to the ${selectedVideoIds.size} selected video$plural."
                            }
                        }
                        com.videoroom.ui.components.Tooltip(text = rowTooltip) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = selectedVideoIds.isNotEmpty()) {
                                        if (isOnVideo) {
                                            onRemoveKeywordByName(tag.name, selectedVideoIds)
                                        } else {
                                            onApplyKeyword(tag.name, selectedVideoIds)
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                            if (isOnVideo) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Applied to current video",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            } else {
                                Spacer(modifier = Modifier.width(18.dp))
                            }
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isActiveFilter) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                            Text(
                                text = "(${tag.videoCount})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        }
                    }
                }
            }
        }
    }
}
