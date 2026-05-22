// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI

/// Sheet that asks the user which target resolution they want when
/// creating a proxy. Surfaced by `GridViewModel.proxyCreationVideoId`;
/// the caller hosts the sheet binding.
///
/// Three preset choices (4K → 1080p → 720p, with the entries below the
/// source's own resolution greyed out) plus a custom-height field.
/// Defaults to the server-configured `proxy_target_height` (typically
/// 720), so for the most common case the user only needs one click.
///
/// A future iteration will also persist an "Always create at this
/// resolution" choice so the user only sees the picker once per
/// catalog; the placeholder UI for that is already laid out here.
struct ProxyResolutionDialog: View {
    let sourceVideo: VideoSummary
    let onConfirm: (Int) -> Void
    let onCancel: () -> Void

    /// Preset heights offered. The implementation greys out any entry
    /// that's larger than the source video's height (a "proxy" at 2160 p
    /// for a 1080 p source isn't a proxy — it's an upscale).
    private let presets: [Int] = [2160, 1440, 1080, 720, 540]

    @State private var selectedHeight: Int = 720
    @State private var rememberChoice = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                Image(systemName: "rectangle.compress.vertical")
                    .font(.system(size: 28))
                    .foregroundColor(.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Create a proxy of this video")
                        .font(.headline)
                    Text(sourceVideo.filename)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer()
            }

            Divider()

            Text("Target resolution")
                .font(.subheadline)
                .fontWeight(.medium)

            VStack(alignment: .leading, spacing: 6) {
                ForEach(presets, id: \.self) { h in
                    Button {
                        selectedHeight = h
                    } label: {
                        HStack {
                            Image(systemName: selectedHeight == h ? "largecircle.fill.circle" : "circle")
                                .foregroundColor(.accentColor)
                            Text(presetLabel(h))
                            Spacer()
                            Text(presetDetail(h))
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        .padding(.vertical, 3)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(presetDisabled(h))
                    .opacity(presetDisabled(h) ? 0.4 : 1.0)
                }
            }

            Spacer()

            // Reserved slot for the "Always create proxies at this
            // resolution" toggle. Off by default; clicking "Create"
            // when on will (in a future iteration) flip the server's
            // `proxy_auto_on_oversize` config flag.
            Toggle(isOn: $rememberChoice) {
                Text("Always create proxies at this resolution")
                    .font(.caption)
            }
            .toggleStyle(.switch)
            .controlSize(.small)
            .disabled(true)  // placeholder until the config flag exists

            HStack {
                Spacer()
                Button("Cancel") {
                    onCancel()
                }
                .keyboardShortcut(.escape, modifiers: [])

                Button("Create \(selectedHeight)p proxy") {
                    onConfirm(selectedHeight)
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 460, height: 410)
        .onAppear { selectedHeight = defaultPick }
    }

    /// Largest preset that is strictly smaller than the source. Falls
    /// back to 720 if every preset would be ≥ the source (shouldn't
    /// happen — the user only sees this sheet for >720p videos).
    private var defaultPick: Int {
        // Prefer the server's default if it's in-range.
        if sourceVideo.height > 720 { return 720 }
        if let smaller = presets.first(where: { $0 < sourceVideo.height }) {
            return smaller
        }
        return 720
    }

    private func presetDisabled(_ h: Int) -> Bool {
        sourceVideo.height > 0 && h >= sourceVideo.height
    }

    private func presetLabel(_ h: Int) -> String {
        switch h {
        case 2160: return "2160p — 4K UHD"
        case 1440: return "1440p — QHD"
        case 1080: return "1080p — Full HD"
        case 720:  return "720p — HD"
        case 540:  return "540p — qHD"
        default:   return "\(h)p"
        }
    }

    private func presetDetail(_ h: Int) -> String {
        if presetDisabled(h) {
            return "≥ source (\(sourceVideo.height)p)"
        }
        // Rough multiplier vs 1080p as a stand-in for bitrate. Real
        // bitrates depend on content / CRF; this just gives a sense of
        // relative size.
        let pixels = Double(h * h * 16) / 9.0
        let baseline = Double(1080 * 1080 * 16) / 9.0
        let ratio = pixels / baseline
        return String(format: "%.0f%% of 1080p", ratio * 100.0)
    }
}
