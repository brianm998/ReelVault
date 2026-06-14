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
                        StreamingPlayerView(stream: stream, video: video, endpoint: connection)
                        VideoMetadataSection(video: video)
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

/// A black, edge-to-edge full-screen player with a close button. Reuses the
/// SAME `StreamPlayer` as the inline detail player (one AVPlayer → no doubled,
/// offset audio), just rendered full-bleed and auto-playing.
struct FullScreenPlayer: View {
    @ObservedObject var stream: StreamPlayer
    let video: VideoSummary
    let endpoint: AppRouter.ConnectionInfo?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            StreamingPlayerView(stream: stream, video: video, endpoint: endpoint,
                                autoPlay: true, fill: true)
                .ignoresSafeArea()
            Button { dismiss() } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title)
                    .foregroundStyle(.white, .black.opacity(0.5))
                    .padding()
            }
        }
        .statusBarHidden(true)
    }
}
