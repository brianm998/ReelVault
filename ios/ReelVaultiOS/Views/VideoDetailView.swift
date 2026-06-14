// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AVKit
import SwiftUI
import ReelVaultKit

/// Detail screen: a streaming player (downscaled to fit) plus metadata.
///
/// Playback streams the daemon's downscaled rendition over the pinned media
/// endpoint via `MediaClient`. That endpoint is the core HTTPS media server
/// (A3/A4) — until it ships, `preparePlayback()` fails gracefully with a note.
struct VideoDetailView: View {
    let video: VideoSummary
    let mediaEndpoint: AppRouter.ConnectionInfo?

    @State private var player: AVPlayer?
    @State private var isPreparing = false
    @State private var playbackError: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                playerSection
                metadataSection
            }
            .padding()
        }
        .navigationTitle(video.filename)
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { player?.pause() }
    }

    @ViewBuilder private var playerSection: some View {
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

    private func preparePlayback() async {
        guard let conn = mediaEndpoint else {
            playbackError = "No media connection available."
            return
        }
        isPreparing = true
        defer { isPreparing = false }
        let endpoint = MediaClient.Endpoint(
            host: conn.host,
            mediaPort: conn.mediaPort,
            fingerprintHex: conn.fingerprintHex,
            bearerToken: conn.bearerToken
        )
        // Natively-playable videos stream as-is (height 0 = original, range-served,
        // no server transcode); everything else is downscaled to fit.
        let height = video.playableNatively ? 0 : 720
        do {
            let item = try await MediaClient().playerItem(videoId: video.id, height: height, from: endpoint)
            let p = AVPlayer(playerItem: item)
            player = p
            p.play()
        } catch {
            playbackError = "Streaming requires the ReelVault media server, which isn't available yet."
        }
    }

    @ViewBuilder private var metadataSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Details").font(.headline)
            detailRow("File", video.filename)
            if video.width > 0 && video.height > 0 {
                detailRow("Resolution", "\(video.width)×\(video.height)")
            }
            if !video.codecVideo.isEmpty {
                detailRow("Codec", video.codecVideo)
            }
            if video.durationMs > 0 {
                detailRow("Duration", Self.formatDuration(ms: video.durationMs))
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

    static func formatDuration(ms: Int) -> String {
        let total = ms / 1000
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, s)
            : String(format: "%d:%02d", m, s)
    }
}
