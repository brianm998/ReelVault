// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// One selectable *source* in the library sidebar — which videos to show.
/// Orthogonal to the view *mode* (grid/list/detail/map). Lightroom-style: one
/// active source at a time (All Videos, a folder, a collection, or a tag).
enum LibrarySection: Hashable {
    case allVideos
    case location(String)   // library location path
    case collection(String) // collection id
    case tag(String)        // tag id
}

/// The library panel (leading column / iPhone sheet): the view-mode switcher
/// (Grid/List/Detail/Map, Detail gated on a selection) plus the browsable
/// sources (all videos, folders, collections, tags). Bound to the shared
/// `GridViewModel`; the parent owns mode + source application.
struct LibrarySidebar: View {
    @ObservedObject var grid: GridViewModel
    @Binding var viewMode: LibraryViewMode
    @Binding var selection: LibrarySection
    /// Whether a video is selected (gates Detail mode).
    var hasSelection: Bool
    /// Apply the chosen source to the shared view-model (parent owns the logic).
    var onSelect: (LibrarySection) -> Void

    var body: some View {
        List(selection: Binding<LibrarySection?>(
            get: { selection },
            set: { newValue in
                if let newValue { selection = newValue; onSelect(newValue) }
            }
        )) {
            viewSection
            sourcesSection
            if !grid.libraryLocations.isEmpty { locationsSection }
            if !grid.collections.isEmpty { collectionsSection }
            if !grid.tags.isEmpty { tagsSection }
        }
        .listStyle(.sidebar)
        .navigationTitle("Library")
        .task {
            grid.loadLibraryLocations()
            grid.loadCollections()
            grid.loadTags()
        }
    }

    private var viewSection: some View {
        Section("View") {
            ForEach(LibraryViewMode.allCases) { mode in
                Button {
                    viewMode = mode
                } label: {
                    HStack {
                        Label(mode.label, systemImage: mode.systemImage)
                        Spacer()
                        if viewMode == mode {
                            Image(systemName: "checkmark").foregroundStyle(.tint)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(mode == .detail && !hasSelection)
                .foregroundStyle(viewMode == mode ? Color.accentColor : .primary)
            }
        }
    }

    private var sourcesSection: some View {
        Section {
            Label("All Videos", systemImage: "film.stack").tag(LibrarySection.allVideos)
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
                        // Smart collections have no stored members, so the
                        // server reports 0; the shared view-model resolves their
                        // real count client-side into `smartCollectionCounts`.
                        countBadge(col.isSmart
                            ? (grid.smartCollectionCounts[col.id] ?? 0)
                            : col.videoCount)
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
