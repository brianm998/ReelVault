// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Detail view mode: the big playable video for the current selection, plus its
/// metadata, with a full-screen button. Playback lives here (not the inspector).
/// Shows an empty state until a video is selected (in Grid/List mode).
struct DetailModeView: View {
    @ObservedObject var grid: GridViewModel
    let connection: AppRouter.ConnectionInfo?
    /// Switch to the internal Map mode (the "Show on Map" location action).
    var onShowOnMap: () -> Void = {}
    @StateObject private var stream = StreamPlayer()
    @State private var fullScreen = false

    private var video: VideoSummary? {
        grid.videos.first(where: { $0.id == grid.selectedVideoId }) ?? grid.selectedVideo
    }

    var body: some View {
        Group {
            if let video {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        StreamingPlayerView(stream: stream, video: video, endpoint: connection,
                                            refreshTick: grid.catalogChangeTick)
                        OfflineDownloadButton(video: video, endpoint: connection)
                        MetadataEditorSection(grid: grid, videoId: video.id)
                        VideoMetadataSection(video: video, refreshTick: grid.catalogChangeTick)
                        VideoDetailExtras(grid: grid, video: video)
                        LocationButtonsSection(grid: grid, videoId: video.id, onShowOnMap: onShowOnMap)
                        DetailGraphsView(grid: grid, videoId: video.id)
                    }
                    .padding()
                }
                .navigationTitle(video.filename)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { fullScreen = true } label: {
                            Image(systemName: "arrow.up.left.and.arrow.down.right")
                        }
                        .help("Full screen")
                    }
                }
                .fullScreenCover(isPresented: $fullScreen) {
                    FullScreenPlayer(stream: stream, video: video, endpoint: connection)
                }
            } else {
                ContentUnavailableView(
                    "No video selected",
                    systemImage: "play.rectangle",
                    description: Text("Pick a video in Grid or List mode to view it here.")
                )
            }
        }
    }
}

/// A black, edge-to-edge full-screen player dismissed by swiping down (no close
/// button — matching the system video full-screen gesture). Reuses the SAME
/// `StreamPlayer` as the inline detail player (one AVPlayer → no doubled, offset
/// audio), just rendered full-bleed and auto-playing.
struct FullScreenPlayer: View {
    @ObservedObject var stream: StreamPlayer
    let video: VideoSummary
    let endpoint: AppRouter.ConnectionInfo?
    @Environment(\.dismiss) private var dismiss
    /// Live downward drag distance — moves the player with the finger and fades
    /// the backdrop so the swipe reads as an interactive dismissal.
    @State private var dragOffset: CGFloat = 0

    private let dismissThreshold: CGFloat = 120

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
                .opacity(1 - min(Double(dragOffset) / 500, 0.7))
            StreamingPlayerView(stream: stream, video: video, endpoint: endpoint,
                                autoPlay: true, fill: true)
                .ignoresSafeArea()
                .offset(y: dragOffset)
        }
        .statusBarHidden(true)
        // simultaneousGesture so the player's own transport controls keep
        // working; we only react to predominantly-downward drags.
        .simultaneousGesture(
            DragGesture(minimumDistance: 20)
                .onChanged { value in
                    if value.translation.height > 0,
                       value.translation.height > abs(value.translation.width) {
                        dragOffset = value.translation.height
                    }
                }
                .onEnded { value in
                    if value.translation.height > dismissThreshold {
                        dismiss()
                    } else {
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                            dragOffset = 0
                        }
                    }
                }
        )
    }
}
