// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit
import AppKit

/// Dialog shown when the daemon has no catalog mounted — at first launch
/// (after a clean install) or after the user picks File → Open. Lists recent
/// catalogs and provides "Open File…" / "New Catalog…" buttons.
///
/// The caller controls dismissal: when `onPick` is invoked, the parent should
/// set the binding to `false` once it has asked the daemon to mount the new
/// catalog.
struct OpenCatalogDialog: View {
    @Binding var isPresented: Bool
    let onPick: (_ path: String) -> Void
    /// When `true`, the only "back-out" action is "Quit ReelVault" — used at
    /// startup when there's no fallback catalog to keep using.
    let isStartup: Bool

    @ObservedObject private var recents = RecentCatalogs.shared
    @State private var hoveredPath: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "books.vertical.fill")
                Text(isStartup ? "Open a Catalog" : "Open Catalog")
                    .font(.headline)
                Spacer()
            }

            Text("A catalog is the SQLite file ReelVault uses to remember your library — locations, tags, notes, thumbnails. Each catalog is independent.")
                .font(.caption)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 8) {
                Button {
                    if let path = pickCatalogFile(create: false) {
                        onPick(path)
                    }
                } label: {
                    Label("Open File…", systemImage: "folder")
                }
                .help("Pick an existing .db file from disk. ReelVault will mount it and load its library.")

                Button {
                    if let path = pickCatalogFile(create: true) {
                        onPick(path)
                    }
                } label: {
                    Label("New Catalog…", systemImage: "doc.badge.plus")
                }
                .help("Pick a folder and create a fresh catalog.db inside it. Use this for a brand-new library.")
            }

            Divider()
            Text("Recent")
                .font(.caption)
                .foregroundColor(.secondary)

            let items = recents.list()
            if items.isEmpty {
                Text("No recent catalogs yet.")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(.vertical, 4)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(items, id: \.self) { path in
                            recentRow(path: path)
                        }
                    }
                }
                .frame(maxHeight: 260)
            }

            HStack {
                Spacer()
                if isStartup {
                    Button("Quit") {
                        // `NSApp.terminate(nil)` is the "polite" path but it
                        // gets deferred while a modal sheet session is
                        // active — clicking Quit from inside the sheet
                        // makes the request never fire. We're at startup
                        // with no catalog mounted and nothing to save, so
                        // dismiss the sheet first and then hard-exit on the
                        // next runloop tick.
                        isPresented = false
                        DispatchQueue.main.async {
                            NSApp.terminate(nil)
                            // Belt-and-suspenders: if terminate() is still
                            // intercepted by something (SwiftUI scenes
                            // sometimes hold on), force exit shortly after.
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                                exit(0)
                            }
                        }
                    }
                    .keyboardShortcut("q", modifiers: [.command])
                    .help("Quit ReelVault without opening a catalog.")
                } else {
                    Button("Cancel") { isPresented = false }
                        .keyboardShortcut(.cancelAction)
                        .help("Close this dialog without changing the currently open catalog.")
                }
            }
        }
        .padding(20)
        .frame(width: 560)
    }

    @ViewBuilder
    private func recentRow(path: String) -> some View {
        let url = URL(fileURLWithPath: path)
        let exists = FileManager.default.fileExists(atPath: path)
        let display = url.deletingPathExtension().lastPathComponent
        let dir = url.deletingLastPathComponent().path

        HStack(spacing: 8) {
            Image(systemName: exists ? "books.vertical" : "exclamationmark.triangle.fill")
                .foregroundColor(exists ? .accentColor : .red)
            VStack(alignment: .leading, spacing: 2) {
                Text(display.isEmpty ? url.lastPathComponent : display)
                    .font(.system(size: 12))
                    .foregroundColor(exists ? .primary : .secondary)
                Text(exists ? dir : "\(dir) (missing)")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer()
            Button {
                recents.remove(path)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundColor(.secondary)
            }
            .buttonStyle(.plain)
            .help("Remove this catalog from the recent list. The file isn't deleted.")
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
        .background(hoveredPath == path ? Color.accentColor.opacity(0.12) : Color.clear)
        .cornerRadius(4)
        .contentShape(Rectangle())
        .onHover { hovering in
            hoveredPath = hovering ? path : nil
        }
        .onTapGesture {
            if exists { onPick(path) }
        }
    }

    /// Native NSOpenPanel for picking (or NSSavePanel for creating) a `.db`
    /// catalog file. Returns the chosen absolute path or `nil` if cancelled.
    private func pickCatalogFile(create: Bool) -> String? {
        if create {
            let panel = NSSavePanel()
            panel.title = "Create New Catalog"
            panel.nameFieldStringValue = "catalog.db"
            panel.allowedContentTypes = []
            panel.canCreateDirectories = true
            return panel.runModal() == .OK ? panel.url?.path : nil
        } else {
            let panel = NSOpenPanel()
            panel.title = "Open Catalog"
            panel.canChooseFiles = true
            panel.canChooseDirectories = false
            panel.allowsMultipleSelection = false
            return panel.runModal() == .OK ? panel.url?.path : nil
        }
    }
}
