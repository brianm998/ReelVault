// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import MapKit

/// Modal sheet that lets the user pin a location on a world map and apply
/// it to one or more videos. Used both for setting a brand-new GPS coordinate
/// and for correcting an existing one. Renders via [OSMMapView] — MapKit
/// with an OpenStreetMap tile overlay — so the map looks identical to the
/// Kotlin desktop client and isn't a webview.
///
/// Two re-use mechanisms surface every coordinate the catalog already
/// knows about, both drawn as the same accent video pins the map view uses
/// (so the picker and the map look identical and the pins are easy to click):
///   * **Existing video pins** — other videos' GPS, grouped per spot with a
///     count, just like the map.
///   * **Named-place pins** — user-defined places (e.g. "Home"), labelled
///     with the name.
///
/// Tapping either kind of pin promotes its coordinate to the candidate;
/// a text field below the map lets the user attach (or rename) a name on
/// the candidate, which is persisted on save via the named-locations RPC.
struct LocationPickerView: View {
    let targetVideoIds: [String]
    /// Existing GPS for the primary video, if any — used to center the map
    /// and pre-populate the candidate pin.
    let initialLocation: CLLocationCoordinate2D?
    /// Every other video's known GPS — offered as secondary pins for one-tap
    /// re-use. Pass an empty array if the catalog has no geotagged content
    /// yet (the toggle then hides itself).
    let existingLocations: [VideoLocation]
    /// Catalog's user-named places. Each is rendered as a teal tag pin with
    /// its name permanently visible; clicking one snaps the candidate to
    /// its coordinate and pre-fills the name field for one-keystroke
    /// re-use.
    let namedLocations: [NamedLocation]
    let onCancel: () -> Void
    /// Fires when the user clicks Save. The closure receives the GPS,
    /// whether to embed it in the video file, and an optional name —
    /// non-empty when the user typed (or kept) a name in the name field.
    /// The caller is responsible for upserting that name into the
    /// named_locations table and for updating each target video's GPS.
    let onApply: (_ latitude: Double, _ longitude: Double, _ writeToFile: Bool, _ name: String?) -> Void
    /// Accent color for the re-use video pins — matches the map view's pins so
    /// the picker looks identical. The candidate marker keeps its own accent.
    let pinColor: NSColor

    @State private var candidate: CLLocationCoordinate2D?
    @State private var writeToFile: Bool = false
    /// Toggle for the "show every known location" overlay. Default-on when
    /// the catalog has any secondary pins. Named-place pins always show
    /// (regardless of this toggle) because they're the recommended re-use
    /// surface.
    @State private var showExistingLocations: Bool
    /// Live text field next to the readout. Pre-populated from the nearest
    /// matching named-location whenever the candidate moves; empty when
    /// there's no name within 250 m.
    @State private var candidateName: String = ""
    /// `true` while the disclosure showing raw lat/long beneath a named
    /// candidate is open. Default closed — the product spec wants the name
    /// to be the primary identity, with the numerics hidden behind a
    /// twirl-down.
    @State private var showCoordsDisclosure: Bool = false

    /// Pre-computed initial camera framing. If we have existing locations,
    /// frame their bounding box; otherwise center on Greenwich and zoom out.
    private let initialCenter: CLLocationCoordinate2D
    private let initialZoomMeters: Double

    init(
        targetVideoIds: [String],
        initialLocation: CLLocationCoordinate2D?,
        existingLocations: [VideoLocation] = [],
        namedLocations: [NamedLocation] = [],
        pinColor: NSColor = .controlAccentColor,
        onCancel: @escaping () -> Void,
        onApply: @escaping (Double, Double, Bool, String?) -> Void
    ) {
        self.targetVideoIds = targetVideoIds
        self.initialLocation = initialLocation
        self.pinColor = pinColor
        // Filter target IDs out of the secondary pin set — it'd be odd to
        // offer "the video's current location" as a pick when it's also the
        // candidate we already pre-populated.
        let targetSet = Set(targetVideoIds)
        let secondary = existingLocations.filter { !targetSet.contains($0.id) }
        self.existingLocations = secondary
        self.namedLocations = namedLocations
        self.onCancel = onCancel
        self.onApply = onApply
        _candidate = State(initialValue: initialLocation)
        _showExistingLocations = State(initialValue: !secondary.isEmpty)

        // Initial map framing — see the previous revision for the full
        // rationale; in short, prefer the bbox of everything we know plus
        // the initial pick, staying zoomed out for product reasons.
        if !secondary.isEmpty || !namedLocations.isEmpty {
            var points: [(Double, Double)] =
                secondary.map { ($0.latitude, $0.longitude) }
            points += namedLocations.map { ($0.latitude, $0.longitude) }
            if let p = initialLocation { points.append((p.latitude, p.longitude)) }
            let frame = Self.bboxFraming(for: points)
            self.initialCenter = frame.center
            self.initialZoomMeters = frame.distance
        } else if let p = initialLocation {
            self.initialCenter = p
            self.initialZoomMeters = 500_000
        } else {
            self.initialCenter = CLLocationCoordinate2D(latitude: 25, longitude: 0)
            self.initialZoomMeters = 20_000_000
        }

        // Pre-fill the candidateName from the closest named-location to
        // the initialLocation, if any.
        if let p = initialLocation,
           let match = Self.nearestNamedLocation(to: p, in: namedLocations) {
            _candidateName = State(initialValue: match.name)
            _showCoordsDisclosure = State(initialValue: false)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "mappin.and.ellipse")
                Text(targetVideoIds.count == 1
                     ? "Set Location"
                     : "Set Location for \(targetVideoIds.count) Videos")
                    .font(.headline)
                Spacer()
            }

            Text("Pan and zoom the map (trackpad pinch works too), then click where the video was captured — or click an existing pin to re-use that spot. The coordinate is saved to ReelVault's catalog and (optionally) embedded into the video file.")
                .font(.caption)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            if !existingLocations.isEmpty {
                Toggle(isOn: $showExistingLocations) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Show \(existingLocations.count) already-known location\(existingLocations.count == 1 ? "" : "s")")
                            .font(.body)
                        Text("These pins mark the catalog's existing GPS coordinates from other videos. Click one to re-use it for the selected video\(targetVideoIds.count == 1 ? "" : "s").")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .toggleStyle(.checkbox)
            }

            OSMMapView(
                pins: composedPins,
                initialCenter: initialCenter,
                initialZoomMeters: initialZoomMeters,
                // Belt-and-suspenders: even if `existingLocations` /
                // `namedLocations` haven't arrived by the time the sheet
                // presents, the map will animate to fit them as soon as
                // they appear instead of being stuck at the global-view
                // fallback.
                autoFitPins: true,
                onMapClick: { coord in setCandidate(coord) },
                onPinClick: { pin in
                    // Tapping the candidate pin itself is a no-op; tapping
                    // any other pin (named place or existing video location)
                    // snaps the candidate to its coordinate.
                    if pin.id == "candidate" { return }
                    setCandidate(pin.coordinate)
                },
                pinColor: pinColor
            )
            .frame(height: 440)
            .cornerRadius(8)

            candidateReadout

            Toggle(isOn: $writeToFile) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Also embed in video file")
                        .font(.body)
                    Text("Uses ffmpeg to rewrite the location atom without re-encoding. Original file is replaced atomically.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            HStack {
                Spacer()
                Button("Cancel") { onCancel() }
                    .keyboardShortcut(.cancelAction)
                Button("Save") {
                    if let c = candidate {
                        let trimmed = candidateName.trimmingCharacters(in: .whitespacesAndNewlines)
                        onApply(
                            c.latitude, c.longitude, writeToFile,
                            trimmed.isEmpty ? nil : trimmed
                        )
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(candidate == nil)
                .help(candidate == nil
                      ? "Click somewhere on the map first."
                      : "Apply the selected coordinate to the target videos.")
            }
        }
        .padding(20)
        .frame(width: 720)
    }

    // MARK: - Readout

    /// The block below the map that shows the candidate's identity
    /// (name-first when there's a match, lat/long otherwise) and the name
    /// editor. Pulled out into its own view to keep `body` readable.
    @ViewBuilder
    private var candidateReadout: some View {
        if let c = candidate {
            let matched = Self.nearestNamedLocation(to: c, in: namedLocations)
            VStack(alignment: .leading, spacing: 6) {
                if matched != nil {
                    // Named location: name is the primary identity; the
                    // raw lat/long sits behind a disclosure twirl-down.
                    HStack(spacing: 6) {
                        Image(systemName: "tag.fill")
                            .foregroundColor(.accentColor)
                        Text("Selected: \(displayName(matched, fallback: c))")
                            .font(.body)
                            .foregroundColor(.accentColor)
                    }
                    DisclosureGroup(
                        isExpanded: $showCoordsDisclosure,
                        content: {
                            Text(String(format: "%.6f, %.6f",
                                        c.latitude, c.longitude))
                                .font(.caption.monospaced())
                                .foregroundColor(.secondary)
                        },
                        label: {
                            Text(showCoordsDisclosure
                                 ? "Hide coordinates"
                                 : "Show coordinates")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    )
                    .padding(.leading, 20)
                } else {
                    // Unnamed coordinate: show lat/long directly so the
                    // user sees exactly where they clicked.
                    Text(String(format: "Selected: %.6f, %.6f",
                                c.latitude, c.longitude))
                        .font(.caption.monospaced())
                        .foregroundColor(.accentColor)
                }

                // Always present: the name text field. Pre-filled for
                // named candidates so renaming is a single edit; empty
                // (with a prompt) for unnamed candidates.
                HStack(spacing: 6) {
                    Image(systemName: "pencil")
                        .foregroundColor(.secondary)
                    TextField(
                        matched == nil
                            ? "Name this location (optional)"
                            : "Rename this location",
                        text: $candidateName
                    )
                    .textFieldStyle(.roundedBorder)
                    .help("Give this location a memorable name. The next video set at this spot (within 250 m) will resolve to this name automatically.")
                }
            }
            .padding(8)
            .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))
        } else {
            Text("Click the map (or an existing pin) to drop a candidate.")
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }

    // MARK: - Pin composition

    private var composedPins: [OSMMapPin] {
        var pins: [OSMMapPin] = []
        // Re-use pins are drawn exactly like the map view's video pins — an
        // accent circle with the place name (when known) and a big, easy click
        // target — so the picker and the map read identically and re-using a
        // spot is a single confident click.
        //
        // Named places ALWAYS render (regardless of the toggle) — they're the
        // recommended re-use surface — labelled with their name.
        for n in namedLocations {
            pins.append(OSMMapPin(
                id: "named-\(n.id)",
                coordinate: CLLocationCoordinate2D(
                    latitude: n.latitude, longitude: n.longitude),
                label: n.name,
                style: .video
            ))
        }
        // Other videos' locations, grouped per coordinate like the map view so
        // co-located clips collapse into one counted pin. A group sitting on a
        // named place is skipped — that named pin already marks the spot.
        if showExistingLocations {
            let groups = Dictionary(grouping: existingLocations) { loc in
                "\(Int((loc.latitude * 1e5).rounded()))_\(Int((loc.longitude * 1e5).rounded()))"
            }
            for group in groups.values {
                let first = group[0]
                let coord = CLLocationCoordinate2D(
                    latitude: first.latitude, longitude: first.longitude)
                if Self.nearestNamedLocation(to: coord, in: namedLocations) != nil { continue }
                pins.append(OSMMapPin(
                    id: "existing-\(first.id)",
                    coordinate: coord,
                    // Unnamed spot → no label, exactly like the map view.
                    label: "",
                    clusteredCount: group.count,
                    style: .video,
                    memberIds: group.map { $0.id }
                ))
            }
        }
        // The candidate keeps a distinct accent marker (a dropped pin, not a
        // circle) so the user's current pick reads apart from the re-use pins.
        if let c = candidate {
            let matched = Self.nearestNamedLocation(to: c, in: namedLocations)
            pins.append(OSMMapPin(
                id: "candidate",
                coordinate: c,
                label: displayName(matched, fallback: c),
                style: .primary
            ))
        }
        return pins
    }

    // MARK: - Helpers

    /// Move the candidate to `coord` and pre-fill the name field from the
    /// nearest matching named-location (or clear it if there's no match).
    /// Centralized so click-on-blank-map and click-on-pin paths converge.
    private func setCandidate(_ coord: CLLocationCoordinate2D) {
        candidate = coord
        let matched = Self.nearestNamedLocation(to: coord, in: namedLocations)
        candidateName = matched?.name ?? ""
        showCoordsDisclosure = false
    }

    private func displayName(_ matched: NamedLocation?, fallback c: CLLocationCoordinate2D) -> String {
        if let m = matched { return m.name }
        return String(format: "%.4f, %.4f", c.latitude, c.longitude)
    }

    /// Pick the named-location closest to `point` whose haversine distance
    /// is within its own `radiusMeters`. Returns nil when no entry is
    /// close enough. Linear scan — list is small.
    static func nearestNamedLocation(
        to point: CLLocationCoordinate2D,
        in candidates: [NamedLocation]
    ) -> NamedLocation? {
        guard !candidates.isEmpty else { return nil }
        var best: (NamedLocation, Double)? = nil
        for loc in candidates {
            let d = haversineMeters(
                lat1: point.latitude, lon1: point.longitude,
                lat2: loc.latitude, lon2: loc.longitude
            )
            if d <= loc.radiusMeters {
                if best == nil || d < best!.1 { best = (loc, d) }
            }
        }
        return best?.0
    }

    /// Great-circle distance in meters. Mirrors GridViewModel's helper so
    /// the picker can do the lookup without a viewmodel reference.
    private static func haversineMeters(
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

    /// Compute a centroid + camera distance that frames every point with a
    /// generous margin. Accepts raw (lat, lon) tuples so the caller can mix
    /// `VideoLocation`s with a free-standing `initialLocation`.
    ///
    /// Padding 2.5×, floor 500 km — keeps the initial view wide per the
    /// product requirement "don't zoom in very far at first".
    private static func bboxFraming(for points: [(Double, Double)])
        -> (center: CLLocationCoordinate2D, distance: Double)
    {
        guard !points.isEmpty else {
            return (CLLocationCoordinate2D(latitude: 25, longitude: 0), 20_000_000)
        }
        let lats = points.map(\.0)
        let lons = points.map(\.1)
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
        let distance = max(500_000, rawDistance)
        return (
            CLLocationCoordinate2D(latitude: centerLat, longitude: centerLon),
            OSMMapView.clampDistance(distance)
        )
    }
}
