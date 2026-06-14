// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Size-class-adaptive root of the connected app, mirroring the macOS client's
/// view-mode model (Grid / List / Detail / Map).
///
/// - **Regular width** (iPad): a 3-column `NavigationSplitView` — library panel
///   (view-mode switcher + sources) | the mode's center content | metadata
///   inspector. Playback lives in Detail mode, not the inspector.
/// - **Compact width** (iPhone): the mode's center in a `NavigationStack`;
///   the library panel is a sheet; tapping a card pushes the detail screen.
struct RootSplitView: View {
    @ObservedObject var grid: GridViewModel
    let connection: AppRouter.ConnectionInfo?

    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var selection: LibrarySection = .allVideos
    @State private var viewMode: LibraryViewMode = .grid
    @AppStorage("ios.thumbnailWidth") private var thumbnailWidth: Double = 170

    var body: some View {
        if sizeClass == .compact {
            CompactLayout(grid: grid, connection: connection,
                          selection: $selection, viewMode: $viewMode,
                          thumbnailWidth: $thumbnailWidth, apply: apply)
        } else {
            RegularLayout(grid: grid, connection: connection,
                          selection: $selection, viewMode: $viewMode,
                          thumbnailWidth: $thumbnailWidth, apply: apply)
        }
    }

    /// Apply a sidebar source to the shared view-model. Lightroom-style: one
    /// active source at a time, so picking one resets the other facet filters.
    private func apply(_ section: LibrarySection) {
        switch section {
        case .allVideos:
            grid.setCollectionFilter(nil); grid.setTagFilter(""); grid.setLocationFilter("")
        case .location(let path):
            grid.setCollectionFilter(nil); grid.setTagFilter(""); grid.setLocationFilter(path)
        case .collection(let id):
            grid.setTagFilter(""); grid.setLocationFilter(""); grid.setCollectionFilter(id)
        case .tag(let id):
            grid.setCollectionFilter(nil); grid.setLocationFilter(""); grid.setTagFilter(id)
        }
    }
}

/// The selected video (preferring the live row, falling back to the cached one).
@MainActor
private func selectedVideo(_ grid: GridViewModel) -> VideoSummary? {
    grid.videos.first(where: { $0.id == grid.selectedVideoId }) ?? grid.selectedVideo
}

// MARK: - Regular (iPad): 3-column split

private struct RegularLayout: View {
    @ObservedObject var grid: GridViewModel
    let connection: AppRouter.ConnectionInfo?
    @Binding var selection: LibrarySection
    @Binding var viewMode: LibraryViewMode
    @Binding var thumbnailWidth: Double
    let apply: (LibrarySection) -> Void

    @State private var columnVisibility: NavigationSplitViewVisibility = .all

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            LibrarySidebar(
                grid: grid, viewMode: $viewMode, selection: $selection,
                hasSelection: grid.selectedVideoId != nil, onSelect: apply)
        } content: {
            center
        } detail: {
            InspectorPanel(video: selectedVideo(grid)) { viewMode = .detail }
        }
        .navigationSplitViewStyle(.balanced)
    }

    @ViewBuilder private var center: some View {
        switch viewMode {
        case .grid:
            LibraryGridScreen(grid: grid, connection: connection, listMode: false,
                              thumbnailWidth: $thumbnailWidth, keyboardEnabled: true) { _ in }
                .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        case .list:
            LibraryGridScreen(grid: grid, connection: connection, listMode: true,
                              thumbnailWidth: $thumbnailWidth, keyboardEnabled: true) { _ in }
                .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        case .detail:
            DetailModeView(grid: grid, connection: connection)
        case .map:
            LibraryMapView(grid: grid) { _ in }
                .navigationTitle("Map").navigationBarTitleDisplayMode(.inline)
        }
    }

    private var title: String {
        grid.totalCount > 0 ? "\(grid.totalCount) Videos" : "Videos"
    }
}

// MARK: - Compact (iPhone): center + sheets + push detail

private struct CompactLayout: View {
    @ObservedObject var grid: GridViewModel
    let connection: AppRouter.ConnectionInfo?
    @Binding var selection: LibrarySection
    @Binding var viewMode: LibraryViewMode
    @Binding var thumbnailWidth: Double
    let apply: (LibrarySection) -> Void

    @State private var pushedVideo: VideoSummary?
    @State private var showLibrary = false

    var body: some View {
        NavigationStack {
            center
                .navigationTitle("ReelVault")
                .navigationBarTitleDisplayMode(.inline)
                .navigationDestination(item: $pushedVideo) { video in
                    VideoDetailView(video: video, mediaEndpoint: connection)
                }
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { showLibrary = true } label: {
                            Label("Library", systemImage: "sidebar.left")
                        }
                    }
                }
        }
        .sheet(isPresented: $showLibrary) {
            NavigationStack {
                LibrarySidebar(
                    grid: grid, viewMode: $viewMode, selection: $selection,
                    hasSelection: grid.selectedVideoId != nil
                ) { section in
                    apply(section)
                    showLibrary = false
                }
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") { showLibrary = false }
                    }
                }
            }
            .presentationDetents([.medium, .large])
        }
    }

    @ViewBuilder private var center: some View {
        switch viewMode {
        case .grid:
            LibraryGridScreen(grid: grid, connection: connection, listMode: false,
                              thumbnailWidth: $thumbnailWidth) { video in pushedVideo = video }
        case .list:
            LibraryGridScreen(grid: grid, connection: connection, listMode: true,
                              thumbnailWidth: $thumbnailWidth) { video in pushedVideo = video }
        case .detail:
            DetailModeView(grid: grid, connection: connection)
        case .map:
            LibraryMapView(grid: grid) { id in
                pushedVideo = grid.videos.first(where: { $0.id == id })
            }
        }
    }
}
