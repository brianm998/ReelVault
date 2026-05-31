// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import AppKit
import MapKit

/// A SwiftUI map view that renders OpenStreetMap tiles natively via MapKit's
/// `MKTileOverlay`. Drop-in replacement for SwiftUI's `Map` view in places
/// where we want OSM imagery instead of Apple's. Same MapKit interaction
/// model — pan, scroll-to-zoom, annotation click — so users don't lose any
/// behavior; only the rendered tile imagery changes.
///
/// Surfaces a vertical zoom slider on the right edge so coarse zooming is
/// reachable without a trackpad / Magic Mouse. Slider state is bidirectional
/// — dragging the slider zooms the map, and panning/scrolling the map
/// updates the slider.
///
/// Why MKTileOverlay and not MapLibre Native:
///   * Google Maps doesn't offer a native macOS SDK at all.
///   * MapLibre Native's *macOS* SDK was deprecated in 2022.
///   * MKTileOverlay is Apple's officially-supported third-party tile path,
///     in-process, no extra dependency, no API key.
struct OSMMapView: View {
    /// Pins to render. The view's coordinator translates these into
    /// `MKPointAnnotation` instances and reconciles diffs across updates.
    let pins: [OSMMapPin]
    let initialCenter: CLLocationCoordinate2D
    /// Initial camera-to-ground distance in meters. Clamped to
    /// `[Self.minCameraDistance, Self.maxCameraDistance]` because MapKit
    /// throws an NSException on `setRegion` with spans > the world.
    let initialZoomMeters: Double
    /// When `true`, the map frames the bbox of `pins` the *first* time the
    /// list goes from empty to non-empty (animated). Useful for the global
    /// map and location-picker where pin data arrives asynchronously over
    /// gRPC — without this, the camera stays at `initialCenter` even after
    /// pins appear. After the auto-frame fires once, user pan/zoom takes
    /// over and the map never auto-re-frames.
    var autoFitPins: Bool = false
    /// Fired when the user clicks somewhere on the map that isn't a pin.
    var onMapClick: ((CLLocationCoordinate2D) -> Void)? = nil
    /// Fired when the user clicks an annotation. For clusters,
    /// `pin.clusteredCount > 1`.
    var onPinClick: ((OSMMapPin) -> Void)? = nil

    /// Visible camera-to-ground distance in meters. State (not a Binding) so
    /// internal slider and pan/zoom interactions stay in sync without
    /// requiring callers to plumb it through.
    @State private var cameraDistance: Double

    /// Closest camera distance the slider exposes — "very local" per the
    /// product spec (≤ 1 km across the screen).
    static let minCameraDistance: Double = 1_000
    /// Farthest camera distance. Stops well short of MapKit's runaway-region
    /// territory so `setCamera` always succeeds.
    static let maxCameraDistance: Double = 15_000_000

    init(
        pins: [OSMMapPin],
        initialCenter: CLLocationCoordinate2D,
        initialZoomMeters: Double,
        autoFitPins: Bool = false,
        onMapClick: ((CLLocationCoordinate2D) -> Void)? = nil,
        onPinClick: ((OSMMapPin) -> Void)? = nil
    ) {
        self.pins = pins
        self.initialCenter = initialCenter
        self.initialZoomMeters = initialZoomMeters
        self.autoFitPins = autoFitPins
        self.onMapClick = onMapClick
        self.onPinClick = onPinClick
        _cameraDistance = State(initialValue: Self.clampDistance(initialZoomMeters))
    }

    static func clampDistance(_ d: Double) -> Double {
        max(minCameraDistance, min(maxCameraDistance, d))
    }

    var body: some View {
        ZStack(alignment: .trailing) {
            _OSMMapKitView(
                pins: pins,
                initialCenter: initialCenter,
                initialZoomMeters: initialZoomMeters,
                autoFitPins: autoFitPins,
                cameraDistance: $cameraDistance,
                onMapClick: onMapClick,
                onPinClick: onPinClick
            )
            VerticalZoomSlider(
                distance: $cameraDistance,
                minDistance: Self.minCameraDistance,
                maxDistance: Self.maxCameraDistance
            )
            .padding(.trailing, 12)
            .padding(.vertical, 12)
        }
    }
}

// MARK: - NSViewRepresentable wrapping MKMapView

/// Inner `NSViewRepresentable` that actually owns the `MKMapView` instance.
/// Kept private so callers see only the higher-level [OSMMapView] composite
/// (map + zoom slider).
private struct _OSMMapKitView: NSViewRepresentable {
    let pins: [OSMMapPin]
    let initialCenter: CLLocationCoordinate2D
    /// Snapshot of the parent's intended zoom distance. Kept alongside
    /// `cameraDistance` so the initial-framing follow-up in `updateNSView`
    /// can re-apply the *parent's* framing — `cameraDistance` is a
    /// long-lived @State on `OSMMapView` and stays at the value
    /// captured on the first render, so it isn't a reliable source of
    /// "the parent currently wants this zoom."
    let initialZoomMeters: Double
    let autoFitPins: Bool
    /// Two-way binding for camera-to-ground distance in meters. The slider
    /// writes it; pan/zoom gestures update it via the delegate.
    @Binding var cameraDistance: Double
    var onMapClick: ((CLLocationCoordinate2D) -> Void)?
    var onPinClick: ((OSMMapPin) -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeNSView(context: Context) -> MKMapView {
        let mapView = MKMapView()
        mapView.delegate = context.coordinator

        // Hide Apple's vector basemap so only the OSM raster tiles show.
        let overlay = UserAgentTileOverlay(
            urlTemplate: "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        )
        overlay.canReplaceMapContent = true
        overlay.maximumZ = 19
        overlay.minimumZ = 0
        mapView.addOverlay(overlay, level: .aboveLabels)
        context.coordinator.tileOverlay = overlay

        // Initial camera. Use `setCamera` instead of `setRegion` because
        // MapKit's `setRegion(MKCoordinateRegion(center:latitudinalMeters:longitudinalMeters:))`
        // throws an NSException when the resulting span exceeds ~180°
        // latitudinally or ~360° longitudinally — and a "show the whole
        // world" initial value (millions of meters at the equator) does
        // exactly that. `setCamera` clamps gracefully.
        context.coordinator.isApplyingCameraChange = true
        let camera = MKMapCamera(
            lookingAtCenter: initialCenter,
            fromDistance: OSMMapView.clampDistance(cameraDistance),
            pitch: 0,
            heading: 0
        )
        mapView.setCamera(camera, animated: false)

        // Click-on-blank-map → onMapClick. `didSelect` handles annotation
        // taps so we only need to detect "no annotation was hit".
        let click = NSClickGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleMapClick(_:))
        )
        click.delaysPrimaryMouseButtonEvents = false
        mapView.addGestureRecognizer(click)

        // Trackpad pinch-to-zoom. MapKit's macOS default isn't fully
        // reliable when an MKTileOverlay replaces the basemap, so we wire
        // our own NSMagnificationGestureRecognizer. The recognizer reads
        // the incremental magnification each tick, applies it to the
        // camera distance, then resets the accumulator — so MapKit's own
        // pinch handling (if it fires) and ours don't fight, they just
        // both contribute small deltas.
        let pinch = NSMagnificationGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handlePinch(_:))
        )
        mapView.addGestureRecognizer(pinch)
        context.coordinator.mapView = mapView

        context.coordinator.syncAnnotations(to: pins)
        return mapView
    }

    func updateNSView(_ nsView: MKMapView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.syncAnnotations(to: pins)

        // Auto-fit on the first non-empty pin set. Late-arriving data
        // (over gRPC) is the usual culprit — without this the camera
        // remains stuck at `initialCenter` even after pins appear. Single
        // pin auto-fits to roughly a regional view via the bbox helper's
        // minimum-distance floor.
        //
        // Returning early matters: the slider-sync block below would
        // otherwise immediately re-apply the *stale* `cameraDistance`
        // (still at `initialZoomMeters`, typically 20,000,000 m / global
        // view) on top of the freshly-applied fit distance — making the
        // fit silently ineffective and dropping the user onto a globe
        // view that visually centers on (0, 0). `applyAutoFit` updates
        // `cameraDistance` asynchronously, which triggers another pass
        // through this method where the slider and the camera now agree.
        if autoFitPins
            && !context.coordinator.hasAutoFramedPins
            && !pins.isEmpty
        {
            context.coordinator.hasAutoFramedPins = true
            applyAutoFit(to: nsView, coordinator: context.coordinator)
            return
        }

        // Initial-framing follow-up: SwiftUI may construct this view with
        // empty `initialCenter`/`initialZoomMeters` (because the data
        // hadn't loaded yet) and *later* re-render with the real values.
        // Without this block, `initialCenter` is only honored inside
        // `makeNSView` (first creation), so the camera stays at whatever
        // garbage value was captured on the first pass — typically the
        // (25, 0) "no-data" fallback — and the user sees the equator
        // off the African coast instead of their actual library footprint.
        //
        // We only re-apply *before* the auto-fit has fired and *only*
        // when the parent's intended center is meaningfully different
        // from the camera's current one. After the user (or auto-fit)
        // touches the camera, this block stops interfering.
        if !context.coordinator.hasAppliedInitialFraming {
            let currentCenter = nsView.camera.centerCoordinate
            let centerDelta = haversineMeters(
                lat1: currentCenter.latitude, lon1: currentCenter.longitude,
                lat2: initialCenter.latitude, lon2: initialCenter.longitude
            )
            // 50 km gate — large enough that user pans don't trigger
            // re-framing, small enough that a wrong initial value (say,
            // (25, 0) vs an actual library footprint in California)
            // always satisfies it.
            if centerDelta > 50_000 {
                context.coordinator.hasAppliedInitialFraming = true
                context.coordinator.isApplyingCameraChange = true
                let clamped = OSMMapView.clampDistance(initialZoomMeters)
                let camera = MKMapCamera(
                    lookingAtCenter: initialCenter,
                    fromDistance: clamped,
                    pitch: 0,
                    heading: 0
                )
                nsView.setCamera(camera, animated: true)
                DispatchQueue.main.async {
                    self.cameraDistance = clamped
                }
                return
            } else {
                // Centers already match — no re-framing needed; mark
                // ourselves done so we don't keep checking on every pass.
                context.coordinator.hasAppliedInitialFraming = true
            }
        }

        // Push the slider's camera distance into MKMapView if they differ
        // by more than 1% — guards against the slider→delegate→slider
        // feedback loop. The `isApplyingCameraChange` flag stops the
        // delegate from echoing this change back to the binding.
        let target = OSMMapView.clampDistance(cameraDistance)
        let current = nsView.camera.centerCoordinateDistance
        if abs(current - target) > current * 0.01 {
            context.coordinator.isApplyingCameraChange = true
            let camera = nsView.camera.copy() as! MKMapCamera
            camera.centerCoordinateDistance = target
            nsView.setCamera(camera, animated: true)
        }
    }

    /// Standard haversine — used by the initial-framing gate to decide
    /// whether the parent's `initialCenter` materially differs from where
    /// the camera is currently pointing. Duplicated here rather than
    /// pulled from LocationPickerView because OSMMapView is a primitive
    /// component that should stay independent of its callers.
    private func haversineMeters(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ) -> Double {
        let earthRadius = 6_371_000.0
        let toRad = Double.pi / 180
        let dLat = (lat2 - lat1) * toRad
        let dLon = (lon2 - lon1) * toRad
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1 * toRad) * cos(lat2 * toRad)
            * sin(dLon / 2) * sin(dLon / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /// Frame the bbox of the current pins with comfortable padding.
    /// Mirrors the framing helper in LocationPickerView: 2.5× pad on the
    /// raw span, floor at 500 km so the camera doesn't snap to street
    /// level on a single-cluster catalog.
    private func applyAutoFit(to mapView: MKMapView, coordinator: Coordinator) {
        guard !pins.isEmpty else { return }
        let lats = pins.map(\.coordinate.latitude)
        let lons = pins.map(\.coordinate.longitude)
        let minLat = lats.min()!
        let maxLat = lats.max()!
        let minLon = lons.min()!
        let maxLon = lons.max()!
        let centerLat = (minLat + maxLat) / 2
        let centerLon = (minLon + maxLon) / 2
        let latSpanMeters = (maxLat - minLat) * 111_000
        let lonSpanMeters = (maxLon - minLon) * 111_000 *
            max(0.1, cos(centerLat * .pi / 180))
        let rawDistance = max(latSpanMeters, lonSpanMeters) * 2.5
        let distance = OSMMapView.clampDistance(max(500_000, rawDistance))
        coordinator.isApplyingCameraChange = true
        let camera = MKMapCamera(
            lookingAtCenter: CLLocationCoordinate2D(
                latitude: centerLat, longitude: centerLon),
            fromDistance: distance,
            pitch: 0,
            heading: 0
        )
        mapView.setCamera(camera, animated: true)
        // Push the new distance back into the parent binding so the slider
        // syncs up to the freshly-applied zoom.
        DispatchQueue.main.async {
            self.cameraDistance = distance
        }
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var parent: _OSMMapKitView
        weak var mapView: MKMapView?
        var tileOverlay: MKTileOverlay?
        /// `true` between a programmatic `setCamera` call and the resulting
        /// `regionDidChangeAnimated` delegate callback, so we don't echo the
        /// programmatic change back into the binding (which would re-trigger
        /// `updateNSView` and re-apply the camera in a loop).
        var isApplyingCameraChange = false
        /// `true` once `autoFitPins` has fired its one-shot frame. Prevents
        /// the camera from snapping back every time the parent updates the
        /// pin list (e.g. when the user adds a candidate pin in the picker).
        var hasAutoFramedPins = false
        /// `true` once `updateNSView` has reconciled `initialCenter` with
        /// the live camera. Defensive against late-arriving parent props
        /// — `initialCenter` would otherwise stay at whatever value the
        /// parent passed during the very first body invocation (typically
        /// the empty-data fallback), even if the parent re-renders later
        /// with a meaningful coordinate.
        var hasAppliedInitialFraming = false
        /// pin.id → live MKAnnotation, so updateNSView diffs are cheap.
        private var annotationsByID: [String: PinAnnotation] = [:]

        init(_ parent: _OSMMapKitView) {
            self.parent = parent
        }

        // MARK: Tile rendering

        func mapView(_ mapView: MKMapView, rendererFor overlay: any MKOverlay) -> MKOverlayRenderer {
            if let tile = overlay as? MKTileOverlay {
                return MKTileOverlayRenderer(tileOverlay: tile)
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        // MARK: Annotation rendering

        func mapView(_ mapView: MKMapView, viewFor annotation: any MKAnnotation) -> MKAnnotationView? {
            guard let pin = annotation as? PinAnnotation else { return nil }
            // Primary pins (e.g. the candidate selection in LocationPicker)
            // get the accent color so they stand out from a dense field of
            // secondary "known location" pins, which render gray.
            switch pin.pin.style {
            case .primary:
                let identifier = "reelvault.pin.primary"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier)
                            as? MKMarkerAnnotationView)
                    ?? MKMarkerAnnotationView(annotation: pin, reuseIdentifier: identifier)
                view.annotation = pin
                view.glyphImage = NSImage(
                    systemSymbolName: "film.fill", accessibilityDescription: nil)
                view.markerTintColor = NSColor.controlAccentColor
                view.canShowCallout = true
                // Don't cluster the candidate with secondary pins — the user
                // needs to see exactly where their pick lands.
                view.clusteringIdentifier = nil
                view.zPriority = .max
                return view
            case .secondary:
                let identifier = "reelvault.pin.secondary"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier)
                            as? MKMarkerAnnotationView)
                    ?? MKMarkerAnnotationView(annotation: pin, reuseIdentifier: identifier)
                view.annotation = pin
                view.glyphImage = NSImage(
                    systemSymbolName: "mappin", accessibilityDescription: nil)
                view.markerTintColor = NSColor.tertiaryLabelColor
                view.canShowCallout = true
                view.clusteringIdentifier = "reelvault.cluster.secondary"
                return view
            case .named:
                // Named places: tagged-rectangle glyph, always-visible label
                // next to the marker so the user can see "Home" / "Office" /
                // etc. without needing to click. We use a teal tint so they
                // pop against both the SF Symbols red of MKMarker defaults
                // and the gray of secondary pins.
                let identifier = "reelvault.pin.named"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier)
                            as? MKMarkerAnnotationView)
                    ?? MKMarkerAnnotationView(annotation: pin, reuseIdentifier: identifier)
                view.annotation = pin
                view.glyphImage = NSImage(
                    systemSymbolName: "tag.fill", accessibilityDescription: nil)
                view.markerTintColor = NSColor.systemTeal
                view.canShowCallout = true
                view.titleVisibility = .visible
                view.clusteringIdentifier = nil
                return view
            }
        }

        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            guard let annotation = view.annotation else { return }
            if let cluster = annotation as? MKClusterAnnotation {
                let count = cluster.memberAnnotations.count
                let memberIDs = cluster.memberAnnotations
                    .compactMap { ($0 as? PinAnnotation)?.pin.id }
                let synthetic = OSMMapPin(
                    id: "cluster-\(memberIDs.first ?? "")",
                    coordinate: cluster.coordinate,
                    label: "\(count) videos here",
                    clusteredCount: count
                )
                parent.onPinClick?(synthetic)
                mapView.deselectAnnotation(annotation, animated: false)
                return
            }
            if let pinAnnotation = annotation as? PinAnnotation {
                parent.onPinClick?(pinAnnotation.pin)
                mapView.deselectAnnotation(annotation, animated: false)
            }
        }

        // MARK: Camera-distance sync (map → slider)

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            if isApplyingCameraChange {
                isApplyingCameraChange = false
                return
            }
            // User-initiated pan/zoom — propagate the new distance back to
            // the binding so the slider tracks it. Async hop avoids
            // mutating SwiftUI state inside MapKit's call stack.
            let newDistance = mapView.camera.centerCoordinateDistance
            let bound = parent.cameraDistance
            if abs(bound - newDistance) > newDistance * 0.01 {
                DispatchQueue.main.async {
                    self.parent.cameraDistance = OSMMapView.clampDistance(newDistance)
                }
            }
        }

        // MARK: Map click (blank area)

        @objc func handleMapClick(_ recognizer: NSClickGestureRecognizer) {
            guard let mapView = mapView else { return }
            let point = recognizer.location(in: mapView)
            // If MapKit dispatched a selection for this click, didSelect
            // already fired and we shouldn't ALSO call onMapClick.
            if mapView.selectedAnnotations.first != nil { return }
            let coord = mapView.convert(point, toCoordinateFrom: mapView)
            parent.onMapClick?(coord)
        }

        // MARK: Pinch-to-zoom

        @objc func handlePinch(_ recognizer: NSMagnificationGestureRecognizer) {
            guard let mapView = mapView else { return }
            // We only want continuous incremental adjustments. The .began
            // and .ended phases carry magnification=0; ignore them so we
            // don't accidentally snap the camera at gesture boundaries.
            guard recognizer.state == .changed else { return }
            let delta = recognizer.magnification
            // magnification semantics: positive = expanded (zoom in), so
            // divide the camera distance by (1 + delta).
            let factor = max(0.1, 1.0 + delta)
            let currentDistance = mapView.camera.centerCoordinateDistance
            let newDistance = OSMMapView.clampDistance(currentDistance / factor)
            if abs(newDistance - currentDistance) < 0.5 {
                recognizer.magnification = 0
                return
            }
            isApplyingCameraChange = true
            let camera = mapView.camera.copy() as! MKMapCamera
            camera.centerCoordinateDistance = newDistance
            mapView.setCamera(camera, animated: false)
            // Mirror into the slider binding immediately so it tracks the
            // pinch live — `regionDidChangeAnimated` will eventually echo,
            // but tweens look smoother if we update right now.
            DispatchQueue.main.async {
                self.parent.cameraDistance = newDistance
            }
            // Reset the accumulator so the next event measures *delta*,
            // not total magnification since gesture start.
            recognizer.magnification = 0
        }

        // MARK: Pin diffing

        func syncAnnotations(to pins: [OSMMapPin]) {
            guard let mapView = mapView else { return }
            let want = Set(pins.map { $0.id })
            for (id, annotation) in annotationsByID where !want.contains(id) {
                mapView.removeAnnotation(annotation)
                annotationsByID.removeValue(forKey: id)
            }
            for pin in pins {
                if let existing = annotationsByID[pin.id] {
                    existing.coordinate = pin.coordinate
                    existing.title = pin.label
                    existing.pin = pin
                } else {
                    let a = PinAnnotation(pin: pin)
                    mapView.addAnnotation(a)
                    annotationsByID[pin.id] = a
                }
            }
        }
    }
}

// MARK: - Vertical zoom slider

/// Right-edge zoom control. Drives `distance` exponentially because zoom
/// feels geometric, not linear — equal slider movement gives equal *ratio*
/// change in distance.
///
/// Uses an `NSSlider` via [NativeVerticalSlider] rather than a rotated
/// SwiftUI `Slider`: SwiftUI's `Slider` keeps its hit-test geometry in the
/// pre-rotation frame, so a −90°-rotated slider produces a narrow horizontal
/// hit strip in the wrong place — most clicks miss, the few that hit jump
/// the value wildly. `NSSlider` with `isVertical = true` is a proper native
/// vertical slider with correct hit-testing.
private struct VerticalZoomSlider: View {
    @Binding var distance: Double
    let minDistance: Double
    let maxDistance: Double

    /// Slider value in [0, 1], log-mapped to `distance`. Top of the slider
    /// (value = 1) corresponds to `minDistance` (zoomed in); bottom
    /// (value = 0) to `maxDistance` (zoomed out).
    private var sliderValue: Binding<Double> {
        Binding(
            get: {
                let logMin = log(minDistance)
                let logMax = log(maxDistance)
                let logCur = log(min(maxDistance, max(minDistance, distance)))
                return (logMax - logCur) / (logMax - logMin)
            },
            set: { newValue in
                let logMin = log(minDistance)
                let logMax = log(maxDistance)
                distance = exp(logMax - newValue * (logMax - logMin))
            }
        )
    }

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "plus.magnifyingglass")
                .font(.caption2)
                .foregroundColor(.secondary)
            NativeVerticalSlider(value: sliderValue, range: 0...1)
                .frame(width: 24, height: 200)
            Image(systemName: "minus.magnifyingglass")
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(8)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 8))
        .help("Zoom: drag up for close-in detail, down for a wider view.")
    }
}

/// SwiftUI wrapper around `NSSlider` configured for vertical orientation.
/// SwiftUI itself doesn't expose `NSSlider.isVertical`, so we drop down to
/// AppKit for a real vertical slider with native hit-testing and tick-mark
/// behavior.
private struct NativeVerticalSlider: NSViewRepresentable {
    @Binding var value: Double
    let range: ClosedRange<Double>

    func makeNSView(context: Context) -> NSSlider {
        let slider = NSSlider()
        slider.isVertical = true
        slider.minValue = range.lowerBound
        slider.maxValue = range.upperBound
        slider.doubleValue = value
        // Fire on every drag tick so the camera tracks the user's finger,
        // not just snap when they let go.
        slider.isContinuous = true
        slider.target = context.coordinator
        slider.action = #selector(Coordinator.valueChanged(_:))
        return slider
    }

    func updateNSView(_ nsView: NSSlider, context: Context) {
        context.coordinator.parent = self
        // Only assign when there's a real diff to avoid feedback loops when
        // SwiftUI re-renders us as a result of our own action firing.
        if abs(nsView.doubleValue - value) > 1e-4 {
            nsView.doubleValue = value
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject {
        var parent: NativeVerticalSlider
        init(_ parent: NativeVerticalSlider) { self.parent = parent }

        @objc @MainActor func valueChanged(_ sender: NSSlider) {
            parent.value = sender.doubleValue
        }
    }
}

// MARK: - Data types

/// Visual weight of a pin. The [LocationPickerView] uses `.primary` for the
/// candidate (current selection), `.secondary` for already-known video
/// locations being offered for re-use, and `.named` for user-named places
/// (e.g. "Home") — named pins surface the label permanently next to the
/// marker so the user can see where their named spots are without hovering.
enum OSMMapPinStyle: Hashable {
    case primary
    case secondary
    case named
}

/// One pin to draw on an [OSMMapView]. `clusteredCount` is filled in only
/// when the coordinator hands back a synthetic pin representing a tapped
/// cluster — single source pins always carry count 1.
struct OSMMapPin: Identifiable, Hashable {
    let id: String
    let coordinate: CLLocationCoordinate2D
    var label: String = ""
    var clusteredCount: Int = 1
    var style: OSMMapPinStyle = .primary

    static func == (lhs: OSMMapPin, rhs: OSMMapPin) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

/// MKAnnotation wrapper that retains the originating [OSMMapPin] so the
/// delegate can hand it back on selection.
private final class PinAnnotation: NSObject, MKAnnotation {
    @objc dynamic var coordinate: CLLocationCoordinate2D
    @objc dynamic var title: String?
    var pin: OSMMapPin

    init(pin: OSMMapPin) {
        self.pin = pin
        self.coordinate = pin.coordinate
        self.title = pin.label.isEmpty ? nil : pin.label
    }
}

/// `MKTileOverlay` subclass that fetches OSM tiles with ReelVault's
/// User-Agent header. OpenStreetMap's tile usage policy requires identifying
/// the requesting application; without this header OSM returns HTTP 403 and
/// the map renders blank.
private final class UserAgentTileOverlay: MKTileOverlay {
    override func loadTile(
        at path: MKTileOverlayPath,
        result: @escaping (Data?, (any Error)?) -> Void
    ) {
        var request = URLRequest(url: self.url(forTilePath: path))
        request.setValue(
            "ReelVault/0.1 (+https://github.com/reelvault/reelvault)",
            forHTTPHeaderField: "User-Agent"
        )
        URLSession.shared.dataTask(with: request) { data, _, error in
            result(data, error)
        }.resume()
    }
}
