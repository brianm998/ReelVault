// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFilterMatchTest {
    private fun v(
        width: Int = 1920, height: Int = 1080, tags: List<String> = emptyList(),
        proxyCount: Int = 0, codecAudio: String = "aac", rating: Int = 0, colorLabel: String = "",
        lat: Double = 0.0, lon: Double = 0.0,
        full: FullResolutionStatus = FullResolutionStatus.Unspecified,
    ) = VideoSummary(
        id = "1", filename = "a.mov", path = "/a.mov", durationMs = 1000, width = width, height = height,
        codecVideo = "h264", codecAudio = codecAudio, fps = 24.0, sizeBytes = 1, indexedAt = 0, creationDate = 0,
        tags = tags, proxyCount = proxyCount, rating = rating, colorLabel = colorLabel,
        gpsLatitude = lat, gpsLongitude = lon, fullResolution = full,
    )

    private val any = LocalFilterState()
    private val yes = AttributeFilterState.Yes
    private val no = AttributeFilterState.No

    @Test fun defaultStateMatchesEverything() {
        assertTrue(v().matchesLocalFilters(any))
    }

    @Test fun hasLocation() {
        val loc = v(lat = 1.0, lon = 2.0)
        assertTrue(loc.matchesLocalFilters(any.copy(hasLocation = yes)))
        assertFalse(v().matchesLocalFilters(any.copy(hasLocation = yes)))
        assertFalse(loc.matchesLocalFilters(any.copy(hasLocation = no)))
        assertTrue(v().matchesLocalFilters(any.copy(hasLocation = no)))
    }

    @Test fun hasKeywords() {
        assertTrue(v(tags = listOf("x")).matchesLocalFilters(any.copy(hasKeywords = yes)))
        assertFalse(v().matchesLocalFilters(any.copy(hasKeywords = yes)))
        assertFalse(v(tags = listOf("x")).matchesLocalFilters(any.copy(hasKeywords = no)))
        assertTrue(v().matchesLocalFilters(any.copy(hasKeywords = no)))
    }

    @Test fun hasProxies() {
        assertTrue(v(proxyCount = 2).matchesLocalFilters(any.copy(hasProxies = yes)))
        assertFalse(v().matchesLocalFilters(any.copy(hasProxies = yes)))
        assertFalse(v(proxyCount = 2).matchesLocalFilters(any.copy(hasProxies = no)))
        assertTrue(v().matchesLocalFilters(any.copy(hasProxies = no)))
    }

    @Test fun fullResolutionUnspecifiedCountsAsNotFull() {
        val s = any.copy(fullResolution = yes)
        assertTrue(v(full = FullResolutionStatus.Full).matchesLocalFilters(s))
        assertFalse(v(full = FullResolutionStatus.NotFull).matchesLocalFilters(s))
        assertFalse(v().matchesLocalFilters(s))
        val n = any.copy(fullResolution = no)
        assertTrue(v().matchesLocalFilters(n))
        assertTrue(v(full = FullResolutionStatus.NotFull).matchesLocalFilters(n))
        assertFalse(v(full = FullResolutionStatus.Full).matchesLocalFilters(n))
    }

    @Test fun hasAudio() {
        assertTrue(v().matchesLocalFilters(any.copy(hasAudio = yes)))
        assertFalse(v(codecAudio = "").matchesLocalFilters(any.copy(hasAudio = yes)))
        assertTrue(v(codecAudio = "").matchesLocalFilters(any.copy(hasAudio = no)))
        assertFalse(v().matchesLocalFilters(any.copy(hasAudio = no)))
    }

    @Test fun orientation() {
        val p = any.copy(orientation = OrientationFilterState.Portrait)
        val l = any.copy(orientation = OrientationFilterState.Landscape)
        assertTrue(v(width = 1080, height = 1920).matchesLocalFilters(p))
        assertFalse(v().matchesLocalFilters(p))
        assertTrue(v().matchesLocalFilters(l))
        assertFalse(v(width = 1080, height = 1920).matchesLocalFilters(l))
    }

    @Test fun squareIsLandscape() {
        val s = v(width = 100, height = 100)
        assertTrue(s.matchesLocalFilters(any.copy(orientation = OrientationFilterState.Landscape)))
        assertFalse(s.matchesLocalFilters(any.copy(orientation = OrientationFilterState.Portrait)))
    }

    @Test fun zeroDimensionsAreNeither() {
        val z = v(width = 0, height = 0)
        assertFalse(z.matchesLocalFilters(any.copy(orientation = OrientationFilterState.Landscape)))
        assertFalse(z.matchesLocalFilters(any.copy(orientation = OrientationFilterState.Portrait)))
    }

    @Test fun zeroWidthWithHeightIsPortrait() {
        assertTrue(v(width = 0, height = 10).matchesLocalFilters(any.copy(orientation = OrientationFilterState.Portrait)))
    }

    @Test fun minRating() {
        assertTrue(v(rating = 3).matchesLocalFilters(any.copy(minRating = 3)))
        assertFalse(v(rating = 2).matchesLocalFilters(any.copy(minRating = 3)))
        assertTrue(v(rating = 0).matchesLocalFilters(any.copy(minRating = 0)))
    }

    @Test fun colorLabel() {
        assertTrue(v(colorLabel = "red").matchesLocalFilters(any.copy(colorLabel = "red")))
        assertFalse(v(colorLabel = "blue").matchesLocalFilters(any.copy(colorLabel = "red")))
        assertFalse(v().matchesLocalFilters(any.copy(colorLabel = "red")))
    }

    @Test fun geoInside() {
        val s = any.copy(location = Triple(40.0, -74.0, 10.0))
        assertTrue(v(lat = 40.01, lon = -74.01).matchesLocalFilters(s))
    }

    @Test fun geoOutsideLat() {
        val s = any.copy(location = Triple(40.0, -74.0, 10.0))
        assertFalse(v(lat = 41.0, lon = -74.0).matchesLocalFilters(s))
    }

    @Test fun geoOutsideLon() {
        val s = any.copy(location = Triple(40.0, -74.0, 10.0))
        assertFalse(v(lat = 40.0, lon = -72.0).matchesLocalFilters(s))
    }

    @Test fun geoRequiresLocation() {
        assertFalse(v().matchesLocalFilters(any.copy(location = Triple(0.0, 0.0, 1000.0))))
    }

    @Test fun geoLonDeltaWidensWithLatitude() {
        // At 60°N, cos = 0.5 so the lon box is twice as wide as at the equator.
        val s = any.copy(location = Triple(60.0, 10.0, 111.0))
        assertTrue(v(lat = 60.0, lon = 11.9).matchesLocalFilters(s))
        assertFalse(v(lat = 60.0, lon = 12.1).matchesLocalFilters(s))
    }

    @Test fun geoCosClampNearPole() {
        // cos(90°) ≈ 0 clamps to 0.01 → lon delta = 111/(111*0.01) = 100°.
        val s = any.copy(location = Triple(90.0, 0.0, 111.0))
        assertTrue(v(lat = 89.5, lon = 99.0).matchesLocalFilters(s))
        assertFalse(v(lat = 89.5, lon = 101.0).matchesLocalFilters(s))
    }

    @Test fun combinedFiltersAllMustPass() {
        val s = any.copy(hasKeywords = yes, minRating = 2)
        assertFalse(v(tags = listOf("t"), rating = 1).matchesLocalFilters(s))
        assertTrue(v(tags = listOf("t"), rating = 2).matchesLocalFilters(s))
    }
}
