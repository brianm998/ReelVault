// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import Foundation

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

    var isInGroup: Bool { !groupId.isEmpty && groupSize > 1 }
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
    let lensModel: String
    let gpsLat: Double
    let gpsLon: Double
    let gpsAltitude: Double
    let notes: String
    let tags: [String]
    let collections: [String]

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
    let videoCount: Int64
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
struct FilterOptions: Equatable {
    var cameras: [String] = []
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
}

struct CatalogEvent: Equatable {
    let kind: CatalogEventKind
    let videoId: String     // Empty for watcher-/scan-lifecycle events.
    let path: String        // The file that triggered it (best-effort).
    let atMs: Int64         // Server-side Unix milliseconds.
    let message: String     // Human-readable (filename for VideoRemoved, etc.)
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
