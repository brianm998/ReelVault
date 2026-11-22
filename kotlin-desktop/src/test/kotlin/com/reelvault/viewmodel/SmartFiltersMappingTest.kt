// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.viewmodel

import com.reelvault.data.models.AttributeFilterState
import com.reelvault.data.models.LOCATION_METADATA_KEY
import com.reelvault.data.models.MetadataColumn
import com.reelvault.data.models.OrientationFilterState
import com.reelvault.data.models.SmartCollectionFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFiltersMappingTest {
    private fun build(
        tagId: String = "",
        tags: List<String> = emptyList(),
        cols: List<MetadataColumn> = emptyList(),
        geo: Triple<Double, Double, Double>? = null,
        rating: Int = 0,
        search: String = "",
    ) = buildSmartFilters(
        tagId = tagId, filterTags = tags, metadataColumns = cols, geo = geo,
        minRating = rating, colorLabel = "", searchQuery = search,
        locationPaths = emptyList(),
        hasLocation = AttributeFilterState.Any, hasKeywords = AttributeFilterState.Any,
        hasProxies = AttributeFilterState.Any, fullResolution = AttributeFilterState.Any,
        hasAudio = AttributeFilterState.Any, orientation = OrientationFilterState.Any,
    )

    @Test fun emptyStateMapsToDefaults() {
        assertEquals(SmartCollectionFilters(), build())
    }

    @Test fun singleTagIdUsedWhenNoMultiTagSet() {
        assertEquals(listOf("t1"), build(tagId = "t1").tagIds)
    }

    @Test fun multiTagSetWinsOverSingleTag() {
        assertEquals(listOf("a", "b"), build(tagId = "t1", tags = listOf("a", "b")).tagIds)
    }

    @Test fun emptyAndLocationColumnsAreDropped() {
        val f = build(cols = listOf(
            MetadataColumn(key = "", values = setOf("x")),
            MetadataColumn(key = "camera", values = emptySet()),
            MetadataColumn(key = LOCATION_METADATA_KEY, values = setOf("y")),
            MetadataColumn(key = "lens", values = setOf("35mm"), negate = true),
        ))
        assertEquals(1, f.columns.size)
        assertEquals("lens", f.columns[0].key)
        assertEquals(listOf("35mm"), f.columns[0].values)
        assertTrue(f.columns[0].negate)
    }

    @Test fun geoTripleMapsToLatLonRadius() {
        val f = build(geo = Triple(1.5, -2.5, 10.0))
        assertEquals(1.5, f.geoLat, 0.0)
        assertEquals(-2.5, f.geoLon, 0.0)
        assertEquals(10.0, f.geoRadiusKm, 0.0)
    }

    @Test fun ratingAndSearchCarriedThrough() {
        val f = build(rating = 4, search = "beach")
        assertEquals(4, f.minRating)
        assertEquals("beach", f.searchQuery)
    }

    @Test fun jsonRoundTripPreservesMapping() {
        val f = build(tags = listOf("a"), rating = 3, search = "q",
            geo = Triple(1.0, 2.0, 3.0),
            cols = listOf(MetadataColumn(key = "codec", values = setOf("prores"))))
        assertEquals(f, SmartCollectionFilters.fromJson(f.toJson()))
    }
}
