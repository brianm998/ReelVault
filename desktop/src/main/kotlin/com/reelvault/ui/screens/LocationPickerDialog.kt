// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.reelvault.data.models.NamedLocation
import com.reelvault.data.models.VideoLocation
import com.reelvault.ui.components.MapPin
import com.reelvault.ui.components.MapPinStyle
import com.reelvault.ui.components.MapView
import com.reelvault.ui.components.Tooltip
import com.reelvault.ui.theme.ReelVaultSpacing
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modal dialog that lets the user pin a location on a world map and apply
 * it to one or more videos. Used both for setting a location on a video
 * that has none, and for correcting one that's wrong.
 *
 * Two re-use mechanisms surface every coordinate the catalog already
 * knows about:
 *   * Secondary video pins — other videos' GPS, faint gray dots.
 *   * Named-place pins — user-defined places (e.g. "Home") shown as teal
 *     markers with the name painted next to them.
 *
 * Tapping either kind of pin promotes its coordinate to the candidate;
 * a text field below the map lets the user attach (or rename) a name on
 * the candidate, which is persisted via the named-locations RPC.
 */
@Composable
fun LocationPickerDialog(
    targetVideoIds: List<String>,
    /** Initial pin (e.g. existing GPS), if any. Used to center the map. */
    initialLocation: Pair<Double, Double>? = null,
    /** All other catalog videos' known GPS — surfaced as faint gray pins
     *  the user can click to re-use. Pass empty if the catalog has none. */
    existingLocations: List<VideoLocation> = emptyList(),
    /** Catalog's user-named places — surfaced as teal pins with the name
     *  painted next to the marker. */
    namedLocations: List<NamedLocation> = emptyList(),
    onDismiss: () -> Unit,
    /** Fires when the user clicks Save. The closure receives the GPS,
     *  whether to embed it in the video file, and an optional name —
     *  non-empty when the user typed (or kept) a name in the name field.
     *  The caller is responsible for upserting that name into the
     *  named_locations table and for updating each target video's GPS. */
    onApply: (latitude: Double, longitude: Double, writeToFile: Boolean, name: String?) -> Unit,
) {
    val targetSet = remember(targetVideoIds) { targetVideoIds.toSet() }
    val secondaryLocations = remember(existingLocations, targetSet) {
        existingLocations.filter { it.id !in targetSet }
    }

    var pinLat by remember { mutableStateOf<Double?>(initialLocation?.first) }
    var pinLon by remember { mutableStateOf<Double?>(initialLocation?.second) }
    var writeToFile by remember { mutableStateOf(false) }
    var showExisting by remember(secondaryLocations) {
        mutableStateOf(secondaryLocations.isNotEmpty())
    }
    var candidateName by remember(initialLocation, namedLocations) {
        val initialMatch = if (initialLocation != null) {
            nearestNamedLocation(initialLocation.first, initialLocation.second, namedLocations)
        } else null
        mutableStateOf(initialMatch?.name ?: "")
    }
    var showCoords by remember { mutableStateOf(false) }

    val (initLat, initLon, initZoom) = remember(
        initialLocation, secondaryLocations, namedLocations
    ) {
        val points = buildList<Pair<Double, Double>> {
            secondaryLocations.forEach { add(it.latitude to it.longitude) }
            namedLocations.forEach { add(it.latitude to it.longitude) }
            initialLocation?.let { add(it) }
        }
        when {
            points.isNotEmpty() -> bboxFraming(points)
            else -> Triple(51.4769, 0.0, 3)
        }
    }

    // Resolve the candidate to a named place (if any) — re-derived on
    // every recomposition because `nameForLocation` is a cheap scan.
    val matched: NamedLocation? = if (pinLat != null && pinLon != null) {
        nearestNamedLocation(pinLat!!, pinLon!!, namedLocations)
    } else null

    val pins = remember(pinLat, pinLon, secondaryLocations, showExisting, namedLocations, matched) {
        buildList {
            // Named places always render (they're the recommended re-use
            // surface) regardless of the show-existing toggle.
            for (n in namedLocations) {
                add(
                    MapPin(
                        id = "named-${n.id}",
                        latitude = n.latitude,
                        longitude = n.longitude,
                        label = n.name,
                        style = MapPinStyle.NAMED,
                    )
                )
            }
            // Secondary video pins, skipping ones already covered by a
            // named pin within 250 m.
            if (showExisting) {
                for (loc in secondaryLocations) {
                    val nearby = nearestNamedLocation(loc.latitude, loc.longitude, namedLocations)
                    if (nearby != null) continue
                    add(
                        MapPin(
                            id = "existing-${loc.id}",
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            label = "%.4f, %.4f".format(loc.latitude, loc.longitude),
                            style = MapPinStyle.SECONDARY,
                        )
                    )
                }
            }
            val lat = pinLat; val lon = pinLon
            if (lat != null && lon != null) {
                add(
                    MapPin(
                        id = "candidate",
                        latitude = lat,
                        longitude = lon,
                        label = matched?.name ?: "%.4f, %.4f".format(lat, lon),
                        style = MapPinStyle.PRIMARY,
                    )
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                Text(
                    if (targetVideoIds.size == 1) "Set Location"
                    else "Set Location for ${targetVideoIds.size} Videos"
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Pan and zoom the map, then click where the video was " +
                        "captured — or click an existing pin to re-use that " +
                        "spot. The coordinate is saved to ReelVault's catalog " +
                        "and (optionally) embedded into the video file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                // Map is placed before the secondary-locations toggle so that
                // the toggle appearing/disappearing (when locations load async)
                // does not shift the MapView's slot position in the Column and
                // cause Compose to recreate the SwingPanel — which would reset
                // the viewport back to the initial center.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                ) {
                    MapView(
                        pins = pins,
                        initialCenter = initLat to initLon,
                        initialZoom = initZoom,
                        onMapClick = { lat, lon ->
                            pinLat = lat
                            pinLon = lon
                            val newMatch = nearestNamedLocation(lat, lon, namedLocations)
                            candidateName = newMatch?.name ?: ""
                            showCoords = false
                        },
                        onPinClick = { pin, _ ->
                            if (pin.id == "candidate") return@MapView
                            pinLat = pin.latitude
                            pinLon = pin.longitude
                            val newMatch = nearestNamedLocation(
                                pin.latitude, pin.longitude, namedLocations)
                            candidateName = newMatch?.name ?: ""
                            showCoords = false
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                if (secondaryLocations.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = showExisting,
                            onCheckedChange = { showExisting = it },
                        )
                        Column {
                            Text(
                                text = "Show ${secondaryLocations.size} already-known " +
                                    "location" + if (secondaryLocations.size == 1) "" else "s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Faint gray pins are existing GPS coordinates from " +
                                    "other videos. Click one to re-use it for the " +
                                    "selected video" +
                                    if (targetVideoIds.size == 1) "." else "s.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                }

                candidateReadout(
                    pinLat = pinLat,
                    pinLon = pinLon,
                    matched = matched,
                    candidateName = candidateName,
                    onNameChange = { candidateName = it },
                    showCoords = showCoords,
                    onToggleCoords = { showCoords = !showCoords },
                )

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = writeToFile, onCheckedChange = { writeToFile = it })
                    Column {
                        Text(
                            text = "Also embed in video file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Uses ffmpeg to rewrite the location atom without " +
                                "re-encoding. Original file is replaced atomically.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            val lat = pinLat; val lon = pinLon
            val enabled = lat != null && lon != null
            Tooltip(
                text = if (enabled) {
                    val n = targetVideoIds.size
                    val plural = if (n == 1) "" else "s"
                    "Apply (${"%.6f".format(lat)}, ${"%.6f".format(lon)}) to $n video$plural."
                } else "Click somewhere on the map first."
            ) {
                Button(
                    onClick = {
                        if (lat != null && lon != null) {
                            val trimmed = candidateName.trim()
                            onApply(lat, lon, writeToFile, trimmed.takeIf { it.isNotEmpty() })
                        }
                    },
                    enabled = enabled,
                ) { Text("Save") }
            }
        },
        dismissButton = {
            Tooltip(text = "Close without changing any location.") {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(720.dp)
    )
}

/** The "Selected: ..." block beneath the map, plus the name-editor text
 *  field. Pulled into its own composable to keep the main body readable. */
@Composable
private fun candidateReadout(
    pinLat: Double?,
    pinLon: Double?,
    matched: NamedLocation?,
    candidateName: String,
    onNameChange: (String) -> Unit,
    showCoords: Boolean,
    onToggleCoords: () -> Unit,
) {
    if (pinLat == null || pinLon == null) {
        Text(
            text = "Click the map (or an existing pin) to drop a candidate.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (matched != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Selected: ${matched.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = onToggleCoords) {
                    Text(
                        text = if (showCoords) "Hide coordinates" else "Show coordinates",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                AnimatedVisibility(showCoords) {
                    Text(
                        text = "%.6f, %.6f".format(pinLat, pinLon),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                    )
                }
            } else {
                Text(
                    text = "Selected: %.6f, %.6f".format(pinLat, pinLon),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = candidateName,
                onValueChange = onNameChange,
                label = {
                    Text(
                        if (matched == null) "Name this location (optional)"
                        else "Rename this location"
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Pick the named-location closest to (lat, lon) whose haversine distance
 *  is within its own radius. Returns null when no entry qualifies. */
internal fun nearestNamedLocation(
    latitude: Double,
    longitude: Double,
    candidates: List<NamedLocation>,
): NamedLocation? {
    if (candidates.isEmpty()) return null
    var best: NamedLocation? = null
    var bestDist = Double.MAX_VALUE
    for (loc in candidates) {
        val d = haversineMeters(latitude, longitude, loc.latitude, loc.longitude)
        if (d <= loc.radiusMeters && d < bestDist) {
            best = loc
            bestDist = d
        }
    }
    return best
}

private fun haversineMeters(
    lat1: Double, lon1: Double, lat2: Double, lon2: Double,
): Double {
    val earthRadius = 6_371_000.0
    val toRad = PI / 180.0
    val dLat = (lat2 - lat1) * toRad
    val dLon = (lon2 - lon1) * toRad
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1 * toRad) * cos(lat2 * toRad) *
        sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadius * c
}

/**
 * Compute a useful initial map framing for a set of points (lat, lon).
 * Returns (centerLat, centerLon, jxmapviewerZoom).
 *
 * Padding +2 zoom levels, floor 9, for a wider-than-tight initial view —
 * see the previous revision for the full rationale. `internal` so other
 * map-bearing screens (e.g. [MapScreen]) can reuse the framing.
 */
internal fun bboxFraming(points: List<Pair<Double, Double>>): Triple<Double, Double, Int> {
    if (points.isEmpty()) return Triple(51.4769, 0.0, 3)
    val lats = points.map { it.first }
    val lons = points.map { it.second }
    val minLat = lats.min(); val maxLat = lats.max()
    val minLon = lons.min(); val maxLon = lons.max()
    val centerLat = (minLat + maxLat) / 2.0
    val centerLon = (minLon + maxLon) / 2.0

    val latSpan = maxLat - minLat
    val lonSpan = (maxLon - minLon) * cos(Math.toRadians(centerLat))
    val span = max(0.001, max(latSpan, lonSpan))

    val osmZ = (ln(360.0 / span) / ln(2.0)).toInt().coerceIn(1, 18)
    val paddedOsmZ = (osmZ - 2).coerceIn(1, 18)
    val jxmapZoom = (19 - paddedOsmZ).coerceAtLeast(9).coerceAtMost(17)
    return Triple(centerLat, centerLon, jxmapZoom)
}
