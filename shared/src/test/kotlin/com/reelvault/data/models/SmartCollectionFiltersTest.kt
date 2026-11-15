// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCollectionFiltersTest {
    @Test fun roundTripAllFields() {
        val f = SmartCollectionFilters(
            columns = listOf(
                SmartCollectionColumn("camera", listOf("A", "B")),
                SmartCollectionColumn("lens", listOf("L"), negate = true),
            ),
            minRating = 3,
            colorLabel = "red",
            tagIds = listOf("t1", "t2"),
            searchQuery = "sunset",
            geoLat = 12.5, geoLon = -45.25, geoRadiusKm = 30.0,
            locationPaths = listOf("/a", "/b"),
            hasLocation = AttributeFilterState.Yes,
            hasKeywords = AttributeFilterState.No,
            hasProxies = AttributeFilterState.Yes,
            fullResolution = AttributeFilterState.No,
            hasAudio = AttributeFilterState.Yes,
            orientation = OrientationFilterState.Portrait,
        )
        assertEquals(f, SmartCollectionFilters.fromJson(f.toJson()))
    }

    @Test fun defaultsRoundTrip() {
        val f = SmartCollectionFilters()
        assertEquals(f, SmartCollectionFilters.fromJson(f.toJson()))
    }

    @Test fun negatedColumnIsBangPrefixedInJson() {
        val json = SmartCollectionFilters(columns = listOf(SmartCollectionColumn("codec", listOf("h264"), true))).toJson()
        assertTrue(json.contains("\"!codec=h264\""))
    }

    @Test fun negatedColumnSurvives() {
        val f = SmartCollectionFilters.fromJson("{\"columns\":[\"!codec=h264\"]}")
        assertEquals(listOf(SmartCollectionColumn("codec", listOf("h264"), true)), f.columns)
    }

    @Test fun nonNegatedColumnHasNoNegate() {
        val f = SmartCollectionFilters.fromJson("{\"columns\":[\"codec=h264\"]}")
        assertFalse(f.columns.single().negate)
    }

    @Test fun multiValueColumnSplitsOnSeparator() {
        val f = SmartCollectionFilters.fromJson("{\"columns\":[\"iso=100\u001F200\"]}")
        assertEquals(listOf("100", "200"), f.columns.single().values)
    }

    @Test fun malformedColumnsAreDropped() {
        val f = SmartCollectionFilters.fromJson("{\"columns\":[\"noequals\",\"=x\",\"!=y\"]}")
        assertTrue(f.columns.isEmpty())
    }

    @Test fun legacyScalarsMapToColumns() {
        val f = SmartCollectionFilters.fromJson(
            "{\"camera\":\"Sony\",\"lens\":\"50mm\",\"codec\":\"prores\",\"captureYear\":2021}"
        )
        assertEquals(
            listOf(
                SmartCollectionColumn("camera", listOf("Sony")),
                SmartCollectionColumn("lens", listOf("50mm")),
                SmartCollectionColumn("codec", listOf("prores")),
                SmartCollectionColumn("year", listOf("2021")),
            ),
            f.columns,
        )
    }

    @Test fun legacyIgnoredWhenColumnsPresent() {
        val f = SmartCollectionFilters.fromJson("{\"columns\":[\"iso=100\"],\"camera\":\"Sony\"}")
        assertEquals(listOf("iso"), f.columns.map { it.key })
    }

    @Test fun blankAndUnknownJsonGiveDefaults() {
        assertEquals(SmartCollectionFilters(), SmartCollectionFilters.fromJson(""))
        assertEquals(SmartCollectionFilters(), SmartCollectionFilters.fromJson("{}"))
        assertEquals(SmartCollectionFilters(), SmartCollectionFilters.fromJson("not json"))
    }

    @Test fun tagIdsWithQuotesAndBackslashesSurvive() {
        val ids = listOf("a\"b", "c\\d", "plain")
        val f = SmartCollectionFilters.fromJson(SmartCollectionFilters(tagIds = ids).toJson())
        assertEquals(ids, f.tagIds)
    }

    @Test fun searchQueryWithQuoteSurvives() {
        val q = "say \"hi\""
        assertEquals(q, SmartCollectionFilters.fromJson(SmartCollectionFilters(searchQuery = q).toJson()).searchQuery)
    }

    @Test fun attributeTokensRoundTrip() {
        for (s in AttributeFilterState.values()) {
            val f = SmartCollectionFilters(hasAudio = s, hasLocation = s)
            val back = SmartCollectionFilters.fromJson(f.toJson())
            assertEquals(s, back.hasAudio)
            assertEquals(s, back.hasLocation)
        }
    }

    @Test fun orientationTokensRoundTrip() {
        for (o in OrientationFilterState.values()) {
            assertEquals(o, SmartCollectionFilters.fromJson(SmartCollectionFilters(orientation = o).toJson()).orientation)
        }
    }

    @Test fun unknownTokensFallBackToAny() {
        val f = SmartCollectionFilters.fromJson("{\"hasAudio\":\"maybe\",\"orientation\":\"square\"}")
        assertEquals(AttributeFilterState.Any, f.hasAudio)
        assertEquals(OrientationFilterState.Any, f.orientation)
    }

    @Test fun hasGeoKeysOnRadius() {
        assertFalse(SmartCollectionFilters(geoLat = 1.0, geoLon = 2.0).hasGeo)
        assertTrue(SmartCollectionFilters(geoRadiusKm = 5.0).hasGeo)
    }

    @Test fun negativeGeoCoordinatesRoundTrip() {
        val f = SmartCollectionFilters.fromJson(SmartCollectionFilters(geoLat = -33.9, geoLon = -151.2, geoRadiusKm = 2.5).toJson())
        assertEquals(-33.9, f.geoLat, 0.0)
        assertEquals(-151.2, f.geoLon, 0.0)
    }
}
