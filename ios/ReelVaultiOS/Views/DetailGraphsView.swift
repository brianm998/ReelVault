// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import ReelVaultKit
import SwiftUI
import UIKit

/// Per-scrub-frame averages used to plot how a clip's light and colour move over
/// its length. `luma` is Rec.601 luminance (0..1); `r`/`g`/`b` are the mean
/// channel values (0..1). (iOS counterpart of the macOS DetailGraphsPanel — the
/// two are separate per-platform reimplementations, like the grid cards.)
private struct GraphFrameStat {
    let luma: Double
    let r: Double
    let g: Double
    let b: Double
    var color: Color { Color(red: r, green: g, blue: b) }
}

/// The "Visuals" graphs shown at the bottom of the iOS detail view: brightness,
/// colour-over-time, and RGB channels derived from the clip's scrub thumbnails,
/// plus an audio-loudness curve from the daemon. Loudness only exists in remote
/// mode (the on-device core doesn't compute it yet), so it simply stays absent
/// in Local Library mode.
struct DetailGraphsView: View {
    @ObservedObject var grid: GridViewModel
    let videoId: String
    /// Drop the "Visuals" headline when hosted inside a CollapsibleSection.
    var showHeader: Bool = true

    @State private var stats: [GraphFrameStat] = []

    private var frames: [PlatformImage?] {
        let base = grid.scrubFrames[videoId] ?? []
        let hi = grid.hiResScrubFrames[videoId] ?? []
        if base.isEmpty { return hi }
        return base.enumerated().map { i, b in (i < hi.count ? hi[i] : nil) ?? b }
    }

    private var frameSignature: String {
        videoId + ":" + frames.map { $0 == nil ? "0" : "1" }.joined()
    }

    private var loudness: [Float] { grid.audioLoudness[videoId] ?? [] }
    private var hasStats: Bool { stats.count >= 2 }
    private var hasLoudness: Bool { loudness.count >= 2 }
    /// The loudness series has finished loading (entry present, empty or not) —
    /// distinguishes "still analysing" from "no audio".
    private var loudnessLoaded: Bool { grid.audioLoudness[videoId] != nil }

    var body: some View {
        // Only render the section once there's something to show (or something is
        // still loading) — an all-empty clip just omits it.
        if hasStats || hasLoudness || !loudnessLoaded {
            VStack(alignment: .leading, spacing: 12) {
                if showHeader { Text("Visuals").font(.headline) }
                if hasStats {
                    sectionLabel("Brightness"); brightnessChart
                    sectionLabel("Color over time"); colorTimeline
                    sectionLabel("RGB channels"); rgbChart
                }
                if hasLoudness {
                    sectionLabel("Loudness"); loudnessChart
                } else if !loudnessLoaded {
                    sectionLabel("Loudness")
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.small)
                        Text("Analyzing audio…").font(.caption).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, minHeight: 40)
                    .background(Color(white: 0.12))
                    .cornerRadius(4)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .task(id: videoId) {
                // Clear the previous clip's stats immediately so a reused instance
                // (iPad, in-place selection change) doesn't render the old video's
                // brightness/RGB/colour curves until the new ones compute.
                stats = []
                grid.loadScrubFrames(videoId: videoId)
                grid.loadAudioLoudness(videoId: videoId)
            }
            .onChange(of: frameSignature) { _, _ in recompute() }
            .onAppear { recompute() }
        }
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text).font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary)
    }

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

    private var colorTimeline: some View {
        HStack(spacing: 0) {
            ForEach(Array(stats.enumerated()), id: \.offset) { _, s in
                Rectangle().fill(s.color)
            }
        }
        .frame(height: 40)
        .cornerRadius(4)
    }

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

    private func path(for value: (GraphFrameStat) -> Double, size: CGSize) -> Path {
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
        // Snapshot each frame to an immutable CGImage on the main actor before
        // handing the per-pixel averaging off the main thread (CGImage is Sendable;
        // PlatformImage isn't).
        let cgImages = frames.compactMap { $0?.cgImage }
        Task.detached(priority: .utility) {
            let computed = cgImages.compactMap(Self.computeFrameStat)
            await MainActor.run { self.stats = computed }
        }
    }

    /// Downscale a frame to a small RGBA bitmap and average its pixels.
    nonisolated private static func computeFrameStat(_ cg: CGImage) -> GraphFrameStat? {
        let side = 12
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: nil, width: side, height: side,
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
            rs += Double(ptr[i * 4]); gs += Double(ptr[i * 4 + 1]); bs += Double(ptr[i * 4 + 2])
        }
        let r = rs / Double(pixels) / 255.0
        let g = gs / Double(pixels) / 255.0
        let b = bs / Double(pixels) / 255.0
        return GraphFrameStat(luma: 0.299 * r + 0.587 * g + 0.114 * b, r: r, g: g, b: b)
    }
}
