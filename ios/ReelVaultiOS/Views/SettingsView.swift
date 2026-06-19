// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The iOS Settings screen — a sheet with sections that mirror the macOS
/// preference dialogs. Reachable via the gear toolbar button in both the
/// sidebar (iPad) and the compact top-bar (iPhone).
///
/// Subsequent gap commits add more sections here (Playback & Proxies,
/// Library Locations, etc.). For now it contains the Appearance section.
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                AppearanceSection()
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
