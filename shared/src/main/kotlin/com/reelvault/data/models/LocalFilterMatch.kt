// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.models

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/** Snapshot of the filters a client can evaluate locally against a [VideoSummary]. */
data class LocalFilterState(
    val hasLocation: AttributeFilterState = AttributeFilterState.Any,
    val hasKeywords: AttributeFilterState = AttributeFilterState.Any,
    val hasProxies: AttributeFilterState = AttributeFilterState.Any,
    val fullResolution: AttributeFilterState = AttributeFilterState.Any,
    val hasAudio: AttributeFilterState = AttributeFilterState.Any,
    val orientation: OrientationFilterState = OrientationFilterState.Any,
    val minRating: Int = 0,
    val colorLabel: String = "",
    /** (lat, lon, radiusKm) or null for no geo constraint. */
    val location: Triple<Double, Double, Double>? = null,
)

/**
 * Does this summary still satisfy the attribute + Lightroom-mark filters we can
 * evaluate client-side? Mirrors the daemon's `build_filter_clauses`
 * (core/src/db.rs) for exactly those filters. Search / metadata / tag /
 * collection filters need the server and aren't checked. `true` means "not
 * filtered out by anything we can see locally".
 */
fun VideoSummary.matchesLocalFilters(state: LocalFilterState): Boolean {
    val s = this
    when (state.hasLocation) {
        AttributeFilterState.Yes -> if (!s.hasLocation) return false
        AttributeFilterState.No -> if (s.hasLocation) return false
        AttributeFilterState.Any -> {}
    }
    when (state.hasKeywords) {
        AttributeFilterState.Yes -> if (s.tags.isEmpty()) return false
        AttributeFilterState.No -> if (s.tags.isNotEmpty()) return false
        AttributeFilterState.Any -> {}
    }
    when (state.hasProxies) {
        AttributeFilterState.Yes -> if (!s.hasProxies) return false
        AttributeFilterState.No -> if (s.hasProxies) return false
        AttributeFilterState.Any -> {}
    }
    // The daemon classifies "full resolution" via a (camera, w, h) tuple set;
    // Full ⇔ in that set and anything else (incl. Unspecified) is "not full".
    when (state.fullResolution) {
        AttributeFilterState.Yes -> if (s.fullResolution != FullResolutionStatus.Full) return false
        AttributeFilterState.No -> if (s.fullResolution == FullResolutionStatus.Full) return false
        AttributeFilterState.Any -> {}
    }
    when (state.hasAudio) {
        AttributeFilterState.Yes -> if (!s.hasAudio) return false
        AttributeFilterState.No -> if (s.hasAudio) return false
        AttributeFilterState.Any -> {}
    }
    // portrait ⇔ height > width; landscape ⇔ has real dims and width ≥ height.
    when (state.orientation) {
        OrientationFilterState.Portrait -> if (!s.isPortrait) return false
        OrientationFilterState.Landscape -> if (!s.isLandscape) return false
        OrientationFilterState.Any -> {}
    }
    if (state.minRating > 0 && s.rating < state.minRating) return false
    if (state.colorLabel.isNotEmpty() && s.colorLabel != state.colorLabel) return false
    // Geo/area bounding box. 1° lat ≈ 111 km; 1° lon ≈ 111·cos(lat) km, with
    // cos clamped near the poles.
    val geo = state.location
    if (geo != null) {
        val (lat, lon, radiusKm) = geo
        if (!s.hasLocation) return false
        val latDelta = abs(radiusKm / 111.0)
        val cosLat = max(0.01, abs(cos(Math.toRadians(lat))))
        val lonDelta = abs(radiusKm / (111.0 * cosLat))
        if (s.gpsLatitude < lat - latDelta || s.gpsLatitude > lat + latDelta) return false
        if (s.gpsLongitude < lon - lonDelta || s.gpsLongitude > lon + lonDelta) return false
    }
    return true
}
