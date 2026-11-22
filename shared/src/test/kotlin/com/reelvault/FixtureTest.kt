// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault

import com.reelvault.data.models.AttributeFilterState
import com.reelvault.data.models.FullResolutionStatus
import com.reelvault.data.models.LocalFilterState
import com.reelvault.data.models.MetadataColumn
import com.reelvault.data.models.OrientationFilterState
import com.reelvault.data.models.SmartCollectionColumn
import com.reelvault.data.models.SmartCollectionFilters
import com.reelvault.data.models.VideoSummary
import com.reelvault.data.models.derivedAttributeMetadataFilters
import com.reelvault.data.models.matchesLocalFilters
import com.reelvault.data.models.toWireValue
import com.reelvault.sync.rewriteSmartFilterTagIds
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the JSON fixtures in `<repo>/testdata`, which the Swift kit suite
 * (kit/Tests/ReelVaultKitTests/FixtureTests.swift) runs too, so both clients
 * must agree on every case.
 */
class FixtureTest {
    private fun load(file: String): JSONObject {
        // Gradle runs tests with the project dir (shared/) as the working dir.
        val f = listOf(File("../testdata/$file"), File("testdata/$file")).first { it.exists() }
        return JSONObject(f.readText())
    }

    private fun cases(file: String, key: String): List<JSONObject> =
        load(file).getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    /** Order-independent canonical text, every number as a Double (mirrors Fixtures.canonical in Swift). */
    private fun canonical(v: Any?): String = when (v) {
        is Boolean -> v.toString()
        is Number -> v.toDouble().toString()
        is String -> "\"$v\""
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is JSONObject -> v.keySet().sorted().joinToString(",", "{", "}") { "\"$it\":" + canonical(v.get(it)) }
        else -> "null"
    }

    private fun attr(s: String) = when (s) {
        "yes" -> AttributeFilterState.Yes
        "no" -> AttributeFilterState.No
        else -> AttributeFilterState.Any
    }

    private fun orient(s: String) = when (s) {
        "portrait" -> OrientationFilterState.Portrait
        "landscape" -> OrientationFilterState.Landscape
        else -> OrientationFilterState.Any
    }

    private fun token(a: AttributeFilterState) = a.name.lowercase()
    private fun token(o: OrientationFilterState) = o.name.lowercase()

    private fun canonicalMap(f: SmartCollectionFilters) = JSONObject().apply {
        put("columns", JSONArray().apply {
            f.columns.forEach {
                put(JSONObject().put("key", it.key).put("values", JSONArray(it.values)).put("negate", it.negate))
            }
        })
        put("minRating", f.minRating.toDouble()); put("colorLabel", f.colorLabel)
        put("tagIds", JSONArray(f.tagIds)); put("searchQuery", f.searchQuery)
        put("geoLat", f.geoLat); put("geoLon", f.geoLon); put("geoRadiusKm", f.geoRadiusKm)
        put("locationPaths", JSONArray(f.locationPaths))
        put("hasLocation", token(f.hasLocation)); put("hasKeywords", token(f.hasKeywords))
        put("hasProxies", token(f.hasProxies)); put("fullResolution", token(f.fullResolution))
        put("hasAudio", token(f.hasAudio)); put("orientation", token(f.orientation))
    }

    private fun filtersFrom(spec: JSONObject) = SmartCollectionFilters(
        columns = spec.optJSONArray("columns")?.let { a ->
            (0 until a.length()).map {
                val c = a.getJSONObject(it)
                SmartCollectionColumn(c.getString("key"), strings(c.getJSONArray("values")), c.optBoolean("negate"))
            }
        } ?: emptyList(),
        minRating = spec.optInt("minRating"),
        colorLabel = spec.optString("colorLabel"),
        tagIds = spec.optJSONArray("tagIds")?.let(::strings) ?: emptyList(),
        searchQuery = spec.optString("searchQuery"),
        geoLat = spec.optDouble("geoLat", 0.0), geoLon = spec.optDouble("geoLon", 0.0),
        geoRadiusKm = spec.optDouble("geoRadiusKm", 0.0),
        locationPaths = spec.optJSONArray("locationPaths")?.let(::strings) ?: emptyList(),
        hasLocation = attr(spec.optString("hasLocation", "any")),
        hasKeywords = attr(spec.optString("hasKeywords", "any")),
        hasProxies = attr(spec.optString("hasProxies", "any")),
        fullResolution = attr(spec.optString("fullResolution", "any")),
        hasAudio = attr(spec.optString("hasAudio", "any")),
        orientation = orient(spec.optString("orientation", "any")),
    )

    @Test fun smartCollectionParseCases() {
        val file = "smart_collection_filters.json"
        val defaults = load(file).getJSONObject("defaults")
        val all = cases(file, "parse")
        assertTrue(all.size >= 15)
        for (c in all) {
            val expected = JSONObject(defaults.toString())
            c.getJSONObject("expected").let { e -> e.keySet().forEach { expected.put(it, e.get(it)) } }
            val got = SmartCollectionFilters.fromJson(c.getString("json"))
            assertEquals("parse/${c.getString("name")}", canonical(expected), canonical(canonicalMap(got)))
        }
    }

    @Test fun smartCollectionRoundtripCases() {
        val all = cases("smart_collection_filters.json", "roundtrip")
        assertTrue(all.size >= 6)
        for (c in all) {
            val f = filtersFrom(c.getJSONObject("filters"))
            val back = SmartCollectionFilters.fromJson(f.toJson())
            assertEquals("roundtrip/${c.getString("name")}", canonical(canonicalMap(f)), canonical(canonicalMap(back)))
        }
    }

    @Test fun metadataWireCases() {
        val doc = load("metadata_wire.json")
        assertEquals(doc.getString("negatePrefix"), com.reelvault.data.models.METADATA_NEGATE_PREFIX)
        assertEquals(doc.getString("valueSeparator"), com.reelvault.data.models.METADATA_VALUE_SEPARATOR)
        val all = cases("metadata_wire.json", "wire")
        assertTrue(all.size >= 5)
        for (c in all) {
            val values = strings(c.getJSONArray("values"))
            val negate = c.getBoolean("negate")
            val expected = c.getString("wire")
            val name = "wire/${c.getString("name")}"
            assertEquals(name, expected, SmartCollectionColumn("k", values, negate).toWireValue())
            // MetadataColumn holds a set; the Swift side sorts, so compare in sorted order.
            assertEquals(name, SmartCollectionColumn("k", values.sorted(), negate).toWireValue(),
                MetadataColumn("k", values.sorted().toCollection(linkedSetOf()), negate = negate).toWireValue())
        }
    }

    @Test fun derivedAttributeFilterCases() {
        val all = cases("metadata_wire.json", "derived")
        assertTrue(all.size >= 6)
        for (c in all) {
            val got = derivedAttributeMetadataFilters(attr(c.getString("hasAudio")), orient(c.getString("orientation")))
            val expected = c.getJSONArray("filters").let { a ->
                (0 until a.length()).map { strings(a.getJSONArray(it)) }
            }
            assertEquals("derived/${c.getString("name")}", expected, got.map { listOf(it.key, it.value) })
        }
    }

    @Test fun localFilterCases() {
        val file = "local_filter.json"
        val defaults = load(file).getJSONObject("videoDefaults")
        val all = cases(file, "cases")
        assertTrue(all.size >= 40)
        for (c in all) {
            val v = JSONObject(defaults.toString())
            c.getJSONObject("video").let { o -> o.keySet().forEach { v.put(it, o.get(it)) } }
            val st = c.getJSONObject("state")
            val video = VideoSummary(
                id = "1", filename = "a.mov", path = "/a.mov", durationMs = 1000,
                width = v.getInt("width"), height = v.getInt("height"),
                codecVideo = "h264", codecAudio = v.getString("codecAudio"), fps = 24.0, sizeBytes = 1,
                indexedAt = 0, creationDate = 0,
                tags = strings(v.getJSONArray("tags")), proxyCount = v.getInt("proxyCount"),
                rating = v.getInt("rating"), colorLabel = v.getString("colorLabel"),
                gpsLatitude = v.getDouble("lat"), gpsLongitude = v.getDouble("lon"),
                fullResolution = when (v.getString("fullResolution")) {
                    "full" -> FullResolutionStatus.Full
                    "notFull" -> FullResolutionStatus.NotFull
                    else -> FullResolutionStatus.Unspecified
                },
            )
            val state = LocalFilterState(
                hasLocation = attr(st.optString("hasLocation", "any")),
                hasKeywords = attr(st.optString("hasKeywords", "any")),
                hasProxies = attr(st.optString("hasProxies", "any")),
                fullResolution = attr(st.optString("fullResolution", "any")),
                hasAudio = attr(st.optString("hasAudio", "any")),
                orientation = orient(st.optString("orientation", "any")),
                minRating = st.optInt("minRating"),
                colorLabel = st.optString("colorLabel"),
                location = st.optJSONArray("location")?.let { Triple(it.getDouble(0), it.getDouble(1), it.getDouble(2)) },
            )
            assertEquals("local/${c.getString("name")}", c.getBoolean("matches"), video.matchesLocalFilters(state))
        }
    }

    @Test fun tagRewriteCases() {
        val file = "tag_rewrite.json"
        val doc = load(file)
        fun map(o: JSONObject) = o.keySet().associateWith { o.getString(it) }
        val src = map(doc.getJSONObject("srcTags"))
        val dst = map(doc.getJSONObject("dstTags"))
        val all = cases(file, "cases")
        assertTrue(all.size >= 7)
        for (c in all) {
            val input = c.getString("input")
            val out = rewriteSmartFilterTagIds(input, src, dst)
            val name = "rewrite/${c.getString("name")}"
            if (c.optBoolean("unchanged")) assertEquals(name, input, out)
            else assertEquals(name, canonical(c.getJSONObject("expected")), canonical(JSONObject(out)))
        }
    }
}
