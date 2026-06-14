// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import MapKit
import SwiftUI
import ReelVaultKit

/// The global map: a pin per geotagged video (the iOS counterpart of the macOS
/// `OSMMapView`, built on MapKit). Tapping a pin selects the video so the
/// inspector / detail follows. Bound to the shared `GridViewModel`'s
/// `videoLocations`.
struct LibraryMapView: View {
    @ObservedObject var grid: GridViewModel
    /// Called when a pin is tapped, so the parent can route to the inspector
    /// (regular) or push detail (compact).
    var onSelect: (String) -> Void

    var body: some View {
        Map {
            ForEach(grid.videoLocations) { loc in
                Annotation(loc.filename, coordinate: loc.coordinate) {
                    Button {
                        if let video = grid.videos.first(where: { $0.id == loc.id }) {
                            grid.selectVideo(video)
                        }
                        onSelect(loc.id)
                    } label: {
                        Image(systemName: "mappin.circle.fill")
                            .font(.title)
                            .foregroundStyle(.red)
                            .background(Circle().fill(.white).padding(4))
                    }
                }
            }
        }
        .overlay(alignment: .center) {
            if grid.isLoadingVideoLocations {
                ProgressView()
            } else if grid.videoLocations.isEmpty {
                ContentUnavailableView(
                    "No geotagged videos",
                    systemImage: "mappin.slash",
                    description: Text("Videos with GPS metadata appear here on the map.")
                )
            }
        }
        .task { grid.loadVideoLocations() }
    }
}

private extension VideoLocation {
    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}
