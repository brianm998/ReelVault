// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI

/// Appearance & Browse settings: accent color scheme, scrub-frame count,
/// and reference information about the other configurable values.
///
/// Changes to `accentScheme` and `scrubFrameCount` are written to
/// `UserDefaults` immediately via `@AppStorage` — no Save button needed.
/// `GridViewModel.loadScrubFrames` reads `scrubFrameCount` each time it
/// fetches frames, so the new value takes effect on the next card hover
/// without a restart.
struct AppearanceSettingsDialog: View {
    @Binding var isPresented: Bool

    /// Raw string persisted in UserDefaults: "blue" or "purple".
    @AppStorage("accentScheme") private var accentScheme: String = "blue"

    /// Number of scrub frames fetched per video. Stored as `Int` but
    /// displayed as a segmented picker over the fixed set of valid values.
    /// `UserDefaults.integer(forKey:)` returns 0 when no value is stored;
    /// `GridViewModel` treats 0 as "use the default (10)".
    @AppStorage("scrubFrameCount") private var scrubFrameCount: Int = 10

    private let scrubOptions = [4, 6, 8, 10, 15, 20]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // ── Header ─────────────────────────────────────────────────
            HStack {
                Image(systemName: "paintpalette")
                    .font(.title2)
                    .foregroundColor(.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Appearance & Browse")
                        .font(.headline)
                    Text("Color scheme and scrub-preview settings")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Spacer()
            }

            Divider()

            // ── Accent color ────────────────────────────────────────────
            Text("Accent color")
                .font(.subheadline)
                .fontWeight(.semibold)

            VStack(alignment: .leading, spacing: 6) {
                ForEach(AccentSchemeOption.allCases) { option in
                    Button {
                        accentScheme = option.rawValue
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: accentScheme == option.rawValue
                                  ? "checkmark.circle.fill"
                                  : "circle")
                                .foregroundColor(accentScheme == option.rawValue
                                                 ? .accentColor
                                                 : .secondary)

                            Circle()
                                .fill(option.swatch)
                                .frame(width: 14, height: 14)
                                .shadow(radius: 1)

                            VStack(alignment: .leading, spacing: 1) {
                                Text(option.label)
                                    .font(.body)
                                    .foregroundColor(.primary)
                                Text(option.description)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.vertical, 4)
                    .padding(.horizontal, 8)
                    .background(
                        accentScheme == option.rawValue
                            ? Color.accentColor.opacity(0.10)
                            : Color.clear,
                        in: RoundedRectangle(cornerRadius: 6)
                    )
                }
            }

            Divider()

            // ── Scrub frames per video ──────────────────────────────────
            Text("Scrub frames per video")
                .font(.subheadline)
                .fontWeight(.semibold)

            Picker("", selection: $scrubFrameCount) {
                ForEach(scrubOptions, id: \.self) { n in
                    Text("\(n)").tag(n)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            Text("How many still frames ReelVault samples from each video for the hover scrub preview. More frames = smoother scrubbing but more memory and daemon traffic. Changes take effect the next time you hover a card that hasn't been sampled yet.")
                .font(.caption)
                .foregroundColor(.secondary)

            Divider()

            // ── Thumbnail size (reference) ──────────────────────────────
            Text("Thumbnail size")
                .font(.subheadline)
                .fontWeight(.semibold)

            Text("Use the size slider in the bottom toolbar to adjust how large card thumbnails appear in Grid and List modes (range: 120 – 400 pt, default: 220 pt). Your last-used size is remembered across launches.")
                .font(.caption)
                .foregroundColor(.secondary)

            Divider()

            // ── Proxy & playback gate (reference) ──────────────────────
            Text("Proxy & playback settings")
                .font(.subheadline)
                .fontWeight(.semibold)

            Text("The maximum inline-playback resolution and the default proxy height are configured in the Playback & Proxies sheet (toolbar → Playback & Proxies…). ReelVault uses these to decide which videos show a \"Too large to play here\" badge and what resolution newly-created proxies target.")
                .font(.caption)
                .foregroundColor(.secondary)

            // ── Dismiss ─────────────────────────────────────────────────
            HStack {
                Spacer()
                Button("Done") { isPresented = false }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 420)
    }
}

// MARK: - Supporting types

private enum AccentSchemeOption: String, CaseIterable, Identifiable {
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
        case .blue:   return "Blue accents matching the macOS system style"
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
