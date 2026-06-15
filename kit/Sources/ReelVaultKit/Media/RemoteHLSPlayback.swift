// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AVFoundation
import Foundation

/// Builds an `AVPlayer` that streams a video from a REMOTE daemon over HLS, via a
/// fingerprint-pinned loopback proxy. Shared by the Apple clients' remote mode:
/// it waits until the rendition is a complete, seekable VOD (so the duration and
/// scrub bar work) or a cap, then returns the player together with the loopback
/// proxy that must be retained for the player's lifetime (the HLS segments 502 if
/// it deallocs mid-playback). Falls back to a download-then-play item if HLS fails.
///
/// This is the desktop counterpart of the iOS `StreamPlayer` remote path; it
/// deliberately omits iOS-only concerns (Photos/local-file playback, the
/// full-screen-cover shared player). A future pass can route iOS through it too.
public enum RemoteHLSPlayback {
    /// A ready-to-play remote stream.
    public struct Prepared {
        public let player: AVPlayer
        /// Retain for the player's lifetime; call `.stop()` on teardown. `nil` when
        /// the download fallback was used (no live proxy backs it).
        public let proxy: LoopbackMediaProxy?
    }

    /// Prepare a player streaming `videoId` at `height` (0 = original/best) from
    /// `endpoint`. Waits up to `maxWaitSeconds` for the rendition to complete so
    /// the scrub bar works; a slower transcode plays progressively once it's
    /// playable. Returns `nil` only if neither HLS nor the download fallback could
    /// produce a playable item.
    @MainActor
    public static func prepare(
        endpoint: MediaClient.Endpoint, videoId: String,
        height: Int, autoPlay: Bool, maxWaitSeconds: TimeInterval = 60
    ) async -> Prepared? {
        // 1) HLS via the pinned loopback proxy.
        let proxy = LoopbackMediaProxy(endpoint: endpoint)
        if (try? await proxy.start()) != nil {
            let outcome = await waitUntilReady(
                proxy: proxy, videoId: videoId, height: height, maxWaitSeconds: maxWaitSeconds)
            if outcome != .failed {
                let asset = AVURLAsset(url: proxy.hlsURL(videoId: videoId, height: height))
                if (try? await asset.load(.isPlayable)) == true {
                    let item = AVPlayerItem(asset: asset)
                    let p = AVPlayer(playerItem: item)
                    if autoPlay { p.play() }
                    NSLog("ReelVault macOS: HLS \(autoPlay ? "playing" : "ready") \(videoId) (h\(height), \(outcome))")
                    return Prepared(player: p, proxy: proxy)
                }
            }
            NSLog("ReelVault macOS: HLS not playable for \(videoId) (h\(height)) — falling back to download")
        }
        proxy.stop()

        // 2) Fallback: download-then-play (pin-verified, correct but not progressive).
        if let item = try? await MediaClient().playerItem(
            videoId: videoId, height: height, ext: "mp4", from: endpoint) {
            let p = AVPlayer(playerItem: item)
            if autoPlay { p.play() }
            return Prepared(player: p, proxy: nil)
        }
        NSLog("ReelVault macOS: remote playback failed for \(videoId) (h\(height))")
        return nil
    }

    enum ReadyOutcome { case ready, capped, failed }

    /// Poll the server's HLS `/status` (which also starts the transcode) until the
    /// session is complete (ENDLIST → seekable VOD), the transcode fails, or we hit
    /// the cap (then play progressively). Mirrors the iOS readiness gate.
    @MainActor
    private static func waitUntilReady(
        proxy: LoopbackMediaProxy, videoId: String,
        height: Int, maxWaitSeconds: TimeInterval
    ) async -> ReadyOutcome {
        let statusURL = proxy.statusURL(videoId: videoId, height: height)
        let start = Date()
        while Date().timeIntervalSince(start) < maxWaitSeconds {
            if Task.isCancelled { return .ready }
            guard let status = await fetchStatus(statusURL) else { return .ready }
            if status.failed { return .failed }
            if status.complete { return .ready }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
        return .capped
    }

    private struct HLSStatus { let segments: Int; let complete: Bool; let failed: Bool }

    private static func fetchStatus(_ url: URL) async -> HLSStatus? {
        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return HLSStatus(
            segments: (obj["segments"] as? Int) ?? 0,
            complete: (obj["complete"] as? Bool) ?? false,
            failed: (obj["failed"] as? Bool) ?? false)
    }
}
