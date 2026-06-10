// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
 * Visual weight for a pin. PRIMARY is a video location — accent-colored,
 * clustered, and labelled with its place name when known. The global map and
 * the location picker's re-usable existing/named locations both use it, so the
 * two render identically. CANDIDATE is the location picker's active pick: the
 * same accent circle plus a white centre dot so it reads apart from the re-use
 * pins, kept in its own pool so it never folds into a cluster. SECONDARY (gray
 * dot) and NAMED (teal tag) are older picker styles retained for reuse.
 * The [ClusterPainter] keeps each pool separate so the candidate never gets
 * folded into a cluster of "everything else".
 */
enum class MapPinStyle { PRIMARY, SECONDARY, NAMED, CANDIDATE }

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
    /** Video ids this pin stands for. A single video pin carries its own id;
     *  a cluster carries every member's id. Lets a click resolve straight to
     *  the underlying videos (e.g. to list them in the map view's right
     *  panel) instead of re-deriving them from a proximity radius. */
    val memberIds: List<String> = emptyList(),
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
    /** When true, the camera frames the bounding box of [pins] once the panel is
     *  laid out, and re-frames whenever the pin set changes — until the user
     *  pans or zooms, after which it never re-frames. Handles pins that arrive
     *  asynchronously (and the filtered set replacing a broader one) without
     *  leaving the camera stranded at the stale [initialCenter]. When false the
     *  camera uses [initialCenter]/[initialZoom] only (e.g. a focused view). */
    autoFitPins: Boolean = false,
    /** Fill color for PRIMARY pins — the user's accent. Defaults to the
     *  classic ReelVault purple so callers that don't theme (the location
     *  picker) look unchanged. */
    pinColor: Color = Color(0xFF6750A4),
    /** Fired when the user clicks (or drags onto) the map at the given lat/lon. */
    onMapClick: ((latitude: Double, longitude: Double) -> Unit)? = null,
    /** Fired when the user clicks a pin (or cluster). For clusters [MapPin.count]
     *  is > 1 and [MapPin.memberIds] holds every member. `additive` is true when
     *  the click carried Shift/Cmd/Ctrl — callers that support multi-select use
     *  it to add to the current selection rather than replace it. */
    onPinClick: ((pin: MapPin, additive: Boolean) -> Unit)? = null,
    /** Fired when the user right-clicks a (non-candidate) pin and chooses
     *  Name/Rename from the native context menu. Only wired when non-null (the
     *  location picker omits it). The menu is a Swing popup so it layers over
     *  the heavyweight JXMapViewer peer; the caller opens the themed naming
     *  dialog. */
    onRenameLocationRequest: ((pin: MapPin) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Live JXMapViewer holder, stable across recompositions. All changing
    // inputs are pushed into it below so the factory can capture only
    // `mapHolder` and keep a stable identity — SwingPanel keys its
    // component-creating DisposableEffect on `factory`, so an unstable factory
    // would rebuild the heavyweight map peer (resetting zoom/center) on every
    // recomposition, e.g. on every pin selection.
    val mapHolder = remember { MapHolder() }
    mapHolder.pins = pins
    mapHolder.autoFitPins = autoFitPins
    mapHolder.onPinClick = onPinClick
    mapHolder.onMapClick = onMapClick
    mapHolder.onRenameLocationRequest = onRenameLocationRequest
    mapHolder.initialCenter = initialCenter
    mapHolder.initialZoom = initialZoom
    mapHolder.pinColor = AwtColor(pinColor.toArgb())

    val factory: () -> JXMapViewer = remember {
        {
                // OpenStreetMap's tile policy requires a non-default User-Agent
                // and rejects Java's default `Java/<version>` UA — so JXMapViewer
                // out of the box renders blank-white because every tile HTTP
                // request returns 403. Setting this property before the first
                // URLConnection identifies ReelVault and unblocks tile fetching.
                installOsmUserAgentOnce()

                val viewer = JXMapViewer().apply {
                    tileFactory = DefaultTileFactory(ReelVaultOsmTileFactoryInfo())
                    isOpaque = true
                    zoom = mapHolder.initialZoom
                    addressLocation = GeoPosition(
                        mapHolder.initialCenter.first, mapHolder.initialCenter.second)
                }
                // Standard pan + zoom interactions shipped with JXMapViewer.
                val panner = PanMouseInputListener(viewer)
                viewer.addMouseListener(panner)
                viewer.addMouseMotionListener(panner)
                viewer.addMouseListener(CenterMapListener(viewer))
                viewer.addMouseWheelListener(ZoomMouseWheelListenerCursor(viewer))
                viewer.addKeyListener(PanKeyListener(viewer))

                // Once the user pans (drag) or zooms (wheel), stop auto-fitting
                // so we never yank the camera back from where they navigated.
                viewer.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                    override fun mouseDragged(e: MouseEvent) { mapHolder.userInteracted = true }
                })
                viewer.addMouseWheelListener { mapHolder.userInteracted = true }

                // Click-to-act: if the click hits a pin, fire onPinClick;
                // otherwise fire onMapClick with the geographic coordinate.
                //
                // We hit-test on mouseReleased (with a small movement slop)
                // rather than mouseClicked: AWT only emits mouseClicked when the
                // press and release land on the *exact* same pixel, so a trackpad
                // tap that jitters even one pixel never fires — which made pins
                // feel unclickable ("clicking does nothing"). A release within
                // CLICK_SLOP_PX of the press is treated as a click; anything
                // larger is a pan and left to PanMouseInputListener.
                viewer.addMouseListener(object : MouseAdapter() {
                    private var pressX = 0
                    private var pressY = 0
                    override fun mousePressed(e: MouseEvent) {
                        // Right-click (popup trigger) over a pin → name/rename menu.
                        if (mapHolder.tryShowPinMenu(e)) return
                        if (e.button == MouseEvent.BUTTON1) { pressX = e.x; pressY = e.y }
                    }
                    override fun mouseReleased(e: MouseEvent) {
                        // macOS fires the popup trigger on release for some inputs.
                        if (mapHolder.tryShowPinMenu(e)) return
                        if (e.button != MouseEvent.BUTTON1) return
                        val mdx = e.x - pressX
                        val mdy = e.y - pressY
                        if (mdx * mdx + mdy * mdy > CLICK_SLOP_PX * CLICK_SLOP_PX) return
                        val v = mapHolder.viewer ?: return
                        // Hit-test against the rendered pins in pixel space —
                        // the marker circle OR the name label, so clicking
                        // either snaps to that pin's exact location.
                        val hit = mapHolder.lastRenderedPins.firstOrNull { rendered ->
                            val dx = rendered.screenX - e.x
                            val dy = rendered.screenY - e.y
                            val onMarker = dx * dx + dy * dy <= PIN_HIT_RADIUS_PX * PIN_HIT_RADIUS_PX
                            val onLabel = rendered.labelBounds?.contains(e.x, e.y) == true
                            onMarker || onLabel
                        }
                        if (hit != null) {
                            val additive = e.isShiftDown || e.isMetaDown || e.isControlDown
                            mapHolder.onPinClick?.invoke(hit.pin, additive)
                            return
                        }
                        val onMapClick = mapHolder.onMapClick
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
                // A press on the slider is a deliberate zoom — freeze auto-fit.
                // (We don't key off the change listener: programmatic auto-fit
                // moves the slider too, and that must not count as user input.)
                zoomSlider.addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) { mapHolder.userInteracted = true }
                })
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

                // Standard (OSM) ↔ satellite (Esri World Imagery) basemap
                // toggle, persisted across sessions. Added as a Swing child of
                // the viewer for the same reason as the zoom slider — Compose
                // can't paint over the heavyweight map peer.
                val osmFactory = DefaultTileFactory(ReelVaultOsmTileFactoryInfo())
                val satelliteFactory = DefaultTileFactory(ReelVaultSatelliteTileFactoryInfo())
                var satelliteOn = MapViewPrefs.loadSatellite()
                viewer.tileFactory = if (satelliteOn) satelliteFactory else osmFactory
                val satelliteToggle = javax.swing.JToggleButton("Satellite", satelliteOn).apply {
                    isFocusable = false
                    font = font.deriveFont(11f)
                    toolTipText = "Switch between the standard map and satellite imagery."
                    addActionListener {
                        satelliteOn = isSelected
                        viewer.tileFactory = if (satelliteOn) satelliteFactory else osmFactory
                        MapViewPrefs.saveSatellite(satelliteOn)
                        viewer.repaint()
                    }
                }
                viewer.add(satelliteToggle)
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
                    val btnW = 88
                    val btnH = 24
                    satelliteToggle.setBounds(
                        (viewer.width - btnW - 12).coerceAtLeast(0),
                        12,
                        btnW,
                        btnH,
                    )
                }
                reapplySliderBounds()
                viewer.addComponentListener(object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent) {
                        reapplySliderBounds()
                        // The viewer now has a real size — frame the pins if we
                        // were waiting on layout, or apply the fixed initial
                        // center (location picker) that couldn't stick at 0×0.
                        mapHolder.maybeAutoFit()
                        mapHolder.maybeApplyInitialCenter()
                    }
                })
                // Belt-and-suspenders: post a one-shot bounds update once
                // Swing has finished the first layout pass. SwingPanel
                // sometimes sizes the viewer through a path that doesn't
                // emit ComponentEvent.RESIZED on this exact widget, leaving
                // the slider stranded at 0×0 until the first user-driven
                // resize. invokeLater after construction guarantees the
                // slider is positioned for the initial paint.
                javax.swing.SwingUtilities.invokeLater {
                    reapplySliderBounds()
                    mapHolder.maybeAutoFit()
                    mapHolder.maybeApplyInitialCenter()
                }
                viewer
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SwingPanel(
            modifier = Modifier.fillMaxSize(),
            factory = factory,
            update = {
                // Live inputs were pushed into mapHolder on this recomposition
                // (above). Re-frame when the pin set changes — unless the user
                // took over the camera — and repaint to reflect updated pins.
                mapHolder.maybeAutoFit()
                // Re-center the fixed-center path if the request changed (e.g.
                // picker data loaded after first layout).
                mapHolder.maybeApplyInitialCenter()
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
    /** See [MapView]'s `autoFitPins`. */
    var autoFitPins: Boolean = false
    /** Set once the user pans or zooms, freezing auto-fit so we never yank the
     *  camera out from under them. */
    var userInteracted: Boolean = false
    /** Ids of the pin set the camera was last auto-fit to, so we only re-fit
     *  when the set actually changes. */
    var lastFitIds: Set<String>? = null
    /** (center, zoom) last pushed onto the viewer by [maybeApplyInitialCenter],
     *  so the non-auto-fit path only re-centers when the request actually
     *  changes — never on every recomposition (e.g. a location-picker pin
     *  click), which would yank the camera back. */
    var lastAppliedInitial: Pair<Pair<Double, Double>, Int>? = null
    // Live inputs routed through the holder rather than captured by the factory
    // lambda. SwingPanel keys its component-creating DisposableEffect on the
    // `factory` reference, so a factory that closed over these (which change on
    // every selection) would rebuild the heavyweight map peer on each click —
    // resetting zoom/center and flinging the camera to the default (north-pole)
    // view. Reading them from the (stable) holder keeps the factory stable.
    var onPinClick: ((MapPin, Boolean) -> Unit)? = null
    var onMapClick: ((Double, Double) -> Unit)? = null
    var onRenameLocationRequest: ((MapPin) -> Unit)? = null
    var initialCenter: Pair<Double, Double> = 0.0 to 0.0
    var initialZoom: Int = 7
    /** Fill for PRIMARY pins — the user's accent color. */
    var pinColor: AwtColor = AwtColor(0x6750A4)
}

/** Frame the current pins if auto-fit is enabled, the user hasn't taken over,
 *  the panel is laid out, and the pin set changed since the last fit. Safe to
 *  call repeatedly (on resize, on update, post-construction). */
private fun MapHolder.maybeAutoFit() {
    if (!autoFitPins || userInteracted) return
    val v = viewer ?: return
    // JXMapViewer needs a real size to compute a fit; skip until laid out.
    if (v.width <= 0 || v.height <= 0) return
    val current = pins
    if (current.isEmpty()) return
    val ids = current.mapTo(HashSet()) { it.id }
    if (ids == lastFitIds) return
    lastFitIds = ids
    val positions = current.mapTo(HashSet()) { GeoPosition(it.latitude, it.longitude) }
    if (positions.size == 1) {
        v.addressLocation = positions.first()
        // Neighbourhood-level view for a lone pin (JXMapViewer zoom counts up
        // as it zooms out; 5 ≈ a few streets across).
        v.zoom = 5
    } else {
        // Fit all positions into ~70% of the viewport.
        v.zoomToBestFit(positions, 0.7)
    }
}

/** Apply [MapHolder.initialCenter]/[MapHolder.initialZoom] once the panel has a
 *  real size — the counterpart to [maybeAutoFit] for the non-auto-fit path
 *  (the location picker, and the map focused on a single coordinate).
 *
 *  JXMapViewer silently drops an `addressLocation` set while the component is
 *  still 0×0 (not yet laid out) and shows its default top-left viewport, i.e.
 *  the north pole — which is why the location picker opened "zoomed into the
 *  arctic". The auto-fit path already re-applies after layout via
 *  [maybeAutoFit]; this gives the fixed-center path the same post-layout
 *  re-apply. Re-applies whenever the requested center/zoom changes (pins can
 *  load asynchronously and re-frame), but never once the user has taken over
 *  the camera. Mutually exclusive with auto-fit. */
private fun MapHolder.maybeApplyInitialCenter() {
    if (autoFitPins || userInteracted) return
    val v = viewer ?: return
    // Same guard as maybeAutoFit: addressLocation only sticks once laid out.
    if (v.width <= 0 || v.height <= 0) return
    val target = initialCenter to initialZoom
    if (target == lastAppliedInitial) return
    lastAppliedInitial = target
    // Zoom first so addressLocation computes the center pixel at the right zoom.
    v.zoom = initialZoom
    v.addressLocation = GeoPosition(initialCenter.first, initialCenter.second)
}

/** On a popup trigger (right-click / Ctrl-click) over a non-candidate pin,
 *  show a native Swing context menu offering to name (or rename) that location
 *  and invoke [MapHolder.onRenameLocationRequest] when chosen. Swing rather than
 *  a Compose menu so it layers correctly over the heavyweight JXMapViewer peer.
 *  Returns true when it handled the event. */
private fun MapHolder.tryShowPinMenu(e: MouseEvent): Boolean {
    if (!e.isPopupTrigger) return false
    val onRename = onRenameLocationRequest ?: return false
    val hit = lastRenderedPins.firstOrNull { rendered ->
        val dx = rendered.screenX - e.x
        val dy = rendered.screenY - e.y
        val onMarker = dx * dx + dy * dy <= PIN_HIT_RADIUS_PX * PIN_HIT_RADIUS_PX
        val onLabel = rendered.labelBounds?.contains(e.x, e.y) == true
        onMarker || onLabel
    } ?: return false
    if (hit.pin.id == "candidate") return false
    val label = if (hit.pin.label.isBlank()) "Name this location…" else "Rename location…"
    javax.swing.JPopupMenu().apply {
        add(javax.swing.JMenuItem(label).apply {
            addActionListener { onRename(hit.pin) }
        })
        show(e.component, e.x, e.y)
    }
    return true
}

private data class RenderedPin(
    val pin: MapPin,
    val screenX: Int,
    val screenY: Int,
    /** Screen rect of the pin's text label (NAMED pins only), so a click on the
     *  label snaps to the pin's exact location just like a click on the icon. */
    val labelBounds: java.awt.Rectangle? = null,
)

/** Pixel radius around a pin center that counts as a click. Generous so small
 *  single-video circles (≈10 px) are easy to hit. */
private const val PIN_HIT_RADIUS_PX = 20

/** Max press→release movement (px) still treated as a click rather than a pan. */
private const val CLICK_SLOP_PX = 6

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

        // Render order: secondary (gray) underneath, then named, then the
        // video pins (primary), then the candidate on top. Hit-test order is
        // the reverse so candidate > primary > named > secondary on overlap.
        val rendered = mutableListOf<RenderedPin>()
        val secondary = pins.filter { it.style == MapPinStyle.SECONDARY }
        val named = pins.filter { it.style == MapPinStyle.NAMED }
        val primary = pins.filter { it.style == MapPinStyle.PRIMARY }
        val candidate = pins.filter { it.style == MapPinStyle.CANDIDATE }

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
        val candidateRender = paintLayer(
            g2, viewport, tileFactory, zoom, width, height,
            pins = candidate, style = MapPinStyle.CANDIDATE
        )
        // Candidate first so click hit-test (firstOrNull) hits it before the
        // video pins, then named, then secondary.
        rendered.addAll(candidateRender)
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
                    // A numbered (clustered) PRIMARY pin still surfaces the place
                    // name its members sit on — the most common one among them —
                    // so a cluster resting on a named location shows that name
                    // beside its count rather than dropping it. Other styles keep
                    // a plain placeholder (never drawn for SECONDARY; a clustered
                    // NAMED pin is vanishingly rare).
                    label = if (style == MapPinStyle.PRIMARY) {
                        cluster.members
                            .mapNotNull { it.label.ifBlank { null } }
                            .groupingBy { it }
                            .eachCount()
                            .maxByOrNull { it.value }
                            ?.key
                            ?: ""
                    } else {
                        "${cluster.members.size} videos here"
                    },
                    style = style,
                    memberIds = cluster.members.flatMap { it.memberIds },
                )
            val fill = when (style) {
                // Video pins and the candidate both use the user's accent, so
                // the picker's re-use pins match the map's.
                MapPinStyle.PRIMARY, MapPinStyle.CANDIDATE -> holder.pinColor
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
                // The candidate is a single pick — give it the unclustered
                // video-pin size (a touch larger, with a centre dot below).
                MapPinStyle.CANDIDATE -> 11
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

            // The candidate carries a white centre dot so the user's active
            // pick reads apart from the plain accent re-use circles.
            if (style == MapPinStyle.CANDIDATE) {
                val dot = 4
                g2.color = AwtColor.WHITE
                g2.fillOval(sx - dot, sy - dot, dot * 2, dot * 2)
            }

            if (drawNumber && displayCount > 1) {
                val text = displayCount.toString()
                val fm = g2.fontMetrics
                val tw = fm.stringWidth(text)
                val th = fm.ascent
                g2.color = AwtColor.WHITE
                g2.drawString(text, sx - tw / 2, sy + th / 2 - 2)
            }

            // Permanently-visible label to the right of the marker, showing
            // the place name. Named pins always carry one; video (PRIMARY)
            // pins carry one whenever their location resolves to a known place
            // — including numbered clusters, which show the place name beside
            // the count in the circle rather than dropping it. A small dark
            // background keeps the text readable over arbitrary tiles; we record
            // its screen rect so a click on the label still selects the pin.
            var labelBounds: java.awt.Rectangle? = null
            val drawLabel = displayPin.label.isNotEmpty() && (
                style == MapPinStyle.NAMED ||
                    style == MapPinStyle.PRIMARY ||
                    style == MapPinStyle.CANDIDATE
            )
            if (drawLabel) {
                val label = displayPin.label
                val fm = g2.fontMetrics
                val tw = fm.stringWidth(label)
                val th = fm.ascent
                val padX = 4
                val padY = 2
                val tx = sx + radius + 4
                val ty = sy + th / 2 - 2
                val rect = java.awt.Rectangle(
                    tx - padX, ty - th - padY + 2,
                    tw + padX * 2, th + padY * 2,
                )
                labelBounds = rect
                g2.color = AwtColor(0, 0, 0, 140)
                g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 6, 6)
                g2.color = AwtColor.WHITE
                g2.drawString(label, tx, ty)
            }
            rendered += RenderedPin(displayPin, sx, sy, labelBounds)
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
private class ReelVaultOsmTileFactoryInfo : TileFactoryInfo(
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
 * Satellite imagery tiles from Esri's free World Imagery basemap. The tile
 * path is `/{z}/{y}/{x}` (note y before x, unlike OSM). Attribution: Esri,
 * Maxar, Earthstar Geographics, and the GIS community.
 */
private class ReelVaultSatelliteTileFactoryInfo : TileFactoryInfo(
    /* minZoom = */ 0,
    /* maxZoom = */ 19,
    /* totalMapZoom = */ 19,
    /* tileSize = */ 256,
    /* xR2L = */ true,
    /* yT2B = */ true,
    /* baseURL = */ "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile",
    /* xParam = */ "x",
    /* yParam = */ "y",
    /* zParam = */ "z",
) {
    override fun getTileUrl(x: Int, y: Int, zoom: Int): String {
        val z = totalMapZoom - zoom
        return "$baseURL/$z/$y/$x"
    }
}

/** Persists the satellite/standard basemap choice across sessions. */
private object MapViewPrefs {
    private val prefs = java.util.prefs.Preferences.userRoot().node("com/reelvault/map")
    fun loadSatellite(): Boolean = prefs.getBoolean("satellite", false)
    fun saveSatellite(value: Boolean) = prefs.putBoolean("satellite", value)
}

/**
 * Set the JVM's HTTP User-Agent system property to identify ReelVault.
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
            "ReelVault/0.1 (+https://github.com/reelvault/reelvault)"
        )
        osmUserAgentInstalled = true
    }
}
