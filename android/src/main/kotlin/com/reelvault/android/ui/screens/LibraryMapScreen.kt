// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reelvault.android.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelvault.android.util.OsmConfig
import com.reelvault.android.viewmodel.GridViewModel
import com.reelvault.data.models.LocationFilterGroup
import com.reelvault.data.models.NamedLocation
import com.reelvault.data.models.VideoLocation
import com.reelvault.data.repository.VideoRepository
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// Tile sources
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Esri World Imagery satellite tile source. Mirrors the desktop's
 * ReelVaultSatelliteTileFactoryInfo — same endpoint, same tile path
 * `/{z}/{y}/{x}` (y before x, unlike OSM's default {z}/{x}/{y}).
 * Attribution: Esri, Maxar, Earthstar Geographics, and the GIS community.
 */
private val ESRI_SATELLITE_SOURCE = object : XYTileSource(
    "EsriWorldImagery",
    /* minZoom = */ 0,
    /* maxZoom = */ 19,
    /* tileSizePixels = */ 256,
    /* fileExtension = */ "",
    /* baseUrls = */ arrayOf(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
    ),
) {
    // ArcGIS tile path convention is /{z}/{y}/{x}, not OSMDroid's default /{z}/{x}/{y}.
    override fun getTileURLString(pMapTileIndex: Long): String =
        "${baseUrl}${MapTileIndex.getZoom(pMapTileIndex)}" +
            "/${MapTileIndex.getY(pMapTileIndex)}" +
            "/${MapTileIndex.getX(pMapTileIndex)}"
}

// ─────────────────────────────────────────────────────────────────────────────
// SharedPreferences persistence (mirrors desktop MapViewPrefs)
// ─────────────────────────────────────────────────────────────────────────────

private const val MAP_PREFS_NAME = "reelvault_map"
private const val PREF_SATELLITE = "satellite"

private fun loadSatellitePref(context: Context): Boolean =
    context.getSharedPreferences(MAP_PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_SATELLITE, false)

private fun saveSatellitePref(context: Context, value: Boolean) {
    context.getSharedPreferences(MAP_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_SATELLITE, value)
        .apply()
}

// ─────────────────────────────────────────────────────────────────────────────
// Location grouping (mirrors desktop GridViewModel.buildLocationFilterGroups and
// iOS GridViewModel.buildLocationFilterGroups)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Group geotagged [locs] into [LocationFilterGroup]s, exactly like the desktop
 * and iOS clients:
 *  1. Each [NamedLocation] claims every video within its radius (haversine).
 *  2. Remaining videos are bucketed at ~110 m precision (lat/lon to 3 decimals)
 *     and represented by their centroid.
 * Sorted by video count (desc), then label.
 */
private fun buildLocationFilterGroups(
    locs: List<VideoLocation>,
    named: List<NamedLocation>,
): List<LocationFilterGroup> {
    val groups = mutableListOf<LocationFilterGroup>()
    val claimed = HashSet<String>()
    for (n in named) {
        val members = locs.filter {
            it.id !in claimed &&
                haversineMeters(n.latitude, n.longitude, it.latitude, it.longitude) <= n.radiusMeters
        }
        if (members.isEmpty()) continue
        members.forEach { claimed += it.id }
        groups += LocationFilterGroup(
            label = n.name,
            latitude = n.latitude,
            longitude = n.longitude,
            radiusKm = n.radiusMeters / 1000.0,
            count = members.size,
            isNamed = true,
        )
    }
    locs.filter { it.id !in claimed }
        .groupBy { "%.3f,%.3f".format(it.latitude, it.longitude) }
        .forEach { (_, members) ->
            val cLat = members.sumOf { it.latitude } / members.size
            val cLon = members.sumOf { it.longitude } / members.size
            groups += LocationFilterGroup(
                label = "%.4f, %.4f".format(cLat, cLon),
                latitude = cLat,
                longitude = cLon,
                radiusKm = 0.2,
                count = members.size,
                isNamed = false,
            )
        }
    return groups.sortedWith(
        compareByDescending<LocationFilterGroup> { it.count }.thenBy { it.label.lowercase() }
    )
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6_371_000.0
    val toRad = Math.PI / 180.0
    val dLat = (lat2 - lat1) * toRad
    val dLon = (lon2 - lon1) * toRad
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1 * toRad) * cos(lat2 * toRad) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadius * c
}

// ─────────────────────────────────────────────────────────────────────────────
// Marker glyph: an accent circle with the video count and an optional place-name
// label below — drawn to a Bitmap so OSMDroid can use it as a Marker icon.
// Mirrors iOS ClusteredMapMarker / the desktop map markers.
// ─────────────────────────────────────────────────────────────────────────────

/** A rendered marker [drawable] plus the vertical anchor (0..1) that places the
 *  circle's centre — not the label — on the geographic point. */
private class MarkerGlyph(val drawable: Drawable, val anchorV: Float)

private fun buildMarkerGlyph(
    context: Context,
    count: Int,
    placeName: String?,
    accentArgb: Int,
): MarkerGlyph {
    val density = context.resources.displayMetrics.density
    fun px(dp: Float): Float = dp * density

    // Circle diameter mirrors iOS (18 / 26 / 34 dp).
    val diameter = px(if (count <= 1) 18f else if (count < 10) 26f else 34f)
    val strokeW = px(2f)
    val countTextSize = px(if (count >= 10) 12f else 11f)
    val labelTextSize = px(11f)
    val labelHPad = px(6f)
    val labelVPad = px(2f)
    val gap = px(3f)
    val shadowR = px(2f)
    val pad = shadowR + strokeW + px(1f)

    val label = placeName?.takeIf { it.isNotEmpty() }
        ?.let { if (it.length > 28) it.take(27) + "…" else it }

    val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentArgb
        style = Paint.Style.FILL
        setShadowLayer(shadowR, 0f, px(1f), android.graphics.Color.argb(110, 0, 0, 0))
    }
    val circleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = strokeW
    }
    val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = countTextSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = labelTextSize
        textAlign = Paint.Align.CENTER
    }
    val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }

    val labelFm = labelPaint.fontMetrics
    val labelTextH = labelFm.descent - labelFm.ascent
    val pillW = if (label != null) labelPaint.measureText(label) + labelHPad * 2 else 0f
    val pillH = if (label != null) labelTextH + labelVPad * 2 else 0f

    val contentW = maxOf(diameter, pillW)
    val width = (contentW + pad * 2).toInt().coerceAtLeast(1)
    val height = (pad * 2 + diameter + if (label != null) gap + pillH else 0f).toInt().coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val cx = width / 2f
    val circleCy = pad + diameter / 2f
    val radius = diameter / 2f - strokeW / 2f

    canvas.drawCircle(cx, circleCy, radius, circleFill)
    canvas.drawCircle(cx, circleCy, radius, circleStroke)

    if (count > 1) {
        val fm = countPaint.fontMetrics
        val baseline = circleCy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(count.toString(), cx, baseline, countPaint)
    }

    if (label != null) {
        val pillTop = pad + diameter + gap
        val rect = RectF(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH)
        val corner = pillH / 2f
        canvas.drawRoundRect(rect, corner, corner, labelBg)
        val baseline = pillTop + labelVPad - labelFm.ascent
        canvas.drawText(label, cx, baseline, labelPaint)
    }

    return MarkerGlyph(BitmapDrawable(context.resources, bitmap), circleCy / height)
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Map screen showing geotagged videos as OSMDroid markers.
 *
 * Matches iOS LibraryMapView: videos are clustered by location (named places +
 * ~110 m coordinate buckets) and each cluster is drawn as an accent circle with
 * its video count plus a place-name label when one is known. Tapping a marker
 * applies a geographic filter to the shared [grid] and pops back to the grid so
 * the user sees only the videos shot at that location.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryMapScreen(
    repository: VideoRepository,
    grid: GridViewModel,
    /** Pop back to the grid (used by a cluster tap, which also applies a geo
     *  facet filter). Distinct from the nav-arrow/system-back, which walk the
     *  session history. */
    onBack: () -> Unit,
    /** Session history back/forward (browser-style). The nav-arrow and system
     *  back walk the history; the forward chevron is enabled when [canGoForward]. */
    onHistoryBack: () -> Unit = {},
    onHistoryForward: () -> Unit = {},
    canGoForward: Boolean = false,
) {
    val accentArgb = MaterialTheme.colorScheme.primary.toArgb()
    val context = LocalContext.current

    // System back leaves the map through the session history (same as the
    // nav-arrow) so it can't strand a phantom grid entry.
    BackHandler { onHistoryBack() }
    // "Show on Map" focus request from the detail view, if any.
    val mapFocus by grid.mapFocus.collectAsStateWithLifecycle()

    // Satellite/standard toggle — initialised from SharedPreferences, persisted
    // when changed. rememberSaveable survives configuration changes (rotation).
    var isSatellite by rememberSaveable { mutableStateOf(loadSatellitePref(context)) }

    // Filtered locations come from the GridViewModel — they already honour the
    // active library filter (search, tags, rating, colour, attributes, etc.)
    // rather than the unfiltered listVideosWithLocations() RPC.
    val locations by grid.videoLocations.collectAsStateWithLifecycle()
    val isLoading by grid.isLoadingVideoLocations.collectAsStateWithLifecycle()
    // Gate the empty-state text on this flag so it doesn't flash before the
    // first load completes (mirrors the grid's hasLoadedOnce pattern).
    val hasLoadedLocationOnce by grid.hasLoadedLocationOnce.collectAsStateWithLifecycle()

    var named by remember { mutableStateOf<List<NamedLocation>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Trigger an immediate filtered-locations load when the map opens so the
    // pin set is fresh. The named locations are best-effort (just pin labels).
    LaunchedEffect(Unit) {
        try {
            named = runCatching { repository.listNamedLocations() }.getOrDefault(emptyList())
        } catch (e: Exception) {
            loadError = context.getString(R.string.map_load_failed, e.message ?: "")
        }
        // Kick a fresh filtered load (no debounce) so the pins reflect the
        // current grid filter immediately, not a possibly-stale cached value.
        grid.loadVideoLocationsFiltered()
    }

    val groups = remember(locations, named) { buildLocationFilterGroups(locations, named) }

    // Hold a reference so we can call onDetach when the composable leaves.
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            mapViewRef?.onDetach()
            // Drop any unconsumed "Show on Map" focus so it can't hijack a later
            // (normal) map open — e.g. if this visit ended on the empty/error
            // state, MapContent never composed to consume it.
            grid.clearMapFocus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.map_title)) },
                navigationIcon = {
                    IconButton(onClick = onHistoryBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    // Session forward chevron (back is the nav-arrow above).
                    IconButton(onClick = onHistoryForward, enabled = canGoForward) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(R.string.nav_history_forward),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                loadError != null -> Text(
                    text = loadError ?: stringResource(R.string.map_unknown_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )

                hasLoadedLocationOnce && locations.isEmpty() -> Text(
                    text = stringResource(R.string.map_no_geotagged),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )

                else -> MapContent(
                    groups = groups,
                    accentArgb = accentArgb,
                    focus = mapFocus,
                    isSatellite = isSatellite,
                    onFocusConsumed = { grid.clearMapFocus() },
                    onSelectGroup = { group ->
                        grid.setGeoLocationFilter(
                            latitude = group.latitude,
                            longitude = group.longitude,
                            radiusKm = group.radiusKm,
                            label = group.label,
                        )
                        onBack()
                    },
                    onMapViewCreated = { mapViewRef = it },
                )
            }

            // Satellite / standard toggle — bottom-start corner, above the OSM
            // attribution that sits at the very bottom of the map.
            if (!isLoading && loadError == null) {
                FloatingActionButton(
                    onClick = {
                        val next = !isSatellite
                        isSatellite = next
                        saveSatellitePref(context, next)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 48.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(
                        imageVector = if (isSatellite) Icons.Filled.Map else Icons.Filled.Satellite,
                        contentDescription = stringResource(
                            if (isSatellite) R.string.map_switch_to_standard
                            else R.string.map_switch_to_satellite
                        ),
                    )
                }
            }

            // Small geotagged-count badge, top-right corner.
            if (!isLoading && loadError == null && locations.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.map_geotagged_count, locations.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            shape = MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Map content (separated so it only composes after data is ready)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MapContent(
    groups: List<LocationFilterGroup>,
    accentArgb: Int,
    focus: Triple<Double, Double, Double>?,
    isSatellite: Boolean,
    onFocusConsumed: () -> Unit,
    onSelectGroup: (LocationFilterGroup) -> Unit,
    onMapViewCreated: (MapView) -> Unit,
) {
    // Plain (non-snapshot) holder so positioning the camera doesn't trigger
    // recomposition. We auto-fit to all markers exactly once; a "Show on Map"
    // focus always wins and recenters on its point.
    val positioned = remember { booleanArrayOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // OSMDroid is configured once at app startup (OsmConfig); this guard
            // is cheap and just ensures it ran before the first MapView inflates.
            OsmConfig.ensureInitialized(ctx)

            MapView(ctx).apply {
                setTileSource(if (isSatellite) ESRI_SATELLITE_SOURCE else TileSourceFactory.MAPNIK)
                setUseDataConnection(true)
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                onMapViewCreated(this)
            }
        },
        update = { mapView ->
            // Flip the tile source when the toggle state changes. OSMDroid
            // clears its in-memory tile cache automatically when setTileSource
            // is called, so the map immediately re-fetches tiles for the new
            // source.
            val wantedSource = if (isSatellite) ESRI_SATELLITE_SOURCE else TileSourceFactory.MAPNIK
            if (mapView.tileProvider.tileSource?.name() != wantedSource.name()) {
                mapView.setTileSource(wantedSource)
            }
            mapView.overlays.clear()

            if (groups.isEmpty()) {
                mapView.invalidate()
                return@AndroidView
            }

            val folder = FolderOverlay()
            for (group in groups) {
                val glyph = buildMarkerGlyph(
                    context = mapView.context,
                    count = group.count,
                    placeName = if (group.isNamed) group.label else null,
                    accentArgb = accentArgb,
                )
                val marker = Marker(mapView).apply {
                    position = GeoPoint(group.latitude, group.longitude)
                    icon = glyph.drawable
                    setAnchor(Marker.ANCHOR_CENTER, glyph.anchorV)
                    title = if (group.isNamed) group.label else mapView.context.resources.getQuantityString(
                        R.plurals.map_marker_video_count, group.count, group.count,
                    )
                    infoWindow = null   // Tapping filters + navigates; no bubble.
                    setOnMarkerClickListener { _, _ ->
                        onSelectGroup(group)
                        true
                    }
                }
                folder.add(marker)
            }
            mapView.overlays.add(folder)

            // Position the camera once the map has a valid layout. A "Show on
            // Map" focus recenters on its point; otherwise auto-fit all markers
            // (only the first time, so later marker refreshes don't yank the view).
            mapView.post {
                val f = focus
                if (f != null) {
                    mapView.controller.setZoom(15.0)
                    mapView.controller.setCenter(GeoPoint(f.first, f.second))
                    positioned[0] = true
                    onFocusConsumed()
                } else if (!positioned[0]) {
                    if (groups.size == 1) {
                        mapView.controller.setZoom(14.0)
                        mapView.controller.setCenter(GeoPoint(groups[0].latitude, groups[0].longitude))
                    } else {
                        val lats = groups.map { it.latitude }
                        val lons = groups.map { it.longitude }
                        val box = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
                        // borderSize = 80px gives comfortable padding around the outermost pins.
                        mapView.zoomToBoundingBox(box, /* animated = */ true, /* borderSize = */ 80)
                    }
                    positioned[0] = true
                }
            }

            mapView.invalidate()
        },
    )
}
