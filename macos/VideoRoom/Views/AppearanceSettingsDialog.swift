// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI

/// Sheet that lets the user choose between the Blue and Purple accent
/// color schemes. The choice is persisted to `UserDefaults` and applied
/// to the whole window via `.accentColor()` on the root view.
struct AppearanceSettingsDialog: View {
    @Binding var isPresented: Bool

    /// Raw string persisted in UserDefaults: "blue" or "purple".
    @AppStorage("accentScheme") private var accentScheme: String = "blue"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Header
            HStack {
                Image(systemName: "paintpalette")
                    .font(.title2)
                    .foregroundColor(.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Appearance")
                        .font(.headline)
                    Text("Choose the accent color scheme for the interface")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Spacer()
            }

            Divider()

            // Color scheme picker
            VStack(alignment: .leading, spacing: 8) {
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

            // Dismiss button
            HStack {
                Spacer()
                Button("Done") { isPresented = false }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 360)
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
        case .purple: return "Classic VideoRoom palette — deep purple accents"
        }
    }

    var swatch: Color {
        switch self {
        case .blue:   return .blue
        case .purple: return .purple
        }
    }
}
