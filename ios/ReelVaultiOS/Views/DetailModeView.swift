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
    @State private var stepFrames = 20

    private var video: VideoSummary? {
        grid.videos.first(where: { $0.id == grid.selectedVideoId }) ?? grid.selectedVideo
    }

    var body: some View {
        Group {
            if let video {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        VStack(spacing: 4) {
                            StreamingPlayerView(stream: stream, video: video, endpoint: connection,
                                                refreshTick: grid.catalogChangeTick,
                                                isFullScreenActive: fullScreen,
                                                onDoubleTap: { fullScreen = true })
                            FrameStepControlBar(stream: stream, video: video, endpoint: connection,
                                                stepFrames: $stepFrames)
                        }
                        OfflineDownloadButton(video: video, endpoint: connection)
                        MetadataEditorSection(grid: grid, videoId: video.id)
                        VideoMetadataSection(video: video, refreshTick: grid.catalogChangeTick,
                                             grid: grid)
                        VideoDetailExtras(grid: grid, video: video)
                        LocationButtonsSection(grid: grid, videoId: video.id, onShowOnMap: onShowOnMap)
                        CaptureDateButtonSection(grid: grid, videoId: video.id)
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
                    FullScreenPlayer(stream: stream, video: video, endpoint: connection,
                                     stepFrames: $stepFrames)
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
///
/// Frame-step controls appear as a semi-transparent auto-hiding overlay at the
/// bottom — tap anywhere to reveal them (they hide after 3 s of inactivity).
struct FullScreenPlayer: View {
    @ObservedObject var stream: StreamPlayer
    let video: VideoSummary
    let endpoint: AppRouter.ConnectionInfo?
    /// Shared step-size with the inline control bar so the user's setting
    /// persists between inline and full-screen mode.
    @Binding var stepFrames: Int
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
                                autoPlay: true, fill: true,
                                onDoubleTap: { dismiss() })
                .ignoresSafeArea()
                .offset(y: dragOffset)

            // Frame-step overlay — auto-hides; tap anywhere to reveal.
            FullScreenFrameStepOverlay(stream: stream, video: video, endpoint: endpoint,
                                       stepFrames: $stepFrames, onDismiss: { dismiss() })
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
