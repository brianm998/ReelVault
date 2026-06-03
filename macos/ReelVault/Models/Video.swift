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
enum FullResolutionStatus: Int {
    case unspecified = 0
    case full = 1
    case notFull = 2

    /// Map an int from the proto wire format. Unknown values fall back
    /// to `.unspecified` so a daemon that adds a new variant doesn't
    /// crash an older client.
    static func from(wire: Int) -> FullResolutionStatus {
        FullResolutionStatus(rawValue: wire) ?? .unspecified
    }
}

struct VideoSummary: Identifiable, Hashable {
    let id: String
    let filename: String
    let path: String
    let width: Int
    let height: Int
    let durationMs: Int
    let fps: Double
    let codecVideo: String
    let codecAudio: String
    let bitrateKbps: Int
    let sizeBytes: Int
    let indexedAt: Int64
    let creationDate: Int64
    let tags: [String]
    let hasThumbnail: Bool
    // Group info
    let groupId: String
    let groupSize: Int
    let groupPreferredId: String
    let groupPreferredPath: String
    // Proxy info. `proxyCount` drives the small "P×N" badge on the
    // card — non-zero means this video has lower-resolution proxies the
    // user can fall back to for inline playback. `proxyOf` non-empty
    // means *this* video is itself a proxy of another row; the grid
    // hides such rows behind their source unless the user clicks
    // "show proxies". `playableNatively` is the server's verdict on
    // whether the video fits under the configured max-native-height.
    let proxyCount: Int
    let proxyOf: String
    let playableNatively: Bool
    /// Lightroom-style 0..5 star rating. 0 means unrated.
    let rating: Int
    /// Lightroom-style color label — one of "", "red", "yellow", "green",
    /// "blue", "purple". Surfaces as the band-color around the card.
    let colorLabel: String
    /// Raw EXIF camera body string (e.g. "SONY ILCE-7RM3"). Empty when the
    /// file has no camera metadata. Carried on the summary so the grid's
    /// configurable "Camera" top-of-card stat slot renders without a
    /// per-video VideoMetadata roundtrip.
    let cameraModel: String
    /// Marketing-friendly camera name resolved by the daemon (e.g.
    /// "Sony a7R III"). Falls back to `cameraModel` when no mapping is
    /// known. UI uses this for display.
    let cameraDisplayName: String
    /// GPS latitude from embedded EXIF/metadata. 0.0 when absent. Carried
    /// on the summary so the grid card can show a location badge without a
    /// per-video VideoMetadata round-trip.
    let gpsLatitude: Double
    /// GPS longitude from embedded EXIF/metadata. 0.0 when absent.
    let gpsLongitude: Double
    /// Lens designation from the video's embedded XMP packet (`aux:Lens`).
    /// Empty when the file has no XMP. Surfaced on the summary so the grid
    /// can both sort by lens and display it in a configurable stat slot
    /// without a per-row VideoMetadata round-trip.
    let lensModel: String
    /// ISO from embedded XMP. 0 when absent.
    let iso: Int
    /// F-number from embedded XMP (e.g. 1.8). 0.0 when absent.
    let aperture: Double
    /// Exposure time in seconds from embedded XMP. 0.0 when absent.
    let exposureTimeS: Double
    /// Focal length in millimeters from embedded XMP. 0.0 when absent.
    let focalLengthMm: Double
    /// Full-resolution badge state set by the daemon's classifier.
    /// `.unspecified` (the default) renders no badge; the other two
    /// render the "Full" / "Not full" chip on the card.
    let fullResolution: FullResolutionStatus

    var isInGroup: Bool { !groupId.isEmpty && groupSize > 1 }
    var hasProxies: Bool { proxyCount > 0 }
    var isProxy: Bool { !proxyOf.isEmpty }
    var hasLocation: Bool { abs(gpsLatitude) > 1e-6 || abs(gpsLongitude) > 1e-6 }
    /// Path to open on double-click — preferred member if in a group, else this video.
    var openPath: String { groupPreferredPath.isEmpty ? path : groupPreferredPath }

    var resolution: String { "\(width)×\(height)" }

    var durationFormatted: String {
        let totalSeconds = durationMs / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }

    var sizeFormatted: String {
        let mb = Double(sizeBytes) / (1024 * 1024)
        if mb > 1024 {
            return String(format: "%.2f GB", mb / 1024)
        }
        return String(format: "%.2f MB", mb)
    }

    /// Return a copy of self with `rating` replaced — used by the
    /// view-model's optimistic update path so a single field change
    /// doesn't require re-fetching the whole row.
    func withRating(_ newRating: Int) -> VideoSummary {
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
            fullResolution: fullResolution
        )
    }

    /// Return a copy of self with `tags` replaced.
    func withTags(_ newTags: [String]) -> VideoSummary {
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
            fullResolution: fullResolution
        )
    }

    /// Return a copy of self with GPS coordinates replaced.
    func withLocation(latitude: Double, longitude: Double) -> VideoSummary {
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
            fullResolution: fullResolution
        )
    }

    /// Return a copy of self with `colorLabel` replaced.
    func withColorLabel(_ newLabel: String) -> VideoSummary {
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
            fullResolution: fullResolution
        )
    }
}

struct VideoMetadata: Identifiable {
    let id: String
    let filename: String
    let path: String
    let width: Int
    let height: Int
    let durationMs: Int
    let fps: Double
    let codecVideo: String
    let codecAudio: String
    let bitrateKbps: Int
    let sizeBytes: Int
    let colorSpace: String
    let hdr: Bool
    let audioChannels: Int
    let audioSampleRate: Int
    let creationDate: Int64
    let cameraModel: String
    /// Marketing-friendly camera name (e.g. "Sony a7R III" for an internal
    /// "SONY ILCE-7RM3"). Falls back to `cameraModel` verbatim when no
    /// mapping is known. UI compares the two: when they differ a
    /// toggleable 'i' affordance reveals the internal name on click.
    let cameraDisplayName: String
    let lensModel: String
    let gpsLat: Double
    let gpsLon: Double
    let gpsAltitude: Double
    let notes: String
    let tags: [String]
    let collections: [String]
    /// Lightroom-style 0..5 star rating mirrored from VideoSummary.
    let rating: Int
    /// Lightroom-style color label mirrored from VideoSummary.
    let colorLabel: String
    /// Photo-EXIF recovered from the video's embedded XMP packet. Each is
    /// "absent" in a domain-specific way: a zero numeric or empty string
    /// means the video didn't carry that field. The detail/inspector view
    /// hides absent rows so a video with no XMP doesn't show seven empty
    /// rows under EXIF.
    let iso: Int
    let aperture: Double
    let exposureTimeS: Double
    let focalLengthMm: Double
    let exposureMode: String
    let exposureProgram: String
    let whiteBalance: String
    /// Mirrors `VideoSummary.fullResolution` — the detail panel shows a
    /// "Resolution Status" row in addition to the card badge.
    let fullResolution: FullResolutionStatus

    var resolution: String { "\(width)×\(height)" }

    var durationFormatted: String {
        let totalSeconds = durationMs / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }

    var sizeFormatted: String {
        let mb = Double(sizeBytes) / (1024 * 1024)
        if mb > 1024 {
            return String(format: "%.2f GB", mb / 1024)
        }
        return String(format: "%.2f MB", mb)
    }

    var bitrateFormatted: String {
        if bitrateKbps > 1000 {
            return String(format: "%.2f Mbps", Double(bitrateKbps) / 1000)
        }
        return "\(bitrateKbps) kbps"
    }

    var creationDateFormatted: String {
        if creationDate == 0 { return "Unknown" }
        let date = Date(timeIntervalSince1970: TimeInterval(creationDate / 1000))
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}

struct Tag: Identifiable, Hashable {
    let id: String
    let name: String
    let color: String?
    let videoCount: Int64
}

struct Collection: Identifiable, Hashable {
    let id: String
    let name: String
    let isSmart: Bool
    var filterJson: String = ""
    let videoCount: Int64
}

struct SmartCollectionFilters {
    var camera: String = ""
    var lens: String = ""
    var codec: String = ""
    var captureYear: Int32 = 0
    var minRating: Int32 = 0
    var colorLabel: String = ""
    var tagIds: [String] = []

    func toJson() -> String {
        func esc(_ s: String) -> String { "\"\(s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\""))\"" }
        let tagsJson = tagIds.map { esc($0) }.joined(separator: ",")
        return #"{"camera":\#(esc(camera)),"lens":\#(esc(lens)),"codec":\#(esc(codec)),"captureYear":\#(captureYear),"minRating":\#(minRating),"colorLabel":\#(esc(colorLabel)),"tagIds":[\#(tagsJson)]}"#
    }

    static func from(json: String) -> SmartCollectionFilters? {
        guard !json.isEmpty else { return nil }
        func strVal(_ key: String) -> String {
            guard let r = json.range(of: "\"\(key)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
                                     options: .regularExpression) else { return "" }
            let matched = String(json[r])
            // Extract the value between the second pair of quotes.
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
        var tagIds: [String] = []
        if let ar = json.range(of: "\"tagIds\"\\s*:\\s*\\[([^\\]]*)\\]", options: .regularExpression) {
            let arrStr = String(json[ar])
            // Extract quoted strings inside the array.
            var scanning = arrStr
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
                if done { tagIds.append(val) }
                if let next = scanning.range(of: "\"") {
                    scanning = String(scanning[next.upperBound...])
                } else { break }
            }
        }
        return SmartCollectionFilters(camera: strVal("camera"), lens: strVal("lens"),
                                      codec: strVal("codec"), captureYear: intVal("captureYear"),
                                      minRating: intVal("minRating"), colorLabel: strVal("colorLabel"),
                                      tagIds: tagIds)
    }
}

struct LibraryLocation: Identifiable, Hashable {
    var id: String { path }
    let path: String
    let recursive: Bool
    let enabled: Bool
    let videoCount: Int64
    let lastScanned: Int64
}

/// Distinct values that can populate the top-bar filter dropdowns.
/// One row in the Camera Names editor. `isBuiltin` is true when the
/// daemon ships a curated mapping for `internalName`; `isCustom` is
/// true when the user has added or overridden a row in this catalog.
/// Both can be true at once — that's the "user replaced a built-in"
/// case, where `marketingName` carries the user's chosen string.
struct CameraNameMapping: Identifiable, Hashable {
    let internalName: String
    let marketingName: String
    let isBuiltin: Bool
    let isCustom: Bool

    /// Stable identity for SwiftUI List/ForEach. Internal name is the
    /// catalog-unique key for a mapping.
    var id: String { internalName }
}

struct FilterOptions: Equatable {
    var cameras: [String] = []
    /// Marketing-friendly names for each entry in `cameras`, same order
    /// and length. Empty when the server didn't supply any (older
    /// catalog/daemon) — the UI then falls back to `cameras` verbatim.
    var cameraDisplayNames: [String] = []
    var lenses: [String] = []
    var codecs: [String] = []
    var captureYears: [Int32] = []
}

/// Information about the catalog the backend currently has open. An empty
/// `path` means the daemon is running but no SQLite file is mounted — the
/// client must call `OpenCatalog` before issuing any other RPC.
struct CatalogInfo: Equatable {
    var path: String = ""
    var name: String = ""
    var videoCount: Int64 = 0
    var openedAtMs: Int64 = 0

    var isOpen: Bool { !path.isEmpty }

    static let closed = CatalogInfo()
}

/// A geotagged video — what the global-map view needs to render a pin.
/// Returned by the daemon's `ListVideosWithLocations` RPC.
struct VideoLocation: Identifiable, Equatable, Hashable {
    let id: String
    let filename: String
    let path: String
    let latitude: Double
    let longitude: Double
    let altitude: Double
    let hasThumbnail: Bool
}

/// A user-defined named place (e.g. "Home", "Yosemite Valley Visitor
/// Center"). The catalog stores a small list of these; clients resolve any
/// video's GPS into a name by picking the nearest entry within
/// `radiusMeters`. See [GridViewModel.nameForLocation].
struct NamedLocation: Identifiable, Equatable, Hashable {
    let id: String
    let name: String
    let latitude: Double
    let longitude: Double
    /// Resolution tolerance in meters. Default 250 — overridable per-row
    /// in the schema for future "Yellowstone-sized" entries.
    let radiusMeters: Double
    /// Unix ms (UTC). 0 if unknown.
    let createdAtMs: Int64
    let updatedAtMs: Int64
}

struct ScanProgress {
    let status: String
    let videosFound: Int
    let videosIndexed: Int
    let currentFile: String
    let progressPercent: Double
}

struct GroupInfo {
    let id: String
    let name: String
    let size: Int
    let preferredVideoId: String
}

// MARK: - Real-time catalog events

/// Mirror of the proto `CatalogEvent.Kind` so view-model code can switch
/// on a domain enum instead of an int32. Adding cases is non-breaking
/// because the repository falls back to `.unknown` for anything new the
/// server might emit.
enum CatalogEventKind {
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
}

struct CatalogEvent: Equatable {
    let kind: CatalogEventKind
    let videoId: String     // Empty for watcher-/scan-lifecycle events.
    let path: String        // The file that triggered it (best-effort).
    let atMs: Int64         // Server-side Unix milliseconds.
    let message: String     // Human-readable (filename for VideoRemoved, etc.)
    /// Set only on `.postIndex*` events; nil otherwise.
    var postIndex: PostIndexProgress? = nil
}

/// Payload for the `.postIndex*` catalog events — the daemon's background
/// proxy-detection / auto-grouping / camera-sensor / timelapse pass. This
/// work is CPU- and IO-heavy and used to be invisible to the UI; the grid
/// surfaces it in a background-activity panel so a long pass doesn't look
/// like the daemon has silently pegged a core.
struct PostIndexProgress: Equatable {
    /// Videos fully post-indexed in the current pass.
    let processed: Int64
    /// Expected total this pass; 0 when unknown.
    let total: Int64
    /// 0..100; 0 when `total` is unknown.
    let percent: Double
    /// Estimated seconds remaining; 0 when unknown.
    let etaSeconds: Int64
    /// Dominant activity: "grouping" | "proxies" | "sensors" | "tagging".
    let phase: String
    /// Last human-readable action, e.g. "linked a.mov → b.mov".
    let detail: String
}

/// Watcher knobs that govern the real-time scanner. Round-trip via
/// `GetWatchSettings` / `UpdateWatchSettings` to surface in the Preferences
/// dialog.
struct WatchSettings: Equatable {
    var enabled: Bool
    var writeSettleMs: Int64
    var pollIntervalMs: Int64

    static let `default` = WatchSettings(enabled: true, writeSettleMs: 5000, pollIntervalMs: 30000)
}

// MARK: - Lightroom-style color labels

/// Color labels mirror Adobe Lightroom's five-colour palette. Stored on
/// `VideoSummary.colorLabel` as the raw value (the empty string means "no
/// label"). The grid uses [.swatch] for the anchor card's band background
/// and [.dimmed] for unselected cards with the same label.
enum ColorLabel: String, CaseIterable, Identifiable, Hashable {
    case none = ""
    case red, yellow, green, blue, purple

    var id: String { rawValue }

    /// Initialise from the raw string on `VideoSummary.colorLabel`. Unknown
    /// values fall back to `.none` so the UI degrades gracefully if the
    /// server adds a colour we don't yet recognise.
    init(_ raw: String) {
        self = ColorLabel(rawValue: raw) ?? .none
    }

    /// Display name for menus and tooltips. "None" reads better in the
    /// right-click submenu than an empty string.
    var displayName: String {
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
    var swatch: Color {
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
    var dimmed: Color { swatch.opacity(0.45) }

    /// Mid-brightness variant used for secondary-selected (non-anchor)
    /// cards. Sits between [.swatch] and [.dimmed] so the user can tell
    /// at a glance which card is the anchor.
    var secondary: Color { swatch.opacity(0.72) }

    /// Keyboard shortcut digit that applies this label, or nil for
    /// labels with no shortcut. Matches Lightroom: 6/7/8/9 for the four
    /// primary colours; purple has no shortcut historically.
    var shortcutKey: Character? {
        switch self {
        case .red:    return "6"
        case .yellow: return "7"
        case .green:  return "8"
        case .blue:   return "9"
        case .purple, .none: return nil
        }
    }

    /// Map a keyboard digit back to a label, used by the global key handler.
    static func from(shortcut: Character) -> ColorLabel? {
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
enum GridStatKey: String, CaseIterable, Identifiable, Hashable {
    case none           = ""
    case filename
    case fileSize       = "file_size"
    case resolutionName = "resolution"    // "1080p", "4K", etc.
    case pixelDimensions = "pixel_dimensions"
    case duration
    case videoCodec     = "video_codec"
    case audioCodec     = "audio_codec"
    case fps
    case bitrate
    case cameraModel    = "camera_model"
    case lensModel      = "lens_model"
    case captureDate    = "capture_date"
    case captureYear    = "capture_year"

    var id: String { rawValue }

    var displayName: String {
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
        case .bitrate:          return "Bitrate"
        case .cameraModel:      return "Camera"
        case .lensModel:        return "Lens"
        case .captureDate:      return "Capture date"
        case .captureYear:      return "Capture year"
        }
    }

    /// Resolve this stat against a [VideoSummary]. Returns the display
    /// string for the card, or empty string if the underlying data is
    /// missing / inapplicable.
    func value(for video: VideoSummary) -> String {
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
}

/// Default top-slot configuration if the catalog has nothing stored yet.
/// Picks four stats that fit the Lightroom screenshot the user shared:
/// filename, file size, resolution shorthand, FPS.
let defaultGridTopSlots: [String] = [
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
enum LibraryFilterMode: String, CaseIterable, Identifiable, Hashable {
    case text, attribute, metadata, clear
    var id: String { rawValue }
    var displayName: String {
        switch self {
        case .text:      return "Text"
        case .attribute: return "Attribute"
        case .metadata:  return "Metadata"
        case .clear:     return "Clear"
        }
    }
}

/// One column in the metadata-mode browser. `key` is a canonical metadata
/// token ("" = not chosen yet); `value` is the selected facet token ("" = All).
struct MetadataColumn: Identifiable, Equatable {
    let id = UUID()
    var key: String = ""
    var value: String = ""
}

/// One selectable value within a metadata facet column.
struct FacetValue: Equatable, Hashable {
    let token: String
    let display: String
    let count: Int64
}

/// The available values for one metadata column (server-computed cascade).
struct MetadataFacetColumn: Equatable {
    let key: String
    let displayName: String
    let isNumeric: Bool
    let values: [FacetValue]
}

/// A metadata key the user can choose for a column.
struct MetadataKeyInfo: Equatable, Hashable {
    let key: String
    let displayName: String
    let isNumeric: Bool
}

/// Result of a GetMetadataFacets call: per-column values + the key picker set.
struct MetadataFacetsResult: Equatable {
    var columns: [MetadataFacetColumn] = []
    var availableKeys: [MetadataKeyInfo] = []
}

/// Default metadata columns shown before the user customizes the bar.
let defaultMetadataColumns: [MetadataColumn] = [
    MetadataColumn(key: "camera"),
    MetadataColumn(key: "lens"),
    MetadataColumn(key: "exposure"),
    MetadataColumn(key: "iso"),
]
