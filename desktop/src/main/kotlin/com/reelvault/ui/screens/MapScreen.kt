// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    /** Accent color for the pins (the user's chosen highlight). */
    pinColor: Color,
    /** Resolve a coordinate to a known place name, or null if the spot isn't a
     *  named place. Drives the pin labels (no label when null). */
    placeNameFor: (latitude: Double, longitude: Double) -> String?,
    /** When non-null, open centered here (≈ neighbourhood zoom) instead of
     *  framing all pins. Set when the user taps a card's location badge; the
     *  caller clears it at the next non-badge navigation into the map. */
    focusedLocation: Pair<Double, Double>? = null,
    /** Fired when the user right-clicks a pin and chooses Name/Rename. The
     *  caller opens the naming dialog and persists via the named-locations RPC. */
    onRenameLocationRequest: ((com.reelvault.ui.components.MapPin) -> Unit)? = null,
    /** True while the filtered location set is being recomputed (the filter
     *  just changed). Surfaces a spinner over the map; the previously-plotted
     *  pins stay put until the new set arrives so the map never blanks out. */
    isLoadingVideoLocations: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Recomputed each recomposition (cheap) rather than remembered, so labels
    // appear as soon as the named-location list loads. The pin label is the
    // place name when known, otherwise blank (the painter then draws no label).
    val pins = locations.map { loc ->
        MapPin(
            id = loc.id,
            latitude = loc.latitude,
            longitude = loc.longitude,
            count = 1,
            label = placeNameFor(loc.latitude, loc.longitude) ?: "",
            memberIds = listOf(loc.id),
        )
    }

    // Focused on a specific video → centre there at zoom 12 (neighbourhood).
    // Otherwise frame the bounding box of all pins, or the whole world when
    // there's nothing to plot.
    val (initLat, initLon, initZoom) = remember(locations, focusedLocation) {
        when {
            // Zoom 4 ≈ a few streets across (JXMapViewer zoom counts UP as it
            // zooms out), so "Show on Map" / a location-badge click lands nearly
            // fully zoomed in on the spot rather than at a regional level.
            focusedLocation != null -> Triple(focusedLocation.first, focusedLocation.second, 4)
            locations.isEmpty() -> Triple(0.0, 0.0, 2)
            else -> bboxFraming(locations.map { it.latitude to it.longitude })
        }
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
                pinColor = pinColor,
                // Frame the actual pins once laid out (and re-frame when the
                // filtered set replaces the broader startup set) — except when
                // focused on a specific coordinate from a card's location badge.
                autoFitPins = focusedLocation == null,
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
                onRenameLocationRequest = onRenameLocationRequest,
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

        // Filter recomputation can take several seconds on a large or
        // network-backed library. Surface a spinner so the user knows the map
        // is catching up — the previously-plotted pins stay on screen meanwhile.
        if (isLoadingVideoLocations) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(ReelVaultSpacing.Medium),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = ReelVaultSpacing.Small,
                        vertical = ReelVaultSpacing.XSmall,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(ReelVaultSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Updating map…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
