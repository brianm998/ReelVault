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
    @State private var fullScreen = false

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
        .toolbar {
            // Full-screen playback — available on iPhone too (not just the iPad
            // Detail mode), so compact users aren't stuck with the small inline player.
            ToolbarItem(placement: .topBarTrailing) {
                Button { fullScreen = true } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                }
                .help("Full screen")
            }
        }
        .fullScreenCover(isPresented: $fullScreen) {
            // Shares the SAME StreamPlayer as the inline view → one AVPlayer, no
            // doubled/offset audio (the K4 fix).
            FullScreenPlayer(stream: stream, video: video, endpoint: mediaEndpoint)
        }
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
    /// Diagnostics for the current item: the "unable to play" triangle otherwise
    /// fails silently. The HLS error log names the failing segment URI + HTTP
    /// status, which (with the loopback-proxy trace and the daemon log) pins down
    /// intermittent failures.
    private var diagObservers: [NSObjectProtocol] = []
    private var statusObservation: NSKeyValueObservation?

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
                let item = AVPlayerItem(asset: asset)
                attachDiagnostics(to: item)
                let p = AVPlayer(playerItem: item)
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
            attachDiagnostics(to: item)
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
            clearDiagnostics()
        }
    }

    private func teardownProxy() {
        proxy?.stop()
        proxy = nil
    }

    /// Log a playback failure (the silent "unable to play" triangle) with the
    /// AVPlayer error log — which records the failing segment URI + HTTP status —
    /// plus stalls and the item's terminal error.
    private func attachDiagnostics(to item: AVPlayerItem) {
        clearDiagnostics()
        statusObservation = item.observe(\.status, options: [.new]) { item, _ in
            if item.status == .failed {
                NSLog("ReelVault player: item FAILED — \(item.error?.localizedDescription ?? "unknown error")")
            }
        }
        let nc = NotificationCenter.default
        diagObservers.append(nc.addObserver(
            forName: .AVPlayerItemNewErrorLogEntry, object: item, queue: .main
        ) { [weak item] _ in
            guard let event = item?.errorLog()?.events.last else { return }
            NSLog("ReelVault player: HLS error — status=\(event.errorStatusCode) domain=\(event.errorDomain) uri=\(event.uri ?? "—") comment=\(event.errorComment ?? "—")")
        })
        diagObservers.append(nc.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main
        ) { note in
            let err = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            NSLog("ReelVault player: failed to play to end — \(err?.localizedDescription ?? "unknown")")
        })
        diagObservers.append(nc.addObserver(
            forName: .AVPlayerItemPlaybackStalled, object: item, queue: .main
        ) { _ in
            NSLog("ReelVault player: playback stalled (buffering / waiting on segments)")
        })
    }

    private func clearDiagnostics() {
        statusObservation?.invalidate()
        statusObservation = nil
        diagObservers.forEach { NotificationCenter.default.removeObserver($0) }
        diagObservers.removeAll()
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

/// The read-only metadata list shown in the inspector (iPad), Detail mode, and
/// the compact (iPhone) detail screen: technical details, a named status-badge
/// list, and the proxy list (fetched per selection).
struct VideoMetadataSection: View {
    let video: VideoSummary
    @State private var proxies: [VideoRepository.ProxyInfo] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            detailsGroup
            StatusBadgeList(video: video)
            if !proxies.isEmpty { proxiesGroup }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // Proxy details aren't on VideoSummary (only a count) — fetch the list
        // for the selected video, same as the macOS inspector.
        .task(id: video.id) {
            proxies = (try? await VideoRepository.shared.listProxies(videoId: video.id)) ?? []
        }
    }

    private var detailsGroup: some View {
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
    }

    private var proxiesGroup: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Proxies (\(proxies.count))").font(.headline)
            ForEach(proxies) { proxy in
                VStack(alignment: .leading, spacing: 2) {
                    Text(proxy.filename).font(.callout).lineLimit(1)
                    Text(Self.proxyDetail(proxy)).font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    /// "720p • 125 MB • auto-detected" — matches the macOS proxy row.
    private static func proxyDetail(_ proxy: VideoRepository.ProxyInfo) -> String {
        var parts: [String] = []
        if proxy.height > 0 { parts.append("\(proxy.height)p") }
        if proxy.sizeBytes > 0 {
            parts.append(ByteCountFormatter.string(fromByteCount: proxy.sizeBytes, countStyle: .file))
        }
        if proxy.autoDetected { parts.append("auto-detected") }
        return parts.joined(separator: " • ")
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

/// A named, vertical list of the same status badges shown on grid cards
/// (keywords / proxies / full-resolution / audio / location) — for the detail
/// panel, where each badge gets a label instead of just an icon.
struct StatusBadgeList: View {
    let video: VideoSummary

    var body: some View {
        let rows = rows
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Status").font(.headline)
                ForEach(rows) { row in
                    HStack(spacing: 8) {
                        Image(systemName: row.icon)
                            .foregroundStyle(row.tint)
                            .frame(width: 22)
                        Text(row.label).font(.callout)
                        Spacer(minLength: 0)
                    }
                }
            }
        }
    }

    private struct Row: Identifiable { let id: String; let icon: String; let tint: Color; let label: String }

    private var rows: [Row] {
        var out: [Row] = []
        if !video.tags.isEmpty {
            out.append(Row(id: "tags", icon: "tag.fill", tint: Color(red: 0.39, green: 0.71, blue: 0.96),
                           label: "\(video.tags.count) keyword\(video.tags.count == 1 ? "" : "s")"))
        }
        if video.hasProxies {
            out.append(Row(id: "proxies", icon: "rectangle.on.rectangle.angled", tint: .primary,
                           label: "\(video.proxyCount) prox\(video.proxyCount == 1 ? "y" : "ies") available"))
        }
        switch video.fullResolution {
        case .full:
            out.append(Row(id: "res", icon: "checkmark.seal.fill", tint: Color(red: 0.51, green: 0.78, blue: 0.52),
                           label: "Full resolution"))
        case .notFull:
            out.append(Row(id: "res", icon: "crop", tint: .primary, label: "Not full resolution"))
        case .unspecified:
            break
        }
        if video.hasAudio {
            out.append(Row(id: "audio", icon: "waveform", tint: .primary, label: "Has audio"))
        }
        if video.hasLocation {
            out.append(Row(id: "loc", icon: "mappin.circle.fill", tint: .red, label: "Has location"))
        }
        return out
    }
}
