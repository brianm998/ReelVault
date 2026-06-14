// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit
import AppKit
import MapKit

/// Top-level map view: every geotagged video in the current library filter,
/// plotted full-area via `OSMMapView`. Clicking a pin (or cluster) reports the
/// videos under it through `onSelectionChange` so the right panel can list
/// them; Shift/⌘-clicking adds to the current selection rather than replacing
/// it. Clicking empty map clears the selection.
///
/// The pin set is whatever `locations` the caller passes — already constrained
/// to the active filter (see `GridViewModel.loadVideoLocationsFilteredAsync`) —
/// so the map honors the Library Filter just like the grid and list do.
struct MapTopLevelView: View {
    let locations: [VideoLocation]
    let selectedVideoIds: [String]
    let onSelectionChange: ([String]) -> Void
    /// Accent color for the pins (the user's chosen highlight).
    var pinColor: NSColor = .controlAccentColor
    /// Resolve a coordinate to a known place name, or nil if it isn't a named
    /// place. Drives the pin labels (no label when nil).
    var placeName: (CLLocationCoordinate2D) -> String? = { _ in nil }
    /// When non-nil, open centred here (≈ neighbourhood zoom) instead of
    /// framing all pins. Set when the user taps a card's location badge; the
    /// caller clears it at the next non-badge navigation into the map.
    var focusedCoordinate: CLLocationCoordinate2D? = nil
    /// Fired when the user right-clicks a pin and picks Name/Rename — the caller
    /// opens the naming sheet and persists via the named-locations RPC.
    var onRequestNameLocation: (CLLocationCoordinate2D) -> Void = { _ in }
    /// True while the filtered location set is being recomputed (the filter just
    /// changed). Surfaces a spinner over the map; the previously-plotted pins
    /// stay put until the new set arrives so the map never blanks out.
    var isLoadingVideoLocations: Bool = false

    /// Co-located videos grouped into one pin so a single marker shows the
    /// count and clicking it selects the whole group. Bucketed to ~1 m. The
    /// label is the place name when known, else blank (no label drawn).
    private var videoPins: [OSMMapPin] {
        let groups = Dictionary(grouping: locations) { loc in
            "\(Int((loc.latitude * 1e5).rounded()))_\(Int((loc.longitude * 1e5).rounded()))"
        }
        return groups.values.map { group in
            let first = group[0]
            let coord = CLLocationCoordinate2D(
                latitude: first.latitude, longitude: first.longitude)
            return OSMMapPin(
                id: first.id,
                coordinate: coord,
                label: placeName(coord) ?? "",
                clusteredCount: group.count,
                style: .video,
                memberIds: group.map { $0.id }
            )
        }
    }

    var body: some View {
        ZStack {
            if locations.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "map")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text("No geotagged videos match the current filter.")
                        .foregroundColor(.secondary)
                    Text("Videos with GPS metadata appear here as pins.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            } else {
                OSMMapView(
                    pins: videoPins,
                    initialCenter: center,
                    // Nearly fully zoomed in when focused on a video (≈ 1 km
                    // across) so "Show on Map" / a location-badge click lands on
                    // the spot, not at a city-wide level; bbox-fit otherwise.
                    initialZoomMeters: focusedCoordinate != nil ? 1_000 : 4_000_000,
                    // Auto-fit to all pins unless the caller asked to centre on
                    // a specific coordinate.
                    autoFitPins: focusedCoordinate == nil,
                    onMapClick: { _ in
                        // A click clear of every pin clears the selection.
                        if !selectedVideoIds.isEmpty { onSelectionChange([]) }
                    },
                    onPinClick: { pin in
                        // Each pin carries the ids of all co-located videos.
                        let ids = pin.memberIds.isEmpty ? [pin.id] : pin.memberIds
                        // Read the live modifier state — Shift/⌘ accumulates.
                        let mods = NSEvent.modifierFlags
                        if mods.contains(.shift) || mods.contains(.command) {
                            var merged = selectedVideoIds
                            for id in ids where !merged.contains(id) { merged.append(id) }
                            onSelectionChange(merged)
                        } else {
                            onSelectionChange(ids)
                        }
                    },
                    onRenameLocationRequest: { pin in
                        onRequestNameLocation(pin.coordinate)
                    },
                    pinColor: pinColor
                )

                // Usage hint, bottom-leading — clear of the satellite toggle
                // (top-left) and zoom slider (right edge) the map hosts.
                VStack {
                    Spacer()
                    HStack {
                        Text("Click a pin to list its videos on the right. ⇧/⌘-click to add more locations.")
                            .font(.caption)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
                        Spacer()
                    }
                }
                .padding(12)
            }

            // Filter recomputation can take several seconds on a large or
            // network-backed library. Surface a spinner so the user knows the
            // map is catching up — the previously-plotted pins stay meanwhile.
            if isLoadingVideoLocations {
                VStack {
                    HStack(spacing: 8) {
                        ProgressView()
                            .controlSize(.small)
                        Text("Updating map…")
                            .font(.caption)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
                    Spacer()
                }
                .padding(12)
            }
        }
    }

    /// Where the map opens: the focused coordinate if set, otherwise the
    /// centroid of all pins (or a neutral mid-ocean point when empty).
    private var center: CLLocationCoordinate2D {
        if let focusedCoordinate { return focusedCoordinate }
        guard !locations.isEmpty else {
            return CLLocationCoordinate2D(latitude: 25, longitude: 0)
        }
        let lat = locations.map(\.latitude).reduce(0, +) / Double(locations.count)
        let lon = locations.map(\.longitude).reduce(0, +) / Double(locations.count)
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }
}
