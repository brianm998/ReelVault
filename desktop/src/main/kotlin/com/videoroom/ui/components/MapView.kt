// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import org.jxmapviewer.JXMapViewer
import org.jxmapviewer.input.CenterMapListener
import org.jxmapviewer.input.PanKeyListener
import org.jxmapviewer.input.PanMouseInputListener
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor
import org.jxmapviewer.painter.Painter
import org.jxmapviewer.viewer.DefaultTileFactory
import org.jxmapviewer.viewer.GeoPosition
import org.jxmapviewer.viewer.TileFactoryInfo
import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JSlider

/**
 * Visual weight for a pin. PRIMARY is the user's active selection (drawn
 * larger, accent-colored); SECONDARY is a re-usable known location pulled
 * from the catalog (drawn smaller, gray); NAMED is a user-defined place
 * with a name permanently visible next to the marker (teal, with label).
 * The [ClusterPainter] keeps the three pools separate so the candidate
 * never gets folded into a cluster of "everything else".
 */
enum class MapPinStyle { PRIMARY, SECONDARY, NAMED }

/**
 * One pin on the map: a video (or cluster of videos) at a GPS coordinate.
 * Used by both the LocationPicker (single editable pin + optional re-usable
 * existing locations) and the global map (many read-only pins that cluster
 * on zoom).
 */
data class MapPin(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    /** Number of videos this pin represents — 1 for a single video, more
     *  when a cluster painter has folded several together. */
    val count: Int = 1,
    /** Label shown on hover; e.g. the filename for single pins. */
    val label: String = "",
    val style: MapPinStyle = MapPinStyle.PRIMARY,
)

/**
 * Compose-Desktop wrapper around JXMapViewer2 (OpenStreetMap tiles). Exposes:
 *   * pan + scroll-to-zoom (built into JXMapViewer)
 *   * a list of [pins] rendered as numbered circles, with simple grid-based
 *     clustering at low zooms so a thousand pins doesn't drown the map
 *   * an [onMapClick] callback giving the (lat, lon) the user clicked
 *   * an [onPinClick] callback when the user clicks an individual pin
 *
 * We embed the Swing component via [SwingPanel]; Compose hands it native
 * input events. Tile fetching is async (JXMapViewer manages its own thread
 * pool) and tiles are cached on disk by jxmapviewer under
 * `~/.jxmapviewer2/`.
 */
@Composable
fun MapView(
    pins: List<MapPin>,
    /** Initial map center; updated only when this value changes between recompositions. */
    initialCenter: Pair<Double, Double> = 0.0 to 0.0,
    initialZoom: Int = 7,
    /** Fired when the user clicks (or drags onto) the map at the given lat/lon. */
    onMapClick: ((latitude: Double, longitude: Double) -> Unit)? = null,
    /** Fired when the user clicks a pin (or cluster). For clusters [MapPin.count]
     *  is > 1 and you'll probably want to zoom in rather than treat it as a leaf. */
    onPinClick: ((MapPin) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Hold the live JXMapViewer reference so we can re-set its pins as the
    // composition's `pins` argument changes. Wrapped in a remember{} keyed on
    // nothing so the same instance survives recompositions.
    val mapHolder = remember { MapHolder() }

    Box(modifier = modifier.fillMaxSize()) {
        SwingPanel(
            modifier = Modifier.fillMaxSize(),
            factory = {
                // OpenStreetMap's tile policy requires a non-default User-Agent
                // and rejects Java's default `Java/<version>` UA — so JXMapViewer
                // out of the box renders blank-white because every tile HTTP
                // request returns 403. Setting this property before the first
                // URLConnection identifies VideoRoom and unblocks tile fetching.
                installOsmUserAgentOnce()

                val viewer = JXMapViewer().apply {
                    tileFactory = DefaultTileFactory(VideoRoomOsmTileFactoryInfo())
                    isOpaque = true
                    zoom = initialZoom
                    addressLocation = GeoPosition(initialCenter.first, initialCenter.second)
                }
                // Standard pan + zoom interactions shipped with JXMapViewer.
                val panner = PanMouseInputListener(viewer)
                viewer.addMouseListener(panner)
                viewer.addMouseMotionListener(panner)
                viewer.addMouseListener(CenterMapListener(viewer))
                viewer.addMouseWheelListener(ZoomMouseWheelListenerCursor(viewer))
                viewer.addKeyListener(PanKeyListener(viewer))

                // Click-to-act: if the click hits a pin, fire onPinClick;
                // otherwise fire onMapClick with the geographic coordinate.
                viewer.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        // Ignore drags-that-ended-as-clicks (PanMouseInputListener
                        // handles those; mouseClicked still fires for true clicks).
                        if (e.button != MouseEvent.BUTTON1) return
                        val v = mapHolder.viewer ?: return
                        // Hit-test against the rendered pins in pixel space.
                        val hit = mapHolder.lastRenderedPins.firstOrNull { rendered ->
                            val dx = rendered.screenX - e.x
                            val dy = rendered.screenY - e.y
                            dx * dx + dy * dy <= PIN_HIT_RADIUS_PX * PIN_HIT_RADIUS_PX
                        }
                        if (hit != null) {
                            onPinClick?.invoke(hit.pin)
                            return
                        }
                        if (onMapClick != null) {
                            val pos = v.tileFactory.pixelToGeo(
                                java.awt.geom.Point2D.Double(
                                    e.x.toDouble() + v.viewportBounds.x,
                                    e.y.toDouble() + v.viewportBounds.y,
                                ),
                                v.zoom,
                            )
                            onMapClick.invoke(pos.latitude, pos.longitude)
                        }
                    }
                })

                // Custom painter handles clustering + numbered circles.
                viewer.overlayPainter = ClusterPainter(mapHolder)
                mapHolder.viewer = viewer
                mapHolder.pins = pins

                // Vertical zoom slider, hosted as a *child* of the
                // JXMapViewer. We can't use a Compose `Slider` overlay here:
                // SwingPanel hosts a heavyweight AWT component, and Compose
                // Multiplatform 1.6.x can't paint lightweight Compose UI on
                // top of a heavyweight peer in the same window. Adding the
                // JSlider directly inside the JXMapViewer (a JPanel under
                // the hood) gets us correct layering for free — Swing's
                // default `paintChildren` runs after `paintComponent`, so
                // the slider draws on top of the map tiles automatically.
                val zoomSlider = JSlider(JSlider.VERTICAL, ZOOM_MIN, ZOOM_MAX, viewer.zoom).apply {
                    // Top of the slider = close-in detail (low JXMapViewer zoom number).
                    // `inverted=true` puts the min value at the top.
                    inverted = true
                    isOpaque = false
                    toolTipText = "Zoom: drag up for close-in detail, down for a wider view."
                }
                // Two-way sync: slider drags update the viewer, mouse-wheel /
                // double-click zoom on the viewer update the slider.
                zoomSlider.addChangeListener {
                    if (viewer.zoom != zoomSlider.value) viewer.zoom = zoomSlider.value
                }
                viewer.addPropertyChangeListener("zoom") { evt ->
                    val z = evt.newValue as? Int ?: return@addPropertyChangeListener
                    if (zoomSlider.value != z) zoomSlider.value = z
                }

                // Attach the slider to the viewer with absolute positioning.
                // We override `doLayout()` instead of using a
                // ComponentListener so the bounds get reapplied on every
                // layout pass — Swing's interop with Compose doesn't always
                // fire ComponentEvent.RESIZED for the initial sizing.
                viewer.layout = null
                viewer.add(zoomSlider)
                // doLayout can't be overridden after construction on the
                // existing viewer instance — instead position the slider
                // from a ComponentListener (any resize-driven layout) AND
                // post an invokeLater so the slider is in place for the
                // first paint even if no resize event ever fires.
                fun reapplySliderBounds() {
                    val sliderWidth = 28
                    val sliderHeight = (viewer.height - 40).coerceAtLeast(120)
                    zoomSlider.setBounds(
                        (viewer.width - sliderWidth - 12).coerceAtLeast(0),
                        ((viewer.height - sliderHeight) / 2).coerceAtLeast(0),
                        sliderWidth,
                        sliderHeight,
                    )
                }
                reapplySliderBounds()
                viewer.addComponentListener(object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent) {
                        reapplySliderBounds()
                    }
                })
                // Belt-and-suspenders: post a one-shot bounds update once
                // Swing has finished the first layout pass. SwingPanel
                // sometimes sizes the viewer through a path that doesn't
                // emit ComponentEvent.RESIZED on this exact widget, leaving
                // the slider stranded at 0×0 until the first user-driven
                // resize. invokeLater after construction guarantees the
                // slider is positioned for the initial paint.
                javax.swing.SwingUtilities.invokeLater { reapplySliderBounds() }
                viewer
            },
            update = {
                // The factory returns a JLayeredPane wrapping the viewer; the
                // viewer is mapHolder.viewer (set in factory). Push updated
                // pins through there.
                mapHolder.pins = pins
                mapHolder.viewer?.repaint()
            },
        )
    }
}

/** Closest the slider lets you zoom in (most detail). Roughly < 1 km across. */
private const val ZOOM_MIN = 2
/** Widest the slider lets you zoom out (whole world). */
private const val ZOOM_MAX = 17

/** Mutable holder shared between the Compose closures and the painter. */
private class MapHolder {
    var viewer: JXMapViewer? = null
    var pins: List<MapPin> = emptyList()
    /** Pin → on-screen pixel position, snapshotted by the painter each draw
     *  so the click handler can hit-test without recomputing. */
    var lastRenderedPins: List<RenderedPin> = emptyList()
}

private data class RenderedPin(val pin: MapPin, val screenX: Int, val screenY: Int)

/** Pixel radius around a pin center that counts as a click. */
private const val PIN_HIT_RADIUS_PX = 14

/** Cluster cell size in pixels — pins inside the same cell are merged. */
private const val CLUSTER_CELL_PX = 60

/**
 * Renders all pins, clustering them by a fixed pixel-grid so dense areas
 * collapse into single numbered circles when zoomed out. Cluster threshold
 * scales naturally because grid cells are in pixel space, not lat/lon.
 *
 * Pin styles render to two independent layers:
 *   * SECONDARY pins (catalog's known re-usable locations) cluster among
 *     themselves and draw first in a neutral gray. Painted first so they
 *     sit underneath everything else.
 *   * PRIMARY pins (the candidate selection, or the global map's video
 *     pins) cluster among themselves and draw on top in the accent color.
 */
private class ClusterPainter(private val holder: MapHolder) : Painter<JXMapViewer> {
    override fun paint(g: Graphics2D, viewer: JXMapViewer, width: Int, height: Int) {
        val pins = holder.pins
        if (pins.isEmpty()) {
            holder.lastRenderedPins = emptyList()
            return
        }
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val viewport = viewer.viewportBounds
        val tileFactory = viewer.tileFactory
        val zoom = viewer.zoom

        // Render order: secondary (gray) underneath, then named (teal with
        // labels), then primary (candidate) on top. Hit-test order is the
        // reverse so primary > named > secondary when clicks overlap.
        val rendered = mutableListOf<RenderedPin>()
        val secondary = pins.filter { it.style == MapPinStyle.SECONDARY }
        val named = pins.filter { it.style == MapPinStyle.NAMED }
        val primary = pins.filter { it.style == MapPinStyle.PRIMARY }

        val secondaryRender = paintLayer(
            g2, viewport, tileFactory, zoom, width, height,
            pins = secondary, style = MapPinStyle.SECONDARY
        )
        val namedRender = paintLayer(
            g2, viewport, tileFactory, zoom, width, height,
            pins = named, style = MapPinStyle.NAMED
        )
        val primaryRender = paintLayer(
            g2, viewport, tileFactory, zoom, width, height,
            pins = primary, style = MapPinStyle.PRIMARY
        )
        // Primary first so click hit-test (firstOrNull) hits the candidate
        // before named pins, then named, then secondary.
        rendered.addAll(primaryRender)
        rendered.addAll(namedRender)
        rendered.addAll(secondaryRender)

        holder.lastRenderedPins = rendered
        g2.dispose()
    }

    /** Cluster + draw a single style pool. Returns the on-screen positions
     *  of the resulting pins so the caller can build a hit-test list. */
    private fun paintLayer(
        g2: Graphics2D,
        viewport: java.awt.Rectangle,
        tileFactory: org.jxmapviewer.viewer.TileFactory,
        zoom: Int,
        width: Int,
        height: Int,
        pins: List<MapPin>,
        style: MapPinStyle,
    ): List<RenderedPin> {
        if (pins.isEmpty()) return emptyList()

        data class Cluster(
            var sumLat: Double, var sumLon: Double, var count: Int,
            val members: MutableList<MapPin>,
        )
        val grid = HashMap<Long, Cluster>()
        for (pin in pins) {
            val px = tileFactory.geoToPixel(GeoPosition(pin.latitude, pin.longitude), zoom)
            val sx = (px.x - viewport.x).toInt()
            val sy = (px.y - viewport.y).toInt()
            if (sx < -CLUSTER_CELL_PX || sy < -CLUSTER_CELL_PX ||
                sx > width + CLUSTER_CELL_PX || sy > height + CLUSTER_CELL_PX) continue
            val cellX = sx / CLUSTER_CELL_PX
            val cellY = sy / CLUSTER_CELL_PX
            val key = (cellX.toLong() shl 32) or (cellY.toLong() and 0xffffffffL)
            val c = grid.getOrPut(key) { Cluster(0.0, 0.0, 0, mutableListOf()) }
            c.sumLat += pin.latitude
            c.sumLon += pin.longitude
            c.count += 1
            c.members.add(pin)
        }

        val rendered = mutableListOf<RenderedPin>()
        for (cluster in grid.values) {
            val avgLat = cluster.sumLat / cluster.count
            val avgLon = cluster.sumLon / cluster.count
            val pixel = tileFactory.geoToPixel(GeoPosition(avgLat, avgLon), zoom)
            val sx = (pixel.x - viewport.x).toInt()
            val sy = (pixel.y - viewport.y).toInt()

            val displayCount = cluster.members.sumOf { it.count }
            val displayPin = if (cluster.members.size == 1) cluster.members.first()
                else MapPin(
                    id = "cluster-${cluster.members.first().id}",
                    latitude = avgLat,
                    longitude = avgLon,
                    count = displayCount,
                    label = "${cluster.members.size} videos here",
                    style = style,
                )
            rendered += RenderedPin(displayPin, sx, sy)

            val fill = when (style) {
                MapPinStyle.PRIMARY -> AwtColor(0x6750A4) // Material primary
                // Soft gray — present but recessive, so the candidate
                // remains the obvious eye-catcher.
                MapPinStyle.SECONDARY -> AwtColor(0x9E9E9E)
                // Teal — matches the macOS .systemTeal used for named
                // pins, so the two clients look the same.
                MapPinStyle.NAMED -> AwtColor(0x009688)
            }
            val drawNumber = style == MapPinStyle.PRIMARY
            val radius = when (style) {
                MapPinStyle.SECONDARY -> 7
                MapPinStyle.NAMED -> 10
                MapPinStyle.PRIMARY -> when {
                    displayCount <= 1 -> 10
                    displayCount < 10 -> 14
                    else -> 18
                }
            }
            g2.color = fill
            g2.fillOval(sx - radius, sy - radius, radius * 2, radius * 2)
            g2.color = AwtColor.WHITE
            g2.stroke = BasicStroke(2f)
            g2.drawOval(sx - radius, sy - radius, radius * 2, radius * 2)

            if (drawNumber && displayCount > 1) {
                val text = displayCount.toString()
                val fm = g2.fontMetrics
                val tw = fm.stringWidth(text)
                val th = fm.ascent
                g2.color = AwtColor.WHITE
                g2.drawString(text, sx - tw / 2, sy + th / 2 - 2)
            }

            // Named pins get a permanently visible label to the right of
            // the marker — that's the whole reason they exist. We render
            // a small dark background under the text for readability over
            // arbitrary map tiles.
            if (style == MapPinStyle.NAMED && displayPin.label.isNotEmpty()) {
                val label = displayPin.label
                val fm = g2.fontMetrics
                val tw = fm.stringWidth(label)
                val th = fm.ascent
                val padX = 4
                val padY = 2
                val tx = sx + radius + 4
                val ty = sy + th / 2 - 2
                g2.color = AwtColor(0, 0, 0, 140)
                g2.fillRoundRect(
                    tx - padX, ty - th - padY + 2,
                    tw + padX * 2, th + padY * 2,
                    6, 6,
                )
                g2.color = AwtColor.WHITE
                g2.drawString(label, tx, ty)
            }
        }
        return rendered
    }
}

/**
 * Custom [TileFactoryInfo] pointing at OpenStreetMap over HTTPS. The
 * `org.jxmapviewer.OSMTileFactoryInfo` bundled with the library uses plain
 * HTTP, which most modern OSM mirrors redirect or refuse — and combined with
 * the wrong User-Agent (see [installOsmUserAgentOnce]) was producing a
 * blank-white map. We use the standard `tile.openstreetmap.org` HTTPS
 * endpoint, which matches OSM's canonical tile URL.
 *
 * Tile zoom convention: JXMapViewer's internal zoom counts DOWN from the
 * max-zoom (so 0 = most detailed). Our `getTileUrl` converts that back to
 * the OSM "z" parameter where 0 = world view.
 */
private class VideoRoomOsmTileFactoryInfo : TileFactoryInfo(
    /* minZoom = */ 0,
    /* maxZoom = */ 19,
    /* totalMapZoom = */ 19,
    /* tileSize = */ 256,
    /* xR2L = */ true,
    /* yT2B = */ true,
    /* baseURL = */ "https://tile.openstreetmap.org",
    /* xParam = */ "x",
    /* yParam = */ "y",
    /* zParam = */ "z",
) {
    override fun getTileUrl(x: Int, y: Int, zoom: Int): String {
        // JXMapViewer's "zoom" is inverted; convert to OSM's standard z.
        val osmZ = totalMapZoom - zoom
        return "$baseURL/$osmZ/$x/$y.png"
    }
}

/**
 * Set the JVM's HTTP User-Agent system property to identify VideoRoom.
 * OpenStreetMap's tile-usage policy (and most other tile servers) reject
 * requests that arrive with the default `Java/<version>` UA, so a JXMapViewer
 * map renders blank-white until this is set. Idempotent — first call wins.
 */
@Volatile private var osmUserAgentInstalled = false
private val osmUserAgentLock = Any()
private fun installOsmUserAgentOnce() {
    if (osmUserAgentInstalled) return
    synchronized(osmUserAgentLock) {
        if (osmUserAgentInstalled) return
        // OSM's policy requires "valid HTTP User-Agent identifying application".
        // Include a contact-ish marker so OSM ops can reach us if needed.
        System.setProperty(
            "http.agent",
            "VideoRoom/0.1 (+https://github.com/videoroom/videoroom)"
        )
        osmUserAgentInstalled = true
    }
}
