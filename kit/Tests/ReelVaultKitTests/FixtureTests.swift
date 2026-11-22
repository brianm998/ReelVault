// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import XCTest
@testable import ReelVaultKit

// Every case lives in `testdata/*.json` and is also run by the Kotlin suite
// (shared/src/test/kotlin/com/reelvault/FixtureTest.kt). One test method per
// case gives granular failures; the `testAll…` methods guarantee a case added
// to a fixture is exercised even before a named method exists.

// MARK: - canonical forms

private func canonicalMap(_ f: SmartCollectionFilters) -> [String: Any] {
    [
        "columns": f.columns.map { ["key": $0.key, "values": $0.values, "negate": $0.negate] as [String: Any] },
        "minRating": Double(f.minRating), "colorLabel": f.colorLabel, "tagIds": f.tagIds,
        "searchQuery": f.searchQuery, "geoLat": f.geoLat, "geoLon": f.geoLon,
        "geoRadiusKm": f.geoRadiusKm, "locationPaths": f.locationPaths,
        "hasLocation": f.hasLocation.rawValue, "hasKeywords": f.hasKeywords.rawValue,
        "hasProxies": f.hasProxies.rawValue, "fullResolution": f.fullResolution.rawValue,
        "hasAudio": f.hasAudio.rawValue, "orientation": f.orientation.rawValue,
    ]
}

private func filters(from spec: [String: Any]) -> SmartCollectionFilters {
    var f = SmartCollectionFilters()
    if let cols = spec["columns"] as? [[String: Any]] {
        f.columns = cols.map {
            SmartCollectionColumn(key: $0["key"] as! String, values: $0["values"] as! [String],
                                  negate: $0["negate"] as? Bool ?? false)
        }
    }
    if let v = spec["minRating"] as? Int { f.minRating = Int32(v) }
    f.colorLabel = spec["colorLabel"] as? String ?? ""
    f.tagIds = spec["tagIds"] as? [String] ?? []
    f.searchQuery = spec["searchQuery"] as? String ?? ""
    f.geoLat = spec["geoLat"] as? Double ?? 0
    f.geoLon = spec["geoLon"] as? Double ?? 0
    f.geoRadiusKm = spec["geoRadiusKm"] as? Double ?? 0
    f.locationPaths = spec["locationPaths"] as? [String] ?? []
    func attr(_ k: String) -> AttributeFilterState { AttributeFilterState(rawValue: spec[k] as? String ?? "any") ?? .any }
    f.hasLocation = attr("hasLocation"); f.hasKeywords = attr("hasKeywords")
    f.hasProxies = attr("hasProxies"); f.fullResolution = attr("fullResolution")
    f.hasAudio = attr("hasAudio")
    f.orientation = OrientationFilterState(rawValue: spec["orientation"] as? String ?? "any") ?? .any
    return f
}

private func fullSpec(_ expected: [String: Any]) throws -> [String: Any] {
    var d = try XCTUnwrap(Fixtures.load("smart_collection_filters.json")["defaults"] as? [String: Any])
    for (k, v) in expected { d[k] = v }
    return d
}

// MARK: - smart collection filters

final class SmartCollectionFixtureTests: XCTestCase {
    private let file = "smart_collection_filters.json"

    private func parse(_ name: String, line: UInt = #line) throws {
        let c = try Fixtures.named(file, "parse", name)
        let json = c["json"] as! String
        // Blank input is `nil` here; the Kotlin side returns defaults.
        let got = SmartCollectionFilters.from(json: json) ?? SmartCollectionFilters()
        XCTAssertEqual(Fixtures.canonical(canonicalMap(got)),
                       Fixtures.canonical(try fullSpec(c["expected"] as! [String: Any])),
                       "parse/\(name)", line: line)
    }

    private func roundtrip(_ name: String, line: UInt = #line) throws {
        let c = try Fixtures.named(file, "roundtrip", name)
        let f = filters(from: c["filters"] as! [String: Any])
        let back = try XCTUnwrap(SmartCollectionFilters.from(json: f.toJson()))
        XCTAssertEqual(Fixtures.canonical(canonicalMap(back)), Fixtures.canonical(canonicalMap(f)),
                       "roundtrip/\(name)", line: line)
    }

    func testAllParseCases() throws {
        for c in try Fixtures.cases(file, "parse") { try parse(c["name"] as! String) }
    }
    func testAllRoundtripCases() throws {
        for c in try Fixtures.cases(file, "roundtrip") { try roundtrip(c["name"] as! String) }
    }

    func testParseBlankGivesDefaults() throws { try parse("blank_gives_defaults") }
    func testParseEmptyObject() throws { try parse("empty_object_gives_defaults") }
    func testParseGarbage() throws { try parse("garbage_gives_defaults") }
    func testParseNegatedColumn() throws { try parse("negated_column") }
    func testParsePlainColumn() throws { try parse("plain_column") }
    func testParseMultiValueColumn() throws { try parse("multi_value_column") }
    func testParseMalformedColumnsDropped() throws { try parse("malformed_columns_dropped") }
    func testParseLegacyScalars() throws { try parse("legacy_scalars_become_columns") }
    func testParseLegacyIgnoredWhenColumnsPresent() throws { try parse("legacy_ignored_when_columns_present") }
    /// Regression: the parser once leaked the JSON key ("tagIds") as an element,
    /// making every smart collection show 0 videos.
    func testParseTagIdsDoNotLeakKeyName() throws { try parse("tag_ids_do_not_leak_key_name") }
    func testParseEmptyTagIds() throws { try parse("empty_tag_ids") }
    func testParseUnknownTokensFallBackToAny() throws { try parse("unknown_tokens_fall_back_to_any") }
    func testParseAttributeTokens() throws { try parse("attribute_tokens") }
    func testParseNegativeGeo() throws { try parse("negative_geo") }
    func testParseRatingColorSearch() throws { try parse("rating_color_search") }
    func testParseEscapes() throws { try parse("escaped_quotes_and_backslashes") }
    func testParseLocationPaths() throws { try parse("location_paths") }

    func testRoundtripDefaults() throws { try roundtrip("defaults") }
    func testRoundtripAllFields() throws { try roundtrip("all_fields") }
    func testRoundtripQuotesAndBackslashes() throws { try roundtrip("quotes_and_backslashes") }
    func testRoundtripNegativeGeo() throws { try roundtrip("negative_geo") }
    func testRoundtripNegatedMultiValueColumn() throws { try roundtrip("negated_multi_value_column") }
    func testRoundtripLandscapeAnyAudio() throws { try roundtrip("landscape_any_audio") }

    func testNegatedColumnIsBangPrefixedInJson() {
        let json = SmartCollectionFilters(columns: [SmartCollectionColumn(key: "codec", values: ["h264"], negate: true)]).toJson()
        XCTAssertTrue(json.contains("\"!codec=h264\""))
    }

    func testHasGeoKeysOnRadius() {
        XCTAssertFalse(SmartCollectionFilters(geoLat: 1, geoLon: 2).hasGeo)
        XCTAssertTrue(SmartCollectionFilters(geoRadiusKm: 5).hasGeo)
    }
}

// MARK: - metadata wire encoding

final class MetadataWireFixtureTests: XCTestCase {
    private let file = "metadata_wire.json"

    private func wire(_ name: String, line: UInt = #line) throws {
        let c = try Fixtures.named(file, "wire", name)
        let values = c["values"] as! [String]
        let negate = c["negate"] as! Bool
        let expected = c["wire"] as! String
        XCTAssertEqual(metadataWireValue(values: values, negate: negate), expected, "wire/\(name)", line: line)
        XCTAssertEqual(SmartCollectionColumn(key: "k", values: values, negate: negate).wireValue, expected, line: line)
        // MetadataColumn holds a Set, so compare in sorted order.
        let sorted = metadataWireValue(values: values.sorted(), negate: negate)
        XCTAssertEqual(MetadataColumn(key: "k", values: Set(values), negate: negate).wireValue, sorted, line: line)
    }

    private func derived(_ name: String, line: UInt = #line) throws {
        let c = try Fixtures.named(file, "derived", name)
        let got = derivedAttributeMetadataFilters(
            hasAudio: AttributeFilterState(rawValue: c["hasAudio"] as! String)!,
            orientation: OrientationFilterState(rawValue: c["orientation"] as! String)!)
        let expected = c["filters"] as! [[String]]
        XCTAssertEqual(got.map { [$0.key, $0.value] }, expected, "derived/\(name)", line: line)
    }

    func testAllWireCases() throws { for c in try Fixtures.cases(file, "wire") { try wire(c["name"] as! String) } }
    func testAllDerivedCases() throws { for c in try Fixtures.cases(file, "derived") { try derived(c["name"] as! String) } }

    func testSeparatorsMatchFixture() throws {
        let f = try Fixtures.load(file)
        XCTAssertEqual(metadataNegatePrefix, f["negatePrefix"] as? String)
        XCTAssertEqual(metadataValueSeparator, f["valueSeparator"] as? String)
        XCTAssertEqual(metadataNegatePrefix.unicodeScalars.first?.value, 0x1E)
        XCTAssertEqual(metadataValueSeparator.unicodeScalars.first?.value, 0x1F)
    }

    func testWirePlainSingle() throws { try wire("plain_single") }
    func testWireNegatedSingle() throws { try wire("negated_single") }
    func testWireMultiJoined() throws { try wire("multi_joined_by_unit_separator") }
    func testWireNegatedMulti() throws { try wire("negated_multi") }
    func testWireEmptyValues() throws { try wire("empty_values") }

    func testDerivedAnyAny() throws { try derived("any_any") }
    func testDerivedAudioYes() throws { try derived("audio_yes") }
    func testDerivedAudioNo() throws { try derived("audio_no") }
    func testDerivedPortrait() throws { try derived("portrait") }
    func testDerivedLandscape() throws { try derived("landscape") }
    func testDerivedBothInOrder() throws { try derived("both_in_order") }
}

// MARK: - local filter matching

final class LocalFilterFixtureTests: XCTestCase {
    private let file = "local_filter.json"

    private func check(_ name: String, line: UInt = #line) throws {
        let doc = try Fixtures.load(file)
        let defaults = doc["videoDefaults"] as! [String: Any]
        let c = try Fixtures.named(file, "cases", name)
        var v = defaults
        for (k, x) in c["video"] as! [String: Any] { v[k] = x }
        let st = c["state"] as! [String: Any]

        let full: FullResolutionStatus = switch v["fullResolution"] as! String {
        case "full": .full
        case "notFull": .notFull
        default: .unspecified
        }
        let video = VideoSummary(
            id: "1", filename: "a.mov", path: "/a.mov", width: v["width"] as! Int, height: v["height"] as! Int,
            durationMs: 1000, fps: 24, codecVideo: "h264", codecAudio: v["codecAudio"] as! String,
            bitrateKbps: 0, sizeBytes: 1, indexedAt: 0, creationDate: 0, tags: v["tags"] as! [String],
            hasThumbnail: false, groupId: "", groupSize: 0, groupPreferredId: "", groupPreferredPath: "",
            proxyCount: v["proxyCount"] as! Int, proxyOf: "", playableNatively: true,
            rating: v["rating"] as! Int, colorLabel: v["colorLabel"] as! String,
            cameraModel: "", cameraDisplayName: "",
            gpsLatitude: v["lat"] as! Double, gpsLongitude: v["lon"] as! Double,
            lensModel: "", iso: 0, aperture: 0, exposureTimeS: 0, focalLengthMm: 0,
            fullResolution: full, isOnline: true, frameCount: 0)

        func attr(_ k: String) -> AttributeFilterState { AttributeFilterState(rawValue: st[k] as? String ?? "any")! }
        var geo: GeoFilter? = nil
        if let g = st["location"] as? [Double] { geo = GeoFilter(latitude: g[0], longitude: g[1], radiusKm: g[2]) }
        let state = LocalFilterState(
            hasLocation: attr("hasLocation"), hasKeywords: attr("hasKeywords"),
            hasProxies: attr("hasProxies"), fullResolution: attr("fullResolution"),
            hasAudio: attr("hasAudio"),
            orientation: OrientationFilterState(rawValue: st["orientation"] as? String ?? "any")!,
            minRating: Int32(st["minRating"] as? Int ?? 0), colorLabel: st["colorLabel"] as? String ?? "",
            location: geo)
        XCTAssertEqual(video.matchesLocalFilters(state), c["matches"] as? Bool, "local/\(name)", line: line)
    }

    func testAllLocalFilterCases() throws {
        for c in try Fixtures.cases(file, "cases") { try check(c["name"] as! String) }
    }

    func testDefaultMatchesEverything() throws { try check("default_matches_everything") }
    func testLocationYes() throws { try check("location_yes_with_gps"); try check("location_yes_without_gps") }
    func testLocationNo() throws { try check("location_no_with_gps"); try check("location_no_without_gps") }
    func testKeywordsYes() throws { try check("keywords_yes_tagged"); try check("keywords_yes_untagged") }
    func testKeywordsNo() throws { try check("keywords_no_tagged"); try check("keywords_no_untagged") }
    func testProxiesYes() throws { try check("proxies_yes_with"); try check("proxies_yes_without") }
    func testProxiesNo() throws { try check("proxies_no_with"); try check("proxies_no_without") }
    func testFullResYes() throws {
        try check("full_res_yes_full"); try check("full_res_yes_notfull")
        try check("full_res_yes_unspecified_counts_as_not_full")
    }
    func testFullResNo() throws { try check("full_res_no_unspecified"); try check("full_res_no_full") }
    func testAudioYes() throws { try check("audio_yes_with"); try check("audio_yes_without") }
    func testAudioNo() throws { try check("audio_no_without"); try check("audio_no_with") }
    func testPortrait() throws { try check("portrait_accepts_tall"); try check("portrait_rejects_wide") }
    func testLandscape() throws { try check("landscape_accepts_wide"); try check("landscape_rejects_tall") }
    func testSquareIsLandscapeNotPortrait() throws { try check("square_is_landscape"); try check("square_is_not_portrait") }
    func testZeroDimensionsAreNeither() throws { try check("zero_dims_not_landscape"); try check("zero_dims_not_portrait") }
    func testZeroWidthWithHeightIsPortrait() throws { try check("zero_width_with_height_is_portrait") }
    func testMinRating() throws { try check("min_rating_met"); try check("min_rating_not_met"); try check("min_rating_zero") }
    func testColorLabel() throws {
        try check("color_label_match"); try check("color_label_mismatch"); try check("color_label_missing")
    }
    func testGeoBox() throws {
        try check("geo_inside"); try check("geo_outside_lat"); try check("geo_outside_lon")
        try check("geo_requires_location")
    }
    func testGeoLonDeltaWidensWithLatitude() throws {
        try check("geo_lon_delta_widens_with_latitude_inside"); try check("geo_lon_delta_widens_with_latitude_outside")
    }
    func testGeoCosClampNearPole() throws {
        try check("geo_cos_clamp_near_pole_inside"); try check("geo_cos_clamp_near_pole_outside")
    }
    func testCombinedFilters() throws {
        try check("combined_fails_when_one_fails"); try check("combined_passes_when_all_pass")
    }
}

// MARK: - tag rewrite (catalog sync)

final class TagRewriteFixtureTests: XCTestCase {
    private let file = "tag_rewrite.json"

    private func check(_ name: String, line: UInt = #line) throws {
        let doc = try Fixtures.load(file)
        let c = try Fixtures.named(file, "cases", name)
        let input = c["input"] as! String
        let out = rewriteSmartFilterTagIds(input, srcTags: doc["srcTags"] as! [String: String],
                                           dstTags: doc["dstTags"] as! [String: String])
        if c["unchanged"] as? Bool == true {
            XCTAssertEqual(out, input, "rewrite/\(name)", line: line)
        } else {
            // Compare as JSON trees: the Kotlin side splices text, this side re-serializes.
            let tree = try JSONSerialization.jsonObject(with: Data(out.utf8))
            XCTAssertEqual(Fixtures.canonical(tree), Fixtures.canonical(c["expected"]!), "rewrite/\(name)", line: line)
        }
    }

    func testAllRewriteCases() throws {
        for c in try Fixtures.cases(file, "cases") { try check(c["name"] as! String) }
    }
    func testRemapsByName() throws { try check("remaps_by_name") }
    func testDropsMissingOnDestination() throws { try check("drops_names_missing_on_destination") }
    func testDropsUnknownSourceIds() throws { try check("drops_unknown_source_ids") }
    func testBlankUnchanged() throws { try check("blank_unchanged"); try check("whitespace_unchanged") }
    func testNoTagIdsUnchanged() throws { try check("no_tag_ids_key_unchanged") }
    func testEmptyArrayStaysEmpty() throws { try check("empty_array_stays_empty") }
}
