// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.screens

import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reelvault.data.models.VideoLocation
import com.reelvault.data.repository.VideoRepository
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.InfoWindow

// Radius (degrees) used to decide whether two markers are close enough to cluster.
private const val CLUSTER_RADIUS_DEG = 0.005

// ─────────────────────────────────────────────────────────────────────────────
// Clustering
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Lightweight cluster: a representative centroid [GeoPoint] and all
 * [VideoLocation]s that were merged into it.
 */
private data class MarkerCluster(
    val center: GeoPoint,
    val members: List<VideoLocation>,
)

/**
 * Group [locations] into clusters using single-linkage nearest-centroid
 * assignment. O(N*C) — sufficient for a typical catalog of a few thousand
 * geotagged videos.
 */
private fun clusterLocations(
    locations: List<VideoLocation>,
    radiusDeg: Double = CLUSTER_RADIUS_DEG,
): List<MarkerCluster> {
    data class MutableCluster(val members: MutableList<VideoLocation> = mutableListOf()) {
        val centroidLat: Double get() = members.map { it.latitude }.average()
        val centroidLon: Double get() = members.map { it.longitude }.average()
        fun distanceTo(lat: Double, lon: Double): Double {
            val dLat = centroidLat - lat
            val dLon = centroidLon - lon
            return Math.sqrt(dLat * dLat + dLon * dLon)
        }
    }

    val clusters = mutableListOf<MutableCluster>()
    for (loc in locations) {
        val nearest = clusters.minByOrNull { it.distanceTo(loc.latitude, loc.longitude) }
        if (nearest != null && nearest.distanceTo(loc.latitude, loc.longitude) <= radiusDeg) {
            nearest.members.add(loc)
        } else {
            clusters.add(MutableCluster(mutableListOf(loc)))
        }
    }
    return clusters.map { c ->
        MarkerCluster(
            center = GeoPoint(c.centroidLat, c.centroidLon),
            members = c.members.toList(),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// InfoWindow (built from plain Android Views — no bonuspack dependency)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Minimal bubble InfoWindow built entirely from Android [LinearLayout] +
 * [TextView]s so that we have no dependency on osmdroid-bonuspack.
 *
 * [title] is shown in bold; [body] below it in smaller text. An optional
 * [onTap] fires when the user taps anywhere on the bubble.
 */
private class BubbleInfoWindow(
    mapView: MapView,
    private val title: String,
    private val body: String,
    private val onTap: (() -> Unit)? = null,
) : InfoWindow(buildBubbleView(mapView.context, title, body), mapView) {

    override fun onOpen(item: Any?) {
        mView.setOnClickListener {
            close()
            onTap?.invoke()
        }
    }

    override fun onClose() { /* nothing */ }
}

/** Build the pop-up bubble view programmatically. */
private fun buildBubbleView(
    context: android.content.Context,
    title: String,
    body: String,
): android.view.View {
    val dp = context.resources.displayMetrics.density

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            (12 * dp).toInt(),
            (8 * dp).toInt(),
            (12 * dp).toInt(),
            (8 * dp).toInt(),
        )
        setBackgroundColor(Color.argb(230, 30, 30, 30))
        elevation = 8 * dp
    }

    val titleView = TextView(context).apply {
        text = title
        setTextColor(Color.WHITE)
        textSize = 13f
        setTypeface(null, android.graphics.Typeface.BOLD)
        gravity = Gravity.START
    }
    container.addView(titleView)

    if (body.isNotEmpty()) {
        val bodyView = TextView(context).apply {
            text = body
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(0, (4 * dp).toInt(), 0, 0)
            gravity = Gravity.START
        }
        container.addView(bodyView)
    }

    return container
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Map screen showing geotagged videos as OSMDroid markers.
 *
 * Matches iOS LibraryMapView. Nearby markers are merged into clusters so
 * videos shot at the same location don't pile up. Tapping a single-video
 * marker shows a filename bubble and navigates to that video on a second tap;
 * tapping a cluster bubble shows the member filenames.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryMapScreen(
    repository: VideoRepository,
    onVideoSelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // Configure OSMDroid: user-agent must be set before tiles are requested.
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = "ReelVault/1.0 (android)"
        }
    }

    var locations by remember { mutableStateOf<List<VideoLocation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            locations = repository.listVideosWithLocations()
        } catch (e: Exception) {
            loadError = "Failed to load map data: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    // Hold a reference so we can call onDetach when the composable leaves.
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    DisposableEffect(Unit) {
        onDispose { mapViewRef?.onDetach() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
                    text = loadError ?: "Unknown error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )

                else -> MapContent(
                    locations = locations,
                    onVideoSelected = onVideoSelected,
                    onMapViewCreated = { mapViewRef = it },
                )
            }

            // Small geotagged-count badge, top-right corner.
            if (!isLoading && loadError == null && locations.isNotEmpty()) {
                Text(
                    text = "${locations.size} geotagged",
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
    locations: List<VideoLocation>,
    onVideoSelected: (String) -> Unit,
    onMapViewCreated: (MapView) -> Unit,
) {
    val clusters = remember(locations) { clusterLocations(locations) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // Load persisted OSMDroid prefs (tile cache path etc.) then create the view.
            Configuration.getInstance().load(
                ctx,
                ctx.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE),
            )

            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)   // OpenStreetMap
                setUseDataConnection(true)
                setMultiTouchControls(true)
                isTilesScaledToDpi = true

                // Dismiss any open InfoWindow on an empty-space tap.
                overlays.add(
                    MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            InfoWindow.closeAllInfoWindowsOn(this@apply)
                            return false
                        }
                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    }),
                )

                onMapViewCreated(this)
            }
        },
        update = { mapView ->
            // Remove all marker overlays added by a previous update, keeping
            // the MapEventsOverlay at index 0.
            if (mapView.overlays.size > 1) {
                mapView.overlays.subList(1, mapView.overlays.size).clear()
            }

            if (clusters.isEmpty()) {
                mapView.invalidate()
                return@AndroidView
            }

            val folder = FolderOverlay()

            for (cluster in clusters) {
                val marker = Marker(mapView).apply {
                    position = cluster.center
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                    if (cluster.members.size == 1) {
                        val video = cluster.members[0]
                        title = video.filename
                        // Single video: tap bubble -> navigate to detail.
                        infoWindow = BubbleInfoWindow(
                            mapView = mapView,
                            title = video.filename,
                            body = "(%.4f, %.4f)".format(video.latitude, video.longitude),
                            onTap = { onVideoSelected(video.id) },
                        )
                    } else {
                        val count = cluster.members.size
                        title = "$count videos"
                        val preview = cluster.members.take(5).joinToString("\n") { it.filename } +
                                if (count > 5) "\n... and ${count - 5} more" else ""
                        // Cluster: show member list, no direct navigation.
                        infoWindow = BubbleInfoWindow(
                            mapView = mapView,
                            title = "$count videos",
                            body = preview,
                        )
                    }

                    setOnMarkerClickListener { m, _ ->
                        InfoWindow.closeAllInfoWindowsOn(mapView)
                        m.showInfoWindow()
                        true
                    }
                }
                folder.add(marker)
            }

            mapView.overlays.add(folder)

            // Zoom to fit all markers once the map has a valid layout.
            mapView.post {
                if (clusters.size == 1) {
                    mapView.controller.setZoom(14.0)
                    mapView.controller.setCenter(clusters[0].center)
                } else {
                    val lats = clusters.map { it.center.latitude }
                    val lons = clusters.map { it.center.longitude }
                    val box = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
                    // borderSize = 80px gives comfortable padding around the outermost pins.
                    mapView.zoomToBoundingBox(box, /* animated = */ true, /* borderSize = */ 80)
                }
            }

            mapView.invalidate()
        },
    )
}
