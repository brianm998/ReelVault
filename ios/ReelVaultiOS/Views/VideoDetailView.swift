// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AVKit
import SwiftUI
import UIKit
import ReelVaultKit

/// Full-screen detail screen (iPhone / compact width): a streaming player
/// (downscaled to fit) plus metadata. Pushed onto the navigation stack when a
/// card is tapped.
struct VideoDetailView: View {
    let video: VideoSummary
    let mediaEndpoint: AppRouter.ConnectionInfo?
    @StateObject private var stream = StreamPlayer()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                StreamingPlayerView(stream: stream, video: video, endpoint: mediaEndpoint)
                VideoMetadataSection(video: video)
            }
            .padding()
        }
        .navigationTitle(video.filename)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Owns a single `AVPlayer` for a streamed rendition. Sharing one instance
/// between the inline detail player and the full-screen cover guarantees the
/// same video never plays twice at once (which produced doubled, offset audio).
@MainActor
final class StreamPlayer: ObservableObject {
    @Published var player: AVPlayer?
    @Published var isPreparing = false
    @Published var error: String?
    private var preparedVideoId: String?
    /// Live loopback proxy backing an HLS stream; retained for the player's
    /// lifetime (segments 502 if it deallocs mid-playback).
    private var proxy: LoopbackMediaProxy?

    /// Prepare the rendition and start playing. Tries HLS streaming first (begins
    /// playing before the whole file transcodes, via a pinned loopback proxy),
    /// then falls back to download-then-play. No-ops if the same video is already
    /// prepared, so the inline view and the full-screen cover share one player
    /// rather than racing two.
    func prepare(video: VideoSummary, endpoint: AppRouter.ConnectionInfo?) async {
        if preparedVideoId == video.id, player != nil {
            player?.play()
            return
        }
        guard let conn = endpoint else { error = "No media connection available."; return }
        isPreparing = true
        defer { isPreparing = false }
        error = nil
        let mediaEndpoint = MediaClient.Endpoint(
            host: conn.host, mediaPort: conn.mediaPort,
            fingerprintHex: conn.fingerprintHex, bearerToken: conn.bearerToken)
        // Always request a fit-to-device height (never the raw original): the
        // server serves/transcodes the closest rendition — so big originals don't
        // stream raw over Wi-Fi.
        let height = Self.streamHeight()

        // 1) HLS streaming via the loopback proxy: AVPlayer plays as segments
        //    arrive instead of waiting for the whole file.
        teardownProxy()
        let proxy = LoopbackMediaProxy(endpoint: mediaEndpoint)
        do {
            _ = try await proxy.start()
            let asset = AVURLAsset(url: proxy.hlsURL(videoId: video.id, height: height))
            if (try? await asset.load(.isPlayable)) == true {
                self.proxy = proxy
                let p = AVPlayer(playerItem: AVPlayerItem(asset: asset))
                player = p
                preparedVideoId = video.id
                p.play()
                return
            }
            NSLog("ReelVault: HLS not playable for \(video.id) (h\(height)); falling back to download")
        } catch {
            NSLog("ReelVault: HLS proxy start failed for \(video.id): \(error); falling back to download")
        }
        proxy.stop()

        // 2) Fallback: download-then-play (pin-verified, correct but not progressive).
        do {
            let item = try await MediaClient().playerItem(
                videoId: video.id, height: height, ext: "mp4", from: mediaEndpoint)
            let p = AVPlayer(playerItem: item)
            player = p
            preparedVideoId = video.id
            p.play()
        } catch {
            NSLog("ReelVault: playback prepare failed for \(video.id) (h\(height)): \(error)")
            self.error = "Couldn't play this video: \(error.localizedDescription)"
        }
    }

    /// Tear down when the view's video changes, so a new selection doesn't keep
    /// playing the previous one.
    func resetIfDifferent(_ videoId: String) {
        if preparedVideoId != videoId {
            player?.pause()
            player = nil
            preparedVideoId = nil
            error = nil
            teardownProxy()
        }
    }

    private func teardownProxy() {
        proxy?.stop()
        proxy = nil
    }

    func pause() { player?.pause() }

    /// Target playback height: the device's native pixel height, capped at 1440
    /// so a no-proxy fallback still transcodes down to a Wi-Fi-friendly size.
    static func streamHeight() -> Int {
        let native = Int(UIScreen.main.nativeBounds.height)
        return min(max(native, 480), 1440)
    }
}

/// The player surface, bound to a (possibly shared) `StreamPlayer`.
struct StreamingPlayerView: View {
    @ObservedObject var stream: StreamPlayer
    let video: VideoSummary
    let endpoint: AppRouter.ConnectionInfo?
    /// Begin playing as soon as the view appears (full-screen mode).
    var autoPlay: Bool = false
    /// Fill the available space instead of a 16:9 box (full-screen mode).
    var fill: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            playerBox
            if let error = stream.error {
                Text(error).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .task(id: video.id) {
            stream.resetIfDifferent(video.id)
            if autoPlay { await stream.prepare(video: video, endpoint: endpoint) }
        }
        .onDisappear { if !fill { stream.pause() } }
    }

    @ViewBuilder private var playerBox: some View {
        let box = ZStack {
            if !fill { RoundedRectangle(cornerRadius: 10).fill(.black) }
            if let player = stream.player {
                VideoPlayer(player: player)
                    .clipShape(RoundedRectangle(cornerRadius: fill ? 0 : 10))
            } else if stream.isPreparing {
                ProgressView().tint(.white)
            } else {
                Button {
                    Task { await stream.prepare(video: video, endpoint: endpoint) }
                } label: {
                    Label("Play", systemImage: "play.fill")
                }
                .buttonStyle(.borderedProminent)
            }
        }
        if fill {
            box.frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            box.aspectRatio(16.0 / 9.0, contentMode: .fit)
        }
    }
}

/// The read-only metadata list shown in Detail mode and the compact detail screen.
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
            // The server-side file path is intentionally omitted — it's
            // meaningless on a remote iOS client with no filesystem access.
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
