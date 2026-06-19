// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reelvault.android.R
import com.reelvault.android.ui.theme.swatch
import com.reelvault.android.viewmodel.GridViewModel
import com.reelvault.data.models.AttributeFilterState
import com.reelvault.data.models.Collection
import com.reelvault.data.models.ColorLabel
import com.reelvault.data.models.OrientationFilterState
import com.reelvault.data.models.Tag
import kotlinx.coroutines.launch

/**
 * A horizontally scrollable chip bar for active library filters, plus a
 * "Filters" leading chip that opens a bottom sheet exposing all filter options.
 *
 * Chip layout (left to right):
 *   1. "Filters" assist chip with a FilterList icon — opens the bottom sheet.
 *   2. One [InputChip] per active filter (tag name, collection name, keyword,
 *      minimum rating, colour label, attribute filters). Each chip has an X
 *      to dismiss it.
 *   3. A "Clear all" chip when two or more filters are active.
 *
 * The bottom sheet gives access to:
 *   - Full-text keyword search
 *   - Minimum star rating (1–5)
 *   - Colour label picker (None / Red / Yellow / Green / Blue / Purple)
 *   - Attribute tri-states (has location / keywords / proxies / full-resolution
 *     / audio)
 *   - Orientation (Any / Portrait / Landscape)
 *   - Tag picker (all tags in the catalog)
 *   - Collection picker
 *   - Sort field + direction
 *
 * All state is read from and written to [GridViewModel] directly, so the bar
 * stays in sync with the rest of the screen without extra plumbing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFilterBar(
    viewModel: GridViewModel,
    tags: List<Tag>,
    collections: List<Collection>,
    modifier: Modifier = Modifier,
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterTagId by viewModel.filterTagId.collectAsState()
    val filterMinRating by viewModel.filterMinRating.collectAsState()
    val filterColorLabel by viewModel.filterColorLabel.collectAsState()
    val selectedCollectionId by viewModel.selectedCollectionId.collectAsState()
    val currentSortField by viewModel.currentSortField.collectAsState()
    val currentSortAscending by viewModel.currentSortAscending.collectAsState()

    // Attribute filters
    val filterHasLocation by viewModel.filterHasLocation.collectAsState()
    val filterHasKeywords by viewModel.filterHasKeywords.collectAsState()
    val filterHasProxies by viewModel.filterHasProxies.collectAsState()
    val filterFullResolution by viewModel.filterFullResolution.collectAsState()
    val filterHasAudio by viewModel.filterHasAudio.collectAsState()
    val filterOrientation by viewModel.filterOrientation.collectAsState()

    val activeTag = remember(filterTagId, tags) { tags.firstOrNull { it.id == filterTagId } }
    val activeCollection = remember(selectedCollectionId, collections) {
        collections.firstOrNull { it.id == selectedCollectionId }
    }
    val activeColorLabel = remember(filterColorLabel) {
        if (filterColorLabel.isEmpty()) null else ColorLabel.from(filterColorLabel)
    }

    // Count active filters to offer "Clear all" when multiple are set.
    val activeFilterCount = listOf(
        searchQuery.isNotEmpty(),
        filterTagId.isNotEmpty(),
        filterMinRating > 0,
        filterColorLabel.isNotEmpty(),
        selectedCollectionId != null,
        filterHasLocation != AttributeFilterState.Any,
        filterHasKeywords != AttributeFilterState.Any,
        filterHasProxies != AttributeFilterState.Any,
        filterFullResolution != AttributeFilterState.Any,
        filterHasAudio != AttributeFilterState.Any,
        filterOrientation != OrientationFilterState.Any,
    ).count { it }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── "Filters" leading chip ─────────────────────────────────
                AssistChip(
                    onClick = { showSheet = true },
                    label = { Text(stringResource(R.string.filter_filters)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (activeFilterCount > 0)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = if (activeFilterCount > 0)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )

                // ── Search query chip ──────────────────────────────────────
                if (searchQuery.isNotEmpty()) {
                    ActiveFilterChip(
                        label = "\"$searchQuery\"",
                        onRemove = { viewModel.clearSearch() },
                    )
                }

                // ── Tag chip ───────────────────────────────────────────────
                if (activeTag != null) {
                    ActiveFilterChip(
                        label = activeTag.name,
                        onRemove = { viewModel.setTagFilter("") },
                    )
                }

                // ── Collection chip ────────────────────────────────────────
                if (activeCollection != null) {
                    ActiveFilterChip(
                        label = activeCollection.name,
                        onRemove = { viewModel.setCollectionFilter(null) },
                    )
                }

                // ── Min-rating chip ────────────────────────────────────────
                if (filterMinRating > 0) {
                    ActiveFilterChip(
                        label = "${"★".repeat(filterMinRating)}+",
                        onRemove = { viewModel.setMinRatingFilter(0) },
                    )
                }

                // ── Colour label chip ──────────────────────────────────────
                if (activeColorLabel != null && activeColorLabel != ColorLabel.None) {
                    ActiveFilterChip(
                        label = activeColorLabel.displayName,
                        leadingDot = activeColorLabel.swatch,
                        onRemove = { viewModel.setColorLabelFilter("") },
                    )
                }

                // ── Attribute chips ────────────────────────────────────────
                if (filterHasLocation != AttributeFilterState.Any) {
                    ActiveFilterChip(
                        label = attrChipLabel(filterHasLocation, stringResource(R.string.filter_attr_location)),
                        onRemove = { viewModel.setHasLocationFilter(AttributeFilterState.Any) },
                    )
                }
                if (filterHasKeywords != AttributeFilterState.Any) {
                    ActiveFilterChip(
                        label = attrChipLabel(filterHasKeywords, stringResource(R.string.filter_attr_keywords)),
                        onRemove = { viewModel.setHasKeywordsFilter(AttributeFilterState.Any) },
                    )
                }
                if (filterHasProxies != AttributeFilterState.Any) {
                    ActiveFilterChip(
                        label = attrChipLabel(filterHasProxies, stringResource(R.string.filter_attr_proxies)),
                        onRemove = { viewModel.setHasProxiesFilter(AttributeFilterState.Any) },
                    )
                }
                if (filterFullResolution != AttributeFilterState.Any) {
                    ActiveFilterChip(
                        label = attrChipLabel(filterFullResolution, stringResource(R.string.filter_attr_full_res)),
                        onRemove = { viewModel.setFullResolutionFilter(AttributeFilterState.Any) },
                    )
                }
                if (filterHasAudio != AttributeFilterState.Any) {
                    ActiveFilterChip(
                        label = attrChipLabel(filterHasAudio, stringResource(R.string.filter_attr_audio)),
                        onRemove = { viewModel.setHasAudioFilter(AttributeFilterState.Any) },
                    )
                }
                if (filterOrientation != OrientationFilterState.Any) {
                    ActiveFilterChip(
                        label = orientationLabel(filterOrientation),
                        onRemove = { viewModel.setOrientationFilter(OrientationFilterState.Any) },
                    )
                }

                // ── Sort indicator chip (always shown) ─────────────────────
                SortChip(
                    sortField = currentSortField,
                    ascending = currentSortAscending,
                    onClick = { showSheet = true },
                )

                // ── Clear-all chip ─────────────────────────────────────────
                if (activeFilterCount >= 2) {
                    AssistChip(
                        onClick = { viewModel.clearAllFilters() },
                        label = { Text(stringResource(R.string.filter_clear_all)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    )
                }
            }
        }
    }

    // ── Filter bottom sheet ────────────────────────────────────────────────
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            FilterSheetContent(
                viewModel = viewModel,
                tags = tags,
                collections = collections,
                searchQuery = searchQuery,
                filterTagId = filterTagId,
                filterMinRating = filterMinRating,
                filterColorLabel = filterColorLabel,
                selectedCollectionId = selectedCollectionId,
                currentSortField = currentSortField,
                currentSortAscending = currentSortAscending,
                filterHasLocation = filterHasLocation,
                filterHasKeywords = filterHasKeywords,
                filterHasProxies = filterHasProxies,
                filterFullResolution = filterFullResolution,
                filterHasAudio = filterHasAudio,
                filterOrientation = filterOrientation,
                onDismiss = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
                },
            )
        }
    }
}

// ── Helpers for chip labels ───────────────────────────────────────────────────

@Composable
private fun attrChipLabel(state: AttributeFilterState, attrName: String): String = when (state) {
    AttributeFilterState.Yes -> stringResource(R.string.filter_attr_has, attrName)
    AttributeFilterState.No -> stringResource(R.string.filter_attr_no, attrName)
    AttributeFilterState.Any -> attrName
}

@Composable
private fun orientationLabel(state: OrientationFilterState): String = when (state) {
    OrientationFilterState.Portrait -> stringResource(R.string.filter_orientation_portrait)
    OrientationFilterState.Landscape -> stringResource(R.string.filter_orientation_landscape)
    OrientationFilterState.Any -> stringResource(R.string.filter_orientation_any)
}

// ── Active filter chip (name + X dismiss button) ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveFilterChip(
    label: String,
    onRemove: () -> Unit,
    leadingDot: Color? = null,
) {
    InputChip(
        selected = true,
        onClick = {},
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (leadingDot != null) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(leadingDot),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        },
        trailingIcon = {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(18.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.filter_remove_filter),
                    modifier = Modifier.size(14.dp),
                )
            }
        },
        colors = InputChipDefaults.inputChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTrailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

// ── Sort indicator chip ───────────────────────────────────────────────────────

@Composable
private fun SortChip(
    sortField: String,
    ascending: Boolean,
    onClick: () -> Unit,
) {
    val label = sortFieldDisplayName(sortField, LocalContext.current)
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = {
            Icon(
                imageVector = if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = if (ascending) stringResource(R.string.filter_sort_ascending) else stringResource(R.string.filter_sort_descending),
                modifier = Modifier.size(14.dp),
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

// ── Bottom sheet content ──────────────────────────────────────────────────────

@Composable
private fun FilterSheetContent(
    viewModel: GridViewModel,
    tags: List<Tag>,
    collections: List<Collection>,
    searchQuery: String,
    filterTagId: String,
    filterMinRating: Int,
    filterColorLabel: String,
    selectedCollectionId: String?,
    currentSortField: String,
    currentSortAscending: Boolean,
    filterHasLocation: AttributeFilterState,
    filterHasKeywords: AttributeFilterState,
    filterHasProxies: AttributeFilterState,
    filterFullResolution: AttributeFilterState,
    filterHasAudio: AttributeFilterState,
    filterOrientation: OrientationFilterState,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Sheet drag handle area replaced by a title row with a Done button.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.filter_filters_and_sort),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.filter_done))
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // ── Keyword search ─────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.filter_search),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.searchVideos(it) },
            placeholder = {
                Text(
                    stringResource(R.string.filter_search_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { viewModel.clearSearch() }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.filter_clear_search), modifier = Modifier.size(16.dp))
                    }
                }
            } else null,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )

        Spacer(Modifier.height(16.dp))

        // ── Minimum rating ─────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.filter_minimum_rating),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 0 = clear rating filter
            FilterChip(
                selected = filterMinRating == 0,
                onClick = { viewModel.setMinRatingFilter(0) },
                label = { Text(stringResource(R.string.filter_any)) },
            )
            (1..5).forEach { pos ->
                FilterChip(
                    selected = filterMinRating == pos,
                    onClick = { viewModel.setMinRatingFilter(if (filterMinRating == pos) 0 else pos) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = if (filterMinRating == pos) MaterialTheme.colorScheme.onSecondaryContainer
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(stringResource(R.string.filter_rating_plus, pos))
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Colour label ───────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.filter_color_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorLabel.values().forEach { label ->
                val isSelected = if (label == ColorLabel.None) filterColorLabel.isEmpty()
                                 else filterColorLabel == label.raw
                ColorLabelChip(
                    label = label,
                    isSelected = isSelected,
                    onClick = {
                        viewModel.setColorLabelFilter(if (isSelected) "" else label.raw)
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Attribute tri-state filters ────────────────────────────────────
        Text(
            text = stringResource(R.string.filter_attributes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AttributeTriStateRow(
                label = stringResource(R.string.filter_attr_location),
                state = filterHasLocation,
                onSetState = { viewModel.setHasLocationFilter(it) },
            )
            AttributeTriStateRow(
                label = stringResource(R.string.filter_attr_keywords),
                state = filterHasKeywords,
                onSetState = { viewModel.setHasKeywordsFilter(it) },
            )
            AttributeTriStateRow(
                label = stringResource(R.string.filter_attr_proxies),
                state = filterHasProxies,
                onSetState = { viewModel.setHasProxiesFilter(it) },
            )
            AttributeTriStateRow(
                label = stringResource(R.string.filter_attr_full_res),
                state = filterFullResolution,
                onSetState = { viewModel.setFullResolutionFilter(it) },
            )
            AttributeTriStateRow(
                label = stringResource(R.string.filter_attr_audio),
                state = filterHasAudio,
                onSetState = { viewModel.setHasAudioFilter(it) },
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Orientation ────────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.filter_orientation),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrientationFilterState.values().forEach { state ->
                FilterChip(
                    selected = filterOrientation == state,
                    onClick = {
                        viewModel.setOrientationFilter(if (filterOrientation == state) OrientationFilterState.Any else state)
                    },
                    label = {
                        Text(
                            text = when (state) {
                                OrientationFilterState.Any -> stringResource(R.string.filter_orientation_any)
                                OrientationFilterState.Portrait -> stringResource(R.string.filter_orientation_portrait)
                                OrientationFilterState.Landscape -> stringResource(R.string.filter_orientation_landscape)
                            },
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Tags ───────────────────────────────────────────────────────────
        if (tags.isNotEmpty()) {
            Text(
                text = stringResource(R.string.filter_keywords),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            // Horizontally scrollable chip row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // "All" / clear chip
                FilterChip(
                    selected = filterTagId.isEmpty(),
                    onClick = { viewModel.setTagFilter("") },
                    label = { Text(stringResource(R.string.filter_all)) },
                )
                tags.forEach { tag ->
                    FilterChip(
                        selected = filterTagId == tag.id,
                        onClick = {
                            viewModel.setTagFilter(if (filterTagId == tag.id) "" else tag.id)
                        },
                        label = {
                            Text(
                                text = if (tag.videoCount > 0) stringResource(R.string.filter_tag_with_count, tag.name, tag.videoCount) else tag.name,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── Collections ────────────────────────────────────────────────────
        if (collections.isNotEmpty()) {
            Text(
                text = stringResource(R.string.filter_collections),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = selectedCollectionId == null,
                    onClick = { viewModel.setCollectionFilter(null) },
                    label = { Text(stringResource(R.string.filter_all)) },
                )
                collections.forEach { col ->
                    FilterChip(
                        selected = selectedCollectionId == col.id,
                        onClick = {
                            viewModel.setCollectionFilter(
                                if (selectedCollectionId == col.id) null else col.id
                            )
                        },
                        label = {
                            Text(
                                text = if (col.videoCount > 0) stringResource(R.string.filter_collection_with_count, col.name, col.videoCount) else col.name,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // ── Sort ───────────────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.filter_sort),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        SortPicker(
            currentField = currentSortField,
            ascending = currentSortAscending,
            onSetSort = { field, asc -> viewModel.setSort(field, asc) },
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ── Colour-label chip ─────────────────────────────────────────────────────────

@Composable
private fun ColorLabelChip(
    label: ColorLabel,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val swatch = label.swatch
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (label != ColorLabel.None) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(
                                width = if (isSelected) 0.dp else 0.5.dp,
                                color = Color.Black.copy(alpha = 0.2f),
                                shape = CircleShape,
                            ),
                    )
                }
                Text(
                    text = label.displayName,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    )
}

// ── Attribute tri-state row ───────────────────────────────────────────────────

/**
 * A labelled row with three [FilterChip]s for the tri-state attribute filter:
 * Any / Has / Doesn't have. Tapping a selected chip resets it to Any.
 */
@Composable
private fun AttributeTriStateRow(
    label: String,
    state: AttributeFilterState,
    onSetState: (AttributeFilterState) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = state == AttributeFilterState.Any,
            onClick = { onSetState(AttributeFilterState.Any) },
            label = { Text(stringResource(R.string.filter_any), style = MaterialTheme.typography.labelSmall) },
        )
        FilterChip(
            selected = state == AttributeFilterState.Yes,
            onClick = {
                onSetState(if (state == AttributeFilterState.Yes) AttributeFilterState.Any else AttributeFilterState.Yes)
            },
            label = { Text(stringResource(R.string.filter_attr_yes), style = MaterialTheme.typography.labelSmall) },
        )
        FilterChip(
            selected = state == AttributeFilterState.No,
            onClick = {
                onSetState(if (state == AttributeFilterState.No) AttributeFilterState.Any else AttributeFilterState.No)
            },
            label = { Text(stringResource(R.string.filter_attr_no_label), style = MaterialTheme.typography.labelSmall) },
        )
    }
}

// ── Sort picker ───────────────────────────────────────────────────────────────

@Composable
private fun SortPicker(
    currentField: String,
    ascending: Boolean,
    onSetSort: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val sortOptions = listOf(
        "indexed_at"     to sortFieldDisplayName("indexed_at", context),
        "creation_date"  to sortFieldDisplayName("creation_date", context),
        "filename"       to sortFieldDisplayName("filename", context),
        "size_bytes"     to sortFieldDisplayName("size_bytes", context),
        "duration_ms"    to sortFieldDisplayName("duration_ms", context),
        "resolution"     to sortFieldDisplayName("resolution", context),
        "fps"            to sortFieldDisplayName("fps", context),
        "codec"          to sortFieldDisplayName("codec", context),
        "bitrate"        to sortFieldDisplayName("bitrate", context),
        "camera"         to sortFieldDisplayName("camera", context),
        "lens"           to sortFieldDisplayName("lens", context),
        "iso"            to sortFieldDisplayName("iso", context),
        "aperture"       to sortFieldDisplayName("aperture", context),
        "exposure_time"  to sortFieldDisplayName("exposure_time", context),
        "focal_length"   to sortFieldDisplayName("focal_length", context),
        "keyword"        to sortFieldDisplayName("keyword", context),
        "rating"         to sortFieldDisplayName("rating", context),
    )

    var menuExpanded by remember { mutableStateOf(false) }
    val currentLabel = sortFieldDisplayName(currentField, context)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Sort field dropdown
        Box {
            AssistChip(
                onClick = { menuExpanded = true },
                label = { Text(currentLabel) },
                trailingIcon = {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.filter_change_sort_field),
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                sortOptions.forEach { (field, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                color = if (field == currentField) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            // Clicking the current field flips direction; a new
                            // field gets the default direction for that key.
                            // Text-based fields default ascending (A→Z); numeric
                            // and date-based fields default descending (largest first).
                            val newAscending = if (field == currentField) !ascending
                                              else (field == "filename" || field == "camera" ||
                                                    field == "codec"    || field == "lens"   ||
                                                    field == "keyword")
                            onSetSort(field, newAscending)
                            menuExpanded = false
                        },
                    )
                }
            }
        }

        // Direction toggle
        IconButton(
            onClick = { onSetSort(currentField, !ascending) },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = if (ascending) stringResource(R.string.filter_sort_ascending_reverse) else stringResource(R.string.filter_sort_descending_reverse),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = if (ascending) stringResource(R.string.filter_ascending) else stringResource(R.string.filter_descending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun sortFieldDisplayName(field: String, context: Context): String = when (field) {
    "indexed_at"    -> context.getString(R.string.sort_date_indexed)
    "creation_date" -> context.getString(R.string.sort_date_captured)
    "filename"      -> context.getString(R.string.sort_filename)
    "size_bytes"    -> context.getString(R.string.sort_file_size)
    "duration_ms"   -> context.getString(R.string.sort_duration)
    "resolution"    -> context.getString(R.string.sort_resolution)
    "fps"           -> context.getString(R.string.sort_frame_rate)
    "codec"         -> context.getString(R.string.sort_codec)
    "bitrate"       -> context.getString(R.string.sort_bitrate)
    "camera"        -> context.getString(R.string.sort_camera)
    "lens"          -> context.getString(R.string.sort_lens)
    "iso"           -> context.getString(R.string.sort_iso)
    "aperture"      -> context.getString(R.string.sort_aperture)
    "exposure_time" -> context.getString(R.string.sort_exposure_time)
    "focal_length"  -> context.getString(R.string.sort_focal_length)
    "keyword"       -> context.getString(R.string.sort_keyword)
    "rating"        -> context.getString(R.string.sort_rating)
    else            -> field.replaceFirstChar { it.uppercase() }
}
