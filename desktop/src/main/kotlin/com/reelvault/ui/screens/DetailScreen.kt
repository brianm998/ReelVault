// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reelvault.LocalAppWindow
import com.reelvault.util.FileDragSource
import com.reelvault.ViewMode
import com.reelvault.trackTextEntryFocus
import com.reelvault.data.models.FullResolutionStatus
import com.reelvault.data.models.GridStatKey
import com.reelvault.ui.theme.ReelVaultSpacing
import com.reelvault.viewmodel.DetailViewModel

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    gridViewModel: com.reelvault.viewmodel.GridViewModel,
    viewMode: ViewMode = ViewMode.GRID,
    onCollapse: () -> Unit = {},
    /** Opens the LocationPickerDialog for the given video IDs. `initial` is
     *  pre-filled GPS coordinate (lat, lon) when one is already set. */
    onEditLocation: (videoIds: List<String>, initial: Pair<Double, Double>?) -> Unit = { _, _ -> },
    /** Opens the CaptureDateDialog for the given video IDs. `initialTs` is
     *  the existing Unix-ms capture timestamp when one is set, else null. */
    onEditCaptureDate: (videoIds: List<String>, initialTs: Long?) -> Unit = { _, _ -> },
    /** Switches to map mode focused on (lat, lon), highlighting this video and
     *  any others captured at the same spot. */
    onShowOnMap: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val metadata = viewModel.metadata
    val isLoading = viewModel.isLoading.collectAsState()
    val error = viewModel.error.collectAsState()
    val notes = viewModel.notes.collectAsState()
    // Drag-out support: drag the inspected master (or a proxy row) into an
    // external editor / file manager. Stateless between gestures, so safe to
    // remember across recompositions.
    val awtWindow = LocalAppWindow.current
    val fileDragSource = remember { FileDragSource() }
    val groupMembers = viewModel.groupMembers.collectAsState()
    val groupPreferredId = viewModel.groupPreferredId.collectAsState()
    // Primary grid/list selection. The detail view-model keeps the last
    // video's metadata until the next selection loads, so gate the inspector
    // on this: when nothing is selected (or the selected video was just
    // filtered out of the grid) show the placeholder, not stale metadata.
    val selectedVideoId by gridViewModel.selectedVideoId.collectAsState()

    // Recessed side panel — the darker control background, matching the macOS
    // detail panel (controlBackgroundColor) and the left library panel.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Header with collapse chevron (mirrors the LibraryPanel's collapse button)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ReelVaultSpacing.XSmall,
                    end = ReelVaultSpacing.Medium,
                    top = ReelVaultSpacing.Medium,
                    bottom = ReelVaultSpacing.Small
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.reelvault.ui.components.Tooltip(
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

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = ReelVaultSpacing.Small),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Box(modifier = Modifier.fillMaxSize()) {
        if (selectedVideoId == null || (metadata.value == null && !isLoading.value)) {
            // When a smart collection is selected but no card is, explain how
            // it gathers videos rather than just prompting for a selection.
            val selColId by gridViewModel.selectedCollectionId.collectAsState()
            val cols by gridViewModel.collections.collectAsState()
            gridViewModel.tags.collectAsState().value // re-render once tag names load
            val smartCol = cols.firstOrNull { it.id == selColId && it.isSmart }
            if (smartCol != null) {
                SmartCollectionCriteria(smartCol.name, gridViewModel.smartCollectionCriteria(smartCol))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(ReelVaultSpacing.Medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PermMedia,
                        contentDescription = "No video selected",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Medium))
                    Text(
                        text = "Select a video to view details",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (metadata.value != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(ReelVaultSpacing.Medium)
            ) {
                // Error message
                if (error.value != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = ReelVaultSpacing.Small),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = error.value!!,
                            modifier = Modifier.padding(ReelVaultSpacing.Small),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Filename — drag it out to an external editor / file manager,
                // with an arrow to open it in the system's default player.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = metadata.value!!.filename,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .dragOutFile(metadata.value!!.path, awtWindow, fileDragSource)
                    )
                    com.reelvault.ui.components.Tooltip(
                        text = "Open this video in your system's default video player"
                    ) {
                        IconButton(
                            onClick = { gridViewModel.openVideoInExternal(metadata.value!!.path) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "Open in default player",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                // (Drag the filename above — or any video card — into an external
                // editor; the arrow opens it in the default player.)

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Medium))

                // Technical metadata
                MetadataItem("Resolution", metadata.value!!.resolution)
                MetadataItem("Duration", metadata.value!!.durationFormatted)
                MetadataItem("FPS", "%.2f".format(metadata.value!!.fps))
                metadata.value!!.frameCountFormatted?.let { MetadataItem("Frames", it) }
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

                when (metadata.value!!.fullResolution) {
                    FullResolutionStatus.Full ->
                        MetadataItem("Resolution Status", "Full resolution")
                    FullResolutionStatus.NotFull ->
                        MetadataItem("Resolution Status", "Not full resolution")
                    FullResolutionStatus.Unspecified -> {} // no row when unknown
                }

                // EXIF / Camera section
                val hasGps = metadata.value!!.gpsLatitude != 0.0 || metadata.value!!.gpsLongitude != 0.0
                val hasShotEXIF = metadata.value!!.iso > 0 ||
                                  metadata.value!!.aperture > 0.0 ||
                                  metadata.value!!.exposureTimeS > 0.0 ||
                                  metadata.value!!.focalLengthMm > 0.0 ||
                                  metadata.value!!.exposureMode.isNotEmpty() ||
                                  metadata.value!!.exposureProgram.isNotEmpty() ||
                                  metadata.value!!.whiteBalance.isNotEmpty()
                val hasExif = metadata.value!!.cameraModel.isNotEmpty() ||
                              metadata.value!!.lensModel.isNotEmpty() ||
                              hasGps ||
                              metadata.value!!.creationDate > 0 ||
                              hasShotEXIF
                if (hasExif) {
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Medium))
                    Text(
                        text = "EXIF",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (metadata.value!!.cameraModel.isNotEmpty()) {
                        CameraMetadataRow(
                            internalName = metadata.value!!.cameraModel,
                            displayName = metadata.value!!.cameraDisplayName
                        )
                    }
                    if (metadata.value!!.lensModel.isNotEmpty()) {
                        MetadataItem("Lens", metadata.value!!.lensModel)
                    }
                    if (metadata.value!!.focalLengthMm > 0.0) {
                        MetadataItem("Focal Length", "%.0f mm".format(metadata.value!!.focalLengthMm))
                    }
                    if (metadata.value!!.aperture > 0.0) {
                        MetadataItem("Aperture", "f/%.1f".format(metadata.value!!.aperture))
                    }
                    if (metadata.value!!.exposureTimeS > 0.0) {
                        MetadataItem("Exposure", GridStatKey.formatExposureTime(metadata.value!!.exposureTimeS))
                    }
                    if (metadata.value!!.iso > 0) {
                        MetadataItem("ISO", metadata.value!!.iso.toString())
                    }
                    if (metadata.value!!.exposureProgram.isNotEmpty()) {
                        MetadataItem("Exposure Program", metadata.value!!.exposureProgram)
                    }
                    if (metadata.value!!.exposureMode.isNotEmpty()) {
                        MetadataItem("Exposure Mode", metadata.value!!.exposureMode)
                    }
                    if (metadata.value!!.whiteBalance.isNotEmpty()) {
                        MetadataItem("White Balance", metadata.value!!.whiteBalance)
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
                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                com.reelvault.ui.components.Tooltip(
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
                        Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                        Text(
                            if (hasGps) "Change location…"
                            else "Set location…",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // "Remove location" button — only shown when a GPS coordinate
                // is already set. Lets the user undo a mis-tagged location.
                if (hasGps) {
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.XSmall))
                    com.reelvault.ui.components.Tooltip(
                        text = "Clear the GPS coordinate from this video. " +
                            "Applies to every video currently selected."
                    ) {
                        OutlinedButton(
                            onClick = {
                                val selected = gridViewModel.selectedVideoIds.value
                                val targets = if (selected.size > 1 && metadata.value!!.id in selected) {
                                    selected
                                } else {
                                    listOf(metadata.value!!.id)
                                }
                                gridViewModel.clearVideoLocations(targets) {
                                    metadata.value?.id?.let { id -> viewModel.loadMetadata(id) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                            Text(
                                "Remove location",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                // "Show on Map" — only when this video has a GPS coordinate.
                // Switches to map mode focused on the spot, with the right-side
                // list highlighting this video plus any others captured there.
                if (hasGps) {
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.XSmall))
                    com.reelvault.ui.components.Tooltip(
                        text = "Switch to the map, zoomed in on where this video was " +
                            "recorded, with any videos captured there listed alongside."
                    ) {
                        OutlinedButton(
                            onClick = {
                                onShowOnMap(
                                    metadata.value!!.gpsLatitude,
                                    metadata.value!!.gpsLongitude,
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                            Text("Show on Map", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // "Set / Change capture date" button. Same pattern as the
                // location button: works on the multi-selection when the
                // current video is part of it.
                Spacer(modifier = Modifier.height(ReelVaultSpacing.XSmall))
                val hasDate = metadata.value!!.creationDate > 0
                com.reelvault.ui.components.Tooltip(
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
                        Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                        Text(
                            if (hasDate) "Change capture date…"
                            else "Set capture date…",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Large))

                // Notes section
                Text(
                    text = "Notes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                com.reelvault.ui.components.Tooltip(
                    text = "Free-form notes about this video. Saved automatically and " +
                        "searchable from the top-bar search field."
                ) {
                    TextField(
                        value = notes.value,
                        onValueChange = { viewModel.updateNotes(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .trackTextEntryFocus(),
                        placeholder = { Text("Add notes...") },
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Large))

                // Keywords / Tags section
                KeywordsSection(
                    primaryVideoTags = metadata.value!!.tags,
                    allTags = gridViewModel.tags.collectAsState().value,
                    selectedVideoIds = gridViewModel.selectedVideoIds.collectAsState().value
                        .ifEmpty { listOf(metadata.value!!.id) },
                    activeFilterTagId = gridViewModel.filterTagId.collectAsState().value,
                    onApplyKeyword = { name, ids ->
                        gridViewModel.applyKeyword(name, ids) {
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

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Large))

                // Collections section
                CollectionsSection(
                    videoCollectionIds = metadata.value!!.collections,
                    allCollections = gridViewModel.collections.collectAsState().value,
                    selectedVideoIds = gridViewModel.selectedVideoIds.collectAsState().value
                        .ifEmpty { listOf(metadata.value!!.id) },
                    selectedCollectionId = gridViewModel.selectedCollectionId.collectAsState().value,
                    onAddToCollection = { collectionId, ids ->
                        gridViewModel.addToCollection(ids, collectionId)
                    },
                    onRemoveFromCollection = { collectionId, ids ->
                        gridViewModel.removeFromCollection(ids, collectionId)
                    },
                    onFilterByCollection = { gridViewModel.setCollection(it) }
                )

                // Group / Stack section
                if (groupMembers.value.size > 1) {
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Large))
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
                        com.reelvault.ui.components.Tooltip(
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
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
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
                                    .padding(ReelVaultSpacing.Small),
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
                                com.reelvault.ui.components.Tooltip(
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
                                com.reelvault.ui.components.Tooltip(
                                    text = "Open ${member.filename} in your system's default video player."
                                ) {
                                    IconButton(
                                        onClick = {
                                            com.reelvault.util.openWithDefault(member.path)
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

                // Proxies section — shown whenever the catalog has any
                // proxies attached to this video. Listing here mirrors
                // the stack section above so the user can scan
                // alternates without leaving the inspector.
                //
                // In Detail (loupe) mode each row is clickable: it
                // tells DetailViewModel to swap the player to that
                // proxy. In Grid/List mode rows are read-only because
                // the inline player always picks the smallest proxy
                // automatically (per the product spec).
                val proxies = viewModel.proxies.collectAsState()
                val selectedProxyId = viewModel.selectedProxyId.collectAsState()
                val playingProxyId = viewModel.playingProxyId.collectAsState()
                val currentSummary = viewModel.currentSummary.collectAsState()
                val activeProxyCreations = gridViewModel.activeProxyCreations.collectAsState()
                val currentVideoId = currentSummary.value?.id
                val activeProxyCreation = if (currentVideoId != null)
                    activeProxyCreations.value[currentVideoId] else null
                if (proxies.value.isNotEmpty() || activeProxyCreation != null) {
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Large))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (activeProxyCreation != null && proxies.value.isEmpty())
                                "Proxies" else "Proxies (${proxies.value.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // In-progress proxy creation indicator — shown while
                    // ffmpeg is encoding so the user knows it's working.
                    if (activeProxyCreation != null) {
                        Spacer(modifier = Modifier.height(ReelVaultSpacing.XSmall))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = androidx.compose.ui.graphics.Color(0xFF0D3B6E).copy(alpha = 0.12f),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReelVaultSpacing.Small)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = activeProxyCreation.message.ifBlank { "Generating proxy…" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (activeProxyCreation.progressPercent > 0) {
                                        Text(
                                            text = "${activeProxyCreation.progressPercent.toInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                if (activeProxyCreation.progressPercent > 0) {
                                    LinearProgressIndicator(
                                        progress = { (activeProxyCreation.progressPercent / 100.0).toFloat() },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }

                    // Surface the "master too large to play here" notice
                    // right next to the proxy list — the proxies are
                    // precisely the way the user can still play this
                    // clip without launching an external editor.
                    val summary = currentSummary.value
                    if (summary != null && !summary.playableNatively) {
                        Spacer(modifier = Modifier.height(ReelVaultSpacing.XSmall))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = if (viewMode == ViewMode.DETAIL) {
                                    "The original is above the inline-playback ceiling. " +
                                        "Pick a proxy below to play it here."
                                } else {
                                    "The original is above the inline-playback ceiling. " +
                                        "Open Detail view to play a proxy in-app."
                                },
                                modifier = Modifier.padding(ReelVaultSpacing.Small),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    if (proxies.value.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = MaterialTheme.shapes.small,
                            )
                    ) {
                        proxies.value.forEachIndexed { idx, proxy ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            // Highlight the proxy that's actually playing (the
                            // auto-chosen one by default), falling back to the
                            // user's explicit pick when nothing is playing yet.
                            val isSelected = proxy.id == (playingProxyId.value ?: selectedProxyId.value)
                            val rowModifier = if (viewMode == ViewMode.DETAIL) {
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // Toggle: clicking the already-selected
                                        // proxy reverts to the master.
                                        viewModel.setSelectedProxy(
                                            if (isSelected) null else proxy.id,
                                        )
                                    }
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            androidx.compose.ui.graphics.Color.Transparent
                                        }
                                    )
                                    .padding(ReelVaultSpacing.Small)
                            } else {
                                Modifier
                                    .fillMaxWidth()
                                    .padding(ReelVaultSpacing.Small)
                            }
                            Row(
                                // Drag a proxy row out to an external editor /
                                // file manager, dropping that proxy's file.
                                modifier = rowModifier.dragOutFile(proxy.path, awtWindow, fileDragSource),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = proxy.filename,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${if (proxy.height > 0) "${proxy.height}p" else "?"} • ${formatBytes(proxy.sizeBytes)}" +
                                            if (proxy.autoDetected) " • auto-detected" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (viewMode == ViewMode.DETAIL) {
                                    com.reelvault.ui.components.Tooltip(
                                        text = if (isSelected) {
                                            "Currently playing this proxy. Click to revert to the original."
                                        } else {
                                            "Play this proxy in the detail view instead of the original."
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Default.PlayCircle else Icons.Default.PlayCircleOutline,
                                            contentDescription = if (isSelected) "Playing" else "Play this proxy",
                                            modifier = Modifier.size(20.dp),
                                            tint = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outline
                                            },
                                        )
                                    }
                                }
                                // Open this proxy in the system's default player.
                                com.reelvault.ui.components.Tooltip(
                                    text = "Open this proxy in your system's default video player"
                                ) {
                                    IconButton(
                                        onClick = { gridViewModel.openVideoInExternal(proxy.path) },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInNew,
                                            contentDescription = "Open proxy in default player",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                                // Break-link affordance — always visible
                                // so the user can correct false
                                // auto-detections regardless of view
                                // mode. Click handler is a stop-event
                                // wrapper so the surrounding row tap
                                // (which switches loupe playback in
                                // DETAIL mode) doesn't fire.
                                Spacer(modifier = Modifier.width(ReelVaultSpacing.XSmall))
                                com.reelvault.ui.components.Tooltip(
                                    text = "Break this proxy link. The proxy file itself stays in the catalog; only the relationship with this master is removed."
                                ) {
                                    IconButton(
                                        onClick = {
                                            viewModel.breakProxyLink(proxy.id, onChanged = {
                                                gridViewModel.loadVideos()
                                                gridViewModel.loadLibraryLocations()
                                            })
                                        },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LinkOff,
                                            contentDescription = "Break proxy link",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    } // if proxies.value.isNotEmpty()

                    // "Add selected as proxy" affordance — visible when
                    // the multi-selection contains exactly one OTHER
                    // video besides the currently-inspected master.
                    // Using the existing selection keeps the workflow
                    // discoverable without a separate picker dialog:
                    // click master, Cmd-click candidate, click button.
                    val selectedIds = gridViewModel.selectedVideoIds.collectAsState()
                    val masterId = currentSummary.value?.id
                    val candidateId = remember(selectedIds.value, masterId) {
                        if (masterId == null) null
                        else selectedIds.value.firstOrNull { it != masterId }
                            ?.takeIf { selectedIds.value.size == 2 }
                    }
                    if (candidateId != null) {
                        Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                        com.reelvault.ui.components.Tooltip(
                            text = "Manually link the second selected video to this master as a proxy. Use this when auto-detection missed a valid proxy."
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.forceProxyLink(candidateId, onChanged = {
                                        gridViewModel.loadVideos()
                                        gridViewModel.loadLibraryLocations()
                                    })
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddLink,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                                Text(
                                    text = "Add selected video as proxy",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }

                // No proxy yet — offer to create one. Detail mode only (per
                // spec), and never for clips that are themselves proxies. The
                // button opens the existing resolution picker, which asks for
                // the target size before encoding and adds the result to the
                // catalog.
                val createProxySummary = currentSummary.value
                if (viewMode == ViewMode.DETAIL &&
                    proxies.value.isEmpty() &&
                    activeProxyCreation == null &&
                    createProxySummary != null &&
                    !createProxySummary.isProxy
                ) {
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Large))
                    Text(
                        text = "Proxies (0)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                    com.reelvault.ui.components.Tooltip(
                        text = "Generate a lower-resolution proxy for this video and add it to the catalog. You'll choose the size next."
                    ) {
                        OutlinedButton(
                            onClick = { gridViewModel.requestCreateProxy(createProxySummary.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.MovieCreation,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                            Text(
                                text = "Create proxy…",
                                style = MaterialTheme.typography.labelMedium,
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

/** Drag-out gesture: detect motion in Compose then hand [path] off to AWT via
 *  [dragSource] so the file can be dropped into an external editor / file
 *  manager. Mirrors the grid card's gesture; uses the Initial pass and never
 *  consumes the tap, so click handlers on the same element still fire. */
private fun Modifier.dragOutFile(
    path: String,
    awtWindow: java.awt.Window?,
    dragSource: FileDragSource,
): Modifier = this.pointerInput(path) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        dragSource.setPendingFiles(listOf(path))
        var handled = false
        while (!handled) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) {
                dragSource.clearPending()
                handled = true
            } else {
                val delta = change.position - start
                val dist = kotlin.math.sqrt((delta.x * delta.x + delta.y * delta.y).toDouble()).toFloat()
                if (dist >= 8f && awtWindow != null) {
                    val screenX = (awtWindow.x + change.position.x).toInt()
                    val screenY = (awtWindow.y + change.position.y).toInt()
                    dragSource.startDragIfPending(awtWindow, screenX, screenY)
                    handled = true
                }
            }
        }
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
    com.reelvault.ui.components.Tooltip(
        text = "Named place at $latitude, $longitude. " +
            "Click 'Show coordinates' to see the exact values."
    ) {
        Column(modifier = Modifier.padding(vertical = ReelVaultSpacing.Small)) {
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
    com.reelvault.ui.components.Tooltip(text = help) {
        Column(modifier = Modifier.padding(vertical = ReelVaultSpacing.Small)) {
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
    "Frames" -> "Total number of frames. A \"~\" prefix means it's estimated from duration × frame rate (the container didn't store an exact count)."
    "Video Codec" -> "Compression format used to encode the video stream (e.g. h264, hevc, prores)."
    "Audio Codec" -> "Compression format used for the audio track."
    "Bitrate" -> "Average data rate. Higher generally means better quality at a given resolution."
    "Size" -> "File size on disk."
    "Color Space" -> "Color encoding standard (e.g. bt709 for HD, bt2020 for 4K HDR)."
    "HDR" -> "High Dynamic Range content with extended brightness and color range."
    "Resolution Status" -> "\"Full\" when the recorded dimensions match a known native sensor mode for this camera (e.g. a timelapse rendered at full sensor resolution). \"Not full\" when the camera is known but the recorded size doesn't match either a native mode or a common video standard."
    "Camera" -> "Camera model recorded in the file's metadata (when available)."
    "Lens" -> "Lens model recorded in the file's metadata."
    "Captured" -> "Original recording date and time from the file's metadata."
    "GPS" -> "Latitude and longitude where the video was recorded (when present)."
    else -> "$label: $value"
}

/**
 * Camera-model row that defaults to the marketing-friendly name (e.g.
 * "Sony a7R III") and reveals a small ⓘ affordance when the core has a
 * mapping for the internal name. Clicking the icon flips the displayed
 * string to the internal model code (e.g. "SONY ILCE-7RM3") and back.
 *
 * When the marketing name equals the internal name (no mapping known),
 * the row collapses to a plain [MetadataItem] — there's no point
 * offering a toggle that would do nothing.
 */
@Composable
fun CameraMetadataRow(internalName: String, displayName: String) {
    val hasMarketing = displayName.isNotEmpty() && displayName != internalName
    if (!hasMarketing) {
        MetadataItem("Camera", internalName)
        return
    }
    var showInternal by remember { mutableStateOf(false) }
    val helpText = if (showInternal) {
        "Showing the internal model name from the file's metadata. " +
            "Click to switch back to the marketing name."
    } else {
        "Showing the marketing name. Click to reveal the internal " +
            "model code recorded in the file's metadata ($internalName)."
    }
    Column(modifier = Modifier.padding(vertical = ReelVaultSpacing.Small)) {
        Text(
            text = "Camera",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (showInternal) internalName else displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(ReelVaultSpacing.XSmall))
            com.reelvault.ui.components.Tooltip(text = helpText) {
                IconButton(
                    onClick = { showInternal = !showInternal },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Toggle internal/marketing camera name",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
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
    allTags: List<com.reelvault.data.models.Tag>,
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
                com.reelvault.ui.components.Tooltip(
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

        Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

        com.reelvault.ui.components.Tooltip(
            text = "Type a new keyword and press Enter to apply it to all selected videos. " +
                "If the keyword doesn't exist yet, it will be created."
        ) {
            TextField(
                value = newKeyword,
                onValueChange = { newKeyword = it },
                placeholder = { Text("Add a keyword…", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().trackTextEntryFocus(),
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

        Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

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
                        com.reelvault.ui.components.Tooltip(
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
                        com.reelvault.ui.components.Tooltip(text = rowTooltip) {
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
                            Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
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

/**
 * Right-panel Collections block:
 *   * Lists all known collections. Rows belonging to the current video are
 *     highlighted with a check; clicking them removes the video from that
 *     collection. Rows not belonging show with a "+" affordance on hover;
 *     clicking adds the video.
 *   * A ">" chevron on the left sets the grid filter to that collection.
 */
@Composable
fun CollectionsSection(
    videoCollectionIds: List<String>,
    allCollections: List<com.reelvault.data.models.Collection>,
    selectedVideoIds: List<String>,
    selectedCollectionId: String?,
    onAddToCollection: (collectionId: String, videoIds: List<String>) -> Unit,
    onRemoveFromCollection: (collectionId: String, videoIds: List<String>) -> Unit,
    onFilterByCollection: (collectionId: String?) -> Unit
) {
    val videoColSet = remember(videoCollectionIds) { videoCollectionIds.toSet() }
    val manualCollections = remember(allCollections) { allCollections.filter { !it.isSmart } }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Collections",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (selectedCollectionId != null) {
                com.reelvault.ui.components.Tooltip(text = "Stop filtering the grid by the current collection.") {
                    TextButton(onClick = { onFilterByCollection(null) }) {
                        Text("Clear filter", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (manualCollections.isEmpty()) {
            Text(
                text = "No collections yet. Use the + in the left panel.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                manualCollections.forEach { col ->
                    val isInCollection = col.id in videoColSet
                    val isActiveFilter = col.id == selectedCollectionId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.reelvault.ui.components.Tooltip(
                            text = if (isActiveFilter)
                                "Currently filtering the grid by '${col.name}'. Click again to clear."
                            else
                                "Filter the grid to show only videos in '${col.name}'."
                        ) {
                            IconButton(
                                onClick = {
                                    onFilterByCollection(if (isActiveFilter) null else col.id)
                                },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Filter by ${col.name}",
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isActiveFilter)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        com.reelvault.ui.components.Tooltip(
                            text = if (isInCollection)
                                "Remove selected video(s) from '${col.name}'."
                            else
                                "Add selected video(s) to '${col.name}'."
                        ) {
                            TextButton(
                                onClick = {
                                    if (isInCollection) {
                                        onRemoveFromCollection(col.id, selectedVideoIds)
                                    } else {
                                        onAddToCollection(col.id, selectedVideoIds)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 4.dp, vertical = 0.dp
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isInCollection) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = col.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isInCollection)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "(${col.videoCount})",
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
}

/** Shown in the details panel when a smart collection is selected but no card
 *  is — explains the rules that decide what the collection gathers. */
@Composable
private fun SmartCollectionCriteria(name: String, criteria: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ReelVaultSpacing.Medium)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(ReelVaultSpacing.XSmall))
        Text(
            text = "Smart collection — videos are gathered automatically by these rules:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(ReelVaultSpacing.Medium))
        if (criteria.isEmpty()) {
            Text(
                text = "No rules set — this collection matches every video.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            criteria.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(76.dp),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
