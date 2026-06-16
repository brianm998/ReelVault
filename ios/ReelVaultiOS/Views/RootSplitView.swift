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
    /// Drives the thin "importing" strip while an on-device ingest pass runs.
    var isIngesting: Bool = false

    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var selection: LibrarySection = .allVideos
    @State private var viewMode: LibraryViewMode = .grid
    @AppStorage("ios.thumbnailWidth") private var thumbnailWidth: Double = 170
    /// Browser-style back/forward across the session's browse locations.
    @StateObject private var history = NavigationHistory()

    var body: some View {
        Group {
            if sizeClass == .compact {
                CompactLayout(grid: grid, connection: connection, isIngesting: isIngesting,
                              selection: $selection, viewMode: $viewMode,
                              thumbnailWidth: $thumbnailWidth, apply: apply,
                              history: history, goBack: goBack, goForward: goForward)
            } else {
                RegularLayout(grid: grid, connection: connection, isIngesting: isIngesting,
                              selection: $selection, viewMode: $viewMode,
                              thumbnailWidth: $thumbnailWidth, apply: apply,
                              history: history, goBack: goBack, goForward: goForward)
            }
        }
        // Record each distinct browse location (source + view mode + selected
        // video). A back/forward restore re-applies the same state, which dedups
        // (no new entry); navigating somewhere new *after* a back truncates the
        // forward path and starts a fresh branch.
        .onChange(of: currentNav) { _, new in history.record(new) }
        .onAppear { history.record(currentNav) }
    }

    /// The current browse location, as recorded in history.
    private var currentNav: NavState {
        NavState(section: selection, viewMode: viewMode, selectedVideoId: grid.selectedVideoId)
    }

    private func goBack() { if let s = history.goBack() { restore(s) } }
    private func goForward() { if let s = history.goForward() { restore(s) } }

    /// Re-apply a history entry. Unlike `apply()` it does NOT force the view mode
    /// back to grid — it restores the entry's exact mode/selection. Set everything
    /// synchronously so the single resulting `currentNav` change dedups against the
    /// entry we just moved to (rather than recording a new one).
    private func restore(_ s: NavState) {
        selection = s.section
        applyFilters(s.section)
        viewMode = s.viewMode
        grid.selectedVideoId = s.selectedVideoId
    }

    /// Apply a sidebar source to the shared view-model. Lightroom-style: one
    /// active source at a time, so picking one resets the other facet filters.
    private func apply(_ section: LibrarySection) {
        // Picking a source is a "show me these videos" intent — get out of the
        // single-video Detail view or the Map (which don't reflect a source
        // change) and back to the browseable grid. Grid/List already show the
        // filtered set, so leave the user's choice between them alone.
        if viewMode == .detail || viewMode == .map { viewMode = .grid }
        applyFilters(section)
    }

    /// Just the facet-filter writes for a source (no view-mode change), shared by
    /// `apply()` and history `restore()`.
    private func applyFilters(_ section: LibrarySection) {
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

/// One browse location for the back/forward history: the active source, the view
/// mode, and the selected video. (Internal, not private, so the internal
/// `NavigationHistory` methods can take/return it.)
struct NavState: Equatable {
    var section: LibrarySection
    var viewMode: LibraryViewMode
    var selectedVideoId: String?
}

/// Browser-style session navigation history. `record` pushes a new location
/// (truncating any forward entries — diverging after a back starts a new branch);
/// `goBack`/`goForward` move the cursor and return the entry to restore. Recording
/// the state a restore produces is a no-op (it equals the cursor), so back/forward
/// don't pollute the history.
@MainActor
final class NavigationHistory: ObservableObject {
    @Published private(set) var canGoBack = false
    @Published private(set) var canGoForward = false
    private var stack: [NavState] = []
    private var index = -1

    func record(_ s: NavState) {
        if index >= 0, stack[index] == s { return }            // dedup (absorbs restores)
        if index < stack.count - 1 { stack.removeSubrange((index + 1)...) }  // truncate forward
        stack.append(s)
        index = stack.count - 1
        refresh()
    }

    func goBack() -> NavState? {
        guard index > 0 else { return nil }
        index -= 1; refresh(); return stack[index]
    }

    func goForward() -> NavState? {
        guard index < stack.count - 1 else { return nil }
        index += 1; refresh(); return stack[index]
    }

    private func refresh() {
        canGoBack = index > 0
        canGoForward = index < stack.count - 1
    }
}

/// Back/forward toolbar buttons, disabled at the ends of the history.
private struct NavHistoryButtons: View {
    @ObservedObject var history: NavigationHistory
    let goBack: () -> Void
    let goForward: () -> Void

    var body: some View {
        Button(action: goBack) { Image(systemName: "chevron.backward") }
            .disabled(!history.canGoBack)
            .help("Back")
        Button(action: goForward) { Image(systemName: "chevron.forward") }
            .disabled(!history.canGoForward)
            .help("Forward")
    }
}

/// The selected video *only if its card is in the currently-loaded grid*. The
/// inspector keys off this (not the cached `grid.selectedVideo`) so that after a
/// source/filter change strands the previous selection off-screen, the right
/// info panel hides instead of showing details for a video no longer in view.
@MainActor
private func visibleSelectedVideo(_ grid: GridViewModel) -> VideoSummary? {
    guard let id = grid.selectedVideoId else { return nil }
    return grid.videos.first(where: { $0.id == id })
}

/// Pushes the navigation *content* down by a thin "importing" strip while an
/// on-device ingest runs. Applied below the toolbar (inside the nav container)
/// so it never covers the top-bar buttons — the old whole-window `safeAreaInset`
/// floated over the UIKit nav bar and hid them.
private struct IngestBannerInset: ViewModifier {
    let isIngesting: Bool
    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) {
                if isIngesting {
                    IngestBanner()
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.25), value: isIngesting)
    }
}

// MARK: - Regular (iPad): 3-column split

private struct RegularLayout: View {
    @ObservedObject var grid: GridViewModel
    let connection: AppRouter.ConnectionInfo?
    let isIngesting: Bool
    @Binding var selection: LibrarySection
    @Binding var viewMode: LibraryViewMode
    @Binding var thumbnailWidth: Double
    let apply: (LibrarySection) -> Void
    @ObservedObject var history: NavigationHistory
    let goBack: () -> Void
    let goForward: () -> Void

    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    /// The user swiped the inspector away. Kept *separate* from the selection so a
    /// dismissed panel stays hidden across selections — it only reappears when the
    /// user swipes back in from the right edge (`revealInspectorGesture`).
    @State private var inspectorHidden = false
    /// Width of the detail column, so the reveal gesture can require a right-edge start.
    @State private var detailAreaWidth: CGFloat = 0

    var body: some View {
        // Two-column split (library | main). The metadata inspector is a small
        // fixed-width trailing panel *inside* the main area — shown only in
        // Grid/List with a selection — so it never squeezes the grid into one
        // column or appears in Detail/Map mode.
        NavigationSplitView(columnVisibility: $columnVisibility) {
            LibrarySidebar(
                grid: grid, viewMode: $viewMode, selection: $selection,
                hasSelection: grid.selectedVideoId != nil, onSelect: apply)
        } detail: {
            HStack(spacing: 0) {
                center
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                if showInspector {
                    Divider()
                    InspectorPanel(grid: grid, video: visibleSelectedVideo(grid),
                                   refreshTick: grid.catalogChangeTick,
                                   onShowOnMap: { viewMode = .map }) { viewMode = .detail }
                        .frame(width: 300)
                        .transition(.move(edge: .trailing))
                        // Swipe right to *hide* the panel — the selection is kept, so
                        // selecting another card leaves the panel hidden until the user
                        // swipes back in from the right edge (revealInspectorGesture).
                        // simultaneousGesture so the panel still scrolls vertically; we
                        // only act on a predominantly-rightward swipe.
                        .simultaneousGesture(
                            DragGesture(minimumDistance: 30)
                                .onEnded { value in
                                    if value.translation.width > 60,
                                       value.translation.width > abs(value.translation.height) {
                                        withAnimation(.easeInOut(duration: 0.2)) {
                                            inspectorHidden = true
                                        }
                                    }
                                }
                        )
                }
            }
            // Measure the detail column so the reveal gesture can tell a swipe that
            // begins at the right edge from one that begins mid-grid.
            .background(
                GeometryReader { geo in
                    Color.clear
                        .onAppear { detailAreaWidth = geo.size.width }
                        .onChange(of: geo.size.width) { _, w in detailAreaWidth = w }
                }
            )
            // Swipe in from the right edge (leftward) to bring a dismissed inspector
            // back — only meaningful when a video is selected. simultaneousGesture so
            // the grid still scrolls and cards still tap.
            .simultaneousGesture(
                DragGesture(minimumDistance: 30)
                    .onEnded { value in
                        guard inspectorHidden, visibleSelectedVideo(grid) != nil else { return }
                        let fromRightEdge = detailAreaWidth > 0
                            && value.startLocation.x > detailAreaWidth - 48
                        if fromRightEdge,
                           value.translation.width < -60,
                           abs(value.translation.width) > abs(value.translation.height) {
                            withAnimation(.easeInOut(duration: 0.2)) { inspectorHidden = false }
                        }
                    }
            )
            // Thin "importing" strip below the toolbar (not overlaying it).
            .modifier(IngestBannerInset(isIngesting: isIngesting))
            .toolbar {
                ToolbarItemGroup(placement: .topBarLeading) {
                    NavHistoryButtons(history: history, goBack: goBack, goForward: goForward)
                }
            }
        }
        // The grid reflows narrower when the inspector opens on a selection, and
        // wider when the library column is collapsed — either can scroll the
        // selected card out of view. Re-center it once the layout settles.
        .onChange(of: showInspector) { _, shown in if shown { scrollToSelection() } }
        .onChange(of: columnVisibility) { _, _ in scrollToSelection() }
    }

    /// Ask the grid to re-center the current selection after a layout change.
    /// Deferred a tick so the reflow finishes before the scroll is issued.
    private func scrollToSelection() {
        guard let id = grid.selectedVideoId else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            grid.pendingScrollVideoId = id
        }
    }

    /// Inspector appears only when browsing (grid/list), the selected video's card
    /// is actually in view (so a stranded selection hides the panel), and the user
    /// hasn't swiped it away (`inspectorHidden`, reset by the reveal gesture).
    private var showInspector: Bool {
        !inspectorHidden && (viewMode == .grid || viewMode == .list) && visibleSelectedVideo(grid) != nil
    }

    @ViewBuilder private var center: some View {
        switch viewMode {
        case .grid:
            // Double-tap a card → Detail mode (single tap selects into the inspector).
            LibraryGridScreen(grid: grid, connection: connection, listMode: false,
                              thumbnailWidth: $thumbnailWidth, keyboardEnabled: true,
                              onDoubleTap: { _ in viewMode = .detail }) { _ in }
                .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        case .list:
            LibraryGridScreen(grid: grid, connection: connection, listMode: true,
                              thumbnailWidth: $thumbnailWidth, keyboardEnabled: true,
                              onDoubleTap: { _ in viewMode = .detail }) { _ in }
                .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        case .detail:
            DetailModeView(grid: grid, connection: connection,
                           onShowOnMap: { viewMode = .map })
        case .map:
            // iPad: tapping a cluster opens a trailing panel of its videos.
            MapModePad(grid: grid, viewMode: $viewMode)
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
    let isIngesting: Bool
    @Binding var selection: LibrarySection
    @Binding var viewMode: LibraryViewMode
    @Binding var thumbnailWidth: Double
    let apply: (LibrarySection) -> Void
    @ObservedObject var history: NavigationHistory
    let goBack: () -> Void
    let goForward: () -> Void

    @State private var pushedVideo: VideoSummary?
    @State private var showLibrary = false

    var body: some View {
        NavigationStack {
            center
                // Thin "importing" strip below the nav bar (not overlaying it).
                .modifier(IngestBannerInset(isIngesting: isIngesting))
                .navigationTitle("ReelVault")
                .navigationBarTitleDisplayMode(.inline)
                .navigationDestination(item: $pushedVideo) { video in
                    VideoDetailView(video: video, grid: grid, mediaEndpoint: connection,
                                    onShowOnMap: { pushedVideo = nil; viewMode = .map })
                }
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { showLibrary = true } label: {
                            Label("Library", systemImage: "sidebar.left")
                        }
                    }
                    ToolbarItemGroup(placement: .topBarLeading) {
                        NavHistoryButtons(history: history, goBack: goBack, goForward: goForward)
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
            // iPhone: tapping a cluster switches to Grid mode filtered to that
            // location (no room for a side panel).
            LibraryMapView(grid: grid) { cluster in
                grid.setLocationFilter(latitude: cluster.latitude,
                                       longitude: cluster.longitude,
                                       radiusKm: cluster.radiusKm)
                viewMode = .grid
            }
        }
    }
}
