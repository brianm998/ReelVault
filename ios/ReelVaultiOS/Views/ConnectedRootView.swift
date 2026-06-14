// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The connected app. For C2 this is a minimal browse list that proves the
/// pinned-TLS connection + ListVideos work end-to-end; C3 replaces it with the
/// real adaptive grid/detail UI bound to the same shared `GridViewModel`.
struct ConnectedRootView: View {
    @StateObject private var grid = GridViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if grid.videos.isEmpty {
                    ContentUnavailableView(
                        "No videos",
                        systemImage: "film",
                        description: Text("The connected catalog is empty, or still loading.")
                    )
                } else {
                    List(grid.videos) { video in
                        HStack {
                            Image(systemName: "film")
                                .foregroundStyle(.secondary)
                            VStack(alignment: .leading) {
                                Text(video.filename).lineLimit(1)
                                if video.width > 0 && video.height > 0 {
                                    Text("\(video.width)×\(video.height)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("ReelVault")
        }
        .task { grid.loadVideos() }
    }
}
