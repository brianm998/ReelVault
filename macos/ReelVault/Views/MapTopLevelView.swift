// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
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
    /// When non-nil, open centred here (≈ neighbourhood zoom) instead of
    /// framing all pins. Set when the user taps a card's location badge; the
    /// caller clears it at the next non-badge navigation into the map.
    var focusedCoordinate: CLLocationCoordinate2D? = nil

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
                    pins: locations.map { loc in
                        OSMMapPin(
                            id: loc.id,
                            coordinate: CLLocationCoordinate2D(
                                latitude: loc.latitude, longitude: loc.longitude),
                            label: loc.filename,
                            memberIds: [loc.id]
                        )
                    },
                    initialCenter: center,
                    // Tight zoom when focused on a video (≈ 5 km across);
                    // moderate bbox-fitting otherwise.
                    initialZoomMeters: focusedCoordinate != nil ? 5_000 : 4_000_000,
                    // Auto-fit to all pins unless the caller asked to centre on
                    // a specific coordinate.
                    autoFitPins: focusedCoordinate == nil,
                    onMapClick: { _ in
                        // A click clear of every pin clears the selection.
                        if !selectedVideoIds.isEmpty { onSelectionChange([]) }
                    },
                    onPinClick: { pin in
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
                    }
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
