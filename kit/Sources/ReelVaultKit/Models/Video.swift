// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import SwiftUI

/// Mirror of proto `FullResolutionStatus`. The daemon's classifier
/// decides whether a video appears to be at its camera's native sensor
/// resolution (`.full`), at a non-standard non-native resolution
/// suggesting a derived/exported variant (`.notFull`), or in a state
/// we can't classify (`.unspecified` — unknown camera or a common
/// video standard like UHD/FHD). Clients render a badge for `.full`
/// and `.notFull`; `.unspecified` gets no badge.
public enum FullResolutionStatus: Int, Sendable {
    case unspecified = 0
    case full = 1
    case notFull = 2

    /// Map an int from the proto wire format. Unknown values fall back
    /// to `.unspecified` so a daemon that adds a new variant doesn't
    /// crash an older client.
    public static func from(wire: Int) -> FullResolutionStatus {
        FullResolutionStatus(rawValue: wire) ?? .unspecified
    }
}

public struct VideoSummary: Identifiable, Hashable, Sendable {
    public let id: String
    public let filename: String
    public let path: String
    public let width: Int
    public let height: Int
    public let durationMs: Int
    public let fps: Double
    public let codecVideo: String
    public let codecAudio: String
    public let bitrateKbps: Int
    public let sizeBytes: Int
    public let indexedAt: Int64
    public let creationDate: Int64
    public let tags: [String]
    public let hasThumbnail: Bool
    // Group info
    public let groupId: String
    public let groupSize: Int
    public let groupPreferredId: String
    public let groupPreferredPath: String
    // Proxy info. `proxyCount` drives the small "P×N" badge on the
    // card — non-zero means this video has lower-resolution proxies the
    // user can fall back to for inline playback. `proxyOf` non-empty
    // means *this* video is itself a proxy of another row; the grid
    // hides such rows behind their source unless the user clicks
    // "show proxies". `playableNatively` is the server's verdict on
    // whether the video fits under the configured max-native-height.
    public let proxyCount: Int
    public let proxyOf: String
    public let playableNatively: Bool
    /// Lightroom-style 0..5 star rating. 0 means unrated.
    public let rating: Int
    /// Lightroom-style color label — one of "", "red", "yellow", "green",
    /// "blue", "purple". Surfaces as the band-color around the card.
    public let colorLabel: String
    /// Raw EXIF camera body string (e.g. "SONY ILCE-7RM3"). Empty when the
    /// file has no camera metadata. Carried on the summary so the grid's
    /// configurable "Camera" top-of-card stat slot renders without a
    /// per-video VideoMetadata roundtrip.
    public let cameraModel: String
    /// Marketing-friendly camera name resolved by the daemon (e.g.
    /// "Sony a7R III"). Falls back to `cameraModel` when no mapping is
    /// known. UI uses this for display.
    public let cameraDisplayName: String
    /// GPS latitude from embedded EXIF/metadata. 0.0 when absent. Carried
    /// on the summary so the grid card can show a location badge without a
    /// per-video VideoMetadata round-trip.
    public let gpsLatitude: Double
    /// GPS longitude from embedded EXIF/metadata. 0.0 when absent.
    public let gpsLongitude: Double
    /// Lens designation from the video's embedded XMP packet (`aux:Lens`).
    /// Empty when the file has no XMP. Surfaced on the summary so the grid
    /// can both sort by lens and display it in a configurable stat slot
    /// without a per-row VideoMetadata round-trip.
    public let lensModel: String
    /// ISO from embedded XMP. 0 when absent.
    public let iso: Int
    /// F-number from embedded XMP (e.g. 1.8). 0.0 when absent.
    public let aperture: Double
    /// Exposure time in seconds from embedded XMP. 0.0 when absent.
    public let exposureTimeS: Double
    /// Focal length in millimeters from embedded XMP. 0.0 when absent.
    public let focalLengthMm: Double
    /// Full-resolution badge state set by the daemon's classifier.
    /// `.unspecified` (the default) renders no badge; the other two
    /// render the "Full" / "Not full" chip on the card.
    public let fullResolution: FullResolutionStatus
    /// False when the daemon found the file missing at the last scan — moved,
    /// renamed, or on an unmounted drive. The grid dims such cards and shows an
    /// "offline" badge instead of only failing when the user hits play.
    public let isOnline: Bool
    /// ffprobe's nb_frames for the video stream (proto `VideoSummary.frame_count`).
    /// 0 when the container didn't report one — `frameCountFormatted` then falls
    /// back to estimating from duration × fps. Surfaced on the summary so the
    /// grid's "Frame count" stat slot renders without a per-video round-trip.
    public let frameCount: Int64

    public var isInGroup: Bool { !groupId.isEmpty && groupSize > 1 }
    public var hasProxies: Bool { proxyCount > 0 }
    public var isProxy: Bool { !proxyOf.isEmpty }
    public var hasLocation: Bool { abs(gpsLatitude) > 1e-6 || abs(gpsLongitude) > 1e-6 }
    /// True when the file carries an audio track. Drives the card's audio badge
    /// and the "has audio" attribute filter.
    public var hasAudio: Bool { !codecAudio.isEmpty }
    /// Orientation buckets for the orientation attribute filter. Square
    /// (width == height) counts as landscape; unknown dimensions are neither.
    public var isPortrait: Bool { height > width }
    public var isLandscape: Bool { width > 0 && width >= height }
    /// Path to open on double-click — preferred member if in a group, else this video.
    public var openPath: String { groupPreferredPath.isEmpty ? path : groupPreferredPath }

    public var resolution: String { "\(width)×\(height)" }

    public var durationFormatted: String {
        let totalSeconds = durationMs / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }

    /// Exact frame count ("12,345") when the container reported one, else an
    /// estimate from duration × fps ("~12,345"), or nil when neither is known.
    /// Mirrors `VideoMetadata.frameCountFormatted` so the card and the detail
    /// panel show the same value.
    public var frameCountFormatted: String? {
        if frameCount > 0 { return frameCount.formatted() }
        if fps > 0 && durationMs > 0 {
            let est = Int64((Double(durationMs) / 1000.0 * fps).rounded())
            if est > 0 { return "~" + est.formatted() }
        }
        return nil
    }

    public var sizeFormatted: String {
        let mb = Double(sizeBytes) / (1024 * 1024)
        if mb > 1024 {
            return String(format: "%.2f GB", mb / 1024)
        }
        return String(format: "%.2f MB", mb)
    }

    /// Return a copy of self with `rating` replaced — used by the
    /// view-model's optimistic update path so a single field change
    /// doesn't require re-fetching the whole row.
    public func withRating(_ newRating: Int) -> VideoSummary {
        VideoSummary(
            id: id, filename: filename, path: path,
            width: width, height: height, durationMs: durationMs,
            fps: fps, codecVideo: codecVideo, codecAudio: codecAudio,
            bitrateKbps: bitrateKbps, sizeBytes: sizeBytes,
            indexedAt: indexedAt, creationDate: creationDate,
            tags: tags, hasThumbnail: hasThumbnail,
            groupId: groupId, groupSize: groupSize,
            groupPreferredId: groupPreferredId, groupPreferredPath: groupPreferredPath,
            proxyCount: proxyCount, proxyOf: proxyOf,
            playableNatively: playableNatively,
            rating: newRating, colorLabel: colorLabel,
            cameraModel: cameraModel, cameraDisplayName: cameraDisplayName,
            gpsLatitude: gpsLatitude, gpsLongitude: gpsLongitude,
            lensModel: lensModel, iso: iso, aperture: aperture,
            exposureTimeS: exposureTimeS, focalLengthMm: focalLengthMm,
            fullResolution: fullResolution,
            isOnline: isOnline,
            frameCount: frameCount
        )
    }

    /// Return a copy of self with `tags` replaced.
    public func withTags(_ newTags: [String]) -> VideoSummary {
        VideoSummary(
            id: id, filename: filename, path: path,
            width: width, height: height, durationMs: durationMs,
            fps: fps, codecVideo: codecVideo, codecAudio: codecAudio,
            bitrateKbps: bitrateKbps, sizeBytes: sizeBytes,
            indexedAt: indexedAt, creationDate: creationDate,
            tags: newTags, hasThumbnail: hasThumbnail,
            groupId: groupId, groupSize: groupSize,
            groupPreferredId: groupPreferredId, groupPreferredPath: groupPreferredPath,
            proxyCount: proxyCount, proxyOf: proxyOf,
            playableNatively: playableNatively,
            rating: rating, colorLabel: colorLabel,
            cameraModel: cameraModel, cameraDisplayName: cameraDisplayName,
            gpsLatitude: gpsLatitude, gpsLongitude: gpsLongitude,
            lensModel: lensModel, iso: iso, aperture: aperture,
            exposureTimeS: exposureTimeS, focalLengthMm: focalLengthMm,
            fullResolution: fullResolution,
            isOnline: isOnline,
            frameCount: frameCount
        )
    }

    /// Return a copy of self with GPS coordinates replaced.
    public func withLocation(latitude: Double, longitude: Double) -> VideoSummary {
        VideoSummary(
            id: id, filename: filename, path: path,
            width: width, height: height, durationMs: durationMs,
            fps: fps, codecVideo: codecVideo, codecAudio: codecAudio,
            bitrateKbps: bitrateKbps, sizeBytes: sizeBytes,
            indexedAt: indexedAt, creationDate: creationDate,
            tags: tags, hasThumbnail: hasThumbnail,
            groupId: groupId, groupSize: groupSize,
            groupPreferredId: groupPreferredId, groupPreferredPath: groupPreferredPath,
            proxyCount: proxyCount, proxyOf: proxyOf,
            playableNatively: playableNatively,
            rating: rating, colorLabel: colorLabel,
            cameraModel: cameraModel, cameraDisplayName: cameraDisplayName,
            gpsLatitude: latitude, gpsLongitude: longitude,
            lensModel: lensModel, iso: iso, aperture: aperture,
            exposureTimeS: exposureTimeS, focalLengthMm: focalLengthMm,
            fullResolution: fullResolution,
            isOnline: isOnline,
            frameCount: frameCount
        )
    }

    /// Return a copy of self with `colorLabel` replaced.
    public func withColorLabel(_ newLabel: String) -> VideoSummary {
        VideoSummary(
            id: id, filename: filename, path: path,
            width: width, height: height, durationMs: durationMs,
            fps: fps, codecVideo: codecVideo, codecAudio: codecAudio,
            bitrateKbps: bitrateKbps, sizeBytes: sizeBytes,
            indexedAt: indexedAt, creationDate: creationDate,
            tags: tags, hasThumbnail: hasThumbnail,
            groupId: groupId, groupSize: groupSize,
            groupPreferredId: groupPreferredId, groupPreferredPath: groupPreferredPath,
            proxyCount: proxyCount, proxyOf: proxyOf,
            playableNatively: playableNatively,
            rating: rating, colorLabel: newLabel,
            cameraModel: cameraModel, cameraDisplayName: cameraDisplayName,
            gpsLatitude: gpsLatitude, gpsLongitude: gpsLongitude,
            lensModel: lensModel, iso: iso, aperture: aperture,
            exposureTimeS: exposureTimeS, focalLengthMm: focalLengthMm,
            fullResolution: fullResolution,
            isOnline: isOnline,
            frameCount: frameCount
        )
    }
}

public struct VideoMetadata: Identifiable, Sendable {
    public let id: String
    public let filename: String
    public let path: String
    public let width: Int
    public let height: Int
    public let durationMs: Int
    public let fps: Double
    public let codecVideo: String
    public let codecAudio: String
    public let bitrateKbps: Int
    public let sizeBytes: Int
    public let colorSpace: String
    public let hdr: Bool
    public let audioChannels: Int
    public let audioSampleRate: Int
    public let creationDate: Int64
    public let cameraModel: String
    /// Marketing-friendly camera name (e.g. "Sony a7R III" for an internal
    /// "SONY ILCE-7RM3"). Falls back to `cameraModel` verbatim when no
    /// mapping is known. UI compares the two: when they differ a
    /// toggleable 'i' affordance reveals the internal name on click.
    public let cameraDisplayName: String
    public let lensModel: String
    public let gpsLat: Double
    public let gpsLon: Double
    public let gpsAltitude: Double
    public let notes: String
    public let tags: [String]
    public let collections: [String]
    /// Lightroom-style 0..5 star rating mirrored from VideoSummary.
    public let rating: Int
    /// Lightroom-style color label mirrored from VideoSummary.
    public let colorLabel: String
    /// Photo-EXIF recovered from the video's embedded XMP packet. Each is
    /// "absent" in a domain-specific way: a zero numeric or empty string
    /// means the video didn't carry that field. The detail/inspector view
    /// hides absent rows so a video with no XMP doesn't show seven empty
    /// rows under EXIF.
    public let iso: Int
    public let aperture: Double
    public let exposureTimeS: Double
    public let focalLengthMm: Double
    public let exposureMode: String
    public let exposureProgram: String
    public let whiteBalance: String
    /// Mirrors `VideoSummary.fullResolution` — the detail panel shows a
    /// "Resolution Status" row in addition to the card badge.
    public let fullResolution: FullResolutionStatus
    /// Total frame count (ffprobe nb_frames), or 0 when the container didn't
    /// report one — `frameCountFormatted` then estimates from duration × fps.
    public let frameCount: Int64

    public var resolution: String { "\(width)×\(height)" }

    public var durationFormatted: String {
        let totalSeconds = durationMs / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }

    /// Frame count for display: the exact stored count when known, otherwise a
    /// "~" estimate from duration × fps, or nil when neither is available.
    public var frameCountFormatted: String? {
        if frameCount > 0 { return frameCount.formatted() }
        if fps > 0 && durationMs > 0 {
            let est = Int64((Double(durationMs) / 1000.0 * fps).rounded())
            if est > 0 { return "~" + est.formatted() }
        }
        return nil
    }

    public var sizeFormatted: String {
        let mb = Double(sizeBytes) / (1024 * 1024)
        if mb > 1024 {
            return String(format: "%.2f GB", mb / 1024)
        }
        return String(format: "%.2f MB", mb)
    }

    public var bitrateFormatted: String {
        if bitrateKbps > 1000 {
            return String(format: "%.2f Mbps", Double(bitrateKbps) / 1000)
        }
        return "\(bitrateKbps) kbps"
    }

    /// Capture date as year-month-day only. The time-of-day from a video's
    /// creation timestamp is frequently wrong (cameras store local wall-clock
    /// without a timezone), so we deliberately show no finer granularity than
    /// the day.
    public var creationDateFormatted: String {
        if creationDate == 0 { return "Unknown" }
        let date = Date(timeIntervalSince1970: TimeInterval(creationDate / 1000))
        return date.formatted(date: .abbreviated, time: .omitted)
    }
}

public struct Tag: Identifiable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let color: String?
    public let videoCount: Int64
}

public struct Collection: Identifiable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let isSmart: Bool
    public var filterJson: String = ""
    public let videoCount: Int64
}

/// One metadata-column constraint captured in a smart collection: a metadata
/// `key` (camera, lens, codec, year, iso, exposure, … — any registry key) and
/// its selected facet `values` (OR-ed). Generalises the old fixed
/// camera/lens/codec/year fields so a smart collection reproduces ANY metadata
/// column the user had narrowed.
public struct SmartCollectionColumn: Sendable {
    public var key: String
    public var values: [String]
    /// "is not" column — matches videos lacking any of `values`.
    public var negate: Bool = false
}

public struct SmartCollectionFilters: Sendable {
    /// Arbitrary metadata-column constraints (replaces the old fixed
    /// camera/lens/codec/year scalars). The "location" virtual key is never
    /// stored here — geo lives in geoLat/geoLon/geoRadiusKm.
    public var columns: [SmartCollectionColumn] = []
    public var minRating: Int32 = 0
    public var colorLabel: String = ""
    public var tagIds: [String] = []
    /// Full-text search box ("keyword") query. Previously dropped, which made a
    /// smart collection saved from a search come back empty.
    public var searchQuery: String = ""
    /// Map proximity filter: keep videos within geoRadiusKm of (geoLat, geoLon).
    /// A radius of 0 means "no geo constraint" (0,0 is a legitimate coordinate,
    /// so radius — never 0 for a real filter — is the presence flag).
    public var geoLat: Double = 0.0
    public var geoLon: Double = 0.0
    public var geoRadiusKm: Double = 0.0
    /// Library-folder selection (the left panel). Empty = all folders. A smart
    /// collection can pin itself to one or more library locations.
    public var locationPaths: [String] = []
    /// Tri-state attribute filters mirrored from the Library Filter's
    /// "attribute" mode. `.any` means the dimension is unconstrained.
    public var hasLocation: AttributeFilterState = .any
    public var hasKeywords: AttributeFilterState = .any
    public var hasProxies: AttributeFilterState = .any
    public var fullResolution: AttributeFilterState = .any
    public var hasAudio: AttributeFilterState = .any
    public var orientation: OrientationFilterState = .any

    /// True when a map-proximity constraint is active.
    public var hasGeo: Bool { geoRadiusKm > 0.0 }

    public func toJson() -> String {
        func esc(_ s: String) -> String { "\"\(s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\""))\"" }
        // Each column is encoded as "key=v1v2" — a flat string so the
        // existing string-array parser round-trips it without a nested-array
        // JSON parser. '=' never appears in a metadata key.
        // A leading "!" on the key marks an "is not" column.
        let colsJson = columns.map { esc(($0.negate ? "!" : "") + $0.key + "=" + $0.values.joined(separator: metadataValueSeparator)) }.joined(separator: ",")
        let tagsJson = tagIds.map { esc($0) }.joined(separator: ",")
        let locJson = locationPaths.map { esc($0) }.joined(separator: ",")
        let attrs = #","locationPaths":[\#(locJson)],"hasLocation":\#(esc(hasLocation.rawValue)),"hasKeywords":\#(esc(hasKeywords.rawValue)),"hasProxies":\#(esc(hasProxies.rawValue)),"fullResolution":\#(esc(fullResolution.rawValue)),"hasAudio":\#(esc(hasAudio.rawValue)),"orientation":\#(esc(orientation.rawValue))"#
        return #"{"columns":[\#(colsJson)],"minRating":\#(minRating),"colorLabel":\#(esc(colorLabel)),"searchQuery":\#(esc(searchQuery)),"tagIds":[\#(tagsJson)],"geoLat":\#(geoLat),"geoLon":\#(geoLon),"geoRadiusKm":\#(geoRadiusKm)\#(attrs)}"#
    }

    public static func from(json: String) -> SmartCollectionFilters? {
        guard !json.isEmpty else { return nil }
        func strVal(_ key: String) -> String {
            guard let r = json.range(of: "\"\(key)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
                                     options: .regularExpression) else { return "" }
            let matched = String(json[r])
            let parts = matched.components(separatedBy: "\"")
            guard parts.count >= 4 else { return "" }
            return parts[3]
                .replacingOccurrences(of: "\\\"", with: "\"")
                .replacingOccurrences(of: "\\\\", with: "\\")
        }
        func intVal(_ key: String) -> Int32 {
            guard let r = json.range(of: "\"\(key)\"\\s*:\\s*([0-9]+)",
                                     options: .regularExpression) else { return 0 }
            let matched = String(json[r])
            let digits = matched.components(separatedBy: CharacterSet.decimalDigits.inverted).joined()
            return Int32(digits) ?? 0
        }
        func dblVal(_ key: String) -> Double {
            guard let r = json.range(of: "\"\(key)\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)",
                                     options: .regularExpression) else { return 0.0 }
            let matched = String(json[r])
            // Trim everything up to the colon, then parse the trailing number.
            guard let colon = matched.range(of: ":") else { return 0.0 }
            return Double(matched[colon.upperBound...].trimmingCharacters(in: .whitespaces)) ?? 0.0
        }
        // Parse a JSON array of strings (handles escaped quotes).
        func strArray(_ key: String) -> [String] {
            guard let ar = json.range(of: "\"\(key)\"\\s*:\\s*\\[([^\\]]*)\\]", options: .regularExpression) else { return [] }
            var out: [String] = []
            // `ar` spans the whole match, including the `"key":[` prefix. Start
            // scanning *after* the opening bracket, otherwise the first quoted
            // token found is the key name itself (e.g. "tagIds"), which would be
            // injected as a phantom array element — for tagIds that phantom is a
            // tag id no video carries, so the smart collection matched nothing.
            var scanning = String(json[ar])
            if let open = scanning.range(of: "[") {
                scanning = String(scanning[open.upperBound...])
            }
            while let qStart = scanning.range(of: "\"") {
                scanning = String(scanning[qStart.upperBound...])
                var val = ""
                var escaped = false
                var done = false
                for ch in scanning {
                    if escaped { val.append(ch); escaped = false }
                    else if ch == "\\" { escaped = true }
                    else if ch == "\"" { done = true; break }
                    else { val.append(ch) }
                }
                if done { out.append(val) }
                if let next = scanning.range(of: "\"") {
                    scanning = String(scanning[next.upperBound...])
                } else { break }
            }
            return out
        }

        var columns: [SmartCollectionColumn] = strArray("columns").compactMap { s in
            guard let eq = s.firstIndex(of: "=") else { return nil }
            var key = String(s[s.startIndex..<eq])
            // A leading "!" on the key marks an "is not" column.
            let negate = key.hasPrefix("!")
            if negate { key.removeFirst() }
            guard !key.isEmpty else { return nil }
            let vals = String(s[s.index(after: eq)...])
                .components(separatedBy: metadataValueSeparator).filter { !$0.isEmpty }
            return SmartCollectionColumn(key: key, values: vals, negate: negate)
        }
        // Backward-compat: smart collections saved before the generic-column
        // format stored camera/lens/codec/captureYear scalars.
        if columns.isEmpty {
            func legacyVals(_ s: String) -> [String] {
                s.components(separatedBy: metadataValueSeparator).filter { !$0.isEmpty }
            }
            let camera = strVal("camera"); if !camera.isEmpty { columns.append(SmartCollectionColumn(key: "camera", values: legacyVals(camera))) }
            let lens = strVal("lens"); if !lens.isEmpty { columns.append(SmartCollectionColumn(key: "lens", values: legacyVals(lens))) }
            let codec = strVal("codec"); if !codec.isEmpty { columns.append(SmartCollectionColumn(key: "codec", values: legacyVals(codec))) }
            let year = intVal("captureYear"); if year != 0 { columns.append(SmartCollectionColumn(key: "year", values: [String(year)])) }
        }
        func attr(_ key: String) -> AttributeFilterState {
            AttributeFilterState(rawValue: strVal(key)) ?? .any
        }
        func orient(_ key: String) -> OrientationFilterState {
            OrientationFilterState(rawValue: strVal(key)) ?? .any
        }
        return SmartCollectionFilters(
            columns: columns,
            minRating: intVal("minRating"),
            colorLabel: strVal("colorLabel"),
            tagIds: strArray("tagIds"),
            searchQuery: strVal("searchQuery"),
            geoLat: dblVal("geoLat"),
            geoLon: dblVal("geoLon"),
            geoRadiusKm: dblVal("geoRadiusKm"),
            locationPaths: strArray("locationPaths"),
            hasLocation: attr("hasLocation"),
            hasKeywords: attr("hasKeywords"),
            hasProxies: attr("hasProxies"),
            fullResolution: attr("fullResolution"),
            hasAudio: attr("hasAudio"),
            orientation: orient("orientation")
        )
    }
}

public struct LibraryLocation: Identifiable, Hashable, Sendable {
    public var id: String { path }
    public let path: String
    public let recursive: Bool
    public let enabled: Bool
    public let videoCount: Int64
    public let lastScanned: Int64
    /// True when this (recursive) location has at least one subdirectory
    /// containing videos — i.e. the library panel should offer to expand it.
    public var hasSubdirectories: Bool = false
}

/// One immediate child directory of a library location (or another
/// subdirectory), reported by the daemon's `ListSubdirectories` RPC. The tree
/// is derived from indexed video paths, so a subdirectory appears only when it
/// (recursively) contains videos.
public struct Subdirectory: Identifiable, Hashable, Sendable {
    public var id: String { path }
    public let path: String
    public let videoCount: Int64
    /// Whether this directory has child directories containing videos of its
    /// own — i.e. it is itself expandable.
    public let hasSubdirectories: Bool
}

/// A single visible row of the library panel's location tree: a library
/// location or one of its (transitive) subdirectories, flattened in display
/// order with its nesting `depth`.
public struct LibraryRow: Identifiable, Hashable, Sendable {
    /// Composite of depth + path so a directory that is also a registered
    /// location (a nested library root) can appear at two depths without an
    /// id clash.
    public var id: String { "\(depth) \(path)" }
    public let path: String
    public let depth: Int
    public let videoCount: Int64
    /// Show a disclosure chevron (the dir has expandable children).
    public let isExpandable: Bool
    public let isExpanded: Bool
    /// False for subdirectory rows — only top-level library locations carry
    /// rescan / remove affordances and the full-path sublabel.
    public let isTopLevel: Bool
}

/// Distinct values that can populate the top-bar filter dropdowns.
/// One row in the Camera Names editor. `isBuiltin` is true when the
/// daemon ships a curated mapping for `internalName`; `isCustom` is
/// true when the user has added or overridden a row in this catalog.
/// Both can be true at once — that's the "user replaced a built-in"
/// case, where `marketingName` carries the user's chosen string.
public struct CameraNameMapping: Identifiable, Hashable, Sendable {
    public let internalName: String
    public let marketingName: String
    public let isBuiltin: Bool
    public let isCustom: Bool

    /// Stable identity for SwiftUI List/ForEach. Internal name is the
    /// catalog-unique key for a mapping.
    public var id: String { internalName }
}

/// One row in the Lens Names editor. `rawName` is the lens string exactly
/// as stored in the catalog (the value ReelVault extracts from
/// `exifEX:LensModel`); `alias` is the display name shown for it. Unlike
/// cameras there is no built-in table, so `alias` equals `rawName` unless
/// the user set a custom override (`isCustom`). `inCatalog` is false for
/// a stale override whose lens no longer appears in any video.
public struct LensNameMapping: Identifiable, Hashable, Sendable {
    public let rawName: String
    public let alias: String
    public let isCustom: Bool
    public let inCatalog: Bool

    /// Stable identity for SwiftUI List/ForEach. The raw lens string is
    /// the catalog-unique key for a mapping.
    public var id: String { rawName }
}

public struct FilterOptions: Equatable, Sendable {
    public var cameras: [String] = []
    /// Marketing-friendly names for each entry in `cameras`, same order
    /// and length. Empty when the server didn't supply any (older
    /// catalog/daemon) — the UI then falls back to `cameras` verbatim.
    public var cameraDisplayNames: [String] = []
    public var lenses: [String] = []
    public var codecs: [String] = []
    public var captureYears: [Int32] = []
}

/// Information about the catalog the backend currently has open. An empty
/// `path` means the daemon is running but no SQLite file is mounted — the
/// client must call `OpenCatalog` before issuing any other RPC.
public struct CatalogInfo: Equatable, Sendable {
    public var path: String = ""
    public var name: String = ""
    public var videoCount: Int64 = 0
    public var openedAtMs: Int64 = 0

    public var isOpen: Bool { !path.isEmpty }

    public static let closed = CatalogInfo()
}

/// A geotagged video — what the global-map view needs to render a pin.
/// Returned by the daemon's `ListVideosWithLocations` RPC.
public struct VideoLocation: Identifiable, Equatable, Hashable, Sendable {
    public let id: String
    public let filename: String
    public let path: String
    public let latitude: Double
    public let longitude: Double
    public let altitude: Double
    public let hasThumbnail: Bool
}

/// A user-defined named place (e.g. "Home", "Yosemite Valley Visitor
/// Center"). The catalog stores a small list of these; clients resolve any
/// video's GPS into a name by picking the nearest entry within
/// `radiusMeters`. See [GridViewModel.nameForLocation].
public struct NamedLocation: Identifiable, Equatable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let latitude: Double
    public let longitude: Double
    /// Resolution tolerance in meters. Default 250 — overridable per-row
    /// in the schema for future "Yellowstone-sized" entries.
    public let radiusMeters: Double
    /// Unix ms (UTC). 0 if unknown.
    public let createdAtMs: Int64
    public let updatedAtMs: Int64
}

public struct ScanProgress: Sendable {
    public let status: String
    public let videosFound: Int
    public let videosIndexed: Int
    public let currentFile: String
    public let progressPercent: Double
}

public struct GroupInfo: Sendable {
    public let id: String
    public let name: String
    public let size: Int
    public let preferredVideoId: String
}

public struct AttachProxiesResult: Sendable {
    public let masterVideoId: String
    public let proxiesAttached: Int
    public let message: String
}

// MARK: - Real-time catalog events

/// Mirror of the proto `CatalogEvent.Kind` so view-model code can switch
/// on a domain enum instead of an int32. Adding cases is non-breaking
/// because the repository falls back to `.unknown` for anything new the
/// server might emit.
public enum CatalogEventKind: Sendable {
    case unknown
    case videoAdded
    case videoModified
    case videoRemoved
    case watcherStarted
    case watcherDisabled
    case scanStarted
    case scanCompleted
    /// A background post-index pass (proxy/group/sensor) began.
    case postIndexStarted
    /// Periodic progress for the in-flight post-index pass.
    case postIndexProgress
    /// The post-index pass drained; clients clear the activity panel.
    case postIndexCompleted
    /// An unpaired LAN device asked to pair; desktop/macOS shows an allow banner.
    case pairingRequested
}

public struct CatalogEvent: Equatable, Sendable {
    public let kind: CatalogEventKind
    public let videoId: String     // Empty for watcher-/scan-lifecycle events.
    public let path: String        // The file that triggered it (best-effort).
    public let atMs: Int64         // Server-side Unix milliseconds.
    public let message: String     // Human-readable (filename for VideoRemoved, etc.)
    /// Set only on `.postIndex*` events; nil otherwise.
    public var postIndex: PostIndexProgress? = nil
}

/// Payload for the `.postIndex*` catalog events — the daemon's background
/// proxy-detection / auto-grouping / camera-sensor / timelapse pass. This
/// work is CPU- and IO-heavy and used to be invisible to the UI; the grid
/// surfaces it in a background-activity panel so a long pass doesn't look
/// like the daemon has silently pegged a core.
public struct PostIndexProgress: Equatable, Sendable {
    /// Videos fully post-indexed in the current pass.
    public let processed: Int64
    /// Expected total this pass; 0 when unknown.
    public let total: Int64
    /// 0..100; 0 when `total` is unknown.
    public let percent: Double
    /// Estimated seconds remaining; 0 when unknown.
    public let etaSeconds: Int64
    /// Dominant activity: "grouping" | "proxies" | "sensors" | "tagging".
    public let phase: String
    /// Last human-readable action, e.g. "linked a.mov → b.mov".
    public let detail: String
}

/// Watcher knobs that govern the real-time scanner. Round-trip via
/// `GetWatchSettings` / `UpdateWatchSettings` to surface in the Preferences
/// dialog.
public struct WatchSettings: Equatable, Sendable {
    public var enabled: Bool
    public var writeSettleMs: Int64
    public var pollIntervalMs: Int64

    public static let `default` = WatchSettings(enabled: true, writeSettleMs: 5000, pollIntervalMs: 30000)
}

// MARK: - Lightroom-style color labels

/// Color labels mirror Adobe Lightroom's five-colour palette. Stored on
/// `VideoSummary.colorLabel` as the raw value (the empty string means "no
/// label"). The grid uses [.swatch] for the anchor card's band background
/// and [.dimmed] for unselected cards with the same label.
public enum ColorLabel: String, CaseIterable, Identifiable, Hashable, Sendable {
    case none = ""
    case red, yellow, green, blue, purple

    public var id: String { rawValue }

    /// Initialise from the raw string on `VideoSummary.colorLabel`. Unknown
    /// values fall back to `.none` so the UI degrades gracefully if the
    /// server adds a colour we don't yet recognise.
    public init(_ raw: String) {
        self = ColorLabel(rawValue: raw) ?? .none
    }

    /// Display name for menus and tooltips. "None" reads better in the
    /// right-click submenu than an empty string.
    public var displayName: String {
        switch self {
        case .none:   return "None"
        case .red:    return "Red"
        case .yellow: return "Yellow"
        case .green:  return "Green"
        case .blue:   return "Blue"
        case .purple: return "Purple"
        }
    }

    /// Fully-saturated swatch used as the band-background for the anchor
    /// card (the primary selection). Tuned to roughly match Lightroom's
    /// classic palette — desaturated enough not to overwhelm the thumbnail.
    public var swatch: Color {
        switch self {
        case .none:   return Color(white: 0.18)        // neutral panel tone
        case .red:    return Color(red: 0.78, green: 0.25, blue: 0.25)
        case .yellow: return Color(red: 0.82, green: 0.72, blue: 0.20)
        case .green:  return Color(red: 0.30, green: 0.65, blue: 0.32)
        case .blue:   return Color(red: 0.22, green: 0.45, blue: 0.78)
        case .purple: return Color(red: 0.55, green: 0.32, blue: 0.74)
        }
    }

    /// Toned-down variant used for unselected cards that still carry this
    /// colour label. Preserves the colour identity at a glance without
    /// shouting for the viewer's attention.
    public var dimmed: Color { swatch.opacity(0.45) }

    /// Mid-brightness variant used for secondary-selected (non-anchor)
    /// cards. Sits between [.swatch] and [.dimmed] so the user can tell
    /// at a glance which card is the anchor.
    public var secondary: Color { swatch.opacity(0.72) }

    /// Keyboard shortcut digit that applies this label, or nil for
    /// labels with no shortcut. Matches Lightroom: 6/7/8/9 for the four
    /// primary colours; purple has no shortcut historically.
    public var shortcutKey: Character? {
        switch self {
        case .red:    return "6"
        case .yellow: return "7"
        case .green:  return "8"
        case .blue:   return "9"
        case .purple, .none: return nil
        }
    }

    /// Map a keyboard digit back to a label, used by the global key handler.
    public static func from(shortcut: Character) -> ColorLabel? {
        switch shortcut {
        case "6": return .red
        case "7": return .yellow
        case "8": return .green
        case "9": return .blue
        default:  return nil
        }
    }
}

// MARK: - Grid card stat slots (Lightroom-style top-of-card)

/// Stable keys naming every stat that can appear in one of the four
/// configurable top-of-card slots. The string value is exchanged with the
/// daemon as part of `GridSettings.top_slots`, so it must match what the
/// Kotlin client emits.
public enum GridStatKey: String, CaseIterable, Identifiable, Hashable, Sendable {
    case none           = ""
    case filename
    case fileSize       = "file_size"
    case resolutionName = "resolution"    // "1080p", "4K", etc.
    case pixelDimensions = "pixel_dimensions"
    case duration
    case videoCodec     = "video_codec"
    case audioCodec     = "audio_codec"
    case fps
    case frameCount     = "frame_count"
    case bitrate
    case cameraModel    = "camera_model"
    case lensModel      = "lens_model"
    case captureDate    = "capture_date"
    case captureYear    = "capture_year"
    case iso
    case aperture
    case exposureTime   = "exposure_time"
    case focalLength    = "focal_length"
    case location

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .none:             return "(empty)"
        case .filename:         return "Filename"
        case .fileSize:         return "File size"
        case .resolutionName:   return "Resolution"
        case .pixelDimensions:  return "Pixel dimensions"
        case .duration:         return "Duration"
        case .videoCodec:       return "Video codec"
        case .audioCodec:       return "Audio codec"
        case .fps:              return "FPS"
        case .frameCount:       return "Frame count"
        case .bitrate:          return "Bitrate"
        case .cameraModel:      return "Camera"
        case .lensModel:        return "Lens"
        case .captureDate:      return "Capture date"
        case .captureYear:      return "Capture year"
        case .iso:              return "ISO"
        case .aperture:         return "Aperture"
        case .exposureTime:     return "Exposure"
        case .focalLength:      return "Focal length"
        case .location:         return "Location"
        }
    }

    /// Resolve this stat against a [VideoSummary]. Returns the display
    /// string for the card, or empty string if the underlying data is
    /// missing / inapplicable.
    ///
    /// `placeName` resolves a (latitude, longitude) to a registered
    /// place-name, or nil when none is in range — only consulted for the
    /// `.location` slot. When absent (or it returns nil) the raw coordinates
    /// are shown as "(lat, lon)", matching the detail panel's order.
    public func value(for video: VideoSummary, placeName: ((Double, Double) -> String?)? = nil) -> String {
        switch self {
        case .none:             return ""
        case .filename:         return video.filename
        case .fileSize:         return video.sizeFormatted
        case .resolutionName:   return resolutionLabel(height: video.height)
        case .pixelDimensions:  return video.resolution
        case .duration:         return video.durationFormatted
        case .videoCodec:       return video.codecVideo.isEmpty ? "" : video.codecVideo
        case .audioCodec:       return video.codecAudio.isEmpty ? "" : video.codecAudio
        case .fps:              return video.fps > 0 ? String(format: "%g fps", video.fps) : ""
        case .frameCount:       return video.frameCountFormatted ?? ""
        case .bitrate:
            if video.bitrateKbps <= 0 { return "" }
            return video.bitrateKbps >= 1000
                ? String(format: "%.1f Mbps", Double(video.bitrateKbps) / 1000)
                : "\(video.bitrateKbps) kbps"
        case .cameraModel:
            // VideoSummary now carries the camera body (display name with
            // user-override mapping resolved, falling back to the raw EXIF
            // string when no mapping exists) so the grid card renders the
            // "Camera" slot without a per-video metadata roundtrip.
            return video.cameraDisplayName.isEmpty ? video.cameraModel : video.cameraDisplayName
        case .lensModel:
            // Surfaced on the summary (like .cameraModel) so the grid
            // card's configurable "Lens" slot renders without a per-video
            // VideoMetadata roundtrip. Empty when the clip carries no XMP.
            return video.lensModel
        case .captureDate:
            if video.creationDate <= 0 { return "" }
            let date = Date(timeIntervalSince1970: TimeInterval(video.creationDate / 1000))
            let df = DateFormatter()
            df.dateStyle = .short
            return df.string(from: date)
        case .captureYear:
            if video.creationDate <= 0 { return "" }
            let date = Date(timeIntervalSince1970: TimeInterval(video.creationDate / 1000))
            let cal = Calendar(identifier: .gregorian)
            return String(cal.component(.year, from: date))
        case .iso:
            return video.iso > 0 ? "ISO \(video.iso)" : ""
        case .aperture:
            return video.aperture > 0 ? String(format: "f/%.1f", video.aperture) : ""
        case .exposureTime:
            return Self.formatExposureTime(video.exposureTimeS)
        case .focalLength:
            return video.focalLengthMm > 0 ? String(format: "%.0f mm", video.focalLengthMm) : ""
        case .location:
            guard video.hasLocation else { return "" }
            // Prefer a registered place-name; fall back to raw "(lat, lon)".
            if let name = placeName?(video.gpsLatitude, video.gpsLongitude) {
                return name
            }
            return String(format: "(%.4f, %.4f)", video.gpsLatitude, video.gpsLongitude)
        }
    }

    /// "1080p" / "720p" / "4K" / "8K" — the common shorthand pros use.
    /// Returns empty string when height is unknown.
    private func resolutionLabel(height: Int) -> String {
        switch height {
        case 0:           return ""
        case 1...479:     return "SD"
        case 480...575:   return "480p"
        case 576...719:   return "576p"
        case 720...1079:  return "720p"
        case 1080...1439: return "1080p"
        case 1440...2159: return "1440p"
        case 2160...4319: return "4K"
        case 4320...:     return "8K"
        default:          return "\(height)p"
        }
    }

    /// Format an EXIF exposure time. Sub-second exposures render as "1/N"
    /// with N the nearest integer reciprocal (how a photographer reads a
    /// shutter speed); anything ≥ 1 s renders as "X.X s". Mirrors the
    /// desktop client's `GridStatKey.formatExposureTime`.
    public static func formatExposureTime(_ seconds: Double) -> String {
        if seconds <= 0 { return "" }
        if seconds >= 1.0 { return String(format: "%.1f s", seconds) }
        let denom = Int((1.0 / seconds).rounded())
        return "1/\(denom)"
    }
}

// MARK: - Place-name resolver (ambient, for the "Location" card slot)

/// Resolves a (latitude, longitude) to a registered place-name, or nil when
/// none is in range. Injected by the views that own the `GridViewModel` so the
/// deeply-nested card cells can label the "Location" slot without each taking a
/// `GridViewModel` reference. The SwiftUI analogue of the Compose client's
/// `placeNameFor` parameter.
private struct PlaceNameResolverKey: EnvironmentKey {
    // The value is an immutable nil default; the closure type isn't Sendable,
    // so opt this constant out of the concurrency check explicitly.
    nonisolated(unsafe) static let defaultValue: ((Double, Double) -> String?)? = nil
}

extension EnvironmentValues {
    public var placeNameResolver: ((Double, Double) -> String?)? {
        get { self[PlaceNameResolverKey.self] }
        set { self[PlaceNameResolverKey.self] = newValue }
    }
}

/// Default top-slot configuration if the catalog has nothing stored yet.
/// Picks four stats that fit the Lightroom screenshot the user shared:
/// filename, file size, resolution shorthand, FPS.
public let defaultGridTopSlots: [String] = [
    GridStatKey.filename.rawValue,
    GridStatKey.fileSize.rawValue,
    GridStatKey.resolutionName.rawValue,
    GridStatKey.fps.rawValue,
]

// MARK: - Library Filter (the bar below the top bar)

/// Which Library Filter editor is visible. Under COMBINE semantics the
/// Text / Attribute / Metadata filters all stay applied at once; this only
/// chooses which editor is shown. (Clear is a momentary action handled by the
/// view model, not a resting mode.)
public enum LibraryFilterMode: String, CaseIterable, Identifiable, Hashable, Sendable {
    // Declaration order drives the selector. A video's place is just another
    // kind of metadata (the `locationMetadataKey` field inside `.metadata`),
    // so there is no standalone location mode.
    case text, attribute, metadata, clear
    public var id: String { rawValue }
    public var displayName: String {
        switch self {
        case .text:      return "Text"
        case .attribute: return "Attribute"
        case .metadata:  return "Metadata"
        case .clear:     return "Clear"
        }
    }
}

/// One selectable entry in the Library Filter's "Location" metadata field
/// (see `locationMetadataKey`): a named place or an unnamed coordinate cluster,
/// with how many catalog videos sit there. Picking one applies a geographic
/// proximity filter centred on it.
public struct LocationFilterGroup: Identifiable, Hashable, Sendable {
    public let label: String
    public let latitude: Double
    public let longitude: Double
    /// Proximity radius (km) to filter by when this entry is chosen.
    public let radiusKm: Double
    public let count: Int
    public let isNamed: Bool
    public var id: String { "\(label)|\(latitude),\(longitude)" }
}

/// Tri-state presence toggle for a Library Filter "attribute" (video has a
/// known location / keywords / proxies, or is full resolution). `any` applies
/// no constraint; `yes` keeps only videos that have the attribute; `no` keeps
/// only those that don't. Maps 1:1 to the proto `AttributeFilter`.
public enum AttributeFilterState: String, CaseIterable, Identifiable, Hashable, Sendable {
    case any, yes, no
    public var id: String { rawValue }
    public var displayName: String {
        switch self {
        case .any: return "Any"
        case .yes: return "Yes"
        case .no:  return "No"
        }
    }
}

/// Tri-state video-orientation filter for the Library Filter's "attribute"
/// mode. `any` applies no constraint; `portrait` keeps videos taller than wide;
/// `landscape` keeps those at least as wide as tall (square counts as
/// landscape). Sent to the daemon as an "orientation" metadata filter (value
/// "portrait" / "landscape"), so it needs no dedicated proto field.
public enum OrientationFilterState: String, CaseIterable, Identifiable, Hashable, Sendable {
    case any, portrait, landscape
    public var id: String { rawValue }
    public var displayName: String {
        switch self {
        case .any:       return "Any"
        case .portrait:  return "Portrait"
        case .landscape: return "Landscape"
        }
    }
}

/// One column in the metadata-mode browser. `key` is a canonical metadata
/// token ("" = not chosen yet); `values` are the selected facet tokens, OR-ed
/// together (empty = All). `anchor` is the value a range-select (shift-click)
/// extends from — the last value picked by a plain or toggle click.
public struct MetadataColumn: Identifiable, Equatable, Sendable {
    public let id = UUID()
    public var key: String = ""
    public var values: Set<String> = []
    public var anchor: String = ""
    /// When true the column matches videos that do NOT have any of `values`
    /// ("is not"); false (the default) is the plain "is" match.
    public var negate: Bool = false
}

/// Separator joining a metadata column's multiple selected facet tokens into a
/// single MetadataFilter value over the wire. ASCII Unit Separator (0x1F),
/// which never appears in real metadata values; the daemon splits on it and
/// OR-matches the parts. Must match the core's `METADATA_VALUE_SEPARATOR`.
public let metadataValueSeparator = "\u{1F}"

/// Leading marker on a MetadataFilter value that flips the column from "is" to
/// "is not". ASCII Record Separator (0x1E); must match the core's
/// `METADATA_NEGATE_PREFIX`. Built from the scalar to avoid an escaped literal.
public let metadataNegatePrefix = String(Character(UnicodeScalar(UInt8(0x1E))))

/// One selectable value within a metadata facet column.
public struct FacetValue: Equatable, Hashable, Sendable {
    public let token: String
    public let display: String
    public let count: Int64

    public init(token: String, display: String, count: Int64) {
        self.token = token
        self.display = display
        self.count = count
    }
}

/// The available values for one metadata column (server-computed cascade).
public struct MetadataFacetColumn: Equatable, Sendable {
    public let key: String
    public let displayName: String
    public let isNumeric: Bool
    public let values: [FacetValue]

    public init(key: String, displayName: String, isNumeric: Bool, values: [FacetValue]) {
        self.key = key
        self.displayName = displayName
        self.isNumeric = isNumeric
        self.values = values
    }
}

/// A metadata key the user can choose for a column.
public struct MetadataKeyInfo: Equatable, Hashable, Sendable {
    public let key: String
    public let displayName: String
    public let isNumeric: Bool

    public init(key: String, displayName: String, isNumeric: Bool) {
        self.key = key
        self.displayName = displayName
        self.isNumeric = isNumeric
    }
}

/// Canonical key of the "Location" metadata field. Unlike the registry-backed
/// keys (camera, lens, …) this one is synthesised entirely client-side: its
/// facet is the catalog's known places (`LocationFilterGroup`) and selecting a
/// value applies a geographic proximity filter via `setLocationFilter` rather
/// than a `MetadataFilter`. The daemon doesn't know this key — it returns an
/// empty facet for it and ignores it as a filter — so it's never sent as one.
public let locationMetadataKey = "location"

/// Result of a GetMetadataFacets call: per-column values + the key picker set.
public struct MetadataFacetsResult: Equatable, Sendable {
    public var columns: [MetadataFacetColumn] = []
    public var availableKeys: [MetadataKeyInfo] = []
}

/// Default metadata columns shown before the user customizes the bar.
public let defaultMetadataColumns: [MetadataColumn] = [
    MetadataColumn(key: "camera"),
    MetadataColumn(key: "lens"),
    MetadataColumn(key: "exposure"),
    MetadataColumn(key: "iso"),
]
