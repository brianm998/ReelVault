// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// One selectable source in the library sidebar. Selecting a source resets the
/// other facet filters so the sidebar behaves Lightroom-style: one active source
/// at a time (All Videos, the map, a location folder, a collection, or a tag).
enum LibrarySection: Hashable {
    case allVideos
    case map
    case location(String)   // library location path
    case collection(String) // collection id
    case tag(String)        // tag id
}

/// The library/filter panel: search box + browsable sources (all videos, map,
/// folders, collections, tags). Used as the leading column of the iPad
/// 3-column layout and as the contents of the iPhone filter sheet. Bound to the
/// shared `GridViewModel`, so it drives the same filter state the macOS client
/// uses; applying a selection lives in the parent via `onSelect`.
struct LibrarySidebar: View {
    @ObservedObject var grid: GridViewModel
    @Binding var selection: LibrarySection
    /// Apply the chosen source to the shared view-model (parent owns the logic).
    var onSelect: (LibrarySection) -> Void

    var body: some View {
        List(selection: Binding(
            get: { selection },
            set: { newValue in
                if let newValue { selection = newValue; onSelect(newValue) }
            }
        )) {
            librarySection
            if !grid.libraryLocations.isEmpty { locationsSection }
            if !grid.collections.isEmpty { collectionsSection }
            if !grid.tags.isEmpty { tagsSection }
        }
        .listStyle(.sidebar)
        .navigationTitle("Library")
        .searchable(text: $grid.searchQuery, prompt: "Search videos")
        .task {
            grid.loadLibraryLocations()
            grid.loadCollections()
            grid.loadTags()
        }
    }

    private var librarySection: some View {
        Section {
            Label("All Videos", systemImage: "film.stack").tag(LibrarySection.allVideos)
            Label("Map", systemImage: "map").tag(LibrarySection.map)
        }
    }

    private var locationsSection: some View {
        Section("Folders") {
            ForEach(grid.libraryLocations) { loc in
                Label {
                    HStack {
                        Text(folderName(loc.path)).lineLimit(1)
                        Spacer()
                        countBadge(loc.videoCount)
                    }
                } icon: {
                    Image(systemName: "folder")
                }
                .tag(LibrarySection.location(loc.path))
            }
        }
    }

    private var collectionsSection: some View {
        Section("Collections") {
            ForEach(grid.collections) { col in
                Label {
                    HStack {
                        Text(col.name).lineLimit(1)
                        Spacer()
                        countBadge(col.videoCount)
                    }
                } icon: {
                    Image(systemName: col.isSmart ? "gearshape.2" : "rectangle.stack")
                }
                .tag(LibrarySection.collection(col.id))
            }
        }
    }

    private var tagsSection: some View {
        Section("Tags") {
            ForEach(grid.tags) { tag in
                Label {
                    HStack {
                        Text(tag.name).lineLimit(1)
                        Spacer()
                        countBadge(tag.videoCount)
                    }
                } icon: {
                    Image(systemName: "tag")
                        .foregroundStyle(tagColor(tag.color))
                }
                .tag(LibrarySection.tag(tag.id))
            }
        }
    }

    @ViewBuilder private func countBadge(_ n: Int64) -> some View {
        Text("\(n)")
            .font(.caption2)
            .foregroundStyle(.secondary)
    }

    private func folderName(_ path: String) -> String {
        let trimmed = path.hasSuffix("/") ? String(path.dropLast()) : path
        return (trimmed as NSString).lastPathComponent.isEmpty ? path : (trimmed as NSString).lastPathComponent
    }

    private func tagColor(_ hex: String?) -> Color {
        guard let hex, hex.hasPrefix("#"), hex.count == 7,
              let value = Int(hex.dropFirst(), radix: 16) else { return .secondary }
        return Color(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }
}
