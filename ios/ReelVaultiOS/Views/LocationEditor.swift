// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import CoreLocation
import MapKit
import ReelVaultKit
import SwiftUI

/// "Set / Change / Remove location" controls for a video — the iOS counterpart of
/// the macOS detail panel's location buttons. Shown at the bottom of the detail
/// view and in the inspector. Reads the live grid row so the label and pin track
/// edits; writes go through the shared GridViewModel so they sync to every client.
struct LocationButtonsSection: View {
    @ObservedObject var grid: GridViewModel
    let videoId: String
    /// Drop the "Location" headline when hosted inside a CollapsibleSection.
    var showHeader: Bool = true
    /// Host-supplied "switch to the internal Map mode" action. When non-nil a
    /// "Show on Map" button appears that focuses this video on the in-app map
    /// (NOT Apple Maps). nil hosts (no way to change view mode) hide the button.
    var onShowOnMap: (() -> Void)? = nil
    @State private var showPicker = false
    /// The width of the widest location button, measured so they all render at
    /// that size — uniform and left-pinned, never stretched full-width (which
    /// truncated labels on iPad) nor sized-to-content (which left them ragged).
    @State private var buttonWidth: CGFloat?

    private var video: VideoSummary? {
        grid.videos.first(where: { $0.id == videoId })
            ?? (grid.selectedVideo?.id == videoId ? grid.selectedVideo : nil)
    }

    var body: some View {
        if let video {
            VStack(alignment: .leading, spacing: 8) {
                if showHeader { Text("Location").font(.headline) }
                if video.hasLocation {
                    Text(String(format: "%.5f, %.5f", video.gpsLatitude, video.gpsLongitude))
                        .font(.caption).foregroundStyle(.secondary)
                }
                locationButton(
                    title: video.hasLocation ? "Change location…" : "Set location…",
                    systemImage: "mappin.and.ellipse"
                ) { showPicker = true }
                if video.hasLocation {
                    locationButton(title: "Remove location", systemImage: "mappin.slash",
                                   role: .destructive) {
                        grid.clearVideoLocations(videoIds: [video.id])
                    }
                    if let onShowOnMap {
                        locationButton(title: "Show on Map", systemImage: "map") {
                            // Focus the in-app map on this clip, then ask the host
                            // to switch to Map mode — no external Apple Maps trip.
                            grid.mapFocus = GeoFilter(latitude: video.gpsLatitude,
                                                      longitude: video.gpsLongitude, radiusKm: 1)
                            onShowOnMap()
                        }
                    }
                }
            }
            // Each button reports its natural width; the widest wins and is applied
            // to all (see buttonWidth). The group hugs the left of its container.
            .onPreferenceChange(LocationButtonWidthKey.self) { w in
                if w > 0 { buttonWidth = w }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .sheet(isPresented: $showPicker) {
                LocationPickerSheet(
                    initial: video.hasLocation
                        ? CLLocationCoordinate2D(latitude: video.gpsLatitude, longitude: video.gpsLongitude)
                        // No location yet: start near the user's other geotagged
                        // clips (if any) so they're not stranded at null island.
                        : grid.videoLocations.first.map {
                            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                        }
                ) { lat, lon, writeToFile in
                    grid.setVideoLocations(videoIds: [video.id], latitude: lat,
                                           longitude: lon, writeToFile: writeToFile)
                }
            }
        }
    }

    /// One location button, left-aligned and rendered at the shared `buttonWidth`
    /// (the widest button's natural size) so all three match and nothing truncates.
    /// Reports its natural width up via a preference while `buttonWidth` is unset.
    @ViewBuilder
    private func locationButton(
        title: String, systemImage: String, role: ButtonRole? = nil, action: @escaping () -> Void
    ) -> some View {
        Button(role: role, action: action) {
            Label(title, systemImage: systemImage)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .frame(width: buttonWidth, alignment: .leading)
        }
        .buttonStyle(.bordered)
        .background(
            GeometryReader { proxy in
                Color.clear.preference(key: LocationButtonWidthKey.self, value: proxy.size.width)
            }
        )
    }
}

/// Tracks the widest location button so they can all adopt that width.
private struct LocationButtonWidthKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

/// Map-based location picker: drag the map under a fixed centre pin to choose a
/// point, optionally write it into the file, Save. (A simpler take on the macOS
/// LocationPickerView — no named-place pins yet.)
struct LocationPickerSheet: View {
    let initial: CLLocationCoordinate2D?
    let onApply: (_ latitude: Double, _ longitude: Double, _ writeToFile: Bool) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var camera: MapCameraPosition
    @State private var center: CLLocationCoordinate2D
    @State private var writeToFile = false

    init(initial: CLLocationCoordinate2D?,
         onApply: @escaping (Double, Double, Bool) -> Void) {
        self.initial = initial
        self.onApply = onApply
        let start = initial ?? CLLocationCoordinate2D(latitude: 20, longitude: 0)
        _center = State(initialValue: start)
        let span = initial == nil
            ? MKCoordinateSpan(latitudeDelta: 120, longitudeDelta: 120)
            : MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
        _camera = State(initialValue: .region(MKCoordinateRegion(center: start, span: span)))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ZStack {
                    Map(position: $camera)
                        .onMapCameraChange { ctx in center = ctx.region.center }
                    // Fixed crosshair pin: the map moves under it, so its tip
                    // always marks the chosen point (offset so the tip, not the
                    // centre of the glyph, sits on the map centre).
                    Image(systemName: "mappin")
                        .font(.title)
                        .foregroundStyle(.red)
                        .shadow(radius: 2)
                        .offset(y: -11)
                        .allowsHitTesting(false)
                }
                Form {
                    Section {
                        LabeledContent("Latitude", value: String(format: "%.5f", center.latitude))
                        LabeledContent("Longitude", value: String(format: "%.5f", center.longitude))
                    } footer: {
                        Text("Drag the map to position the pin.")
                    }
                    Section {
                        Toggle("Write location into the file", isOn: $writeToFile)
                    } footer: {
                        Text("Also embeds the GPS tag in the video file, not just the catalog.")
                    }
                }
                .frame(height: 230)
            }
            .navigationTitle("Set Location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onApply(center.latitude, center.longitude, writeToFile)
                        dismiss()
                    }
                }
            }
        }
    }
}
