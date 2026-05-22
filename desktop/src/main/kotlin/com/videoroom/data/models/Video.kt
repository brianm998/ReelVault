// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.data.models

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class VideoSummary(
    val id: String,
    val filename: String,
    val path: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val codecVideo: String,
    val codecAudio: String,
    val fps: Double,
    val sizeBytes: Long,
    val indexedAt: Long,
    val creationDate: Long,
    val tags: List<String> = emptyList(),
    val hasThumbnail: Boolean = false,
    // Group (stack) info
    val groupId: String = "",
    val groupSize: Int = 1,
    val groupPreferredId: String = "",
    val groupPreferredPath: String = "",
    // Proxy info. `proxyCount` drives the small "P×N" badge on the
    // card; `proxyOf` non-empty marks this row as a proxy of another
    // video (the grid hides such rows under their source unless
    // expanded); `playableNatively` is the server's verdict on whether
    // this video fits under the configured max-native-height.
    val proxyCount: Int = 0,
    val proxyOf: String = "",
    val playableNatively: Boolean = true,
) {
    val isInGroup: Boolean get() = groupId.isNotEmpty() && groupSize > 1
    val hasProxies: Boolean get() = proxyCount > 0
    val isProxy: Boolean get() = proxyOf.isNotEmpty()
    /// Path that should be opened when user double-clicks; falls back to own path
    val openPath: String get() = if (groupPreferredPath.isNotEmpty()) groupPreferredPath else path

    val resolution: String get() = "${width}x${height}"
    val durationFormatted: String get() {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return if (minutes > 0) {
            String.format("%d:%02d", minutes, secs)
        } else {
            String.format("%ds", secs)
        }
    }
    val sizeMB: Double get() = sizeBytes / (1024.0 * 1024.0)
}

data class VideoMetadata(
    val id: String,
    val filename: String,
    val path: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
    val bitrate: Long,
    val codecVideo: String,
    val colorSpace: String,
    val hdr: Boolean,
    val codecAudio: String,
    val audioChannels: Int,
    val audioSampleRate: Int,
    val creationDate: Long,
    val modificationDate: Long,
    val indexedAt: Long,
    val cameraModel: String = "",
    val lensModel: String = "",
    val gpsLatitude: Double = 0.0,
    val gpsLongitude: Double = 0.0,
    val gpsAltitude: Double = 0.0,
    val tags: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val notes: String = "",
    val volumeId: String = "",
    val isOnline: Boolean = true
) {
    val resolution: String get() = "$width x $height"
    val durationFormatted: String get() {
        val seconds = durationMs / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
            minutes > 0 -> String.format("%d:%02d", minutes, secs)
            else -> String.format("%ds", secs)
        }
    }
    val bitrateFormatted: String get() = "${bitrate / 1000} kbps"
    val sizeFormatted: String get() {
        return when {
            sizeBytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
            sizeBytes >= 1024 * 1024 -> String.format("%.2f MB", sizeBytes / (1024.0 * 1024.0))
            else -> String.format("%.2f KB", sizeBytes / 1024.0)
        }
    }
    val creationDateFormatted: String get() {
        return if (creationDate > 0) {
            val instant = Instant.ofEpochMilli(creationDate)
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
            formatter.format(instant)
        } else {
            "Unknown"
        }
    }
}

data class Tag(
    val id: String,
    val name: String,
    val color: String = "",
    val videoCount: Long = 0
)

data class Collection(
    val id: String,
    val name: String,
    val isSmart: Boolean = false,
    val filterJson: String = "",
    val videoCount: Long = 0
)

data class LibraryLocation(
    val path: String,
    val recursive: Boolean = true,
    val enabled: Boolean = true,
    val videoCount: Long = 0,
    val lastScanned: Long = 0
)

/** Distinct values that can populate the top-bar filter dropdowns. */
data class FilterOptions(
    val cameras: List<String> = emptyList(),
    val lenses: List<String> = emptyList(),
    val codecs: List<String> = emptyList(),
    val captureYears: List<Int> = emptyList()
)

/**
 * The catalog the backend currently has open. An [isOpen] of `false` means
 * the daemon is running but no SQLite file is mounted yet; the client must
 * call `OpenCatalog` before issuing any other RPC.
 */
data class CatalogInfo(
    val path: String = "",
    val name: String = "",
    val videoCount: Long = 0,
    val openedAtMs: Long = 0
) {
    val isOpen: Boolean get() = path.isNotEmpty()

    companion object {
        val Closed = CatalogInfo()
    }
}

/**
 * A geotagged video — what the global-map view needs to render a pin. The
 * backend's `ListVideosWithLocations` RPC returns one of these per video
 * with known GPS.
 */
data class VideoLocation(
    val id: String,
    val filename: String,
    val path: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val hasThumbnail: Boolean = false,
)

/**
 * A user-defined named place (e.g. "Home", "Yosemite Valley Visitor
 * Center"). The catalog stores a small list of these; clients resolve any
 * video's GPS into a name by picking the nearest entry within
 * [radiusMeters]. See `GridViewModel.nameForLocation`.
 */
data class NamedLocation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** Resolution tolerance in meters. Default 250 — overridable per-row
     *  in the schema for future "Yellowstone-sized" entries. */
    val radiusMeters: Double = 250.0,
    /** Unix ms (UTC). 0 if unknown. */
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
)

// --- Real-time catalog events ---

/**
 * Mirror of proto `CatalogEvent.Kind`. View-model code switches on this
 * domain enum instead of the int32 wire value so adding kinds to the
 * proto is non-breaking (unknown values map to [Unknown]).
 */
enum class CatalogEventKind {
    Unknown,
    VideoAdded,
    VideoModified,
    VideoRemoved,
    WatcherStarted,
    WatcherDisabled,
    ScanStarted,
    ScanCompleted,
}

data class CatalogEvent(
    val kind: CatalogEventKind,
    /** Empty for watcher-/scan-lifecycle events. */
    val videoId: String,
    /** The file that triggered the event (best-effort). */
    val path: String,
    /** Server-side Unix milliseconds. */
    val atMs: Long,
    /** Human-readable note (filename for VideoRemoved, etc.). */
    val message: String,
)

/**
 * Watcher knobs that govern the real-time scanner. Round-trip via
 * `GetWatchSettings` / `UpdateWatchSettings` to surface in the Preferences
 * dialog.
 */
data class WatchSettings(
    val enabled: Boolean,
    val writeSettleMs: Long,
    val pollIntervalMs: Long,
) {
    companion object {
        val Default = WatchSettings(enabled = true, writeSettleMs = 5_000, pollIntervalMs = 30_000)
    }
}
