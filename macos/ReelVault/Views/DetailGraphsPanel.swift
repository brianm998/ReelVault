// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AppKit
import ReelVaultKit
import SwiftUI

/// Per-scrub-frame averages used to plot how a clip's light and colour move
/// over its length. `luma` is Rec.601 luminance (0..1); `r`/`g`/`b` are the
/// mean channel values (0..1).
private struct FrameStat {
    let luma: Double
    let r: Double
    let g: Double
    let b: Double
    var color: Color { Color(red: r, green: g, blue: b) }
}

/// Detail-mode left panel: brightness and colour graphs derived from the
/// video's scrub thumbnails. Gives a quick read on how exposure and colour
/// shift across the clip without scrubbing through it frame by frame. Stats
/// are sampled from whatever scrub frames are loaded (the higher-res detail
/// set when present, else the base set), off the main thread.
struct DetailGraphsPanel: View {
    @ObservedObject var gridViewModel: GridViewModel
    let onCollapse: () -> Void

    @State private var stats: [FrameStat] = []

    private var selectedId: String? { gridViewModel.selectedVideoId }

    /// Best available frame per index: the detail-resolution thumbnail when
    /// present (steadier averages), otherwise the base scrub frame.
    private var frames: [NSImage?] {
        guard let id = selectedId else { return [] }
        let base = gridViewModel.scrubFrames[id] ?? []
        let hi = gridViewModel.hiResScrubFrames[id] ?? []
        if base.isEmpty { return hi }
        return base.enumerated().map { i, b in (i < hi.count ? hi[i] : nil) ?? b }
    }

    /// Cheap signature so recompute only fires when the frame set changes.
    private var frameSignature: String {
        (selectedId ?? "") + ":" + frames.map { $0 == nil ? "0" : "1" }.joined()
    }

    /// Audio loudness-over-time for the selected video (the daemon's ffmpeg
    /// series), each sample normalized to 0...1. Empty when there's no audio.
    private var loudness: [Float] { selectedId.flatMap { gridViewModel.audioLoudness[$0] } ?? [] }
    private var hasStats: Bool { stats.count >= 2 }
    private var hasLoudness: Bool { loudness.count >= 2 }
    /// Whether the loudness series has finished loading (the dict entry exists,
    /// empty or not). Distinguishes "still analysing" (entry absent) from "loaded,
    /// no audio" (entry present but empty) so a slow first decode isn't a silent
    /// blank. The first decode is a full-file audio pass over a (possibly slow,
    /// networked) library; the daemon caches the result so later opens are instant.
    private var loudnessLoaded: Bool { selectedId.flatMap { gridViewModel.audioLoudness[$0] } != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("VISUALS").font(.caption).foregroundColor(.secondary)
                Spacer()
                Button(action: onCollapse) {
                    Image(systemName: "chevron.left")
                }
                .buttonStyle(.plain)
                .help("Hide this panel (Tab)")
            }
            .padding(.horizontal, 12)
            .padding(.top, 12)
            .padding(.bottom, 8)
            Divider()

            if !hasStats && !hasLoudness {
                Spacer()
                Text(selectedId == nil ? "Select a video" : "Analyzing frames…")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity)
                Spacer()
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if hasStats {
                            sectionLabel("Brightness")
                            brightnessChart
                            sectionLabel("Color over time")
                            colorTimeline
                            sectionLabel("RGB channels")
                            rgbChart
                        }
                        // Loudness comes from the daemon (ffmpeg), independent of
                        // the scrub frames, so it can appear before/without them.
                        // Three states: ready → chart; still decoding → a hint
                        // (the first decode can be slow over a networked library);
                        // loaded-but-empty → nothing (the clip has no audio).
                        if hasLoudness {
                            sectionLabel("Loudness")
                            loudnessChart
                        } else if !loudnessLoaded {
                            sectionLabel("Loudness")
                            HStack(spacing: 6) {
                                ProgressView().controlSize(.small)
                                Text("Analyzing audio…").font(.caption).foregroundColor(.secondary)
                            }
                            .frame(maxWidth: .infinity, minHeight: 40, alignment: .center)
                            .background(Color(white: 0.12))
                            .cornerRadius(4)
                        }
                    }
                    .padding(12)
                }
            }
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Color(nsColor: .controlBackgroundColor))
        .onAppear { recompute() }
        .onChange(of: frameSignature) { _, _ in recompute() }
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text).font(.system(size: 11, weight: .medium)).foregroundColor(.secondary)
    }

    /// Filled luminance curve across the clip (left = start, right = end).
    private var brightnessChart: some View {
        Canvas { ctx, size in
            guard stats.count >= 2 else { return }
            let line = path(for: { $0.luma }, size: size)
            var area = line
            area.addLine(to: CGPoint(x: size.width, y: size.height))
            area.addLine(to: CGPoint(x: 0, y: size.height))
            area.closeSubpath()
            ctx.fill(area, with: .color(.white.opacity(0.15)))
            ctx.stroke(line, with: .color(.white), lineWidth: 2)
        }
        .frame(height: 72)
        .background(Color(white: 0.12))
        .cornerRadius(4)
    }

    /// A strip of each frame's average colour, in clip order.
    private var colorTimeline: some View {
        HStack(spacing: 0) {
            ForEach(Array(stats.enumerated()), id: \.offset) { _, s in
                Rectangle().fill(s.color)
            }
        }
        .frame(height: 40)
        .cornerRadius(4)
    }

    /// Three overlaid channel curves (red / green / blue) across the clip.
    private var rgbChart: some View {
        Canvas { ctx, size in
            guard stats.count >= 2 else { return }
            ctx.stroke(path(for: { $0.r }, size: size), with: .color(Color(red: 0.9, green: 0.45, blue: 0.45)), lineWidth: 2)
            ctx.stroke(path(for: { $0.g }, size: size), with: .color(Color(red: 0.5, green: 0.78, blue: 0.52)), lineWidth: 2)
            ctx.stroke(path(for: { $0.b }, size: size), with: .color(Color(red: 0.39, green: 0.71, blue: 0.96)), lineWidth: 2)
        }
        .frame(height: 72)
        .background(Color(white: 0.12))
        .cornerRadius(4)
    }

    /// Filled loudness curve across the clip (left = start, right = end). Values
    /// are normalized momentary loudness in 0...1 (silence ≈ 0, full scale ≈ 1).
    private var loudnessChart: some View {
        Canvas { ctx, size in
            guard loudness.count >= 2 else { return }
            let line = loudnessPath(size: size)
            var area = line
            area.addLine(to: CGPoint(x: size.width, y: size.height))
            area.addLine(to: CGPoint(x: 0, y: size.height))
            area.closeSubpath()
            let teal = Color(red: 0.30, green: 0.82, blue: 0.88)
            ctx.fill(area, with: .color(teal.opacity(0.20)))
            ctx.stroke(line, with: .color(teal), lineWidth: 2)
        }
        .frame(height: 72)
        .background(Color(white: 0.12))
        .cornerRadius(4)
    }

    private func loudnessPath(size: CGSize) -> Path {
        var p = Path()
        let n = loudness.count
        for (i, v) in loudness.enumerated() {
            let x = n > 1 ? size.width * CGFloat(i) / CGFloat(n - 1) : 0
            let y = size.height * (1 - CGFloat(max(0, min(1, Double(v)))))
            if i == 0 { p.move(to: CGPoint(x: x, y: y)) } else { p.addLine(to: CGPoint(x: x, y: y)) }
        }
        return p
    }

    private func path(for value: (FrameStat) -> Double, size: CGSize) -> Path {
        var p = Path()
        let n = stats.count
        for (i, s) in stats.enumerated() {
            let x = n > 1 ? size.width * CGFloat(i) / CGFloat(n - 1) : 0
            let y = size.height * (1 - CGFloat(max(0, min(1, value(s)))))
            if i == 0 { p.move(to: CGPoint(x: x, y: y)) } else { p.addLine(to: CGPoint(x: x, y: y)) }
        }
        return p
    }

    private func recompute() {
        if let id = selectedId {
            gridViewModel.loadScrubFrames(videoId: id)
            gridViewModel.loadAudioLoudness(videoId: id)
        }
        // Snapshot each frame to an immutable CGImage on the main actor before
        // handing work off: NSImage isn't Sendable and these instances stay
        // owned by the view model, but CGImage is, so the per-pixel averaging
        // can run off-main without risking a data race on the shared images.
        let cgImages = frames.compactMap { $0?.cgImage(forProposedRect: nil, context: nil, hints: nil) }
        Task.detached(priority: .utility) {
            let computed = cgImages.compactMap(Self.computeFrameStat)
            await MainActor.run { self.stats = computed }
        }
    }

    /// Downscale a frame to a small RGBA bitmap (Core Graphics averages while
    /// it scales) and average the pixels. Returns nil if the bitmap context
    /// can't be created. Takes a Sendable CGImage so it's safe to call
    /// off the main thread.
    nonisolated private static func computeFrameStat(_ cg: CGImage) -> FrameStat? {
        let side = 12
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        // Let CGContext own the pixel buffer (valid for the context's lifetime)
        // rather than handing it a pointer into a local array, which wouldn't
        // outlive the call that created the context.
        guard let ctx = CGContext(
            data: nil,
            width: side, height: side,
            bitsPerComponent: 8, bytesPerRow: side * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: side, height: side))
        guard let buffer = ctx.data else { return nil }
        let ptr = buffer.bindMemory(to: UInt8.self, capacity: side * side * 4)
        var rs = 0.0, gs = 0.0, bs = 0.0
        let pixels = side * side
        for i in 0..<pixels {
            rs += Double(ptr[i * 4])
            gs += Double(ptr[i * 4 + 1])
            bs += Double(ptr[i * 4 + 2])
        }
        let r = rs / Double(pixels) / 255.0
        let g = gs / Double(pixels) / 255.0
        let b = bs / Double(pixels) / 255.0
        return FrameStat(luma: 0.299 * r + 0.587 * g + 0.114 * b, r: r, g: g, b: b)
    }
}
