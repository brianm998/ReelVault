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
    /// Fired when the user right-clicks a (non-candidate) pin and chooses
    /// Name/Rename from the native context menu. Only wired when non-null (the
    /// location picker omits it), which also gates whether the menu appears.
    var onRenameLocationRequest: ((OSMMapPin) -> Void)? = nil
    /// Fill color for `.video` pins — the user's chosen accent.
    var pinColor: NSColor = .controlAccentColor

    /// Visible camera-to-ground distance in meters. State (not a Binding) so
    /// internal slider and pan/zoom interactions stay in sync without
    /// requiring callers to plumb it through.
    @State private var cameraDistance: Double

    /// Standard map vs. satellite imagery; persisted across sessions.
    @State private var satellite: Bool = UserDefaults.standard.bool(forKey: "reelvault.map.satellite")

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
        onPinClick: ((OSMMapPin) -> Void)? = nil,
        onRenameLocationRequest: ((OSMMapPin) -> Void)? = nil,
        pinColor: NSColor = .controlAccentColor
    ) {
        self.pins = pins
        self.initialCenter = initialCenter
        self.initialZoomMeters = initialZoomMeters
        self.autoFitPins = autoFitPins
        self.onMapClick = onMapClick
        self.onPinClick = onPinClick
        self.onRenameLocationRequest = onRenameLocationRequest
        self.pinColor = pinColor
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
                satellite: satellite,
                onMapClick: onMapClick,
                onPinClick: onPinClick,
                onRenameLocationRequest: onRenameLocationRequest,
                pinColor: pinColor
            )
            VerticalZoomSlider(
                distance: $cameraDistance,
                minDistance: Self.minCameraDistance,
                maxDistance: Self.maxCameraDistance
            )
            .padding(.trailing, 12)
            .padding(.vertical, 12)
        }
        .overlay(alignment: .topLeading) {
            Button {
                satellite.toggle()
                UserDefaults.standard.set(satellite, forKey: "reelvault.map.satellite")
            } label: {
                Label(satellite ? "Map" : "Satellite",
                      systemImage: satellite ? "map" : "globe.americas.fill")
                    .font(.system(size: 11))
            }
            .buttonStyle(.bordered)
            .padding(12)
            .help("Switch between the standard map and satellite imagery")
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
    /// `true` shows MapKit's native satellite (hybrid) imagery; `false` shows
    /// the OSM raster tiles.
    var satellite: Bool = false
    var onMapClick: ((CLLocationCoordinate2D) -> Void)?
    var onPinClick: ((OSMMapPin) -> Void)?
    var onRenameLocationRequest: ((OSMMapPin) -> Void)?
    /// Fill color for `.video` pins — the user's chosen accent.
    var pinColor: NSColor = .controlAccentColor

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    /// Apply the chosen basemap: OSM raster tiles (replacing Apple's vector
    /// basemap) for the standard map, or MapKit's native hybrid imagery for
    /// satellite. Idempotent, so it's safe to call on every update.
    private func applyBasemap(satellite: Bool, to mapView: MKMapView, coordinator: Coordinator) {
        if satellite {
            if let existing = coordinator.tileOverlay {
                mapView.removeOverlay(existing)
                coordinator.tileOverlay = nil
            }
            if mapView.mapType != .hybrid { mapView.mapType = .hybrid }
        } else {
            if mapView.mapType != .standard { mapView.mapType = .standard }
            if coordinator.tileOverlay == nil {
                let overlay = UserAgentTileOverlay(
                    urlTemplate: "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
                )
                overlay.canReplaceMapContent = true
                overlay.maximumZ = 19
                overlay.minimumZ = 0
                mapView.addOverlay(overlay, level: .aboveLabels)
                coordinator.tileOverlay = overlay
            }
        }
    }

    func makeNSView(context: Context) -> MKMapView {
        let mapView = MKMapView()
        mapView.delegate = context.coordinator

        // Standard = OSM raster tiles (replacing Apple's vector basemap);
        // satellite = MapKit's native hybrid imagery. Re-applied on toggle.
        applyBasemap(satellite: satellite, to: mapView, coordinator: context.coordinator)

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

        // Right-click (secondary button) → name/rename the pin under the cursor.
        let secondaryClick = NSClickGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleSecondaryClick(_:))
        )
        secondaryClick.buttonMask = 0x2  // secondary (right) mouse button
        mapView.addGestureRecognizer(secondaryClick)

        context.coordinator.mapView = mapView

        context.coordinator.syncAnnotations(to: pins)
        return mapView
    }

    func updateNSView(_ nsView: MKMapView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.syncAnnotations(to: pins)
        applyBasemap(satellite: satellite, to: nsView, coordinator: context.coordinator)

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
                // Don't cluster primary pins. Clustering breaks click handling
                // (a tapped cluster's members aren't in `annotations`, so the
                // click-gesture's `nearestPin` misses and the tap is treated as
                // an empty-map click). Co-located videos are instead resolved on
                // click by gathering every video at the tapped coordinate — see
                // MapTopLevelView's onPinClick.
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
            case .video:
                // Map-view video pin: accent circle with the co-located count
                // and the place name (when known) below — matching the Kotlin
                // client. A drawn image rather than a marker so it reads as a
                // circle, not a teardrop.
                let identifier = "reelvault.pin.video"
                let view = mapView.dequeueReusableAnnotationView(withIdentifier: identifier)
                    ?? MKAnnotationView(annotation: pin, reuseIdentifier: identifier)
                view.annotation = pin
                let (image, offset) = makeVideoPinImage(
                    count: pin.pin.clusteredCount,
                    label: pin.pin.label,
                    color: parent.pinColor
                )
                view.image = image
                view.centerOffset = offset
                view.canShowCallout = false
                view.clusteringIdentifier = nil
                // Show every pin (don't let MapKit cull overlapping ones), to
                // match the Kotlin client which plots them all.
                view.displayPriority = .required
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
                    clusteredCount: count,
                    memberIds: memberIDs
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
            // The always-visible name label sits outside MapKit's marker tap
            // target, so a click on it wouldn't select the annotation and would
            // otherwise drop a candidate at a nearby point. Hit-test the pins
            // ourselves (with a tolerance that covers the label) and snap to the
            // nearest existing one; only a click clear of every pin drops a
            // fresh candidate. To pick a spot near a pin, the user zooms in.
            if let pin = nearestPin(to: point, in: mapView, tolerance: 40) {
                parent.onPinClick?(pin)
                return
            }
            let coord = mapView.convert(point, toCoordinateFrom: mapView)
            parent.onMapClick?(coord)
        }

        // MARK: Secondary (right) click → name/rename menu

        @objc func handleSecondaryClick(_ recognizer: NSClickGestureRecognizer) {
            guard let mapView = mapView, parent.onRenameLocationRequest != nil else { return }
            let point = recognizer.location(in: mapView)
            // Only over an existing pin (same tolerance as the left-click path);
            // a right-click on blank map does nothing.
            guard let pin = nearestPin(to: point, in: mapView, tolerance: 40),
                  pin.id != "candidate" else { return }
            let menu = NSMenu()
            let title = pin.label.isEmpty ? "Name this location…" : "Rename location…"
            let item = NSMenuItem(
                title: title,
                action: #selector(renameMenuClicked(_:)),
                keyEquivalent: ""
            )
            item.target = self
            item.representedObject = pin
            menu.addItem(item)
            menu.popUp(positioning: nil, at: point, in: mapView)
        }

        @objc func renameMenuClicked(_ sender: NSMenuItem) {
            guard let pin = sender.representedObject as? OSMMapPin else { return }
            parent.onRenameLocationRequest?(pin)
        }

        /// The non-candidate pin whose marker is within `tolerance` screen
        /// points of `point`, nearest first, or nil. Lets a click on (or beside,
        /// i.e. on the label of) an existing pin select its exact coordinate.
        private func nearestPin(
            to point: CGPoint,
            in mapView: MKMapView,
            tolerance: CGFloat
        ) -> OSMMapPin? {
            var best: OSMMapPin?
            var bestDistance = tolerance * tolerance
            for annotation in mapView.annotations {
                guard let pinAnnotation = annotation as? PinAnnotation,
                      pinAnnotation.pin.id != "candidate" else { continue }
                let p = mapView.convert(pinAnnotation.coordinate, toPointTo: mapView)
                let dx = p.x - point.x
                let dy = p.y - point.y
                let distance = dx * dx + dy * dy
                if distance <= bestDistance {
                    bestDistance = distance
                    best = pinAnnotation.pin
                }
            }
            return best
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

// MARK: - Video pin image

/// Build the image for a `.video` annotation: an accent-colored circle with
/// the co-located video count (when > 1) and, when known, the place name in a
/// pill below. Returns the image plus the `centerOffset` that keeps the circle
/// centred on the coordinate — zero when there's no label (the common case),
/// so the circle sits exactly on the spot.
private func makeVideoPinImage(count: Int, label: String, color: NSColor) -> (NSImage, CGPoint) {
    let diameter: CGFloat = count <= 1 ? 18 : (count < 10 ? 26 : 34)
    let border: CGFloat = 2
    let hasLabel = !label.isEmpty
    let labelAttrs: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: 11, weight: .medium),
        .foregroundColor: NSColor.white,
    ]
    let labelSize = hasLabel ? (label as NSString).size(withAttributes: labelAttrs) : .zero
    let labelPadX: CGFloat = 5
    let labelPadY: CGFloat = 2
    let labelGap: CGFloat = hasLabel ? 3 : 0
    let labelBoxW = hasLabel ? labelSize.width + labelPadX * 2 : 0
    let labelBoxH = hasLabel ? labelSize.height + labelPadY * 2 : 0
    let width = max(diameter, labelBoxW)
    let height = diameter + labelGap + labelBoxH

    let image = NSImage(size: CGSize(width: width, height: height))
    image.lockFocus()
    // NSImage origin is bottom-left (y up); draw the circle at the TOP.
    let circleX = (width - diameter) / 2
    let circleY = height - diameter
    let circleRect = CGRect(x: circleX + border / 2, y: circleY + border / 2,
                            width: diameter - border, height: diameter - border)
    let circle = NSBezierPath(ovalIn: circleRect)
    color.setFill(); circle.fill()
    NSColor.white.setStroke(); circle.lineWidth = border; circle.stroke()

    if count > 1 {
        let countAttrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: diameter * 0.44, weight: .semibold),
            .foregroundColor: NSColor.white,
        ]
        let s = "\(count)" as NSString
        let sz = s.size(withAttributes: countAttrs)
        s.draw(at: CGPoint(x: circleX + (diameter - sz.width) / 2,
                           y: circleY + (diameter - sz.height) / 2),
               withAttributes: countAttrs)
    }

    if hasLabel {
        let boxX = (width - labelBoxW) / 2
        let boxRect = CGRect(x: boxX, y: 0, width: labelBoxW, height: labelBoxH)
        NSColor(white: 0, alpha: 0.55).setFill()
        NSBezierPath(roundedRect: boxRect, xRadius: 4, yRadius: 4).fill()
        (label as NSString).draw(at: CGPoint(x: boxX + labelPadX, y: labelPadY),
                                 withAttributes: labelAttrs)
    }
    image.unlockFocus()

    // Positive y nudges the image down so the circle (drawn at the top) stays
    // on the coordinate when a label extends the image downward; zero when
    // unlabeled so the circle is exactly on the spot.
    return (image, CGPoint(x: 0, y: (height - diameter) / 2))
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
    /// Map-view video-location pin: an accent-colored circle showing the
    /// number of co-located videos, with the place name (when known) below.
    /// Matches the Kotlin client's pins.
    case video
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
    /// Video ids this pin stands for. A single video pin carries its own id;
    /// a cluster carries every member's id (filled in by the coordinator when
    /// it hands back a tapped cluster). Lets a click resolve straight to the
    /// underlying videos — e.g. to list them in the map view's right panel.
    var memberIds: [String] = []

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
