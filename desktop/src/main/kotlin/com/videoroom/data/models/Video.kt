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
    /** Lightroom-style 0..5 star rating. 0 means unrated. */
    val rating: Int = 0,
    /** Lightroom-style colour label — "" | red | yellow | green | blue | purple.
     *  Drives the band-tint around the card in the grid. */
    val colorLabel: String = "",
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
    /**
     * Marketing-friendly camera name (e.g. "Sony a7R III" for an internal
     * "SONY ILCE-7RM3"). Falls back to [cameraModel] verbatim when the
     * core has no mapping. UI compares the two: when they differ a
     * toggleable ⓘ affordance reveals the internal name on click.
     */
    val cameraDisplayName: String = "",
    val lensModel: String = "",
    val gpsLatitude: Double = 0.0,
    val gpsLongitude: Double = 0.0,
    val gpsAltitude: Double = 0.0,
    val tags: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val notes: String = "",
    val volumeId: String = "",
    val isOnline: Boolean = true,
    /** Lightroom-style 0..5 star rating mirrored from VideoSummary. */
    val rating: Int = 0,
    /** Lightroom-style colour label mirrored from VideoSummary. */
    val colorLabel: String = "",
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
    /**
     * Marketing-friendly names for each entry in [cameras], same order
     * and length. Empty when the server didn't supply any (older
     * catalog/daemon) — the UI then falls back to [cameras] verbatim.
     */
    val cameraDisplayNames: List<String> = emptyList(),
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

// --- Lightroom-style color labels ---

/**
 * Color labels mirror Adobe Lightroom's five-colour palette. Stored on
 * [VideoSummary.colorLabel] as the raw string value; "" means "no label".
 * The card uses [swatch] for the anchor card's band-background and [dimmed]
 * for unselected cards that still carry this label.
 */
enum class ColorLabel(
    val raw: String,
    val displayName: String,
    /** Saturated background colour used when this card is the anchor. */
    val swatch: androidx.compose.ui.graphics.Color,
    /** Keyboard digit that applies this label, or null. Purple has no key. */
    val shortcutDigit: Char? = null,
) {
    None  ("",       "None",   androidx.compose.ui.graphics.Color(0xFF2E2E2E), null),
    Red   ("red",    "Red",    androidx.compose.ui.graphics.Color(0xFFC75050), '6'),
    Yellow("yellow", "Yellow", androidx.compose.ui.graphics.Color(0xFFD1B233), '7'),
    Green ("green",  "Green",  androidx.compose.ui.graphics.Color(0xFF4DA653), '8'),
    Blue  ("blue",   "Blue",   androidx.compose.ui.graphics.Color(0xFF3873C7), '9'),
    Purple("purple", "Purple", androidx.compose.ui.graphics.Color(0xFF8C52BD), null);

    /** Toned-down variant used by unselected cards that still carry this label. */
    val dimmed: androidx.compose.ui.graphics.Color get() = swatch.copy(alpha = 0.45f)

    /** Mid-brightness variant for secondary-selected (non-anchor) cards. */
    val secondary: androidx.compose.ui.graphics.Color get() = swatch.copy(alpha = 0.72f)

    companion object {
        /** Look up by the raw wire-string. Unknown values fall back to [None]. */
        fun from(raw: String): ColorLabel = values().firstOrNull { it.raw == raw } ?: None

        /** Map a keyboard digit to a colour (or null if no mapping). */
        fun fromShortcut(c: Char): ColorLabel? = values().firstOrNull { it.shortcutDigit == c }
    }
}

// --- Grid card stat slots (Lightroom-style top-of-card) ---

/**
 * Stable keys naming every stat that can appear in one of the four top-of-
 * card slots. The string value is exchanged with the daemon as part of
 * `GridSettings.top_slots`, so it must match what the macOS client emits.
 */
enum class GridStatKey(val raw: String, val displayName: String) {
    None           ("",                  "(empty)"),
    Filename       ("filename",          "Filename"),
    FileSize       ("file_size",         "File size"),
    ResolutionName ("resolution",        "Resolution"),
    PixelDimensions("pixel_dimensions",  "Pixel dimensions"),
    Duration       ("duration",          "Duration"),
    VideoCodec     ("video_codec",       "Video codec"),
    AudioCodec     ("audio_codec",       "Audio codec"),
    Fps            ("fps",               "FPS"),
    Bitrate        ("bitrate",           "Bitrate"),
    CameraModel    ("camera_model",      "Camera"),
    LensModel      ("lens_model",        "Lens"),
    CaptureDate    ("capture_date",      "Capture date"),
    CaptureYear    ("capture_year",      "Capture year");

    /** Resolve this stat against a [VideoSummary] into the display string. */
    fun valueFor(video: VideoSummary): String = when (this) {
        None             -> ""
        Filename         -> video.filename
        FileSize         -> formatBytes(video.sizeBytes)
        ResolutionName   -> resolutionLabel(video.height)
        PixelDimensions  -> if (video.width > 0 && video.height > 0) "${video.width}×${video.height}" else ""
        Duration         -> video.durationFormatted
        VideoCodec       -> video.codecVideo
        AudioCodec       -> video.codecAudio
        Fps              -> if (video.fps > 0) "%.0f fps".format(video.fps) else ""
        Bitrate          -> ""  // VideoSummary doesn't carry bitrate today
        CameraModel,
        LensModel        -> ""  // Both live on VideoMetadata, not the summary
        CaptureDate      -> if (video.creationDate > 0) {
            val instant = java.time.Instant.ofEpochMilli(video.creationDate)
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(java.time.ZoneId.systemDefault())
                .format(instant)
        } else ""
        CaptureYear      -> if (video.creationDate > 0) {
            val instant = java.time.Instant.ofEpochMilli(video.creationDate)
            java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                .year.toString()
        } else ""
    }

    companion object {
        fun fromRaw(raw: String): GridStatKey = values().firstOrNull { it.raw == raw } ?: None

        private fun formatBytes(b: Long): String = when {
            b >= 1024L * 1024 * 1024 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
            b >= 1024L * 1024        -> "%.2f MB".format(b / (1024.0 * 1024))
            b > 0                    -> "%.2f KB".format(b / 1024.0)
            else                     -> ""
        }

        private fun resolutionLabel(h: Int): String = when {
            h <= 0     -> ""
            h < 480    -> "SD"
            h < 576    -> "480p"
            h < 720    -> "576p"
            h < 1080   -> "720p"
            h < 1440   -> "1080p"
            h < 2160   -> "1440p"
            h < 4320   -> "4K"
            else       -> "8K"
        }
    }
}

/** Default grid top-slot configuration before the server replies with the
 *  saved value. Matches the macOS client so a freshly-created catalog looks
 *  identical regardless of which client opens it first. */
val defaultGridTopSlots: List<String> = listOf(
    GridStatKey.Filename.raw,
    GridStatKey.FileSize.raw,
    GridStatKey.ResolutionName.raw,
    GridStatKey.Fps.raw,
)
