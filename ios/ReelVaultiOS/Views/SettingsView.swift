// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The iOS Settings screen — a sheet with sections that mirror the macOS
/// preference dialogs. Reachable via the gear toolbar button in both the
/// sidebar (iPad) and the compact top-bar (iPhone).
///
/// Sections:
///  - Appearance — accent color + scrub-frame count (always shown)
///  - Playback & Proxies — inline-playback ceiling + default proxy height
///    (shown only when connected to a remote daemon; backed by the server's
///    `max_native_playback_height` / `proxy_target_height` config keys via
///    `VideoRepository.getConfig` / `updateConfig`)
struct SettingsView: View {
    /// Non-nil while connected to a remote daemon. Nil in local/offline mode,
    /// which means the Playback & Proxies section is hidden — there is no server
    /// config to read or write.
    var connection: AppRouter.ConnectionInfo? = nil

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                AppearanceSection()
                if connection != nil {
                    PlaybackSection()
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Playback & Proxies section

/// Mirrors macOS `PlaybackSettingsDialog` — two server-config pickers:
///  • Maximum inline-playback height (`max_native_playback_height`)
///  • Default proxy height (`proxy_target_height`)
///
/// Settings are loaded from the daemon via `VideoRepository.getConfig` when the
/// section appears and saved back via `updateConfig` when either picker changes.
/// A brief "Saving…" overlay is shown during the round-trip.
private struct PlaybackSection: View {
    @State private var maxNativeHeight: Int = 2160
    @State private var proxyTargetHeight: Int = 720
    @State private var loading = true
    @State private var saving = false

    private let nativePresets: [Int] = [720, 1080, 1440, 2160, 4320]
    private let proxyPresets: [Int] = [540, 720, 1080, 1440]

    var body: some View {
        Section {
            if loading {
                HStack {
                    ProgressView()
                        .controlSize(.small)
                    Text("Loading…")
                        .foregroundStyle(.secondary)
                }
            } else {
                // Inline-playback ceiling
                Picker("Max inline-playback height", selection: $maxNativeHeight) {
                    ForEach(nativePresets, id: \.self) { h in
                        Text(heightLabel(h)).tag(h)
                    }
                }
                .pickerStyle(.menu)
                .onChange(of: maxNativeHeight) { _, newValue in
                    Task { await save(maxNative: newValue, proxy: nil) }
                }

                // Default proxy height
                Picker("Default proxy height", selection: $proxyTargetHeight) {
                    ForEach(proxyPresets, id: \.self) { h in
                        Text(heightLabel(h)).tag(h)
                    }
                }
                .pickerStyle(.menu)
                .onChange(of: proxyTargetHeight) { _, newValue in
                    Task { await save(maxNative: nil, proxy: newValue) }
                }
            }
        } header: {
            HStack {
                Text("Playback & Proxies")
                if saving {
                    Spacer()
                    ProgressView()
                        .controlSize(.mini)
                }
            }
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("Videos taller than the inline-playback ceiling are marked \"Too large to play here\" and offered a one-click proxy.")
                Text("Newly-generated proxies default to the proxy height. Smaller = faster and smaller files.")
            }
            .foregroundStyle(.secondary)
        }
        .task { await loadSettings() }
    }

    private func heightLabel(_ h: Int) -> String {
        switch h {
        case 540:  return "540p"
        case 720:  return "720p"
        case 1080: return "1080p"
        case 1440: return "1440p"
        case 2160: return "2160p (4K)"
        case 4320: return "4320p (8K)"
        default:   return "\(h)p"
        }
    }

    private func loadSettings() async {
        loading = true
        if let cfg = await VideoRepository.shared.getConfig() {
            maxNativeHeight = cfg.maxNativePlaybackHeight
            proxyTargetHeight = cfg.proxyTargetHeight
        }
        loading = false
    }

    private func save(maxNative: Int?, proxy: Int?) async {
        saving = true
        _ = await VideoRepository.shared.updateConfig(
            maxNativePlaybackHeight: maxNative,
            proxyTargetHeight: proxy
        )
        saving = false
    }
}

// MARK: - Appearance section

/// Accent color scheme and scrub-frame-count settings — mirrors
/// `AppearanceSettingsDialog` on macOS. Written to `UserDefaults` immediately
/// via `@AppStorage`; no Save button needed.
private struct AppearanceSection: View {
    /// Raw string persisted in UserDefaults: "blue" or "purple".
    @AppStorage("accentScheme") private var accentScheme: String = "blue"

    /// Number of scrub frames fetched per video. 0 means "use the default (10)".
    @AppStorage("scrubFrameCount") private var scrubFrameCount: Int = 10

    private let scrubOptions = [4, 6, 8, 10, 15, 20]

    var body: some View {
        Section {
            ForEach(AccentSchemeOption.allCases) { option in
                Button {
                    accentScheme = option.rawValue
                } label: {
                    HStack(spacing: 12) {
                        Circle()
                            .fill(option.swatch)
                            .frame(width: 22, height: 22)
                            .shadow(radius: 1)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(option.label)
                                .foregroundStyle(.primary)
                            Text(option.description)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if accentScheme == option.rawValue {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.tint)
                                .fontWeight(.semibold)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text("Accent Color")
        } footer: {
            Text("Changes apply immediately across the whole app.")
                .foregroundStyle(.secondary)
        }

        Section {
            Picker("Scrub frames per video", selection: $scrubFrameCount) {
                ForEach(scrubOptions, id: \.self) { n in
                    Text("\(n)").tag(n)
                }
            }
            .pickerStyle(.menu)
        } header: {
            Text("Browse")
        } footer: {
            Text("How many still frames ReelVault samples per video for the hover scrub preview. Changes take effect the next time an un-sampled card is hovered.")
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Supporting types

/// The available accent-color schemes. Raw value matches the `accentScheme`
/// `UserDefaults` key used on macOS, so the preference roams correctly if
/// `UserDefaults` is ever synced.
enum AccentSchemeOption: String, CaseIterable, Identifiable {
    case blue   = "blue"
    case purple = "purple"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .blue:   return "Blue"
        case .purple: return "Purple"
        }
    }

    var description: String {
        switch self {
        case .blue:   return "Blue accents matching the iOS system style"
        case .purple: return "Classic ReelVault palette — deep purple accents"
        }
    }

    var swatch: Color {
        switch self {
        case .blue:   return .blue
        case .purple: return .purple
        }
    }
}
