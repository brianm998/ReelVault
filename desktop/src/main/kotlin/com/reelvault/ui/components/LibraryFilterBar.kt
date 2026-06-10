// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reelvault.data.models.AttributeFilterState
import com.reelvault.data.models.ColorLabel
import com.reelvault.data.models.FacetColumn
import com.reelvault.data.models.LibraryFilterMode
import com.reelvault.data.models.MetadataColumn
import com.reelvault.data.models.MetadataKeyInfo
import com.reelvault.ui.theme.ReelVaultSpacing
import com.reelvault.viewmodel.GridViewModel
import java.awt.Cursor
import java.util.prefs.Preferences

/**
 * The Library Filter bar. Sits at the top of the centre content column (below
 * the top bar, between the two side panels) and offers four modes — Text /
 * Attribute / Metadata / Clear. Under COMBINE semantics the Text/Attribute/
 * Metadata filters all stay applied at once; the selector only chooses which
 * editor is shown. "Clear" is a resting mode that resets the filter and shows
 * nothing below. A video's place is one of the Metadata fields ("Location");
 * there is no separate Location mode.
 */
@Composable
fun LibraryFilterBar(
    viewModel: GridViewModel,
    onSearchFocusChanged: (Boolean) -> Unit = {},
    /** Hide the "Location" presence option from the attribute filter — set in
     *  map mode, where every video is located by definition and the location
     *  filter is forced on. */
    hideLocationOption: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val mode by viewModel.libraryFilterMode.collectAsState()
    // Drag-adjustable height for the metadata editor. Persisted across sessions.
    val metadataHeight = remember { mutableStateOf(LibraryFilterBarPrefs.loadHeight().dp) }

    // Window chrome — the bar blends into the grid below it, matching the macOS
    // filter bar (windowBackgroundColor).
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column {
            // Top row: "Filter:" pinned left, the mode selector centred, the
            // sort controls pinned right.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ReelVaultSpacing.Small, vertical = ReelVaultSpacing.XSmall)
            ) {
                Text(
                    text = "Filter:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                Box(modifier = Modifier.align(Alignment.Center)) {
                    LibraryFilterModeSelector(mode) { viewModel.setLibraryFilterMode(it) }
                }
                // Sort: "sort by" field on the left, direction arrow on the
                // right. Far right of the bar, mirroring the macOS filter bar.
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    LibrarySortControls(viewModel)
                }
            }

            if (mode != LibraryFilterMode.Clear) {
                // Border between the two views (selector ↑ / editor ↓).
                HorizontalDivider()
                when (mode) {
                    LibraryFilterMode.Text -> LibraryTextEditor(viewModel, onSearchFocusChanged)
                    LibraryFilterMode.Attribute -> LibraryAttributeEditor(viewModel, hideLocationOption)
                    LibraryFilterMode.Metadata -> LibraryMetadataEditor(viewModel, metadataHeight.value)
                    LibraryFilterMode.Clear -> {}
                }
            }

            // Bottom border of the bar. In metadata mode it doubles as a
            // drag handle that resizes the editor's height.
            if (mode == LibraryFilterMode.Metadata) {
                MetadataResizeHandle(metadataHeight)
            } else {
                HorizontalDivider()
            }
        }
    }
}

/** Segmented Text | Attribute | Metadata | Clear selector. */
@Composable
private fun LibraryFilterModeSelector(
    current: LibraryFilterMode,
    onSelect: (LibraryFilterMode) -> Unit,
) {
    val entries = listOf(
        LibraryFilterMode.Text to "Text",
        LibraryFilterMode.Attribute to "Attribute",
        LibraryFilterMode.Metadata to "Metadata",
        LibraryFilterMode.Clear to "Clear",
    )
    Surface(
        shape = RoundedCornerShape(6.dp),
        // Recessed segmented control — matches the macOS selector
        // (controlBackgroundColor) sitting on the lighter filter bar.
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row {
            entries.forEachIndexed { idx, (modeValue, label) ->
                val selected = modeValue == current
                if (idx > 0) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
                Box(
                    modifier = Modifier
                        .clickable { onSelect(modeValue) }
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                        )
                        .padding(horizontal = ReelVaultSpacing.Medium, vertical = ReelVaultSpacing.XSmall),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/**
 * Sort controls — a "sort by" field dropdown on the left and an ascending /
 * descending direction arrow on the right. Pinned to the far right of the
 * filter bar's top row, mirroring the macOS filter bar. Reads and writes the
 * sort state directly on the [GridViewModel].
 */
@Composable
private fun LibrarySortControls(viewModel: GridViewModel) {
    val currentSort by viewModel.currentSortField.collectAsState()
    val sortAscending by viewModel.currentSortAscending.collectAsState()
    val sortOptions = listOf(
        "filename" to "Filename",
        "indexed_at" to "Date Added",
        "creation_date" to "Date Captured",
        "duration" to "Duration",
        "size" to "File Size",
        "resolution" to "Resolution",
        "fps" to "Frame Rate",
        "codec" to "Codec",
        "bitrate" to "Bitrate",
        "camera" to "Camera",
        "lens" to "Lens",
        "iso" to "ISO",
        "aperture" to "Aperture",
        "exposure_time" to "Exposure Time",
        "focal_length" to "Focal Length",
        "keyword" to "Keyword",
    )
    val currentSortLabel = sortOptions.firstOrNull { it.first == currentSort }?.second ?: currentSort
    var showSortMenu by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ReelVaultSpacing.XSmall),
    ) {
        // "Sort by" field dropdown (left)
        Box {
            Tooltip(
                text = "Sort the video grid. Click the same field again to reverse direction."
            ) {
                OutlinedButton(
                    onClick = { showSortMenu = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(
                        text = currentSortLabel,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false },
            ) {
                Text(
                    text = "Sort by",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = ReelVaultSpacing.Medium,
                        vertical = ReelVaultSpacing.Small,
                    ),
                )
                sortOptions.forEach { (key, label) ->
                    val isSelected = key == currentSort
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = label,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                                    Icon(
                                        imageVector = if (sortAscending) {
                                            Icons.Default.ArrowUpward
                                        } else {
                                            Icons.Default.ArrowDownward
                                        },
                                        contentDescription = if (sortAscending) "Ascending" else "Descending",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        onClick = {
                            if (isSelected) {
                                viewModel.setSort(key, !sortAscending)
                            } else {
                                viewModel.setSort(key, key == "filename" || key == "camera" || key == "codec")
                            }
                            showSortMenu = false
                        },
                    )
                }
            }
        }

        // Ascending / descending direction toggle (right)
        Tooltip(
            text = if (sortAscending) {
                "Sorted ascending — click to reverse"
            } else {
                "Sorted descending — click to reverse"
            },
        ) {
            IconButton(
                onClick = { viewModel.setSort(currentSort, !sortAscending) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = if (sortAscending) "Ascending" else "Descending",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Text mode: the search box (filename / notes), centred, bound to the flow. */
@Composable
private fun LibraryTextEditor(viewModel: GridViewModel, onSearchFocusChanged: (Boolean) -> Unit) {
    val query by viewModel.searchQuery.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ReelVaultSpacing.Small, vertical = ReelVaultSpacing.XSmall),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = {
                Text(
                    "Search videos...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier
                .width(200.dp)
                .onFocusChanged { onSearchFocusChanged(it.isFocused) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
    }
}

/** Attribute mode: a single row leading with the presence selectors
 *  (location / keywords / proxies / full resolution), then rating + colour.
 *  Each presence selector shows only its current value and opens a menu with
 *  the other choices on click. */

@Composable
private fun LibraryAttributeEditor(viewModel: GridViewModel, hideLocation: Boolean = false) {
    val minRating by viewModel.filterMinRating.collectAsState()
    val colorLabel by viewModel.filterColorLabel.collectAsState()
    val hasLocation by viewModel.filterHasLocation.collectAsState()
    val hasKeywords by viewModel.filterHasKeywords.collectAsState()
    val hasProxies by viewModel.filterHasProxies.collectAsState()
    val fullResolution by viewModel.filterFullResolution.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ReelVaultSpacing.Small, vertical = ReelVaultSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ReelVaultSpacing.Medium, Alignment.CenterHorizontally),
    ) {
        if (!hideLocation) {
            AttributeDropdown("Location", hasLocation, "Filter by whether a video has a known GPS location") {
                viewModel.setHasLocationFilter(it)
            }
        }
        AttributeDropdown("Keywords", hasKeywords, "Filter by whether a video has any keywords") {
            viewModel.setHasKeywordsFilter(it)
        }
        AttributeDropdown("Proxies", hasProxies, "Filter by whether a video has any proxies") {
            viewModel.setHasProxiesFilter(it)
        }
        AttributeDropdown("Full Res", fullResolution, "Filter by whether a video is full resolution") {
            viewModel.setFullResolutionFilter(it)
        }
        Text("Rating", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MinRatingStarPicker(minRating) { viewModel.setMinRatingFilter(it) }
        Text("Color", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ColorSwatchPicker(colorLabel) { viewModel.setColorLabelFilter(it) }
    }
}

/** A labelled presence selector — Any / Yes / No — for one attribute. The
 *  trigger shows only the current value with a caret; clicking opens a menu
 *  listing all three so the user can pick a different one. */
@Composable
private fun AttributeDropdown(
    label: String,
    state: AttributeFilterState,
    tooltip: String,
    onChange: (AttributeFilterState) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ReelVaultSpacing.XSmall),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Tooltip(text = tooltip) {
            Box {
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .clickable { expanded = true }
                            .padding(start = ReelVaultSpacing.Small, top = 2.dp, end = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AttributeFilterState.values().forEach { value ->
                        DropdownMenuItem(
                            text = { Text(value.name) },
                            onClick = {
                                onChange(value)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Five tappable stars. Clicking star N sets "≥ N"; re-clicking N clears to 0. */
@Composable
private fun MinRatingStarPicker(minRating: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { pos ->
            val filled = pos <= minRating
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clickable { onPick(if (minRating == pos) 0 else pos) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "At least $pos star(s)",
                    modifier = Modifier.size(18.dp),
                    tint = if (filled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                )
            }
        }
    }
}

/** Colour squares: filled with the label colour, narrow black border, accent
 *  ring when selected. Re-clicking the active swatch clears the filter. */
@Composable
private fun ColorSwatchPicker(selectedRaw: String, onPick: (String) -> Unit) {
    val selected = ColorLabel.from(selectedRaw)
    Row(horizontalArrangement = Arrangement.spacedBy(ReelVaultSpacing.XSmall)) {
        ColorLabel.values().filter { it != ColorLabel.None }.forEach { label ->
            val isSelected = label == selected
            Tooltip(text = label.displayName) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(2.dp)
                        .background(label.swatch, RoundedCornerShape(2.dp))
                        .clickable { onPick(if (isSelected) "" else label.raw) },
                )
            }
        }
    }
}

/** Stable token for a "Location" facet value / the active geo filter: the
 *  centre coordinates at fixed precision, so a place's token equals the active
 *  filter's token iff they denote the same spot. */
private fun locationFacetToken(latitude: Double, longitude: Double): String =
    "%.6f,%.6f".format(latitude, longitude)

/** Metadata mode: a horizontal, cascading set of metadata columns, centred,
 *  with a drag-adjustable [height]. One of the offered fields is "Location"
 *  ([com.reelvault.data.models.LOCATION_METADATA_KEY]): a client-side virtual
 *  column whose values are the catalog's known places and whose selection
 *  drives the geographic proximity filter rather than a metadata filter. */
@Composable
private fun LibraryMetadataEditor(viewModel: GridViewModel, height: Dp) {
    val columns by viewModel.metadataColumns.collectAsState()
    val facets by viewModel.metadataFacets.collectAsState()
    val serverKeys by viewModel.metadataAvailableKeys.collectAsState()
    val locationGroups by viewModel.filterLocationGroups.collectAsState()
    val activeLocation by viewModel.filterLocation.collectAsState()
    // Keep the place list warm while the metadata editor is open: it's the
    // facet for any "Location" column and gates whether that field is offered.
    LaunchedEffect(Unit) { viewModel.loadFilterLocations() }

    // Splice the client-side "Location" field into the key picker — but only
    // when the catalog actually has geotagged videos, matching how the
    // registry-backed keys appear only when they have data.
    val availableKeys = remember(serverKeys, locationGroups) {
        if (locationGroups.isNotEmpty() &&
            serverKeys.none { it.key == com.reelvault.data.models.LOCATION_METADATA_KEY }
        ) {
            serverKeys + com.reelvault.data.models.MetadataKeyInfo(
                com.reelvault.data.models.LOCATION_METADATA_KEY, "Location", false,
            )
        } else {
            serverKeys
        }
    }
    // The "Location" column's facet (places + counts) and a token → place lookup
    // for click handling; the token also marks the active place for highlight.
    val locationFacet = remember(locationGroups) {
        com.reelvault.data.models.FacetColumn(
            key = com.reelvault.data.models.LOCATION_METADATA_KEY,
            displayName = "Location",
            isNumeric = false,
            values = locationGroups.map { g ->
                com.reelvault.data.models.FacetValue(
                    token = locationFacetToken(g.latitude, g.longitude),
                    display = g.label,
                    count = g.count.toLong(),
                )
            },
        )
    }
    val placeByToken = remember(locationGroups) {
        locationGroups.associateBy { locationFacetToken(it.latitude, it.longitude) }
    }
    val activeLocationToken = activeLocation?.let { locationFacetToken(it.first, it.second) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ReelVaultSpacing.Small, vertical = ReelVaultSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(ReelVaultSpacing.Small, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
    ) {
        AddColumnButton { viewModel.addMetadataColumn(atFront = true) }
        columns.forEachIndexed { i, col ->
            val isLocation = col.key == com.reelvault.data.models.LOCATION_METADATA_KEY
            // A "Location" column mirrors the active geo filter (not a stored
            // value set) and routes clicks to setLocationFilter; every other
            // column uses its server-computed facet and the metadata click path.
            val columnForView = if (isLocation) {
                col.copy(values = activeLocationToken?.let { setOf(it) } ?: emptySet())
            } else {
                col
            }
            val onValueClick: (String, Boolean, Boolean) -> Unit = if (isLocation) {
                { token, _, _ ->
                    when {
                        token.isEmpty() -> viewModel.setLocationFilter(null, null)
                        // Re-clicking the active place is a no-op (use "All" to
                        // clear), mirroring how facet columns treat their value.
                        token == activeLocationToken -> {}
                        else -> placeByToken[token]?.let {
                            viewModel.setLocationFilter(it.latitude, it.longitude, it.radiusKm)
                        }
                    }
                }
            } else {
                { token, shift, toggle -> viewModel.onMetadataValueClicked(i, token, shift, toggle) }
            }
            MetadataColumnView(
                column = columnForView,
                facet = if (isLocation) locationFacet else facets.getOrNull(i),
                availableKeys = availableKeys,
                canRemove = columns.size > 1,
                onPickKey = { key -> viewModel.setMetadataColumnKey(i, key) },
                onValueClick = onValueClick,
                onRemove = { viewModel.removeMetadataColumn(i) },
            )
        }
        AddColumnButton { viewModel.addMetadataColumn(atFront = false) }
    }
}

@Composable
private fun AddColumnButton(onClick: () -> Unit) {
    Tooltip(text = "Add a metadata column") {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add column",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetadataColumnView(
    column: MetadataColumn,
    facet: FacetColumn?,
    availableKeys: List<MetadataKeyInfo>,
    canRemove: Boolean,
    onPickKey: (String) -> Unit,
    onValueClick: (token: String, shift: Boolean, toggle: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.width(168.dp).fillMaxHeight()) {
        // Header: clickable title (key picker) + optional remove.
        Row(verticalAlignment = Alignment.CenterVertically) {
            var menuOpen by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                TextButton(
                    onClick = { menuOpen = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = ReelVaultSpacing.XSmall,
                        vertical = 2.dp,
                    ),
                ) {
                    Text(
                        text = when {
                            column.key.isEmpty() -> "Choose field"
                            else -> facet?.displayName?.takeIf { it.isNotEmpty() } ?: column.key
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "Change field",
                        modifier = Modifier.size(16.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (availableKeys.isEmpty()) {
                        DropdownMenuItem(text = { Text("No metadata available") }, onClick = {}, enabled = false)
                    }
                    availableKeys.forEach { info ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    info.displayName,
                                    color = if (info.key == column.key) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            onClick = {
                                onPickKey(info.key)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            if (canRemove) {
                Tooltip(text = "Remove this column") {
                    IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove column",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        when {
            column.key.isEmpty() -> {
                Text(
                    "Pick a metadata field",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(ReelVaultSpacing.XSmall),
                )
            }
            facet == null -> {
                Box(Modifier.padding(ReelVaultSpacing.Small)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    item {
                        // "All" clears the column; modifiers don't apply to it.
                        FacetValueRow(
                            label = "All",
                            count = null,
                            selected = column.values.isEmpty(),
                        ) { _, _ -> onValueClick("", false, false) }
                    }
                    items(facet.values) { v ->
                        FacetValueRow(
                            label = v.display.ifEmpty { v.token },
                            count = v.count,
                            selected = v.token in column.values,
                        ) { shift, toggle -> onValueClick(v.token, shift, toggle) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FacetValueRow(
    label: String,
    count: Long?,
    selected: Boolean,
    onClick: (shift: Boolean, toggle: Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .multiSelectClickable(onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .padding(horizontal = ReelVaultSpacing.XSmall, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A [clickable]-like modifier that also reports the Shift / toggle (Ctrl on
 *  Windows/Linux, Cmd on macOS) modifier state held at press time, so facet
 *  rows can implement range- and toggle-select. Reads
 *  [AwaitPointerEventScope.currentEvent] — the most reliable way to capture
 *  modifiers on Compose Desktop, independent of which widget holds keyboard
 *  focus. Mirrors VideoCard's shiftAwareClickable (without double-click). */
private fun Modifier.multiSelectClickable(
    onClick: (shiftPressed: Boolean, togglePressed: Boolean) -> Unit,
): Modifier = this.pointerInput(onClick) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = true)
        val mods = currentEvent.keyboardModifiers
        val shift = mods.isShiftPressed
        val toggle = mods.isMetaPressed || mods.isCtrlPressed
        waitForUpOrCancellation() ?: return@awaitEachGesture
        onClick(shift, toggle)
    }
}

/** The bar's bottom border in metadata mode — a vertical-resize drag handle
 *  that grows / shrinks the metadata editor. Mirrors [PanelResizeHandle]'s
 *  idiom (predefined AWT cursor + `draggable`). */
@Composable
private fun MetadataResizeHandle(height: MutableState<Dp>) {
    val density = LocalDensity.current
    val resizeCursor = remember { PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)) }
    val state = rememberDraggableState { deltaPx ->
        val deltaDp = with(density) { deltaPx.toDp() }
        val clamped = (height.value + deltaDp).coerceIn(LibraryFilterBarPrefs.MIN_HEIGHT.dp, LibraryFilterBarPrefs.MAX_HEIGHT.dp)
        height.value = clamped
        LibraryFilterBarPrefs.saveHeight(clamped.value)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .pointerHoverIcon(resizeCursor)
            .draggable(orientation = Orientation.Vertical, state = state),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider()
    }
}

/** Persists the metadata editor's drag-adjusted height across sessions. */
private object LibraryFilterBarPrefs {
    const val DEFAULT_HEIGHT: Float = 240f
    // The editor can shrink to ~half of what it used to bottom out at, so it
    // can be tucked away when only a couple of metadata rows are in use.
    const val MIN_HEIGHT: Float = 60f
    const val MAX_HEIGHT: Float = 600f

    private val prefs = Preferences.userRoot().node("com/reelvault/libraryfilter")

    fun loadHeight(): Float {
        val raw = prefs.getFloat("metadataHeight", -1f)
        return if (raw > 0f) raw.coerceIn(MIN_HEIGHT, MAX_HEIGHT) else DEFAULT_HEIGHT
    }

    fun saveHeight(value: Float) {
        prefs.putFloat("metadataHeight", value)
    }
}
