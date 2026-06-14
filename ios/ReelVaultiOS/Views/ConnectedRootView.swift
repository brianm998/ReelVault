// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The connected app: an adaptive video grid that pushes to a detail view.
/// (C4 adds the iPad 3-column / iPhone-portrait-sheet layout on top of this.)
struct ConnectedRootView: View {
    @EnvironmentObject private var router: AppRouter
    @StateObject private var grid = GridViewModel()

    var body: some View {
        NavigationStack {
            VideoGridView(grid: grid)
                .navigationTitle("ReelVault")
                .navigationDestination(for: String.self) { id in
                    if let video = grid.videos.first(where: { $0.id == id }) {
                        VideoDetailView(video: video, mediaEndpoint: router.connection)
                    }
                }
        }
        .task { grid.loadVideos() }
    }
}
