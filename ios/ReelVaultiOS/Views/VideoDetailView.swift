// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AVKit
import SwiftUI
import ReelVaultKit

/// Full-screen detail screen (iPhone / compact width): a streaming player
/// (downscaled to fit) plus metadata. Pushed onto the navigation stack when a
/// card is tapped. The iPad / regular layout shows the same content in the
/// `InspectorPanel` side column instead — both compose the shared
/// `StreamingPlayerView` + `VideoMetadataSection` below.
struct VideoDetailView: View {
    let video: VideoSummary
    let mediaEndpoint: AppRouter.ConnectionInfo?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                StreamingPlayerView(video: video, endpoint: mediaEndpoint)
                VideoMetadataSection(video: video)
            }
            .padding()
        }
        .navigationTitle(video.filename)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// A streaming player for one video, downscaled by the daemon to fit. Playback
/// streams the daemon's rendition over the pinned media endpoint via
/// `MediaClient` (the core HTTPS media server, A3/A4). Resets when `video`
/// changes so the inspector follows the grid selection.
struct StreamingPlayerView: View {
    let video: VideoSummary
    let endpoint: AppRouter.ConnectionInfo?

    @State private var player: AVPlayer?
    @State private var isPreparing = false
    @State private var playbackError: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(.black)
                if let player {
                    VideoPlayer(player: player)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                } else if isPreparing {
                    ProgressView().tint(.white)
                } else {
                    Button {
                        Task { await preparePlayback() }
                    } label: {
                        Label("Play", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .aspectRatio(16.0 / 9.0, contentMode: .fit)

            if let playbackError {
                Text(playbackError)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .onChange(of: video.id) { _, _ in
            player?.pause()
            player = nil
            playbackError = nil
        }
        .onDisappear { player?.pause() }
    }

    private func preparePlayback() async {
        guard let conn = endpoint else {
            playbackError = "No media connection available."
            return
        }
        isPreparing = true
        defer { isPreparing = false }
        let mediaEndpoint = MediaClient.Endpoint(
            host: conn.host,
            mediaPort: conn.mediaPort,
            fingerprintHex: conn.fingerprintHex,
            bearerToken: conn.bearerToken
        )
        // Natively-playable videos stream as-is (height 0 = original, range-served,
        // no server transcode); everything else is downscaled to fit.
        let height = video.playableNatively ? 0 : 720
        do {
            let item = try await MediaClient().playerItem(videoId: video.id, height: height, from: mediaEndpoint)
            let p = AVPlayer(playerItem: item)
            player = p
            p.play()
        } catch {
            playbackError = "Streaming requires the ReelVault media server, which isn't available yet."
        }
    }
}

/// The read-only metadata list shared by the detail screen and the inspector.
struct VideoMetadataSection: View {
    let video: VideoSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Details").font(.headline)
            detailRow("File", video.filename)
            if video.width > 0 && video.height > 0 {
                detailRow("Resolution", video.resolution)
            }
            if !video.codecVideo.isEmpty {
                detailRow("Codec", video.codecVideo)
            }
            if video.durationMs > 0 {
                detailRow("Duration", video.durationFormatted)
            }
            if !video.cameraDisplayName.isEmpty {
                detailRow("Camera", video.cameraDisplayName)
            }
            if !video.lensModel.isEmpty {
                detailRow("Lens", video.lensModel)
            }
            if video.hasLocation {
                detailRow("Location", String(format: "%.5f, %.5f", video.gpsLatitude, video.gpsLongitude))
            }
            detailRow("Path", video.path)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func detailRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label)
                .foregroundStyle(.secondary)
                .frame(width: 90, alignment: .leading)
            Text(value).textSelection(.enabled)
            Spacer(minLength: 0)
        }
        .font(.callout)
    }
}
