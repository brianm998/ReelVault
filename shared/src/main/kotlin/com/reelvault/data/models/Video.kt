// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.models

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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
    /** Raw EXIF camera body string (e.g. "SONY ILCE-7RM3"). Empty when
     *  the file has no camera metadata. Carried on the summary so the
     *  grid's "Camera" top-of-card stat slot renders without a per-video
     *  VideoMetadata roundtrip. */
    val cameraModel: String = "",
    /** Marketing-friendly camera name resolved by the daemon (e.g.
     *  "Sony a7R III"). Falls back to [cameraModel] when no mapping
     *  exists. UI uses this for display. */
    val cameraDisplayName: String = "",
    /** GPS latitude from embedded EXIF/metadata. 0.0 when absent. Carried
     *  on the summary so the grid card can show a location badge without a
     *  per-video VideoMetadata round-trip. */
    val gpsLatitude: Double = 0.0,
    /** GPS longitude from embedded EXIF/metadata. 0.0 when absent. */
    val gpsLongitude: Double = 0.0,
    /** Lens designation from the video's embedded XMP packet (`aux:Lens`).
     *  Empty when the file has no XMP. Surfaced on the summary so the grid
     *  can both sort by lens and display it in a configurable stat slot
     *  without a per-row VideoMetadata round-trip. */
    val lensModel: String = "",
    /** ISO from embedded XMP. 0 when absent. */
    val iso: Int = 0,
    /** F-number from embedded XMP (e.g. 1.8). 0.0 when absent. */
    val aperture: Double = 0.0,
    /** Exposure time in seconds from embedded XMP. 0.0 when absent. */
    val exposureTimeS: Double = 0.0,
    /** Focal length in millimeters from embedded XMP. 0.0 when absent. */
    val focalLengthMm: Double = 0.0,
    /** Overall stream bitrate in kbps (proto `VideoSummary.bitrate` / 1000).
     *  0 when unknown. Surfaced on the summary — like [iso]/[aperture]/etc. —
     *  so the grid's configurable "Bitrate" stat slot renders without a
     *  per-video VideoMetadata round-trip. */
    val bitrateKbps: Int = 0,
    /** Full-resolution badge state set by the daemon's classifier.
     *  [FullResolutionStatus.Unspecified] (the default) renders no badge;
     *  the other two render the "Full" / "Not full" chip on the card. */
    val fullResolution: FullResolutionStatus = FullResolutionStatus.Unspecified,
    /** False when the daemon found the file missing at the last scan — moved,
     *  renamed, or on an unmounted drive. The grid dims such cards and shows an
     *  "offline" badge instead of only failing when the user hits play. */
    val isOnline: Boolean = true,
    /** ffprobe's nb_frames for the video stream (proto `VideoSummary.frame_count`).
     *  0 when the container didn't report one — [frameCountFormatted] then falls
     *  back to estimating from duration × fps. Surfaced on the summary so the
     *  grid's "Frame count" stat slot renders without a per-video round-trip. */
    val frameCount: Long = 0,
) {
    val isInGroup: Boolean get() = groupId.isNotEmpty() && groupSize > 1
    val hasProxies: Boolean get() = proxyCount > 0
    val isProxy: Boolean get() = proxyOf.isNotEmpty()
    val hasLocation: Boolean get() = gpsLatitude != 0.0 || gpsLongitude != 0.0
    /** True when the file carries an audio track. Drives the card's audio
     *  badge and the "has audio" attribute filter. */
    val hasAudio: Boolean get() = codecAudio.isNotEmpty()
    /** Orientation buckets for the orientation attribute filter. Square
     *  (width == height) counts as landscape; unknown dimensions are neither. */
    val isPortrait: Boolean get() = height > width
    val isLandscape: Boolean get() = width > 0 && width >= height
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
    /** Exact frame count ("12,345") when the container reported one, else an
     *  estimate from duration × fps ("~12,345"), or null when neither is known.
     *  Mirrors [VideoMetadata.frameCountFormatted] so the card and the detail
     *  panel show the same value. */
    val frameCountFormatted: String? get() {
        if (frameCount > 0) return "%,d".format(frameCount)
        if (fps > 0 && durationMs > 0) {
            val est = Math.round(durationMs / 1000.0 * fps)
            if (est > 0) return "~%,d".format(est)
        }
        return null
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
    /** Photo-EXIF recovered from the video's embedded XMP packet. Each is
     *  "absent" in a domain-specific way: a zero numeric or empty string
     *  means the video didn't carry that field. The detail/inspector view
     *  hides absent rows so a video with no XMP doesn't show seven empty
     *  rows under EXIF. */
    val iso: Int = 0,
    val aperture: Double = 0.0,
    val exposureTimeS: Double = 0.0,
    val focalLengthMm: Double = 0.0,
    val exposureMode: String = "",
    val exposureProgram: String = "",
    val whiteBalance: String = "",
    /** Mirrors [VideoSummary.fullResolution] — the detail panel shows
     *  a richer "Resolution status" row in addition to the card badge. */
    val fullResolution: FullResolutionStatus = FullResolutionStatus.Unspecified,
    /** Total frame count (ffprobe nb_frames), or 0 when the container didn't
     *  report one — [frameCountFormatted] then estimates from duration × fps. */
    val frameCount: Long = 0,
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
    /** Frame count for display: the exact stored count when known, otherwise a
     *  "~" estimate from duration × fps, or null when neither is available. */
    val frameCountFormatted: String? get() {
        if (frameCount > 0) return "%,d".format(frameCount)
        if (fps > 0 && durationMs > 0) {
            val est = Math.round(durationMs / 1000.0 * fps)
            if (est > 0) return "~%,d".format(est)
        }
        return null
    }
    val bitrateFormatted: String get() = "${bitrate / 1000} kbps"
    val sizeFormatted: String get() {
        return when {
            sizeBytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
            sizeBytes >= 1024 * 1024 -> String.format("%.2f MB", sizeBytes / (1024.0 * 1024.0))
            else -> String.format("%.2f KB", sizeBytes / 1024.0)
        }
    }
    /** Capture date as year-month-day only. The time-of-day from a video's
     *  creation timestamp is frequently wrong (cameras store local wall-clock
     *  without a timezone), so we deliberately show no finer granularity than
     *  the day. */
    val creationDateFormatted: String get() {
        return if (creationDate > 0) {
            val instant = Instant.ofEpochMilli(creationDate)
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
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

/** Saved filter criteria for a smart collection. */
/** One metadata-column constraint captured in a smart collection: a metadata
 *  [key] (camera, lens, codec, year, iso, exposure, … — any registry key) and
 *  its selected facet [values] (OR-ed). Generalises the old fixed
 *  camera/lens/codec/year fields so a smart collection reproduces ANY metadata
 *  column the user had narrowed. */
data class SmartCollectionColumn(
    val key: String,
    val values: List<String>,
    /** "is not" column — matches videos lacking any of [values]. */
    val negate: Boolean = false,
)

/** Stable lowercase tokens for the tri-state attribute filters in smart-
 *  collection JSON. Kept identical across clients so a collection saved on one
 *  client parses on the other (macOS uses the same "any"/"yes"/"no" strings). */
private fun AttributeFilterState.toToken(): String = when (this) {
    AttributeFilterState.Any -> "any"
    AttributeFilterState.Yes -> "yes"
    AttributeFilterState.No -> "no"
}

private fun attrFromToken(s: String): AttributeFilterState = when (s) {
    "yes" -> AttributeFilterState.Yes
    "no" -> AttributeFilterState.No
    else -> AttributeFilterState.Any
}

/** Stable lowercase tokens for the orientation filter in smart-collection JSON,
 *  kept identical across clients (macOS uses the same strings). */
private fun OrientationFilterState.toToken(): String = when (this) {
    OrientationFilterState.Any -> "any"
    OrientationFilterState.Portrait -> "portrait"
    OrientationFilterState.Landscape -> "landscape"
}

private fun orientationFromToken(s: String): OrientationFilterState = when (s) {
    "portrait" -> OrientationFilterState.Portrait
    "landscape" -> OrientationFilterState.Landscape
    else -> OrientationFilterState.Any
}

data class SmartCollectionFilters(
    /** Arbitrary metadata-column constraints (replaces the old fixed
     *  camera/lens/codec/year scalars). The "location" virtual key is never
     *  stored here — geo lives in [geoLat]/[geoLon]/[geoRadiusKm]. */
    val columns: List<SmartCollectionColumn> = emptyList(),
    val minRating: Int = 0,
    val colorLabel: String = "",
    val tagIds: List<String> = emptyList(),
    /** Full-text search box ("keyword") query. Previously dropped, which made a
     *  smart collection saved from a search come back empty. */
    val searchQuery: String = "",
    /** Map proximity filter: keep videos within [geoRadiusKm] of (geoLat,
     *  geoLon). A radius of 0 means "no geo constraint" (lat/lon 0,0 is a
     *  legitimate coordinate, so radius — never 0 for a real filter — is the
     *  presence flag). */
    val geoLat: Double = 0.0,
    val geoLon: Double = 0.0,
    val geoRadiusKm: Double = 0.0,
    /** Library-folder selection (left panel). Empty = all folders. A smart
     *  collection can pin itself to one or more library locations. */
    val locationPaths: List<String> = emptyList(),
    /** Tri-state attribute filters mirrored from the Library Filter's
     *  "attribute" mode. [AttributeFilterState.Any] = unconstrained. */
    val hasLocation: AttributeFilterState = AttributeFilterState.Any,
    val hasKeywords: AttributeFilterState = AttributeFilterState.Any,
    val hasProxies: AttributeFilterState = AttributeFilterState.Any,
    val fullResolution: AttributeFilterState = AttributeFilterState.Any,
    val hasAudio: AttributeFilterState = AttributeFilterState.Any,
    val orientation: OrientationFilterState = OrientationFilterState.Any,
) {
    /** True when a map-proximity constraint is active. */
    val hasGeo: Boolean get() = geoRadiusKm > 0.0

    fun toJson(): String = buildString {
        append("{")
        // Each column is encoded as "key=v1v2" — a flat string so the
        // existing string-array parser round-trips it without a nested-array
        // JSON parser. '=' never appears in a metadata key; values keep their
        // own separator.
        append("\"columns\":[")
        columns.forEachIndexed { i, c ->
            if (i > 0) append(",")
            // A leading "!" on the key marks an "is not" column.
            val prefix = if (c.negate) "!" else ""
            append((prefix + c.key + "=" + c.values.joinToString(METADATA_VALUE_SEPARATOR)).jsonStr())
        }
        append("]")
        append(",\"minRating\":$minRating")
        append(",\"colorLabel\":${colorLabel.jsonStr()}")
        append(",\"searchQuery\":${searchQuery.jsonStr()}")
        append(",\"tagIds\":[${tagIds.joinToString(",") { it.jsonStr() }}]")
        append(",\"geoLat\":$geoLat")
        append(",\"geoLon\":$geoLon")
        append(",\"geoRadiusKm\":$geoRadiusKm")
        append(",\"locationPaths\":[${locationPaths.joinToString(",") { it.jsonStr() }}]")
        append(",\"hasLocation\":${hasLocation.toToken().jsonStr()}")
        append(",\"hasKeywords\":${hasKeywords.toToken().jsonStr()}")
        append(",\"hasProxies\":${hasProxies.toToken().jsonStr()}")
        append(",\"fullResolution\":${fullResolution.toToken().jsonStr()}")
        append(",\"hasAudio\":${hasAudio.toToken().jsonStr()}")
        append(",\"orientation\":${orientation.toToken().jsonStr()}")
        append("}")
    }

    companion object {
        fun fromJson(json: String): SmartCollectionFilters {
            fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")
            fun str(key: String): String {
                val m = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)
                return m?.groupValues?.get(1)?.let { unescape(it) } ?: ""
            }
            fun int(key: String): Int =
                Regex(""""$key"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            fun dbl(key: String): Double =
                Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""").find(json)
                    ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            fun strArray(key: String): List<String> =
                Regex(""""$key"\s*:\s*\[([^\]]*)]""").find(json)?.groupValues?.get(1)
                    ?.let { Regex(""""((?:[^"\\]|\\.)*)"""").findAll(it).map { m -> unescape(m.groupValues[1]) }.toList() }
                    ?: emptyList()

            val columns = strArray("columns").mapNotNull { s ->
                val eq = s.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val rawKey = s.substring(0, eq)
                val negate = rawKey.startsWith("!")
                val key = if (negate) rawKey.substring(1) else rawKey
                if (key.isEmpty()) return@mapNotNull null
                val vals = s.substring(eq + 1).split(METADATA_VALUE_SEPARATOR).filter { it.isNotEmpty() }
                SmartCollectionColumn(key, vals, negate)
            }
            // Backward-compat: smart collections saved before the generic-column
            // format stored camera/lens/codec/captureYear scalars.
            val legacy: List<SmartCollectionColumn> = if (columns.isNotEmpty()) emptyList() else buildList {
                fun legacyVals(s: String) = s.split(METADATA_VALUE_SEPARATOR).filter { it.isNotEmpty() }
                str("camera").takeIf { it.isNotEmpty() }?.let { add(SmartCollectionColumn("camera", legacyVals(it))) }
                str("lens").takeIf { it.isNotEmpty() }?.let { add(SmartCollectionColumn("lens", legacyVals(it))) }
                str("codec").takeIf { it.isNotEmpty() }?.let { add(SmartCollectionColumn("codec", legacyVals(it))) }
                int("captureYear").takeIf { it != 0 }?.let { add(SmartCollectionColumn("year", listOf(it.toString()))) }
            }
            return SmartCollectionFilters(
                columns = columns.ifEmpty { legacy },
                minRating = int("minRating"),
                colorLabel = str("colorLabel"),
                searchQuery = str("searchQuery"),
                tagIds = strArray("tagIds"),
                geoLat = dbl("geoLat"),
                geoLon = dbl("geoLon"),
                geoRadiusKm = dbl("geoRadiusKm"),
                locationPaths = strArray("locationPaths"),
                hasLocation = attrFromToken(str("hasLocation")),
                hasKeywords = attrFromToken(str("hasKeywords")),
                hasProxies = attrFromToken(str("hasProxies")),
                fullResolution = attrFromToken(str("fullResolution")),
                hasAudio = attrFromToken(str("hasAudio")),
                orientation = orientationFromToken(str("orientation")),
            )
        }

        private fun String.jsonStr() = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
}

data class LibraryLocation(
    val path: String,
    val recursive: Boolean = true,
    val enabled: Boolean = true,
    val videoCount: Long = 0,
    val lastScanned: Long = 0,
    /** True when this (recursive) location has at least one subdirectory
     *  containing videos — i.e. the library panel should offer to expand it. */
    val hasSubdirectories: Boolean = false
)

/**
 * One immediate child directory of a library location (or another
 * subdirectory), as reported by the daemon's `ListSubdirectories` RPC. The
 * tree is derived from indexed video paths, so a subdirectory appears only
 * when it (recursively) contains videos.
 */
data class Subdirectory(
    val path: String,
    val videoCount: Long,
    /** Whether this directory has child directories containing videos of its
     *  own — i.e. it is itself expandable. */
    val hasSubdirectories: Boolean
)

/**
 * A single visible row of the library panel's location tree: a library
 * location or one of its (transitive) subdirectories, flattened in display
 * order with its nesting [depth]. The panel renders this list directly so the
 * tree stays compatible with the virtualized list and the existing
 * range/multi-select machinery.
 */
data class LibraryRow(
    val path: String,
    val depth: Int,
    val videoCount: Long,
    /** Show a disclosure chevron (the dir has expandable children). */
    val isExpandable: Boolean,
    val isExpanded: Boolean,
    /** False for subdirectory rows — only top-level library locations carry
     *  rescan / remove affordances and the full-path sublabel. */
    val isTopLevel: Boolean
)

/** Distinct values that can populate the top-bar filter dropdowns. */
/**
 * One row in the Camera Names editor. [isBuiltin] is true when the
 * daemon ships a curated mapping for [internalName]; [isCustom] is
 * true when the user has added or overridden a row in this catalog.
 * Both can be true at once — that's the "user replaced a built-in"
 * case, where [marketingName] carries the user's chosen string.
 */
data class CameraNameMapping(
    val internalName: String,
    val marketingName: String,
    val isBuiltin: Boolean,
    val isCustom: Boolean,
)

/**
 * One row in the Lens Names editor. [rawName] is the lens string exactly
 * as stored in the catalog (the value ReelVault extracts from
 * `exifEX:LensModel`); [alias] is the display name shown for it. Unlike
 * cameras there is no built-in table, so [alias] equals [rawName] unless
 * the user set a custom override ([isCustom]). [inCatalog] is false for a
 * stale override whose lens no longer appears in any video.
 */
data class LensNameMapping(
    val rawName: String,
    val alias: String,
    val isCustom: Boolean,
    val inCatalog: Boolean,
)

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
    /** A background post-index pass (proxy/group/sensor) began. */
    PostIndexStarted,
    /** Periodic progress for the in-flight post-index pass. */
    PostIndexProgress,
    /** The post-index pass drained; clients clear the activity panel. */
    PostIndexCompleted,
    /** An unpaired LAN device asked to pair; show an allow/dismiss banner. */
    PairingRequested,
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
    /** Set only on POST_INDEX_* events; null otherwise. */
    val postIndex: PostIndexProgress? = null,
)

/**
 * Payload for the POST_INDEX_* catalog events — the daemon's background
 * proxy-detection / auto-grouping / camera-sensor / timelapse pass. This
 * work is CPU- and IO-heavy and used to be invisible to the UI; the grid
 * surfaces it in a background-activity panel so a long pass doesn't look
 * like the daemon has silently pegged a core.
 */
data class PostIndexProgress(
    /** Videos fully post-indexed in the current pass. */
    val processed: Long,
    /** Expected total this pass; 0 when unknown. */
    val total: Long,
    /** 0..100; 0 when [total] is unknown. */
    val percent: Double,
    /** Estimated seconds remaining; 0 when unknown. */
    val etaSeconds: Long,
    /** Dominant activity: "grouping" | "proxies" | "sensors" | "tagging". */
    val phase: String,
    /** Last human-readable action, e.g. "linked a.mov -> b.mov". */
    val detail: String,
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
 * The card uses [swatchArgb] for the anchor card's band-background colour.
 * Platforms add their own extension functions for dimmed/secondary variants.
 */
enum class ColorLabel(
    val raw: String,
    val displayName: String,
    /** Saturated background colour as a raw ARGB value packed into a Long. */
    val swatchArgb: Long,
    /** Keyboard digit that applies this label, or null. Purple has no key. */
    val shortcutDigit: Char? = null,
) {
    None  ("",       "None",   0xFF2E2E2EL, null),
    Red   ("red",    "Red",    0xFFC75050L, '6'),
    Yellow("yellow", "Yellow", 0xFFD1B233L, '7'),
    Green ("green",  "Green",  0xFF4DA653L, '8'),
    Blue  ("blue",   "Blue",   0xFF3873C7L, '9'),
    Purple("purple", "Purple", 0xFF8C52BDL, null);

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
    FrameCount     ("frame_count",       "Frame count"),
    Bitrate        ("bitrate",           "Bitrate"),
    CameraModel    ("camera_model",      "Camera"),
    LensModel      ("lens_model",        "Lens"),
    CaptureDate    ("capture_date",      "Capture date"),
    CaptureYear    ("capture_year",      "Capture year"),
    Iso            ("iso",               "ISO"),
    Aperture       ("aperture",          "Aperture"),
    ExposureTime   ("exposure_time",     "Exposure"),
    FocalLength    ("focal_length",      "Focal length"),
    Location       ("location",          "Location");

    /**
     * Resolve this stat against a [VideoSummary] into the display string.
     *
     * [placeNameFor] resolves a (latitude, longitude) to a registered
     * place-name, or null when none is within range — only consulted for the
     * [Location] slot. When absent (or it returns null) the raw coordinates
     * are shown as "(lat, lon)", matching the detail panel's order.
     */
    fun valueFor(
        video: VideoSummary,
        placeNameFor: ((Double, Double) -> String?)? = null,
    ): String = when (this) {
        None             -> ""
        Filename         -> video.filename
        FileSize         -> formatBytes(video.sizeBytes)
        ResolutionName   -> resolutionLabel(video.height)
        PixelDimensions  -> if (video.width > 0 && video.height > 0) "${video.width}×${video.height}" else ""
        Duration         -> video.durationFormatted
        VideoCodec       -> video.codecVideo
        AudioCodec       -> video.codecAudio
        Fps              -> if (video.fps > 0) "%.0f fps".format(video.fps) else ""
        FrameCount       -> video.frameCountFormatted ?: ""
        Bitrate          -> if (video.bitrateKbps <= 0) ""
            else if (video.bitrateKbps >= 1000) "%.1f Mbps".format(video.bitrateKbps / 1000.0)
            else "${video.bitrateKbps} kbps"
        CameraModel      -> video.cameraDisplayName.ifEmpty { video.cameraModel }
        LensModel        -> video.lensModel
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
        Iso              -> if (video.iso > 0) "ISO ${video.iso}" else ""
        Aperture         -> if (video.aperture > 0) "f/%.1f".format(video.aperture) else ""
        ExposureTime     -> formatExposureTime(video.exposureTimeS)
        FocalLength      -> if (video.focalLengthMm > 0) "%.0f mm".format(video.focalLengthMm) else ""
        Location         -> if (video.hasLocation) {
            // Prefer a registered place-name; fall back to raw "(lat, lon)".
            placeNameFor?.invoke(video.gpsLatitude, video.gpsLongitude)
                ?: "(%.4f, %.4f)".format(video.gpsLatitude, video.gpsLongitude)
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

        /** Format an EXIF exposure time. Sub-second exposures render as
         *  "1/Nth" with N rounded to the nearest standard shutter step
         *  (60/125/250/500/1000/2000/4000), matching how a photographer
         *  reads them. Anything >= 1 s renders as "X.X s". */
        fun formatExposureTime(seconds: Double): String {
            if (seconds <= 0.0) return ""
            return if (seconds >= 1.0) {
                "%.1f s".format(seconds)
            } else {
                val denom = (1.0 / seconds).let { d ->
                    // Snap to a tidy nearest integer; for very fast shutters
                    // the floating reconstruction is rarely exact (1/4000
                    // round-trips through f64 as 4000.000...).
                    d.roundToInt()
                }
                "1/$denom"
            }
        }
    }
}

/**
 * Mirror of proto `FullResolutionStatus`. The daemon's classifier
 * decides whether a video appears to be at its camera's native sensor
 * resolution (Full), at a non-standard non-native resolution
 * suggesting a derived/exported variant (NotFull), or in a state we
 * can't classify (Unspecified — unknown camera or a common video
 * standard like UHD/FHD). Clients render a badge for Full and NotFull;
 * Unspecified gets no badge.
 */
enum class FullResolutionStatus(val wire: Int) {
    Unspecified(0),
    Full(1),
    NotFull(2);

    companion object {
        fun fromWire(value: Int): FullResolutionStatus =
            values().firstOrNull { it.wire == value } ?: Unspecified
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

// --- Library Filter (the bar below the top bar) ---

/**
 * Which editor the Library Filter bar is showing. Under COMBINE semantics the
 * Text/Attribute/Metadata filters all stay applied at once; this only chooses
 * which one is visible. [Clear] is a momentary action (reset everything), not a
 * resting mode — the view model snaps back to [Text] after a clear.
 *
 * There is no standalone "Location" mode: a video's place is just another kind
 * of metadata, surfaced as the [LOCATION_METADATA_KEY] field inside [Metadata].
 */
enum class LibraryFilterMode { Text, Attribute, Metadata, Clear }

/** One selectable entry in the Library Filter's "Location" metadata field
 *  (see [LOCATION_METADATA_KEY]): a named place or an unnamed coordinate
 *  cluster, with how many catalog videos sit there. Picking one applies a
 *  geographic proximity filter centred on it. */
data class LocationFilterGroup(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    /** Proximity radius (km) to filter by when this entry is chosen. */
    val radiusKm: Double,
    val count: Int,
    val isNamed: Boolean,
)

/**
 * Tri-state presence toggle for a Library Filter "attribute" (video has a known
 * location / keywords / proxies, or is full resolution). [Any] applies no
 * constraint; [Yes] keeps only videos that have the attribute; [No] keeps only
 * those that don't. Maps 1:1 to the proto `AttributeFilter`.
 */
enum class AttributeFilterState { Any, Yes, No;
    /** Cycle Any -> Yes -> No -> Any for a single click-through control. */
    fun next(): AttributeFilterState = when (this) {
        Any -> Yes
        Yes -> No
        No -> Any
    }
}

/**
 * Tri-state video-orientation filter for the Library Filter's "attribute" mode.
 * [Any] applies no constraint; [Portrait] keeps videos taller than wide;
 * [Landscape] keeps those at least as wide as tall (square counts as
 * landscape). Sent to the daemon as an "orientation" metadata filter (value
 * "portrait" / "landscape"), so it needs no dedicated proto field.
 */
enum class OrientationFilterState { Any, Portrait, Landscape }

/**
 * One column in the Library Filter's "metadata" mode. [key] is a canonical
 * metadata token (see the core's metadata_keys registry); "" means "not chosen
 * yet" (a placeholder that prompts the key picker). [values] are the selected
 * facet tokens, OR-ed together; an empty set means "All" (no constraint from
 * this column). [anchor] is the value a range-select (shift-click) extends
 * from — the last value picked by a plain or toggle click.
 */
data class MetadataColumn(
    val key: String = "",
    val values: Set<String> = emptySet(),
    val anchor: String = "",
    /** When true the column matches videos that do NOT have any of [values]
     *  ("is not"); the default false is the plain "is" match. */
    val negate: Boolean = false,
)

/** One selectable value within a metadata facet column. */
data class FacetValue(val token: String, val display: String, val count: Long)

/** The available values for one metadata column, as computed by the server. */
data class FacetColumn(
    val key: String,
    val displayName: String,
    val isNumeric: Boolean,
    val values: List<FacetValue>,
)

/** A metadata key the user can pick for a column. */
data class MetadataKeyInfo(val key: String, val displayName: String, val isNumeric: Boolean)

/** Canonical key of the "Location" metadata field. Unlike the registry-backed
 *  keys (camera, lens, ...) this one is synthesised entirely client-side: its
 *  facet is the catalog's known places ([LocationFilterGroup]) and selecting a
 *  value applies a geographic proximity filter via `setLocationFilter` rather
 *  than a `MetadataFilter`. The daemon doesn't know this key — it returns an
 *  empty facet for it and ignores it as a filter — so it's never sent as one. */
const val LOCATION_METADATA_KEY = "location"

/** Generic (key, value) metadata filter sent to the daemon. [value] may carry
 *  several selected tokens joined by [METADATA_VALUE_SEPARATOR]; the core splits
 *  on it and OR-matches the parts. */
data class MetadataFilter(val key: String, val value: String)

/** The wire metadata filters that encode the has-audio and orientation attribute
 *  choices. Unlike the four older attribute filters (location / keywords /
 *  proxies / full-res) these have no dedicated proto field — they ride in
 *  [MetadataFilter]s under the special keys "has_audio" and "orientation", which
 *  the daemon's `build_filter_clauses` recognises (matching codec_audio and
 *  width/height respectively). Appended to the column-derived filters in every
 *  ListVideos request path so both the grid and smart-collection counts honour
 *  them. */
fun derivedAttributeMetadataFilters(
    hasAudio: AttributeFilterState,
    orientation: OrientationFilterState,
): List<MetadataFilter> = buildList {
    when (hasAudio) {
        AttributeFilterState.Yes -> add(MetadataFilter("has_audio", "yes"))
        AttributeFilterState.No -> add(MetadataFilter("has_audio", "no"))
        AttributeFilterState.Any -> {}
    }
    when (orientation) {
        OrientationFilterState.Portrait -> add(MetadataFilter("orientation", "portrait"))
        OrientationFilterState.Landscape -> add(MetadataFilter("orientation", "landscape"))
        OrientationFilterState.Any -> {}
    }
}

/** Separator joining a metadata column's multiple selected facet tokens into a
 *  single [MetadataFilter.value] over the wire. ASCII Unit Separator (0x1F),
 *  which never appears in real metadata values. Must match the core's
 *  `METADATA_VALUE_SEPARATOR`. */
const val METADATA_VALUE_SEPARATOR = ""

/** Leading marker on a [MetadataFilter.value] that flips the column from "is" to
 *  "is not". ASCII Record Separator (0x1E); must match the core's
 *  `METADATA_NEGATE_PREFIX`. */
val METADATA_NEGATE_PREFIX: String = 30.toChar().toString()

/** Result of a GetMetadataFacets call: per-column values + the key picker set. */
data class MetadataFacetsResult(
    val columns: List<FacetColumn> = emptyList(),
    val availableKeys: List<MetadataKeyInfo> = emptyList(),
)

/** Default metadata columns shown before the user customizes the bar. */
val defaultMetadataColumns: List<MetadataColumn> = listOf(
    MetadataColumn("camera"),
    MetadataColumn("lens"),
    MetadataColumn("exposure"),
    MetadataColumn("iso"),
)
