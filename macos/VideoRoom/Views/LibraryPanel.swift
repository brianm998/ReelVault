// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI

/// Left-side panel listing scanned library locations and collections.
/// Clicking a location row filters the grid to that path; clicking a
/// collection row scopes the grid to that collection; "All Videos" clears both.
struct LibraryPanel: View {
    let locations: [LibraryLocation]
    let selectedPath: String
    let totalVideos: Int64
    let onSelect: (String) -> Void
    let onAddLibrary: () -> Void
    /// Called when the user chooses "Remove from library" for a location.
    /// The caller is responsible for showing a confirmation alert.
    var onRemoveLocation: ((LibraryLocation) -> Void)? = nil
    /// Called when the user clicks the rescan button for a location row.
    var onRescan: ((LibraryLocation) -> Void)? = nil
    /// Set of paths currently being rescanned; drives the spinner in each row.
    var rescanningPaths: Set<String> = []
    let onCollapse: () -> Void

    var collections: [Collection] = []
    var selectedCollectionId: String? = nil
    var onSelectCollection: ((String?) -> Void)? = nil
    var onCreateCollection: ((String) -> Void)? = nil
    var onCreateSmartCollection: (() -> Void)? = nil
    var onDeleteCollection: ((Collection) -> Void)? = nil

    @State private var showNewCollectionAlert = false
    @State private var newCollectionName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header row: add-folder button · label · collapse chevron
            HStack(spacing: 4) {
                Button(action: onAddLibrary) {
                    Image(systemName: "plus")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Add a folder to your library")
                Text("LIBRARY")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()
                Button(action: onCollapse) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Hide library panel (Tab)")
            }
            .padding(.horizontal, 12)
            .padding(.top, 12)
            .padding(.bottom, 8)

            List {
                // "All Videos" — clears both location and collection filters.
                LocationRow(
                    systemImage: "film.stack",
                    label: "All Videos",
                    sublabel: nil,
                    count: totalVideos,
                    isSelected: selectedPath.isEmpty && selectedCollectionId == nil,
                    tooltip: "Show every video in your library, across all scanned folders.",
                    onClick: {
                        onSelect("")
                        onSelectCollection?(nil)
                    }
                )
                .listRowInsets(EdgeInsets())

                if !locations.isEmpty {
                    Divider()
                        .listRowInsets(EdgeInsets(top: 4, leading: 8, bottom: 4, trailing: 8))
                        .listRowSeparator(.hidden)
                }

                ForEach(locations) { loc in
                    LocationRow(
                        systemImage: loc.path == selectedPath ? "folder.fill" : "folder",
                        label: Self.displayName(loc.path),
                        sublabel: loc.path,
                        count: loc.videoCount,
                        isSelected: loc.path == selectedPath,
                        tooltip: "Show only videos from \(loc.path) (\(loc.videoCount) videos).\nRight-click or swipe left to remove.",
                        onClick: {
                            onSelect(loc.path)
                            onSelectCollection?(nil)
                        },
                        onRescan: onRescan != nil ? { onRescan!(loc) } : nil,
                        isRescanning: rescanningPaths.contains(loc.path)
                    )
                    .listRowInsets(EdgeInsets())
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        if let remove = onRemoveLocation {
                            Button(role: .destructive) { remove(loc) } label: {
                                Label("Remove", systemImage: "trash")
                            }
                        }
                    }
                    .contextMenu {
                        if let remove = onRemoveLocation {
                            Button(role: .destructive) { remove(loc) } label: {
                                Label("Remove from Library…", systemImage: "trash")
                            }
                        }
                    }
                }

                // ── Collections section ──────────────────────────────────
                Section {
                    if collections.isEmpty {
                        Text("No collections yet")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                            .padding(.leading, 30)
                            .listRowInsets(EdgeInsets(top: 2, leading: 0, bottom: 2, trailing: 0))
                    } else {
                        ForEach(collections) { col in
                            LocationRow(
                                systemImage: col.isSmart ? "sparkles" : "folder.badge.plus",
                                label: col.name,
                                sublabel: nil,
                                count: col.videoCount,
                                isSelected: col.id == selectedCollectionId,
                                tooltip: col.isSmart
                                    ? "Smart collection — filters videos automatically. Right-click to delete."
                                    : "\(col.videoCount) video\(col.videoCount == 1 ? "" : "s"). Right-click to delete.",
                                onClick: {
                                    onSelect("")
                                    onSelectCollection?(col.id)
                                }
                            )
                            .listRowInsets(EdgeInsets())
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                if let del = onDeleteCollection {
                                    Button(role: .destructive) { del(col) } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                }
                            }
                            .contextMenu {
                                if let del = onDeleteCollection {
                                    Button(role: .destructive) { del(col) } label: {
                                        Label("Delete Collection…", systemImage: "trash")
                                    }
                                }
                            }
                        }
                    }
                } header: {
                    HStack(spacing: 4) {
                        if onCreateCollection != nil {
                            Button {
                                newCollectionName = ""
                                showNewCollectionAlert = true
                            } label: {
                                Image(systemName: "plus")
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundColor(.secondary)
                            }
                            .buttonStyle(.plain)
                            .help("Create a new empty collection")
                        }
                        Text("COLLECTIONS")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Spacer()
                        if onCreateSmartCollection != nil {
                            Button(action: { onCreateSmartCollection?() }) {
                                Image(systemName: "sparkles")
                                    .font(.system(size: 10))
                                    .foregroundColor(.secondary)
                            }
                            .buttonStyle(.plain)
                            .help("Save current filters as a smart collection")
                        }
                    }
                    .padding(.horizontal, 8)
                    .padding(.top, 6)
                    .padding(.bottom, 2)
                }
                .listSectionSeparator(.hidden)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Color(.controlBackgroundColor))
            .alert("New Collection", isPresented: $showNewCollectionAlert) {
                TextField("Collection name", text: $newCollectionName)
                Button("Create") {
                    let name = newCollectionName.trimmingCharacters(in: .whitespaces)
                    if !name.isEmpty { onCreateCollection?(name) }
                }
                Button("Cancel", role: .cancel) {}
            }
        }
        .frame(maxHeight: .infinity)
        .background(Color(.controlBackgroundColor))
    }

    private static func displayName(_ path: String) -> String {
        let trimmed = path.hasSuffix("/") ? String(path.dropLast()) : path
        let last = trimmed.components(separatedBy: "/").last ?? trimmed
        return last.isEmpty ? "/" : last
    }
}

private struct LocationRow: View {
    let systemImage: String
    let label: String
    let sublabel: String?
    let count: Int64
    let isSelected: Bool
    var tooltip: String = ""
    let onClick: () -> Void
    var onRescan: (() -> Void)? = nil
    var isRescanning: Bool = false

    @State private var isHovered = false

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                    .font(.system(size: 14))
                    .foregroundColor(isSelected ? .accentColor : .primary)
                    .frame(width: 18)
                VStack(alignment: .leading, spacing: 1) {
                    Text(label)
                        .font(.system(size: 12))
                        .foregroundColor(isSelected ? .accentColor : .primary)
                        .lineLimit(1)
                    if let sublabel = sublabel {
                        Text(sublabel)
                            .font(.system(size: 10))
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
                Spacer(minLength: 4)
                Text("\(count)")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                // Rescan spinner or button — shown to the right of the count badge.
                if isRescanning {
                    ProgressView()
                        .scaleEffect(0.5)
                        .frame(width: 18, height: 18)
                } else if onRescan != nil && isHovered {
                    Button {
                        onRescan?()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 11))
                    }
                    .buttonStyle(.plain)
                    .help("Rescan this folder and reconnect any proxies")
                    .frame(width: 18, height: 18)
                } else {
                    // Reserve space so count badge doesn't jump on hover.
                    Color.clear.frame(width: 18, height: 18)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(isSelected ? Color.accentColor.opacity(0.18) : Color.clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(tooltip)
        .onHover { isHovered = $0 }
    }
}

/// Thin vertical strip rendered in place of a collapsed side panel. Click to expand.
struct CollapsedPanelStrip: View {
    /// If true the chevron points left (so it points "into the screen" from the
    /// right edge — used on the right panel). Otherwise it points right.
    let expandIconLeft: Bool
    let tooltip: String
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack {
                Image(systemName: expandIconLeft ? "chevron.left" : "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.secondary)
                    .padding(.top, 12)
                Spacer()
            }
            .frame(width: 22)
            .frame(maxHeight: .infinity)
            .background(Color(.controlBackgroundColor).opacity(0.6))
        }
        .buttonStyle(.plain)
        .help(tooltip)
    }
}
