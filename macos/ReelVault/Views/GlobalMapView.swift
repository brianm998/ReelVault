// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import MapKit

/// Full-screen world map with every geotagged video plotted. Renders via
/// [OSMMapView] (MapKit + OpenStreetMap tile overlay), matching the Kotlin
/// client visually. MapKit's automatic annotation clustering handles dense
/// regions; tapping a cluster fires `onLocationPick` with the cluster's
/// average coordinate and a wider radius so the grid filter scoops up
/// every video that was under the icon.
struct GlobalMapView: View {
    let locations: [VideoLocation]
    let onDismiss: () -> Void
    /// Fired when the user picks a pin. Caller wires this to the grid's
    /// proximity filter. `radiusKm` scales with the cluster size — tight box
    /// for a single video, wider for big clusters.
    let onLocationPick: (_ latitude: Double, _ longitude: Double, _ radiusKm: Double) -> Void
    /// When non-nil, the map opens centred on this coordinate with a tight
    /// zoom (≈ neighbourhood view) instead of auto-fitting all pins. Used
    /// when the user taps a specific video's location badge.
    var focusedCoordinate: CLLocationCoordinate2D? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "globe")
                Text("Map of \(locations.count) geotagged video\(locations.count == 1 ? "" : "s")")
                    .font(.headline)
                Spacer()
            }

            Text("Pan + scroll to zoom. Click a pin to filter the grid to videos near that location. Pins automatically cluster when many videos share the same area.")
                .font(.caption)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            // When focused on a specific video, centre there at a tight zoom.
            // Otherwise centre on the centroid and let autoFitPins frame all pins.
            let center: CLLocationCoordinate2D = focusedCoordinate ?? {
                guard !locations.isEmpty else {
                    return CLLocationCoordinate2D(latitude: 25, longitude: 0)
                }
                let lat = locations.map(\.latitude).reduce(0, +) / Double(locations.count)
                let lon = locations.map(\.longitude).reduce(0, +) / Double(locations.count)
                return CLLocationCoordinate2D(latitude: lat, longitude: lon)
            }()

            OSMMapView(
                pins: locations.map { loc in
                    OSMMapPin(
                        id: loc.id,
                        coordinate: CLLocationCoordinate2D(
                            latitude: loc.latitude, longitude: loc.longitude),
                        label: loc.filename
                    )
                },
                initialCenter: center,
                // Tight zoom when focused on a single video (≈ 5 km across);
                // world-scale when empty; moderate bbox-fitting otherwise.
                initialZoomMeters: focusedCoordinate != nil ? 5_000
                    : (locations.isEmpty ? 20_000_000 : 4_000_000),
                // Disable auto-fit when the caller wants a specific location
                // centred — auto-fit would jump to the full-collection bbox.
                autoFitPins: focusedCoordinate == nil,
                onPinClick: { pin in
                    // Cluster pins widen the proximity filter so the grid
                    // picks up every member video; single pins stay tight.
                    let radius: Double
                    switch pin.clusteredCount {
                    case 0...1: radius = 0.5
                    case 2..<10: radius = 2
                    case 10..<50: radius = 10
                    default: radius = 50
                    }
                    onLocationPick(pin.coordinate.latitude, pin.coordinate.longitude, radius)
                }
            )
            .frame(minHeight: 540)
            .cornerRadius(8)

            HStack {
                Spacer()
                Button("Done") { onDismiss() }
                    .keyboardShortcut(.defaultAction)
                    .help("Close the global map. Any filter set by clicking a pin stays applied to the grid.")
            }
        }
        .padding(20)
        .frame(minWidth: 880, minHeight: 720)
    }
}
