// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Preferences sheet for inline-playback gating and default proxy
/// resolution. Two sliders backed by the server's `max_native_playback_height`
/// and `proxy_target_height` config keys.
///
/// Behavior surfaced to the user:
///   * Anything above the playback ceiling is marked "Too large to play
///     here" on its grid card and offers a one-click proxy.
///   * Newly-generated proxies default to the proxy height.
struct PlaybackSettingsDialog: View {
    @Binding var isPresented: Bool

    @State private var maxNativeHeight: Int = 2160
    @State private var proxyTargetHeight: Int = 720
    @State private var loading = true
    @State private var saving = false

    private let heightPresets: [Int] = [720, 1080, 1440, 2160, 4320]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Image(systemName: "play.rectangle.fill")
                    .font(.title2)
                    .foregroundColor(.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Playback & Proxies")
                        .font(.headline)
                    Text("Gate inline playback by resolution; pick a default proxy size")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Spacer()
            }

            Divider()

            if loading {
                HStack {
                    ProgressView().scaleEffect(0.7)
                    Text("Loading current settings…")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                playbackSection
                Divider()
                proxySection
            }

            Spacer()

            HStack {
                Spacer()
                Button("Cancel") { isPresented = false }
                    .keyboardShortcut(.escape, modifiers: [])
                Button(saving ? "Saving…" : "Save") {
                    Task { await saveAndDismiss() }
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(loading || saving)
            }
        }
        .padding(20)
        .frame(width: 540, height: 420)
        .task { await loadSettings() }
    }

    private var playbackSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Maximum inline-playback height")
                .fontWeight(.medium)
            Picker("", selection: $maxNativeHeight) {
                ForEach(heightPresets, id: \.self) { h in
                    Text(heightLabel(h)).tag(h)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            Text("Videos taller than this show \"Too large to play here\" and offer a proxy. Higher = more inline playback but slower scrolling on a busy grid.")
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }

    private var proxySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Default proxy height")
                .fontWeight(.medium)
            Picker("", selection: $proxyTargetHeight) {
                ForEach([540, 720, 1080, 1440], id: \.self) { h in
                    Text(heightLabel(h)).tag(h)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            Text("Newly-created proxies default to this height. Smaller = faster + smaller files; larger = closer to the original quality.")
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }

    private func heightLabel(_ h: Int) -> String {
        switch h {
        case 540:  return "540p"
        case 720:  return "720p"
        case 1080: return "1080p"
        case 1440: return "1440p"
        case 2160: return "2160p"
        case 4320: return "4320p"
        default:   return "\(h)p"
        }
    }

    private func loadSettings() async {
        loading = true
        let cfg = await VideoRepository.shared.getConfig()
        if let cfg = cfg {
            maxNativeHeight = cfg.maxNativePlaybackHeight
            proxyTargetHeight = cfg.proxyTargetHeight
        }
        loading = false
    }

    private func saveAndDismiss() async {
        saving = true
        _ = await VideoRepository.shared.updateConfig(
            maxNativePlaybackHeight: maxNativeHeight,
            proxyTargetHeight: proxyTargetHeight
        )
        saving = false
        isPresented = false
    }
}
