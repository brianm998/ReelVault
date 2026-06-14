// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Scrollable adaptive grid of video cards, bound to the shared GridViewModel.
/// (C4 adds the size-class-driven 3-column / sheet layout; this is the core grid
/// it composes.) Cards lazily load their thumbnails over gRPC.
struct VideoGridView: View {
    @ObservedObject var grid: GridViewModel
    /// Minimum card width; the grid flows as many columns as fit.
    var minCardWidth: CGFloat = 150

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: minCardWidth), spacing: 10)]
    }

    var body: some View {
        ScrollView {
            if grid.videos.isEmpty {
                ContentUnavailableView(
                    "No videos",
                    systemImage: "film",
                    description: Text("The connected catalog is empty, or still loading.")
                )
                .padding(.top, 80)
            } else {
                LazyVGrid(columns: columns, spacing: 10) {
                    ForEach(grid.videos) { video in
                        NavigationLink(value: video.id) {
                            VideoCardView(video: video, image: grid.thumbnails[video.id])
                                .onAppear { grid.loadThumbnail(videoId: video.id) }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(10)
            }
        }
    }
}

/// A single grid card: thumbnail (or placeholder) plus filename + resolution.
struct VideoCardView: View {
    let video: VideoSummary
    let image: PlatformImage?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack {
                Rectangle().fill(.quaternary)
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: "film")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                }
            }
            .aspectRatio(16.0 / 9.0, contentMode: .fill)
            .frame(maxWidth: .infinity)
            .clipped()
            .clipShape(RoundedRectangle(cornerRadius: 8))

            Text(video.filename)
                .font(.caption)
                .lineLimit(1)
            if video.width > 0 && video.height > 0 {
                Text("\(video.width)×\(video.height)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
    }
}
