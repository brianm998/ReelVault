// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import MapKit
import SwiftUI
import ReelVaultKit

/// The global map — the iOS counterpart of the macOS `OSMMapView` and the Kotlin
/// desktop map. Geotagged videos are *clustered by location* (named places +
/// ~110 m coordinate buckets, via the shared `GridViewModel.mapLocationClusters`),
/// and each cluster is drawn as a circle with its video count plus a place-name
/// label when one is known — NOT a pin per video labelled with a filename.
///
/// Tapping a cluster reports it to the parent: on iPad it opens a side panel of
/// the videos there; on iPhone it switches to Grid mode filtered to that location.
struct LibraryMapView: View {
    @ObservedObject var grid: GridViewModel
    /// A cluster was tapped. The parent routes it (iPad panel / iPhone grid filter).
    var onSelectCluster: (LocationFilterGroup) -> Void

    @State private var camera: MapCameraPosition = .automatic
    @AppStorage("ios.mapUseSatellite") private var useSatellite = false

    var body: some View {
        let clusters = grid.mapLocationClusters()
        Map(position: $camera) {
            ForEach(clusters) { cluster in
                Annotation("", coordinate: CLLocationCoordinate2D(
                    latitude: cluster.latitude, longitude: cluster.longitude)) {
                    Button {
                        onSelectCluster(cluster)
                    } label: {
                        ClusteredMapMarker(count: cluster.count,
                                           placeName: cluster.isNamed ? cluster.label : nil)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .mapStyle(useSatellite ? .hybrid : .standard)
        .overlay(alignment: .center) {
            if grid.isLoadingVideoLocations && grid.videoLocations.isEmpty {
                ProgressView()
            } else if grid.videoLocations.isEmpty {
                ContentUnavailableView(
                    "No geotagged videos",
                    systemImage: "mappin.slash",
                    description: Text("Videos with GPS metadata appear here on the map.")
                )
            }
        }
        .overlay(alignment: .topTrailing) {
            Button { useSatellite.toggle() } label: {
                Image(systemName: useSatellite ? "map.fill" : "map")
                    .padding(8)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
            }
            .buttonStyle(.plain)
            .padding(12)
        }
        // Filtered load populates both `videoLocations` (the pins) and
        // `geotaggedVideos` (full rows the iPad panel opens), honouring the active
        // library filter. Named places drive the cluster labels.
        .task {
            grid.loadVideoLocationsFiltered()
            grid.loadNamedLocations()
        }
        // "Show on Map" (detail/inspector) recenters us here instead of opening
        // Apple Maps. Consume the request and clear it. onAppear covers the case
        // where it was set just before the map became visible.
        .onAppear { focus(grid.mapFocus) }
        .onChange(of: grid.mapFocus) { _, f in focus(f) }
    }

    private func focus(_ f: GeoFilter?) {
        guard let f else { return }
        let span = MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
        camera = .region(MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: f.latitude, longitude: f.longitude),
            span: span))
        grid.mapFocus = nil
    }
}

/// A location-cluster marker: an accent circle with the video count (when >1) and,
/// when the place is known, a name label beneath — matching the macOS + desktop
/// map markers.
struct ClusteredMapMarker: View {
    let count: Int
    let placeName: String?

    var body: some View {
        VStack(spacing: 2) {
            ZStack {
                Circle()
                    .fill(Color.accentColor)
                    .frame(width: diameter, height: diameter)
                    .overlay(Circle().strokeBorder(.white, lineWidth: 2))
                if count > 1 {
                    Text("\(count)")
                        .font(.system(size: count >= 10 ? 13 : 12, weight: .semibold))
                        .foregroundStyle(.white)
                }
            }
            .shadow(radius: 2)
            if let placeName, !placeName.isEmpty {
                Text(placeName)
                    .font(.caption2)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 1)
                    .background(Color.black.opacity(0.55), in: Capsule())
                    .fixedSize()
            }
        }
    }

    /// Larger circles for busier clusters (18 / 26 / 34 pt), mirroring macOS.
    private var diameter: CGFloat { count <= 1 ? 18 : (count < 10 ? 26 : 34) }
}

/// iPad map mode: the map plus a trailing panel that appears when a cluster is
/// tapped, listing the videos at that location (the macOS-style right panel).
/// Tapping a video opens it in Detail mode.
struct MapModePad: View {
    @ObservedObject var grid: GridViewModel
    @Binding var viewMode: LibraryViewMode
    @State private var selected: LocationFilterGroup?

    var body: some View {
        HStack(spacing: 0) {
            LibraryMapView(grid: grid) { cluster in selected = cluster }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            if let selected {
                Divider()
                MapLocationPanel(grid: grid, cluster: selected,
                                 onOpen: { video in
                                     grid.selectVideo(video)
                                     viewMode = .detail
                                 },
                                 onClose: { self.selected = nil })
                    .frame(width: 300)
                    .transition(.move(edge: .trailing))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: selected)
    }
}

/// The trailing panel for a tapped map cluster on iPad: the place name (or
/// "Location") plus a grid of full video cards, matching the macOS
/// `MapVideoListPanel`. iPhone routes through the grid instead of this panel.
struct MapLocationPanel: View {
    @ObservedObject var grid: GridViewModel
    let cluster: LocationFilterGroup
    var onOpen: (VideoSummary) -> Void
    var onClose: () -> Void

    private let columns = [GridItem(.adaptive(minimum: 120), spacing: 8)]

    var body: some View {
        let members = grid.mapClusterMembers(cluster)
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(cluster.isNamed ? cluster.label : "Location")
                        .font(.headline).lineLimit(2)
                    Text("\(members.count) video\(members.count == 1 ? "" : "s")")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Button(action: onClose) { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
            }
            .padding()
            Divider()
            ScrollView {
                LazyVGrid(columns: columns, spacing: 8) {
                    ForEach(members) { video in
                        VideoCardView(
                            video: video,
                            image: grid.thumbnails[video.id],
                            topSlots: grid.topSlots,
                            isSelected: grid.selectedVideoId == video.id,
                            onActivate: {
                                grid.selectVideo(video)
                                onOpen(video)
                            }
                        )
                        .onAppear { grid.loadThumbnail(videoId: video.id) }
                    }
                }
                .padding(8)
            }
        }
        .background(.bar)
    }
}
