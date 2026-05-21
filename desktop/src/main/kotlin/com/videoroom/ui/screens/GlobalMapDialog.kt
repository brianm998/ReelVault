// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.videoroom.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.videoroom.data.models.VideoLocation
import com.videoroom.ui.components.MapPin
import com.videoroom.ui.components.MapView
import com.videoroom.ui.components.Tooltip
import com.videoroom.ui.theme.VideoRoomSpacing

/**
 * Full-screen world map with every geotagged video plotted. Pins cluster
 * with counts when zoomed out; clicking a single-video pin (or a small
 * enough cluster) hands the location back to the caller so the grid can
 * filter to "videos taken here".
 */
@Composable
fun GlobalMapDialog(
    locations: List<VideoLocation>,
    onDismiss: () -> Unit,
    /** Fired when the user picks a pin. Caller wires this to the grid's
     *  proximity filter. The radius is derived from the cluster size — a
     *  single pin gets a tight 0.5km box, larger clusters get wider boxes. */
    onLocationPick: (latitude: Double, longitude: Double, radiusKm: Double) -> Unit,
) {
    val pins = remember(locations) {
        locations.map { loc ->
            MapPin(
                id = loc.id,
                latitude = loc.latitude,
                longitude = loc.longitude,
                count = 1,
                label = loc.filename,
            )
        }
    }

    // Frame the bounding box of every loaded location so the map opens at
    // a zoom that actually shows all the pins. The earlier centroid +
    // fixed-zoom approach was way too tight for sparsely-spread libraries
    // — clicking the map button on a globally-shot collection would land
    // the camera at street level near one of the cities.
    val (initLat, initLon, initZoom) = remember(locations) {
        if (locations.isEmpty()) {
            Triple(0.0, 0.0, 2)
        } else {
            bboxFraming(locations.map { it.latitude to it.longitude })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                Text("Map of ${locations.size} geotagged video" + if (locations.size == 1) "" else "s")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Pan + scroll to zoom. Numbered pins are clusters — click one to filter the grid to videos near that location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .height(540.dp)) {
                    MapView(
                        pins = pins,
                        initialCenter = initLat to initLon,
                        initialZoom = initZoom,
                        onPinClick = { pin ->
                            // Cluster pins get a wider proximity box so the
                            // grid shows all the videos that were under the
                            // cluster icon; single pins stay tight.
                            val radius = when {
                                pin.count >= 50 -> 50.0
                                pin.count >= 10 -> 10.0
                                pin.count > 1 -> 2.0
                                else -> 0.5
                            }
                            onLocationPick(pin.latitude, pin.longitude, radius)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        },
        confirmButton = {
            Tooltip(text = "Close the global map. Any filter set by clicking a pin stays applied to the grid.") {
                Button(onClick = onDismiss) { Text("Done") }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(900.dp)
    )
}
