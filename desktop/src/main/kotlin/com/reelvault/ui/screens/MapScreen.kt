// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.reelvault.data.models.VideoLocation
import com.reelvault.ui.components.MapPin
import com.reelvault.ui.components.MapView
import com.reelvault.ui.theme.ReelVaultSpacing

/**
 * Top-level map view: every geotagged video in the current library filter,
 * plotted full-area. Clicking a pin (or cluster) reports the videos under it
 * via [onSelectionChange] so the right panel can list them; Shift/Cmd/Ctrl-
 * clicking adds to the current selection rather than replacing it. Clicking
 * empty map clears the selection.
 *
 * The pin set is whatever [locations] the caller passes — already constrained
 * to the active filter (see `GridViewModel.loadVideoLocationsFilteredAsync`) —
 * so the map honors the Library Filter just like the grid and list do.
 */
@Composable
fun MapScreen(
    locations: List<VideoLocation>,
    selectedVideoIds: List<String>,
    onSelectionChange: (List<String>) -> Unit,
    /** When non-null, open centered here (≈ neighbourhood zoom) instead of
     *  framing all pins. Set when the user taps a card's location badge. */
    focusedLocation: Pair<Double, Double>? = null,
    /** Called once the one-shot [focusedLocation] has been applied, so the
     *  caller can clear it (a later re-entry then frames all pins again). */
    onFocusConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pins = remember(locations) {
        locations.map { loc ->
            MapPin(
                id = loc.id,
                latitude = loc.latitude,
                longitude = loc.longitude,
                count = 1,
                label = loc.filename,
                memberIds = listOf(loc.id),
            )
        }
    }

    // Focused on a specific video → centre there at zoom 12 (neighbourhood).
    // Otherwise frame the bounding box of all pins, or the whole world when
    // there's nothing to plot.
    val (initLat, initLon, initZoom) = remember(locations, focusedLocation) {
        when {
            focusedLocation != null -> Triple(focusedLocation.first, focusedLocation.second, 12)
            locations.isEmpty() -> Triple(0.0, 0.0, 2)
            else -> bboxFraming(locations.map { it.latitude to it.longitude })
        }
    }

    // Clear the one-shot focus once it has been applied on this composition,
    // so a later re-entry frames all pins instead of re-locking onto the old
    // coordinate. Safe because MapView only reads initialCenter/Zoom when it
    // first creates its Swing peer — clearing the prop won't move the map.
    LaunchedEffect(focusedLocation) {
        if (focusedLocation != null) onFocusConsumed()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (locations.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ReelVaultSpacing.Small),
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "No geotagged videos match the current filter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Videos with GPS metadata appear here as pins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            MapView(
                pins = pins,
                initialCenter = initLat to initLon,
                initialZoom = initZoom,
                onPinClick = { pin, additive ->
                    val ids = pin.memberIds.ifEmpty { listOf(pin.id) }
                    onSelectionChange(
                        if (additive) (selectedVideoIds + ids).distinct() else ids
                    )
                },
                onMapClick = { _, _ ->
                    // A click clear of every pin clears the selection.
                    if (selectedVideoIds.isNotEmpty()) onSelectionChange(emptyList())
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Usage hint, top-left, kept clear of the satellite toggle and
            // zoom slider which the MapView hosts on the right edge.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(ReelVaultSpacing.Medium),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "Click a pin to list its videos on the right. " +
                        "Shift/⌘-click to add more locations.",
                    modifier = Modifier.padding(
                        horizontal = ReelVaultSpacing.Small,
                        vertical = ReelVaultSpacing.XSmall,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
