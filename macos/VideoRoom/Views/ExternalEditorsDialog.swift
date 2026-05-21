// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI
import AppKit

/// "External Editors" preferences sheet. Lists every supported editor on this
/// platform with license, install status, custom-path override, and a button
/// to visit the vendor homepage. Changes are persisted to `EditorRegistry`
/// as the user makes them — no separate "Save" step.
struct ExternalEditorsDialog: View {
    @Binding var isPresented: Bool
    @StateObject private var registry: EditorRegistry = EditorRegistry.shared

    private var editors: [ExternalEditor] { EditorCatalog.forCurrentPlatform }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "wrench.and.screwdriver.fill")
                Text("External Editors")
                    .font(.headline)
                Spacer()
            }

            Text("Pick which video editors VideoRoom can hand off to. Enabled + installed editors show up in the right-click menu on every video card.")
                .font(.caption)
                .foregroundColor(.secondary)

            Text("Running on \(HostPlatform.current.displayName). \(editors.count) editor\(editors.count == 1 ? "" : "s") shipped for this platform.")
                .font(.caption2)
                .foregroundColor(.secondary)

            Divider()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(editors.enumerated()), id: \.element.id) { i, editor in
                        if i > 0 {
                            Divider()
                        }
                        EditorRowView(editor: editor)
                            // Observing `registry.revision` forces the row to
                            // re-read state after any user action.
                            .id("\(editor.id)#\(registry.revision)")
                    }
                }
            }
            .frame(maxHeight: 480)

            HStack {
                Spacer()
                Button("Done") { isPresented = false }
                    .keyboardShortcut(.defaultAction)
                    .help("Close the preferences dialog. Changes are saved automatically.")
            }
        }
        .padding(20)
        .frame(width: 640)
    }
}

private struct EditorRowView: View {
    let editor: ExternalEditor
    @ObservedObject private var registry: EditorRegistry = EditorRegistry.shared

    var body: some View {
        let cfg = registry.config(for: editor)
        let resolved = registry.resolvedPath(for: editor)
        let installed = resolved != nil

        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(editor.name)
                            .font(.system(size: 13, weight: .semibold))
                        LicenseBadge(license: editor.license)
                        InstallBadge(isInstalled: installed)
                    }
                    if !editor.notes.isEmpty {
                        Text(editor.notes)
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if let path = resolved {
                        Text(path)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundColor(Color(.tertiaryLabelColor))
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { cfg.enabled },
                    set: { newValue in
                        registry.update(editor, EditorConfig(enabled: newValue, customPath: cfg.customPath))
                    }
                ))
                .labelsHidden()
                .toggleStyle(.switch)
                .help(cfg.enabled
                      ? "Disabled editors don't appear in the right-click \"Open with\" menu."
                      : "Enable this editor to make it available in the right-click menu.")
            }

            // Action row: Browse / Reset / Visit homepage
            HStack(spacing: 8) {
                Button {
                    if let picked = registry.pickCustomPath(for: editor) {
                        registry.update(editor, EditorConfig(enabled: cfg.enabled, customPath: picked))
                    }
                } label: {
                    Label("Browse…", systemImage: "folder")
                        .font(.caption)
                }
                .controlSize(.small)
                .help("Choose the exact .app bundle if VideoRoom couldn't find \(editor.name) automatically.")

                if !cfg.customPath.isEmpty {
                    Button("Reset") {
                        registry.update(editor, EditorConfig(enabled: cfg.enabled, customPath: ""))
                    }
                    .controlSize(.small)
                    .help("Forget the custom path and go back to auto-detecting from VideoRoom's defaults.")
                }

                Spacer()

                Button {
                    registry.openHomepage(editor)
                } label: {
                    Label(installed ? "Visit site" : "Get installer",
                          systemImage: "arrow.up.right.square")
                        .font(.caption)
                }
                .controlSize(.small)
                .help("Open \(editor.name)'s download page in your default browser.")
            }
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 4)
    }
}

private struct LicenseBadge: View {
    let license: EditorLicense

    var body: some View {
        Text(license.display)
            .font(.system(size: 10, weight: .medium))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(background)
            .foregroundColor(foreground)
            .cornerRadius(3)
    }

    private var background: Color {
        switch license {
        case .freeOpenSource: return Color.green.opacity(0.18)
        case .paid:           return Color.red.opacity(0.18)
        case .freeAndPaid:    return Color.orange.opacity(0.18)
        }
    }
    private var foreground: Color {
        switch license {
        case .freeOpenSource: return Color.green
        case .paid:           return Color.red
        case .freeAndPaid:    return Color.orange
        }
    }
}

private struct InstallBadge: View {
    let isInstalled: Bool

    var body: some View {
        Text(isInstalled ? "Installed" : "Not installed")
            .font(.system(size: 10, weight: .medium))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(isInstalled ? Color.accentColor.opacity(0.18) : Color.gray.opacity(0.18))
            .foregroundColor(isInstalled ? Color.accentColor : Color.secondary)
            .cornerRadius(3)
    }
}
