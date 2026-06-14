// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Size-class-adaptive root of the connected app.
///
/// - **Regular width** (iPad both orientations, iPhone landscape): a 3-column
///   `NavigationSplitView` ≈ the macOS client — library/filter sidebar | grid
///   (or map) | inspector. Hardware-keyboard arrow navigation is enabled.
/// - **Compact width** (iPhone portrait): a full-bleed grid in a
///   `NavigationStack`; the sidebar and inspector become sheets reached from
///   toolbar buttons (`.presentationDetents`), and tapping a card pushes the
///   detail screen. Touch-only — no keyboard, per the three-client rule.
struct RootSplitView: View {
    @ObservedObject var grid: GridViewModel
    let connection: AppRouter.ConnectionInfo?

    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var selection: LibrarySection = .allVideos

    var body: some View {
        if sizeClass == .compact {
            CompactLayout(grid: grid, connection: connection, selection: $selection, apply: apply)
        } else {
            RegularLayout(grid: grid, connection: connection, selection: $selection, apply: apply)
        }
    }

    /// Apply a sidebar source to the shared view-model. Lightroom-style: one
    /// active source at a time, so picking one resets the other facet filters.
    private func apply(_ section: LibrarySection) {
        switch section {
        case .allVideos, .map:
            grid.setCollectionFilter(nil)
            grid.setTagFilter("")
            grid.setLocationFilter("")
        case .location(let path):
            grid.setCollectionFilter(nil)
            grid.setTagFilter("")
            grid.setLocationFilter(path)
        case .collection(let id):
            grid.setTagFilter("")
            grid.setLocationFilter("")
            grid.setCollectionFilter(id)
        case .tag(let id):
            grid.setCollectionFilter(nil)
            grid.setLocationFilter("")
            grid.setTagFilter(id)
        }
    }
}

// MARK: - Regular (iPad / landscape): 3-column split

private struct RegularLayout: View {
    @ObservedObject var grid: GridViewModel
    let connection: AppRouter.ConnectionInfo?
    @Binding var selection: LibrarySection
    let apply: (LibrarySection) -> Void

    @State private var columnVisibility: NavigationSplitViewVisibility = .all

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            LibrarySidebar(grid: grid, selection: $selection, onSelect: apply)
        } content: {
            Group {
                if selection == .map {
                    LibraryMapView(grid: grid) { _ in }   // selection handled in-place
                        .navigationTitle("Map")
                } else {
                    LibraryGridScreen(grid: grid, connection: connection, keyboardEnabled: true) { _ in }
                        .navigationTitle(title)
                        .navigationBarTitleDisplayMode(.inline)
                }
            }
        } detail: {
            InspectorPanel(video: grid.selectedVideo, connection: connection)
        }
        .navigationSplitViewStyle(.balanced)
    }

    private var title: String {
        grid.totalCount > 0 ? "\(grid.totalCount) Videos" : "Videos"
    }
}

// MARK: - Compact (iPhone portrait): grid + sheets

private struct CompactLayout: View {
    @ObservedObject var grid: GridViewModel
    let connection: AppRouter.ConnectionInfo?
    @Binding var selection: LibrarySection
    let apply: (LibrarySection) -> Void

    @State private var pushedVideo: VideoSummary?
    @State private var showFilters = false
    @State private var showMap = false

    var body: some View {
        NavigationStack {
            LibraryGridScreen(grid: grid, connection: connection, keyboardEnabled: false) { video in
                pushedVideo = video
            }
            .navigationTitle("ReelVault")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(item: $pushedVideo) { video in
                VideoDetailView(video: video, mediaEndpoint: connection)
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showFilters = true } label: {
                        Label("Library", systemImage: "line.3.horizontal.decrease.circle")
                    }
                }
                ToolbarItem(placement: .bottomBar) {
                    Button { showMap = true } label: {
                        Label("Map", systemImage: "map")
                    }
                }
            }
        }
        .sheet(isPresented: $showFilters) {
            NavigationStack {
                LibrarySidebar(grid: grid, selection: $selection) { section in
                    apply(section)
                    showFilters = false
                }
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") { showFilters = false }
                    }
                }
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showMap) {
            NavigationStack {
                LibraryMapView(grid: grid) { id in
                    showMap = false
                    pushedVideo = grid.videos.first(where: { $0.id == id })
                }
                .navigationTitle("Map")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") { showMap = false }
                    }
                }
            }
        }
    }
}
