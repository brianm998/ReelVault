// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI

/// Left-side panel listing scanned library locations with their video counts.
/// Clicking a row filters the grid to that location; the "All Videos" row
/// clears the filter.
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

            // Use a List so we get native swipe-to-delete and the built-in
            // context-menu affordance without extra hit-testing work.
            List {
                LocationRow(
                    systemImage: "film.stack",
                    label: "All Videos",
                    sublabel: nil,
                    count: totalVideos,
                    isSelected: selectedPath.isEmpty,
                    tooltip: "Show every video in your library, across all scanned folders.",
                    onClick: { onSelect("") }
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
                        onClick: { onSelect(loc.path) },
                        onRescan: onRescan != nil ? { onRescan!(loc) } : nil,
                        isRescanning: rescanningPaths.contains(loc.path)
                    )
                    .listRowInsets(EdgeInsets())
                    // Swipe-left reveals the destructive Remove action.
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        if let remove = onRemoveLocation {
                            Button(role: .destructive) {
                                remove(loc)
                            } label: {
                                Label("Remove", systemImage: "trash")
                            }
                        }
                    }
                    // Right-click context menu.
                    .contextMenu {
                        if let remove = onRemoveLocation {
                            Button(role: .destructive) {
                                remove(loc)
                            } label: {
                                Label("Remove from Library…", systemImage: "trash")
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Color(.controlBackgroundColor))
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
