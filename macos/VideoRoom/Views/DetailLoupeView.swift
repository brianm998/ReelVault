// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI
import AVKit
import AVFoundation
import AppKit

/// Three-state info overlay cycle, advanced by the 'i' key (Lightroom-style).
enum InfoOverlayState: Equatable {
    case none
    case camera   // camera model + lens model
    case file     // filename, capture date, resolution + megapixels
}

/// Single-video loupe view: shows the selected video full-size with hover-scrub
/// preview (just like the grid card), and once the user presses Play, swaps in
/// an AVKit player surface with a bottom scrubber + step controls. Reuses the
/// existing scrub-frame cache from GridViewModel so the static preview is
/// instant.
struct DetailLoupeView: View {
    @ObservedObject var gridViewModel: GridViewModel
    @ObservedObject var detailViewModel: DetailViewModel
    let infoOverlay: InfoOverlayState

    /// Configurable step size for the ±N-frame buttons. Default 20.
    @State private var stepFrames: Int = 20

    /// AVPlayer is created lazily on the first Play press. We keep it as state
    /// rather than recreating it from URL each render so the existing playback
    /// position survives recompositions of the surrounding view.
    @State private var player: AVPlayer? = nil
    @State private var playerVideoId: String? = nil
    @State private var isPlaying: Bool = false
    @State private var currentTimeSec: Double = 0
    @State private var durationSec: Double = 0
    /// Time-observer token; we drop it when the player goes away.
    @State private var timeObserver: Any? = nil

    /// The video summary currently being inspected — drawn from the grid
    /// selection. Recomputes when the selection changes so the loupe always
    /// follows the user's pick.
    private var video: VideoSummary? {
        gridViewModel.videos.first(where: { $0.id == gridViewModel.selectedVideoId })
    }

    private var metadata: VideoMetadata? { detailViewModel.metadata }

    var body: some View {
        VStack(spacing: 0) {
            if let video = video {
                content(for: video)
            } else {
                emptyState
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.001))  // captures hover/clicks
        .onChange(of: gridViewModel.selectedVideoId) { _, _ in
            // Selection changed → tear down any in-flight playback so the
            // next "play" press starts fresh on the new video.
            teardownPlayer()
        }
        .onDisappear { teardownPlayer() }
    }

    // MARK: - Empty / placeholder

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "film")
                .font(.system(size: 56))
                .foregroundColor(.secondary)
            Text("Select a video in the grid to view it here.")
                .foregroundColor(.secondary)
            Text("Press G to return to the grid.")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Main content

    @ViewBuilder
    private func content(for video: VideoSummary) -> some View {
        ZStack(alignment: .topLeading) {
            // Preview area — AVKit player once playback has started for the
            // current video, otherwise the static hover-scrub frame mosaic.
            if playerVideoId == video.id, let player = player {
                AVPlayerNSView(player: player)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black)
            } else {
                ScrubPreview(
                    video: video,
                    thumbnail: gridViewModel.thumbnails[video.id],
                    scrubFrames: gridViewModel.scrubFrames[video.id] ?? [],
                    onHoverEnter: { gridViewModel.loadScrubFrames(videoId: video.id) }
                )
            }

            // Cycling info overlay (top-left).
            if infoOverlay != .none {
                InfoOverlayView(state: infoOverlay, video: video, metadata: metadata)
                    .padding(12)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)

        ControlBar(
            video: video,
            isPlaying: isPlaying,
            currentTimeSec: currentTimeSec,
            durationSec: durationSec > 0 ? durationSec : Double(video.durationMs) / 1000.0,
            playbackStarted: playerVideoId == video.id,
            stepFrames: $stepFrames,
            onPlayPause: { onPlayPause(for: video) },
            onStepBackOne: { stepFrames(by: -1, for: video) },
            onStepForwardOne: { stepFrames(by: 1, for: video) },
            onStepBackN: { stepFrames(by: -stepFrames, for: video) },
            onStepForwardN: { stepFrames(by: stepFrames, for: video) },
            onScrub: { seconds in seek(to: seconds) }
        )
    }

    // MARK: - Player lifecycle

    private func onPlayPause(for video: VideoSummary) {
        if playerVideoId != video.id {
            // First play for this video: build a fresh AVPlayer.
            teardownPlayer()
            let url = URL(fileURLWithPath: video.path)
            let p = AVPlayer(url: url)
            attachObservers(to: p)
            player = p
            playerVideoId = video.id
            p.play()
            isPlaying = true
        } else if let p = player {
            if p.timeControlStatus == .playing {
                p.pause()
                isPlaying = false
            } else {
                p.play()
                isPlaying = true
            }
        }
    }

    private func stepFrames(by frames: Int, for video: VideoSummary) {
        let fps = video.fps > 0 ? video.fps : 30.0
        // Make sure playback has been mounted — without it stepping has
        // nothing to step against. We start paused at the very beginning so
        // the user can step before they ever press play.
        if playerVideoId != video.id {
            teardownPlayer()
            let url = URL(fileURLWithPath: video.path)
            let p = AVPlayer(url: url)
            attachObservers(to: p)
            player = p
            playerVideoId = video.id
        }
        guard let p = player else { return }
        // Pause while stepping so successive presses don't fight the playhead.
        p.pause()
        isPlaying = false
        let deltaSec = Double(frames) / fps
        let target = (currentTimeSec + deltaSec).clamped(to: 0...(durationSec > 0 ? durationSec : .infinity))
        let time = CMTime(seconds: target, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        p.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
    }

    private func seek(to seconds: Double) {
        guard let p = player else { return }
        let time = CMTime(seconds: seconds, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        p.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
    }

    private func attachObservers(to p: AVPlayer) {
        // Periodic time observer fires ~10x/sec while playing, drives the
        // scrubber. We deliberately don't drive it faster — the scrubber is
        // visual and 100ms granularity is plenty.
        let interval = CMTime(seconds: 0.1, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        timeObserver = p.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
            currentTimeSec = time.seconds.isFinite ? time.seconds : 0
            if let item = p.currentItem {
                let dur = item.duration.seconds
                if dur.isFinite && dur > 0 { durationSec = dur }
            }
        }
    }

    private func teardownPlayer() {
        if let token = timeObserver, let p = player {
            p.removeTimeObserver(token)
        }
        timeObserver = nil
        player?.pause()
        player = nil
        playerVideoId = nil
        isPlaying = false
        currentTimeSec = 0
        durationSec = 0
    }
}

// MARK: - Hover-scrub preview (no playback)

/// Renders the chosen video's thumbnail; when the cursor is hovering, swaps in
/// the scrub frame whose horizontal position matches the cursor. Mirrors the
/// behavior in VideoCardView, just sized to fill the available area.
private struct ScrubPreview: View {
    let video: VideoSummary
    let thumbnail: NSImage?
    let scrubFrames: [NSImage?]
    let onHoverEnter: () -> Void

    @State private var hoverX: CGFloat? = nil
    @State private var areaWidth: CGFloat = 0
    @State private var didEnter: Bool = false

    private var displayed: NSImage? {
        if let x = hoverX, areaWidth > 0, !scrubFrames.isEmpty {
            let frac = max(0, min(1, x / areaWidth))
            let idx = min(scrubFrames.count - 1, Int(frac * CGFloat(scrubFrames.count)))
            if let frame = scrubFrames[idx] { return frame }
        }
        return thumbnail
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black
                if let img = displayed {
                    Image(nsImage: img)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    Image(systemName: "film")
                        .font(.system(size: 96))
                        .foregroundColor(.secondary)
                }
            }
            .onAppear { areaWidth = geo.size.width }
            .onChange(of: geo.size.width) { _, w in areaWidth = w }
            .onContinuousHover { phase in
                switch phase {
                case .active(let p):
                    if !didEnter {
                        didEnter = true
                        onHoverEnter()
                    }
                    hoverX = p.x
                case .ended:
                    didEnter = false
                    hoverX = nil
                }
            }
        }
    }
}

// MARK: - Info overlay

private struct InfoOverlayView: View {
    let state: InfoOverlayState
    let video: VideoSummary
    let metadata: VideoMetadata?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            switch state {
            case .none:
                EmptyView()
            case .camera:
                let camera = (metadata?.cameraModel.isEmpty == false ? metadata!.cameraModel : "Camera: unknown")
                let lens = (metadata?.lensModel.isEmpty == false ? metadata!.lensModel : "Lens: unknown")
                Text(camera)
                    .foregroundColor(.white)
                    .font(.system(size: 13, weight: .semibold))
                Text(lens)
                    .foregroundColor(.white.opacity(0.85))
                    .font(.system(size: 11))
            case .file:
                let captured: String = {
                    if let m = metadata, m.creationDate > 0 { return m.creationDateFormatted }
                    if video.creationDate > 0 {
                        let d = Date(timeIntervalSince1970: TimeInterval(video.creationDate / 1000))
                        return d.formatted(date: .abbreviated, time: .shortened)
                    }
                    return "Unknown"
                }()
                let mp = video.width > 0 && video.height > 0
                    ? String(format: "%.1f MP", Double(video.width * video.height) / 1_000_000.0)
                    : "—"
                Text(video.filename)
                    .foregroundColor(.white)
                    .font(.system(size: 13, weight: .semibold))
                Text("Captured: \(captured)")
                    .foregroundColor(.white.opacity(0.85))
                    .font(.system(size: 11))
                Text("\(video.width) × \(video.height) (\(mp))")
                    .foregroundColor(.white.opacity(0.85))
                    .font(.system(size: 11))
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(Color.black.opacity(0.6))
        .cornerRadius(6)
    }
}

// MARK: - Bottom control bar

private struct ControlBar: View {
    let video: VideoSummary
    let isPlaying: Bool
    let currentTimeSec: Double
    let durationSec: Double
    let playbackStarted: Bool
    @Binding var stepFrames: Int
    let onPlayPause: () -> Void
    let onStepBackOne: () -> Void
    let onStepForwardOne: () -> Void
    let onStepBackN: () -> Void
    let onStepForwardN: () -> Void
    let onScrub: (Double) -> Void

    private var maxSeconds: Double { max(durationSec, 0.1) }

    var body: some View {
        VStack(spacing: 4) {
            // Scrubber row.
            HStack(spacing: 8) {
                Text(formatTime(currentTimeSec))
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(.secondary)
                    .frame(width: 56, alignment: .trailing)
                Slider(
                    value: Binding(
                        get: { min(currentTimeSec, maxSeconds) },
                        set: { onScrub($0) }
                    ),
                    in: 0...maxSeconds
                )
                .disabled(!playbackStarted)
                Text(formatTime(durationSec))
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(.secondary)
                    .frame(width: 56, alignment: .leading)
            }

            // Buttons row.
            HStack(spacing: 12) {
                Spacer()
                Button(action: onStepBackN) {
                    Image(systemName: "gobackward")
                }
                .buttonStyle(.borderless)
                .help("Step back \(stepFrames) frames")

                Button(action: onStepBackOne) {
                    Image(systemName: "backward.frame")
                }
                .buttonStyle(.borderless)
                .help("Step back 1 frame")

                Button(action: onPlayPause) {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 22))
                }
                .buttonStyle(.borderless)
                .help(isPlaying ? "Pause" : "Play in-app")

                Button(action: onStepForwardOne) {
                    Image(systemName: "forward.frame")
                }
                .buttonStyle(.borderless)
                .help("Step forward 1 frame")

                Button(action: onStepForwardN) {
                    Image(systemName: "goforward")
                }
                .buttonStyle(.borderless)
                .help("Step forward \(stepFrames) frames")

                Spacer()

                // Configurable step size.
                HStack(spacing: 4) {
                    Text("Step:")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                    Button("-5") { stepFrames = max(1, stepFrames - 5) }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    Text("\(stepFrames)")
                        .font(.system(size: 11, design: .monospaced))
                        .frame(width: 30)
                    Button("+5") { stepFrames = min(600, stepFrames + 5) }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                }
                .help("How many frames the \"step ±N\" buttons skip. Default 20.")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color(.windowBackgroundColor))
        .overlay(
            Rectangle()
                .frame(height: 1)
                .foregroundColor(Color(.separatorColor)),
            alignment: .top
        )
    }

    private func formatTime(_ seconds: Double) -> String {
        let s = Int(max(0, seconds))
        let h = s / 3600
        let m = (s % 3600) / 60
        let sec = s % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, sec) }
        return String(format: "%d:%02d", m, sec)
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
