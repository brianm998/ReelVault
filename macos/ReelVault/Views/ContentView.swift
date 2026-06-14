// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit
import AppKit
import CoreLocation

struct ContentView: View {
    @StateObject private var gridViewModel = GridViewModel()
    @StateObject private var detailViewModel = DetailViewModel()
    @State private var connectionState: ConnectionState = .connecting
    @State private var connectionError: String = ""
    // Per-view-mode panel state — width + open/closed flag for each of
    // (grid, list, detail). Lightroom keeps these independent, and so
    // do we: a user who narrows the right panel in list mode probably
    // wants the wider one back when they switch to detail. Defaults
    // are loaded from `UserDefaults` on init so widths persist across
    // launches; mutations are written back via `setLeft…` /
    // `setRight…` helpers below.
    @State private var leftPanelWidths: [ViewMode: CGFloat] =
        PanelPrefs.loadWidths(side: .left)
    @State private var rightPanelWidths: [ViewMode: CGFloat] =
        PanelPrefs.loadWidths(side: .right)
    @State private var leftPanelExpandeds: [ViewMode: Bool] =
        PanelPrefs.loadExpandeds(side: .left)
    @State private var rightPanelExpandeds: [ViewMode: Bool] =
        PanelPrefs.loadExpandeds(side: .right)
    // Thumbnail size — persisted to UserDefaults so the user's last size
    // survives restarts. Falls back to 220 pt when no preference is stored.
    @AppStorage("thumbnailWidth") private var thumbnailWidth: Double = 220
    // Mirrors the accent scheme picked in AppearanceSettingsDialog ("blue"
    // / "purple"). Drives which colour variant of the title-bar brand mark
    // is shown — the Dock tile itself can't be swapped at runtime, but the
    // in-app icon can.
    @AppStorage("accentScheme") private var accentScheme: String = "blue"
    @State private var showAddLibrarySheet = false
    @State private var showHelpSheet = false
    @State private var showWatchSettingsSheet = false
    @State private var showPlaybackSettingsSheet = false
    @State private var showCameraNamesSheet = false
    @State private var showLensNamesSheet = false
    @State private var showAppearanceSettingsSheet = false
    @State private var showLibrarySettingsSheet = false
    @State private var showOpenCatalogSheet = false
    // Wrapper that gives the proxy-picker sheet an Identifiable item
    // to bind to (sheet(item:) requires that). We don't need a real
    // model here — the video summary is enough to derive everything
    // the dialog renders.
    private struct ProxyTarget: Identifiable {
        let video: VideoSummary
        var id: String { video.id }
    }
    @State private var openCatalogIsStartup = false
    @State private var currentCatalog: CatalogInfo = .closed
    /// When non-nil, the map view opens centred on this coordinate instead of
    /// auto-fitting all pins. Set when the user taps a card's location badge;
    /// cleared on the next non-badge navigation into the map.
    @State private var globalMapFocusCoord: CLLocationCoordinate2D? = nil
    /// Videos under the pin(s) the user has clicked on the map — listed as
    /// cards in the right panel while the map is the active view.
    @State private var mapSelectedVideoIds: [String] = []
    /// Non-nil while the map's right-click "Name / Rename location" sheet is up.
    @State private var renameLocationTarget: RenameLocationTarget? = nil
    /// Non-nil → LocationPicker sheet is presenting for these video IDs.
    @State private var locationPickerTargets: [String]? = nil
    @State private var locationPickerInitial: CLLocationCoordinate2D? = nil
    /// Non-nil → CaptureDate sheet is presenting for these video IDs.
    @State private var datePickerTargets: [String]? = nil
    @State private var datePickerInitial: Int64? = nil
    /// Top-level view mode: the catalog grid vs. the single-video loupe.
    @State private var viewMode: ViewMode = .grid
    /// 'i'-cycling info overlay state — only meaningful in detail mode.
    @State private var infoOverlay: InfoOverlayState = .none
    /// True while the loupe is in full screen ('f'): all chrome is hidden and a
    /// semi-transparent floating control replaces the bottom bar. Kept in sync
    /// with the window's actual full-screen state via the notifications below.
    @State private var detailFullscreen = false
    /// When 'f' is pressed from grid/list/map it jumps to a full-frame view of
    /// the selected video; this is the view to return to on exit. nil = entered
    /// from the loupe itself, so exit just leaves full screen and stays in detail.
    @State private var preFullscreenMode: ViewMode? = nil
    /// Non-nil while the "Remove library location?" confirmation alert is shown.
    @State private var locationToRemove: LibraryLocation? = nil
    @State private var collectionToDelete: Collection? = nil
    @State private var showSmartCollectionSheet = false
    @State private var smartCollectionName = ""
    /// Monotonically-incrementing token passed to DetailLoupeView. Each
    /// increment triggers a play/pause toggle inside the loupe.
    @State private var detailPlayToggle: Int = 0
    /// Monotonically-incrementing tokens passed to DetailLoupeView for the
    /// ← / → keys in detail mode. Each increment steps the playhead one frame
    /// back / forward — but only while the clip is paused; the loupe enforces
    /// that gate (it owns the player state).
    @State private var detailStepBackToggle: Int = 0
    @State private var detailStepForwardToggle: Int = 0
    /// Non-nil when a newer GitHub release has been detected.
    /// Dismissed by the user; rechecked every 24 h.
    @State private var pendingUpdate: ReleaseInfo? = nil

    @EnvironmentObject private var appState: AppState
    @ObservedObject private var recents = RecentCatalogs.shared

    enum ConnectionState { case connecting, connected, failed }
    enum ViewMode { case grid, list, detail, map }

    var body: some View {
        Group {
            switch connectionState {
            case .connecting:
                ConnectingView()
            case .connected:
                mainUI
            case .failed:
                ConnectionErrorView(
                    errorMessage: connectionError.isEmpty
                        ? "Failed to connect to ReelVault backend on localhost:50051"
                        : connectionError,
                    onRetry: { Task { await setupConnection() } }
                )
            }
        }
        .frame(minWidth: 1200, minHeight: 800)
        // ReelVault is dark-mode only — light mode is intentionally not offered.
        .preferredColorScheme(.dark)
        .task { await setupConnection() }
        // Check for updates at startup and every 24 h. Network failures are
        // silently swallowed — never show an error banner for a background check.
        .task {
            while true {
                if let release = await UpdateChecker.checkLatestRelease(
                    owner: AppVersion.githubOwner, repo: AppVersion.githubRepo),
                   UpdateChecker.isNewer(release.version, than: AppVersion.current) {
                    pendingUpdate = release
                }
                try? await Task.sleep(for: .seconds(24 * 60 * 60))
            }
        }
        // Install an app-level NSEvent monitor so keyboard shortcuts work even
        // when no SwiftUI view holds explicit focus. This is more reliable than
        // `.onKeyPress` for app-wide hotkeys.
        .modifier(GlobalKeyboardShortcuts(
            onTab: {
                let anyOpen = leftPanelExpanded || rightPanelExpanded
                setLeftPanelExpanded(!anyOpen)
                setRightPanelExpanded(!anyOpen)
            },
            onGroupSelected: { gridViewModel.groupSelectedVideos() },
            onSelectAll: { gridViewModel.selectAllVisible() },
            onDeselectAll: { gridViewModel.clearSelection() },
            onSetGridMode: { withAnimation(.easeInOut(duration: 0.2)) { viewMode = .grid } },
            onSetListMode: { withAnimation(.easeInOut(duration: 0.2)) { viewMode = .list } },
            onSetDetailMode: { withAnimation(.easeInOut(duration: 0.2)) { viewMode = .detail } },
            onSetMapMode: {
                // Entering the map by shortcut frames all pins (no card-badge
                // focus), so clear any stale focus coordinate first.
                globalMapFocusCoord = nil
                withAnimation(.easeInOut(duration: 0.2)) { viewMode = .map }
            },
            onCycleInfoOverlay: {
                infoOverlay = {
                    switch infoOverlay {
                    case .none: return .camera
                    case .camera: return .file
                    case .file: return .none
                    }
                }()
            },
            onToggleFullScreen: {
                if detailFullscreen {
                    // Exit (the didExit observer restores the prior view).
                    NSApp.keyWindow?.toggleFullScreen(nil)
                } else if viewMode == .detail {
                    preFullscreenMode = nil
                    NSApp.keyWindow?.toggleFullScreen(nil)
                } else if gridViewModel.selectedVideoId != nil {
                    // From grid/list/map with a selected card: jump straight to a
                    // full-frame view of it, remembering where to return.
                    preFullscreenMode = viewMode
                    viewMode = .detail
                    NSApp.keyWindow?.toggleFullScreen(nil)
                }
            },
            onSpaceBar: {
                switch viewMode {
                case .grid, .list:
                    // Only respond when exactly one video is selected so we
                    // don't accidentally start playback during multi-select.
                    if gridViewModel.playingVideoId != nil {
                        gridViewModel.stopPlayback()
                    } else if let id = gridViewModel.selectedVideoId,
                              gridViewModel.selectedVideoIds.count <= 1 {
                        gridViewModel.playVideoPreferProxy(videoId: id)
                    }
                case .detail:
                    // Delegate to DetailLoupeView via the toggle token.
                    detailPlayToggle += 1
                case .map:
                    // No inline playback context on the map.
                    break
                }
            },
            onSetRating: { rating in
                gridViewModel.setRatingOnSelection(rating)
            },
            onSetColorLabel: { label in
                gridViewModel.setColorLabelOnSelection(label)
            },
            onArrow: { direction, extend in
                switch viewMode {
                case .grid, .list:
                    // Move (or, with Shift, extend) the single selection.
                    let moved = extend
                        ? gridViewModel.extendSelection(direction)
                        : gridViewModel.moveSelection(direction)
                    if let moved {
                        detailViewModel.setCurrentVideo(moved)
                        detailViewModel.loadMetadata(videoId: moved.id)
                    }
                case .detail:
                    // ← / → step the playhead one frame back / forward while
                    // the clip is paused (the loupe enforces the paused-only
                    // gate); ↑ / ↓ do nothing here.
                    switch direction {
                    case .left: detailStepBackToggle += 1
                    case .right: detailStepForwardToggle += 1
                    case .up, .down: break
                    }
                case .map:
                    break
                }
            }
        ))
        // Keep `detailFullscreen` in lockstep with the window's real full-screen
        // state, so a native exit (Esc / green button) also restores the chrome.
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didEnterFullScreenNotification)) { _ in
            if viewMode == .detail { detailFullscreen = true }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didExitFullScreenNotification)) { _ in
            detailFullscreen = false
            // Return to the view 'f' was pressed from (grid/list/map), if any.
            if let prev = preFullscreenMode {
                viewMode = prev
                preFullscreenMode = nil
            }
        }
        .sheet(isPresented: $showAddLibrarySheet) {
            AddLibraryDialog(isPresented: $showAddLibrarySheet) { paths, recursive, autoGroup, dateFormat, datePosition in
                gridViewModel.addLibraryAndScanMultiple(
                    paths: paths,
                    recursive: recursive,
                    autoGroup: autoGroup,
                    filenameDateFormat: dateFormat,
                    filenameDatePosition: datePosition
                )
            }
        }
        .sheet(isPresented: $showWatchSettingsSheet) {
            WatchSettingsDialog(isPresented: $showWatchSettingsSheet)
        }
        .sheet(isPresented: $showPlaybackSettingsSheet) {
            PlaybackSettingsDialog(isPresented: $showPlaybackSettingsSheet)
        }
        .sheet(isPresented: $showAppearanceSettingsSheet) {
            AppearanceSettingsDialog(isPresented: $showAppearanceSettingsSheet)
        }
        .sheet(isPresented: $showLibrarySettingsSheet) {
            LibrarySettingsDialog(isPresented: $showLibrarySettingsSheet)
        }
        // Proxy creation picker. Observes `proxyCreationVideoId` on the
        // grid view-model — non-nil means "user just clicked Create
        // proxy on a card, show the resolution picker". Confirm calls
        // back into the view-model to start the job; cancel clears
        // the request.
        .sheet(item: Binding<ProxyTarget?>(
            get: { gridViewModel.proxyCreationVideoId.flatMap { id in
                gridViewModel.videos.first(where: { $0.id == id }).map { ProxyTarget(video: $0) }
            } },
            set: { _ in /* dismissals handled explicitly below */ }
        )) { target in
            ProxyResolutionDialog(
                sourceVideo: target.video,
                onConfirm: { height in
                    gridViewModel.startProxyCreation(videoId: target.video.id, targetHeight: height)
                },
                onCancel: { gridViewModel.cancelProxyCreation() }
            )
        }
        .sheet(isPresented: $showCameraNamesSheet) {
            CameraNamesView()
        }
        .sheet(isPresented: $showLensNamesSheet) {
            LensNamesView()
        }
        .sheet(isPresented: $showOpenCatalogSheet) {
            OpenCatalogDialog(
                isPresented: $showOpenCatalogSheet,
                onPick: { path in
                    showOpenCatalogSheet = false
                    Task { await openCatalog(path: path) }
                },
                isStartup: openCatalogIsStartup
            )
        }
        .sheet(item: Binding(
            get: { locationPickerTargets.map(LocationPickerTargets.init) },
            set: { locationPickerTargets = $0?.ids }
        )) { targets in
            LocationPickerView(
                targetVideoIds: targets.ids,
                initialLocation: locationPickerInitial,
                existingLocations: gridViewModel.videoLocations,
                namedLocations: gridViewModel.namedLocations,
                // Same accent the map view uses, so the picker's re-use pins
                // match it exactly.
                pinColor: accentScheme == "purple" ? .systemPurple : .systemBlue,
                onCancel: {
                    locationPickerTargets = nil
                    locationPickerInitial = nil
                },
                onApply: { lat, lon, writeToFile, name in
                    // If the user typed (or kept) a name, upsert it first
                    // so subsequent UI refreshes can resolve the new GPS
                    // into a name immediately. If the candidate matched an
                    // existing named-location AND the user kept the same
                    // name, we still re-upsert with that name + coord,
                    // which the daemon handles as an idempotent update.
                    if let n = name {
                        let existing = LocationPickerView.nearestNamedLocation(
                            to: CLLocationCoordinate2D(latitude: lat, longitude: lon),
                            in: gridViewModel.namedLocations
                        )
                        gridViewModel.saveNamedLocation(
                            id: existing?.id ?? "",
                            name: n,
                            latitude: lat,
                            longitude: lon
                        )
                    }
                    gridViewModel.setVideoLocations(
                        videoIds: targets.ids,
                        latitude: lat,
                        longitude: lon,
                        writeToFile: writeToFile
                    ) {
                        // Refresh detail panel so the new GPS shows up.
                        if let first = targets.ids.first {
                            detailViewModel.loadMetadata(videoId: first)
                        }
                    }
                    locationPickerTargets = nil
                    locationPickerInitial = nil
                }
            )
        }
        // Map right-click "Name / Rename location" sheet.
        .sheet(item: $renameLocationTarget) { target in
            LocationNameSheet(
                initialName: target.existing?.name ?? "",
                onCancel: { renameLocationTarget = nil },
                onSave: { newName in
                    gridViewModel.saveNamedLocation(
                        id: target.existing?.id ?? "",
                        name: newName,
                        // Keep an existing place's centre; otherwise pin the new
                        // name to the exact spot the user right-clicked.
                        latitude: target.existing?.latitude ?? target.coordinate.latitude,
                        longitude: target.existing?.longitude ?? target.coordinate.longitude,
                        radiusMeters: target.existing?.radiusMeters ?? 250
                    )
                    renameLocationTarget = nil
                }
            )
        }
        .sheet(item: Binding(
            get: { datePickerTargets.map(LocationPickerTargets.init) },
            set: { datePickerTargets = $0?.ids }
        )) { targets in
            CaptureDateView(
                targetVideoIds: targets.ids,
                initialTimestampMs: datePickerInitial,
                // Filename of the single target (for filename-based date
                // inference); nil for multi-select so the infer controls hide.
                primaryFilename: targets.ids.count == 1
                    ? (gridViewModel.videos.first { $0.id == targets.ids[0] }?.filename
                        ?? (gridViewModel.selectedVideo?.id == targets.ids[0]
                            ? gridViewModel.selectedVideo?.filename : nil))
                    : nil,
                onCancel: {
                    datePickerTargets = nil
                    datePickerInitial = nil
                },
                onApply: { ms, writeToFile in
                    gridViewModel.setVideoCaptureDates(
                        videoIds: targets.ids,
                        timestampMs: ms,
                        writeToFile: writeToFile
                    ) {
                        if let first = targets.ids.first {
                            detailViewModel.loadMetadata(videoId: first)
                        }
                    }
                    datePickerTargets = nil
                    datePickerInitial = nil
                }
            )
        }
        // Sync the recent list + current catalog name up to AppState so the
        // File menu (which lives at App scope) sees current values.
        .onAppear {
            appState.recents = recents.list()
            appState.currentCatalogName = currentCatalog.name
        }
        .onChange(of: recents.revision) { _, _ in
            appState.recents = recents.list()
        }
        .onChange(of: currentCatalog.name) { _, newName in
            appState.currentCatalogName = newName
        }
        // File menu actions wired in from app-scope commands.
        .onChange(of: appState.openCatalogRequestToken) { _, _ in
            openCatalogIsStartup = false
            showOpenCatalogSheet = true
        }
        .onChange(of: appState.closeCatalogRequestToken) { _, _ in
            Task { await closeCurrentCatalog() }
        }
        .onChange(of: appState.openRecentRequest.token) { _, _ in
            let path = appState.openRecentRequest.path
            if !path.isEmpty {
                Task { await openCatalog(path: path) }
            }
        }
        .onChange(of: appState.clearRecentsRequestToken) { _, _ in
            for path in recents.list() { recents.remove(path) }
        }
        .onChange(of: appState.showHelpRequestToken) { _, _ in
            showHelpSheet = true
        }
        .sheet(isPresented: $showHelpSheet) {
            HelpView()
        }
    }

    private var mainUI: some View {
        VStack(spacing: 0) {
            // All chrome is hidden in full-screen detail mode, leaving just the
            // video and its floating control.
            if !detailFullscreen {
                topBar
                scanBanner
                postIndexBanner
                scanResultBanner
                updateBanner
                locationFilterBanner
            }
            mainContent
            if !detailFullscreen { bottomBar }
        }
        // Refresh the map's pins from the current filter whenever the map
        // becomes the active view. Filter edits made while the map is up
        // already refresh videoLocations via the grid reload.
        .onChange(of: viewMode) { _, newMode in
            // DETAIL pins the loupe's video even when a metadata edit filters it
            // out; returning to GRID/LIST/MAP drops a now-hidden selection so the
            // inspector doesn't strand it.
            gridViewModel.onViewModeChanged(isDetail: newMode == .detail)
            if newMode == .map {
                Task {
                    await gridViewModel.loadVideoLocationsFilteredAsync()
                    // Named places drive the pin labels.
                    await gridViewModel.loadNamedLocationsAsync()
                }
            }
        }
    }

    /// Present the location-picker sheet for `videoIds`, framed on `initial`
    /// (the current location for an "update", or nil to frame on all data for an
    /// "add"). Awaits the location loads first so the picker's init sees
    /// populated arrays (else it centres on (25, 0)). Shared by the grid/list/map
    /// context menus and the detail panel.
    private func presentLocationPicker(_ videoIds: [String], initial: (Double, Double)?) {
        Task {
            await gridViewModel.loadVideoLocationsAsync()
            await gridViewModel.loadNamedLocationsAsync()
            locationPickerTargets = videoIds
            locationPickerInitial = initial.map {
                CLLocationCoordinate2D(latitude: $0.0, longitude: $0.1)
            }
        }
    }

    @ViewBuilder
    private var updateBanner: some View {
        if let release = pendingUpdate {
            HStack(spacing: 8) {
                Image(systemName: "arrow.up.circle.fill")
                    .foregroundColor(.accentColor)
                VStack(alignment: .leading, spacing: 0) {
                    Text("ReelVault \(release.version) is available")
                        .font(.callout)
                    Text("You are running \(AppVersion.current)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Spacer()
                Button {
                    if let url = URL(string: release.releaseUrl) {
                        NSWorkspace.shared.open(url)
                    }
                } label: {
                    Label("Download", systemImage: "arrow.down.circle")
                        .font(.caption)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .help("Open the GitHub releases page to download \(release.version)")

                Button {
                    pendingUpdate = nil
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Dismiss this update notification")
            }
            .padding(8)
            .background(Color.accentColor.opacity(0.12))
        }
    }

    /// Prominent strip across the top of the grid that surfaces an active
    /// proximity filter, so the user always knows why the grid is showing
    /// a subset and can clear the filter in one click. Without this, the
    /// transition from "tapped a pin on the map" to "now in a filtered
    /// grid" is jarring — the result looks like the entire library
    /// disappeared.
    @ViewBuilder
    private var locationFilterBanner: some View {
        if let filter = gridViewModel.filterLocation {
            // Resolve the coordinate into a name when one exists in the
            // catalog's named-locations table; otherwise fall back to the
            // raw lat/long. Mirrors the DetailView's name-first treatment.
            let matched = gridViewModel.nameForLocation(
                latitude: filter.latitude, longitude: filter.longitude)
            let identity: String = matched?.name
                ?? String(format: "%.4f, %.4f", filter.latitude, filter.longitude)
            HStack(spacing: 8) {
                Image(systemName: matched != nil ? "tag.fill" : "mappin.circle.fill")
                    .foregroundColor(.accentColor)
                VStack(alignment: .leading, spacing: 0) {
                    Text("Filtered to videos near \(identity)")
                        .font(.callout)
                    Text("\(gridViewModel.totalCount) match\(gridViewModel.totalCount == 1 ? "" : "es") within \(filter.radiusKm == floor(filter.radiusKm) ? String(format: "%.0f", filter.radiusKm) : String(format: "%.1f", filter.radiusKm)) km")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Spacer()
                Button {
                    gridViewModel.setLocationFilter(latitude: nil, longitude: nil)
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "xmark.circle.fill")
                        Text("Show all videos")
                            .font(.caption)
                    }
                }
                .buttonStyle(.borderless)
                .help("Clear the location filter and return to the full library.")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color.accentColor.opacity(0.15))
            .overlay(
                Rectangle()
                    .fill(Color.accentColor)
                    .frame(height: 2),
                alignment: .bottom
            )
        }
    }

    /// Loads the accent-matched titlebar icon from the SPM resource bundle.
    /// Falls back to an empty image rather than crashing the app if the
    /// resource ever goes missing.
    private var titlebarIcon: Image {
        let name = accentScheme == "purple"
            ? "AppIcon-titlebar-purple"
            : "AppIcon-titlebar-blue"
        if let url = Bundle.module.url(forResource: name, withExtension: "png"),
           let nsImage = NSImage(contentsOf: url) {
            return Image(nsImage: nsImage)
        }
        return Image(systemName: "film")
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            titlebarIcon
                .resizable()
                .interpolation(.high)
                .frame(width: 26, height: 26)
            VStack(alignment: .leading, spacing: 0) {
                Text("ReelVault")
                    .font(.system(size: 18, weight: .semibold))
                if currentCatalog.isOpen {
                    Text(currentCatalog.name)
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                        .help("Open catalog: \(currentCatalog.path)")
                }
            }

            // Search field + filter dropdowns now live in the Library Filter
            // bar (LibraryFilterBar), below the top bar in the centre column.

            Spacer()

            // Trailing controls, left → right: Proxy · Live · Group · Map ·
            // Settings. Kept identical in order to the Compose client.

            // Proxy-playback indicator — relocated here from an overlay on the
            // video itself so it never covers the frame. Visible only while the
            // detail player is showing a proxy.
            if let banner = detailViewModel.proxyBanner {
                Button {
                    detailViewModel.requestScrollToProxies()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "film")
                            .font(.system(size: 10, weight: .semibold))
                        // "selected" is redundant — a proxy is always the one
                        // selected (by the user or auto), so just say "Playing proxy".
                        Text("Playing proxy")
                            .font(.system(size: 11, weight: .medium))
                    }
                    .foregroundColor(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(Color(red: 0.16, green: 0.53, blue: 0.53).opacity(0.85))
                    )
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(banner.detail.map {
                    "Showing proxy: \($0). The detail player is showing a proxy, not the master file. Click to jump to it in the details panel, or pick a different proxy / revert to the master there."
                } ?? "The detail player is showing a proxy, not the master file. Click to jump to it in the details panel, or pick a different proxy / revert to the master there.")
            }

            // Live-updates pill — shows whether the server's file watcher
            // is active and offers a one-click entry into its settings.
            // Green dot = live; grey dot = paused.
            Button {
                showWatchSettingsSheet = true
            } label: {
                HStack(spacing: 4) {
                    Circle()
                        .fill(gridViewModel.liveUpdatesEnabled ? Color.green : Color.gray)
                        .frame(width: 8, height: 8)
                    Text("Live")
                        .font(.system(size: 11, weight: .medium))
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(Color.secondary.opacity(0.4), lineWidth: 1)
                )
            }
            .buttonStyle(.borderless)
            .help(gridViewModel.liveUpdatesEnabled
                  ? "Live updates are on — ReelVault is watching your libraries for new and changed files and will add them automatically. Click to adjust."
                  : "Live updates are off. Click to turn them on or adjust the watcher settings.")

            // Group selected (enabled when 2+ selected)
            Button {
                gridViewModel.groupSelectedVideos()
            } label: {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: "square.stack.3d.up.fill")
                        .foregroundColor(
                            gridViewModel.selectedVideoIds.count >= 2 ? .accentColor : .secondary
                        )
                    if !gridViewModel.selectedVideoIds.isEmpty {
                        Text("\(gridViewModel.selectedVideoIds.count)")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Capsule().fill(Color.accentColor))
                            .offset(x: 8, y: -6)
                    }
                }
            }
            .buttonStyle(.borderless)
            .disabled(gridViewModel.selectedVideoIds.count < 2)
            .help(gridViewModel.selectedVideoIds.count >= 2
                  ? "Stack the \(gridViewModel.selectedVideoIds.count) selected videos into a group (⌘G). One representative will be shown in the grid; click its stack badge to expand."
                  : "Shift-click or ⌘-click two or more videos in the grid to enable grouping.")

            // (The world map is now a top-level view — reachable from the
            // view-mode toggle in the bottom bar, or the 'M' shortcut — so it
            // no longer has a top-bar button.)

            // (The active location-filter affordance lives in
            // `locationFilterBanner` — a full-width strip above the grid —
            // rather than as a tiny chip up here, so users actually notice
            // why the grid is narrowed.)

            // Settings — every preference collapsed into one gear menu so the
            // top bar isn't a row of mystery glyphs. Playback, Library, and
            // Appearance sit at the top level; the name-mapping editors live in
            // a Names submenu. (Adding folders is done from the Library panel.)
            Menu {
                Button {
                    showPlaybackSettingsSheet = true
                } label: {
                    Label("Playback & Proxies…", systemImage: "play.rectangle")
                }
                Button {
                    showLibrarySettingsSheet = true
                } label: {
                    Label("Library — Auto-Tagging…", systemImage: "books.vertical")
                }
                Button {
                    showAppearanceSettingsSheet = true
                } label: {
                    Label("Appearance…", systemImage: "paintpalette")
                }
                Divider()
                Menu {
                    Button {
                        showCameraNamesSheet = true
                    } label: {
                        Label("Camera Names…", systemImage: "camera.metering.matrix")
                    }
                    Button {
                        showLensNamesSheet = true
                    } label: {
                        Label("Lens Names…", systemImage: "camera.aperture")
                    }
                } label: {
                    Label("Names", systemImage: "textformat")
                }
            } label: {
                Image(systemName: "gearshape")
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .help("Settings — playback & proxies, library auto-tagging, appearance, and camera/lens name mappings.")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color(.windowBackgroundColor))
        .overlay(Rectangle().frame(height: 1).foregroundColor(Color(.separatorColor)), alignment: .bottom)
    }

    /// Full-width bottom bar: view-mode toggle (left), thumbnail-size slider
    /// (right). Sort controls live in the Library Filter bar (top-right).
    /// Height matches a standard macOS toolbar row.
    private var bottomBar: some View {
        VStack(spacing: 0) {
            Divider()
            HStack(spacing: 0) {
                // Left cluster — view-mode toggle. Order matches the Compose
                // desktop client: Grid, List, Detail, Map (left to right).
                // Custom binding so a click animates the layout reflow.
                Picker("", selection: Binding(
                    get: { viewMode },
                    set: { newMode in
                        // Entering the map from the toggle frames all pins, so
                        // drop any card-badge focus coordinate first.
                        if newMode == .map { globalMapFocusCoord = nil }
                        withAnimation(.easeInOut(duration: 0.2)) { viewMode = newMode }
                    }
                )) {
                    Image(systemName: "square.grid.2x2").tag(ViewMode.grid)
                    Image(systemName: "list.bullet").tag(ViewMode.list)
                    Image(systemName: "play.rectangle").tag(ViewMode.detail)
                    Image(systemName: "map").tag(ViewMode.map)
                }
                .pickerStyle(.segmented)
                .frame(width: 180)
                .labelsHidden()
                .help("Switch between Grid (G), List (L), Catalog/Detail (D), and Map (M) views.")

                Spacer()

                // Right cluster — thumbnail-size slider (disabled in Catalog/Detail mode)
                let sliderEnabled = viewMode == .grid || viewMode == .list
                HStack(spacing: 6) {
                    Image(systemName: "photo")
                        .font(.system(size: 10))
                        .foregroundColor(sliderEnabled ? .primary : .secondary)
                    Slider(value: $thumbnailWidth, in: 120...400)
                        .frame(width: 140)
                        .disabled(!sliderEnabled)
                        .help(sliderEnabled
                              ? "Drag to resize thumbnails. The grid automatically adjusts how many columns fit."
                              : "Thumbnail size only applies in Grid or List mode.")
                    Image(systemName: "photo")
                        .font(.system(size: 14))
                        .foregroundColor(sliderEnabled ? .primary : .secondary)
                }
                .opacity(sliderEnabled ? 1.0 : 0.4)
            }
            .padding(.horizontal, 12)
            .frame(height: 44)
            .background(Color(.windowBackgroundColor))
        }
    }

    @ViewBuilder
    private var scanBanner: some View {
        if let status = gridViewModel.scanStatus ?? gridViewModel.watcherBanner {
            HStack {
                ProgressView().scaleEffect(0.6)
                Text(status).font(.caption)
                Spacer()
            }
            .padding(8)
            .background(Color.accentColor.opacity(0.15))
        }
    }

    /// Background post-index activity — proxy detection / grouping / sensor
    /// lookups. Driven by the `.postIndex*` catalog events, so it surfaces
    /// watcher-triggered passes (which have no scan banner) and explains why
    /// the core is busy and roughly how far along it is.
    @ViewBuilder
    private var postIndexBanner: some View {
        if let pi = gridViewModel.postIndexProgress {
            let phaseLabel: String = {
                switch pi.phase {
                case "grouping": return "Grouping clips"
                case "proxies": return "Detecting proxies"
                case "sensors": return "Fetching camera data"
                case "tagging": return "Tagging timelapses"
                default: return "Post-indexing"
                }
            }()
            let countText = pi.total > 0 ? "\(pi.processed) / \(pi.total)" : "\(pi.processed)"
            let etaText: String = {
                let s = pi.etaSeconds
                if s <= 0 { return "" }
                if s < 60 { return "~\(s)s left" }
                if s < 3600 { return "~\(s / 60) min left" }
                return "~\(s / 3600)h \((s % 3600) / 60)m left"
            }()
            VStack(alignment: .leading, spacing: 2) {
                // Single line: phase label (left) · progress bar (middle,
                // greedy) · time estimate (right).
                HStack(spacing: 8) {
                    ProgressView().scaleEffect(0.6)
                    Text("\(phaseLabel) (\(countText))")
                        .font(.caption)
                        .lineLimit(1)
                        .fixedSize()
                        // Crossfade when the phase changes (e.g. proxies →
                        // tagging). Keyed on `pi.phase` so the per-second
                        // count updates snap rather than animate.
                        .contentTransition(.opacity)
                        .animation(.easeInOut(duration: 0.25), value: pi.phase)
                    if pi.total > 0 {
                        ProgressView(value: min(max(pi.percent, 0), 100), total: 100)
                            .progressViewStyle(.linear)
                    } else {
                        // Total unknown (rare) — indeterminate bar.
                        ProgressView().progressViewStyle(.linear)
                    }
                    if !etaText.isEmpty {
                        Text(etaText).font(.caption).foregroundColor(.secondary).fixedSize()
                    }
                }
                // Optional second row: the live per-item detail.
                if !pi.detail.isEmpty {
                    Text(pi.detail)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
            .padding(8)
            .background(Color.blue.opacity(0.12))
        }
    }

    @ViewBuilder
    private var scanResultBanner: some View {
        if let result = gridViewModel.scanResult {
            HStack {
                Image(systemName: result.success
                      ? (result.videosFound == 0 ? "exclamationmark.triangle" : "checkmark.circle.fill")
                      : "xmark.octagon.fill")
                    .foregroundColor(result.success
                                     ? (result.videosFound == 0 ? .orange : .green)
                                     : .red)
                Text(result.message).font(.caption)
                Spacer()
                Button {
                    gridViewModel.clearScanResult()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Dismiss this notification")
            }
            .padding(8)
            .background(
                result.success
                    ? (result.videosFound == 0 ? Color.orange.opacity(0.15) : Color.green.opacity(0.15))
                    : Color.red.opacity(0.15)
            )
        }
    }

    private var mainContent: some View {
        HStack(spacing: 0) {
            // Left panel — in detail mode the slot shows the brightness/colour
            // graphs (a per-video view); otherwise the library navigation panel
            // or its collapsed strip. Hidden entirely in full-screen detail mode.
            if detailFullscreen {
                // no panel in full screen
            } else if leftPanelExpanded {
                if viewMode == .detail {
                    DetailGraphsPanel(
                        gridViewModel: gridViewModel,
                        onCollapse: { setLeftPanelExpanded(false) }
                    )
                    .frame(width: leftPanelWidth)
                } else {
                LibraryPanel(
                    rows: gridViewModel.visibleLibraryRows,
                    locations: gridViewModel.libraryLocations,
                    selectedPaths: gridViewModel.selectedLocationPaths,
                    totalVideos: gridViewModel.libraryLocations.reduce(0) { $0 + $1.videoCount },
                    onSelect: { path in
                        // Read the modifiers captured at mouse-down (same source
                        // the grid uses): Shift = range, ⌘ = toggle.
                        let mods = ModifierSnapshot.lastMouseDownModifiers
                        if path.isEmpty {
                            gridViewModel.setLocationFilter("")
                        } else if mods.contains(.shift) {
                            gridViewModel.selectLocationRange(path)
                        } else if mods.contains(.command) {
                            gridViewModel.toggleLocationFilter(path)
                        } else {
                            gridViewModel.setLocationFilter(path)
                        }
                    },
                    onToggleExpand: { gridViewModel.toggleExpand($0) },
                    scrollToPath: gridViewModel.pendingLibraryScroll,
                    onAddLibrary: { showAddLibrarySheet = true },
                    onRemoveLocation: { locationToRemove = $0 },
                    onRescan: { loc in gridViewModel.rescanLibrary(path: loc.path) },
                    rescanningPaths: Set(gridViewModel.rescanningPaths),
                    onCollapse: { setLeftPanelExpanded(false) },
                    collections: gridViewModel.collections,
                    smartCollectionCounts: gridViewModel.smartCollectionCounts,
                    selectedCollectionId: gridViewModel.selectedCollectionId,
                    onSelectCollection: { gridViewModel.setCollectionFilter($0) },
                    onCreateCollection: { name in
                        gridViewModel.createCollection(name: name, isSmart: false)
                    },
                    onCreateSmartCollection: {
                        smartCollectionName = ""
                        showSmartCollectionSheet = true
                    },
                    onDeleteCollection: { collectionToDelete = $0 }
                )
                .alert(
                    "Remove library location?",
                    isPresented: Binding(
                        get: { locationToRemove != nil },
                        set: { if !$0 { locationToRemove = nil } }
                    ),
                    presenting: locationToRemove
                ) { loc in
                    Button("Remove", role: .destructive) {
                        gridViewModel.removeLibraryLocation(path: loc.path)
                        locationToRemove = nil
                    }
                    Button("Cancel", role: .cancel) { locationToRemove = nil }
                } message: { loc in
                    let n = loc.videoCount
                    let word = n == 1 ? "video" : "videos"
                    Text("\(n) \(word) from \"\(loc.path)\" will be removed from your catalog. The files on disk will not be deleted.")
                }
                .alert(
                    "Delete collection?",
                    isPresented: Binding(
                        get: { collectionToDelete != nil },
                        set: { if !$0 { collectionToDelete = nil } }
                    ),
                    presenting: collectionToDelete
                ) { col in
                    Button("Delete", role: .destructive) {
                        gridViewModel.deleteCollection(id: col.id)
                        collectionToDelete = nil
                    }
                    Button("Cancel", role: .cancel) { collectionToDelete = nil }
                } message: { col in
                    Text("\"\(col.name)\" will be permanently deleted. The videos in it will not be affected.")
                }
                .sheet(isPresented: $showSmartCollectionSheet) {
                    SmartCollectionNameSheet(
                        name: $smartCollectionName,
                        onSave: { name in
                            gridViewModel.createCollection(
                                name: name,
                                isSmart: true,
                                filterJson: gridViewModel.buildSmartCollectionFilterJson()
                            )
                            showSmartCollectionSheet = false
                        },
                        onCancel: { showSmartCollectionSheet = false }
                    )
                }
                .frame(width: leftPanelWidth)
                } // end else — library vs. graphs panel
                // Drag handle on the inner edge — drag right to widen,
                // left to shrink. Cursor switches to a horizontal
                // resize affordance while hovering the handle.
                PanelResizeHandle(isLeftPanel: true,
                                  currentWidth: leftPanelWidth) { newWidth in
                    setLeftPanelWidth(newWidth)
                }
            } else {
                CollapsedPanelStrip(
                    expandIconLeft: false,
                    tooltip: "Show library panel (Tab)",
                    onClick: { setLeftPanelExpanded(true) }
                )
            }

            if !detailFullscreen { Divider() }

            // Middle column — the Library Filter bar pinned above the grid /
            // list. Living between the two panel dividers makes the bar stop at
            // the side panels and track their resize / collapse automatically.
            VStack(spacing: 0) {
                if viewMode != .detail {
                    // Banner shown the whole time a smart collection is the active
                    // view. It explains the view, offers Update / Reset once the
                    // user edits the live filter, and a ✕ to clear back to all.
                    if let smart = gridViewModel.collections.first(where: {
                        $0.id == gridViewModel.selectedCollectionId && $0.isSmart
                    }) {
                        let diverged = gridViewModel.divergedSmartCollection != nil
                        HStack(spacing: 8) {
                            Image(systemName: "sparkles")
                                .font(.system(size: 11))
                            Text(diverged
                                 ? "You changed the filter for smart collection “\(smart.name)”. Update it to match these criteria, or reset to its saved rules."
                                 : "Viewing smart collection “\(smart.name)”.")
                                .font(.caption)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if diverged {
                                Button("Reset to default") { gridViewModel.revertActiveSmartCollection() }
                                Button("Update collection") { gridViewModel.updateActiveSmartCollection() }
                                    .buttonStyle(.borderedProminent)
                            }
                            Button {
                                gridViewModel.clearSmartCollectionShowAll()
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 13))
                                    .foregroundColor(.secondary)
                            }
                            .buttonStyle(.plain)
                            .help("Clear the filter and show all videos")
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .frame(maxWidth: .infinity)
                        .background(Color.accentColor.opacity(0.15))
                    }
                    LibraryFilterBar(vm: gridViewModel, hideLocationOption: viewMode == .map)
                }
                switch viewMode {
                case .grid:
                    GridView(
                        viewModel: gridViewModel,
                        detailViewModel: detailViewModel,
                        thumbnailMinWidth: CGFloat(thumbnailWidth),
                        onEditLocation: { presentLocationPicker($0, initial: $1) },
                        onLocationClick: { lat, lon in
                            Task {
                                await gridViewModel.loadVideoLocationsFilteredAsync()
                                // Pre-select the videos at this exact coordinate
                                // so the map's right panel is populated as soon
                                // as the view switches.
                                mapSelectedVideoIds = gridViewModel.videoLocations
                                    .filter {
                                        abs($0.latitude - lat) < 1e-9 && abs($0.longitude - lon) < 1e-9
                                    }
                                    .map { $0.id }
                                globalMapFocusCoord = CLLocationCoordinate2D(latitude: lat, longitude: lon)
                                withAnimation(.easeInOut(duration: 0.2)) { viewMode = .map }
                            }
                        }
                    )
                    // Always claim the full height (not just width) so the
                    // centre column never collapses to the spinner's intrinsic
                    // size during a reload — otherwise the VStack shrinks and
                    // the enclosing HStack centres it, making the filter bar
                    // jump to the middle and back. The grid's ScrollView is
                    // already greedy; this keeps the loading / empty states
                    // greedy too.
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .list:
                    ListView(
                        viewModel: gridViewModel,
                        detailViewModel: detailViewModel,
                        // Same slider value the grid uses — a list-mode card
                        // matches its grid-mode counterpart in size, so the
                        // size slider scales both views in lockstep instead
                        // of leaving list-mode cards half the width.
                        thumbnailHeight: CGFloat(thumbnailWidth),
                        onEditLocation: { presentLocationPicker($0, initial: $1) },
                        onLocationClick: { lat, lon in
                            Task {
                                await gridViewModel.loadVideoLocationsFilteredAsync()
                                // Pre-select the videos at this exact coordinate
                                // so the map's right panel is populated as soon
                                // as the view switches.
                                mapSelectedVideoIds = gridViewModel.videoLocations
                                    .filter {
                                        abs($0.latitude - lat) < 1e-9 && abs($0.longitude - lon) < 1e-9
                                    }
                                    .map { $0.id }
                                globalMapFocusCoord = CLLocationCoordinate2D(latitude: lat, longitude: lon)
                                withAnimation(.easeInOut(duration: 0.2)) { viewMode = .map }
                            }
                        }
                    )
                    // Same full-height claim as grid mode (see above) so the
                    // filter bar stays pinned while the list reloads.
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .detail:
                    DetailLoupeView(
                        gridViewModel: gridViewModel,
                        detailViewModel: detailViewModel,
                        infoOverlay: infoOverlay,
                        playToggle: detailPlayToggle,
                        stepBackToggle: detailStepBackToggle,
                        stepForwardToggle: detailStepForwardToggle,
                        fullscreen: detailFullscreen,
                        onToggleFullscreen: { NSApp.keyWindow?.toggleFullScreen(nil) }
                    )
                    .frame(maxWidth: .infinity)
                case .map:
                    MapTopLevelView(
                        locations: gridViewModel.videoLocations,
                        selectedVideoIds: mapSelectedVideoIds,
                        onSelectionChange: { mapSelectedVideoIds = $0 },
                        pinColor: accentScheme == "purple" ? .systemPurple : .systemBlue,
                        placeName: { coord in
                            gridViewModel.nameForLocation(
                                latitude: coord.latitude, longitude: coord.longitude)?.name
                        },
                        focusedCoordinate: globalMapFocusCoord,
                        onRequestNameLocation: { coord in
                            let existing = gridViewModel.nameForLocation(
                                latitude: coord.latitude, longitude: coord.longitude)
                            renameLocationTarget = RenameLocationTarget(
                                coordinate: coord, existing: existing)
                        },
                        isLoadingVideoLocations: gridViewModel.isLoadingVideoLocations
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .frame(maxWidth: .infinity)

            if !detailFullscreen { Divider() }

            // Right detail panel — its drag handle sits on the LEFT
            // edge (between the divider and the panel body) so drags
            // toward the right narrow the panel, drags toward the
            // left widen it. Hidden entirely in full-screen detail mode.
            if detailFullscreen {
                // no panel in full screen
            } else if rightPanelExpanded {
                PanelResizeHandle(isLeftPanel: false,
                                  currentWidth: rightPanelWidth) { newWidth in
                    setRightPanelWidth(newWidth)
                }
                if viewMode == .map {
                    // Map mode: the videos at the selected location(s) as cards,
                    // in place of the metadata inspector.
                    let selSet = Set(mapSelectedVideoIds)
                    let mapVideos = gridViewModel.geotaggedVideos.filter { selSet.contains($0.id) }
                    // When the selected spot is a named place, show its name in
                    // the panel header instead of the generic "here".
                    let mapLocationName = mapVideos.first.flatMap {
                        gridViewModel.nameForLocation(
                            latitude: $0.gpsLatitude, longitude: $0.gpsLongitude)?.name
                    }
                    MapVideoListPanel(
                        gridViewModel: gridViewModel,
                        onEditLocation: { presentLocationPicker($0, initial: $1) },
                        videos: mapVideos,
                        // Spinner while a clicked location's videos are still
                        // being resolved (and none are showing yet).
                        loading: !selSet.isEmpty && mapVideos.isEmpty
                            && gridViewModel.isLoadingVideoLocations,
                        locationName: mapLocationName,
                        thumbnailMinWidth: CGFloat(thumbnailWidth),
                        currentVideoId: gridViewModel.selectedVideoId,
                        onCardClick: { openMapVideo($0, in: .map) },
                        onOpenInGrid: { openMapVideo($0, in: .grid) },
                        onOpenInList: { openMapVideo($0, in: .list) },
                        onOpenInDetail: { openMapVideo($0, in: .detail) },
                        onOpenAllInGrid: {
                            gridViewModel.filterToVideosLocation(mapSelectedVideoIds)
                            viewMode = .grid
                        },
                        onOpenAllInList: {
                            gridViewModel.filterToVideosLocation(mapSelectedVideoIds)
                            viewMode = .list
                        },
                        onCollapse: { setRightPanelExpanded(false) }
                    )
                    .frame(width: rightPanelWidth)
                } else {
                DetailView(
                    viewModel: detailViewModel,
                    gridViewModel: gridViewModel,
                    onCollapse: { setRightPanelExpanded(false) },
                    isLoupeMode: viewMode == .detail,
                    onEditLocation: { presentLocationPicker($0, initial: $1) },
                    onEditCaptureDate: { videoIds, initialTs in
                        datePickerTargets = videoIds
                        datePickerInitial = initialTs
                    },
                    onShowOnMap: { lat, lon in
                        Task {
                            await gridViewModel.loadVideoLocationsFilteredAsync()
                            mapSelectedVideoIds = gridViewModel.videoLocations
                                .filter { abs($0.latitude - lat) < 1e-9 && abs($0.longitude - lon) < 1e-9 }
                                .map { $0.id }
                            globalMapFocusCoord = CLLocationCoordinate2D(latitude: lat, longitude: lon)
                            withAnimation(.easeInOut(duration: 0.2)) { viewMode = .map }
                        }
                    }
                )
                .frame(width: rightPanelWidth)
                } // end else — map list vs. metadata inspector
            } else {
                CollapsedPanelStrip(
                    expandIconLeft: true,
                    tooltip: "Show details panel (Tab)",
                    onClick: { setRightPanelExpanded(true) }
                )
            }
        }
    }

    /// Navigate to `mode` focused on `video` — used by the map's right-panel
    /// "Open in Grid/List/Detail" actions and card clicks. Selects the video
    /// and loads its metadata so the target view shows it as the current pick.
    private func openMapVideo(_ video: VideoSummary, in mode: ViewMode) {
        gridViewModel.selectVideo(video)
        detailViewModel.setCurrentVideo(video)
        detailViewModel.loadMetadata(videoId: video.id)
        withAnimation(.easeInOut(duration: 0.2)) { viewMode = mode }
    }

    // MARK: - Panel width / open-state accessors
    //
    // The four `@State` dictionaries above store per-view-mode values;
    // these computed properties + setters read/write the entry for the
    // current `viewMode` and persist updates to UserDefaults so they
    // survive across app launches.

    private var leftPanelWidth: CGFloat {
        leftPanelWidths[viewMode] ?? PanelPrefs.defaultWidth
    }
    private var rightPanelWidth: CGFloat {
        rightPanelWidths[viewMode] ?? PanelPrefs.defaultWidth
    }
    private var leftPanelExpanded: Bool {
        leftPanelExpandeds[viewMode] ?? true
    }
    private var rightPanelExpanded: Bool {
        rightPanelExpandeds[viewMode] ?? true
    }

    private func setLeftPanelWidth(_ width: CGFloat) {
        let clamped = PanelPrefs.clamp(width)
        leftPanelWidths[viewMode] = clamped
        PanelPrefs.saveWidth(side: .left, mode: viewMode, value: clamped)
    }
    private func setRightPanelWidth(_ width: CGFloat) {
        let clamped = PanelPrefs.clamp(width)
        rightPanelWidths[viewMode] = clamped
        PanelPrefs.saveWidth(side: .right, mode: viewMode, value: clamped)
    }
    private func setLeftPanelExpanded(_ open: Bool) {
        withAnimation(.easeInOut(duration: 0.2)) {
            leftPanelExpandeds[viewMode] = open
        }
        PanelPrefs.saveExpanded(side: .left, mode: viewMode, value: open)
    }
    private func setRightPanelExpanded(_ open: Bool) {
        withAnimation(.easeInOut(duration: 0.2)) {
            rightPanelExpandeds[viewMode] = open
        }
        PanelPrefs.saveExpanded(side: .right, mode: viewMode, value: open)
    }

    /// Connect to the gRPC daemon. Flow:
    ///   1. Probe localhost:50051. If something's there, reuse it.
    ///   2. Otherwise spawn our own daemon via `ServerLauncher`.
    ///   3. Once connected, ask the daemon what catalog it has open.
    ///   4. If none, auto-open the most-recent if it still exists, else
    ///      prompt the user with the OpenCatalog sheet.
    private func setupConnection() async {
        connectionState = .connecting
        let defaultPort = 50051
        let launcher = ServerLauncher.shared

        // Step 1: probe the default port.
        let reachable = launcher.isReachable(host: "127.0.0.1", port: defaultPort)
        let port: Int
        if reachable {
            port = defaultPort
        } else {
            // Step 2: spawn our own daemon.
            guard let listening = await launcher.launch(preferredPort: defaultPort, dbPath: nil) else {
                connectionError = "Couldn't start the ReelVault backend. Set REELVAULT_CORE_BIN or build core with `cargo build`."
                connectionState = .failed
                return
            }
            port = listening.port
        }

        let connected = await VideoRepository.shared.connect(host: "127.0.0.1", port: port)
        guard connected else {
            connectionError = "Connected to port \(port) but the daemon didn't respond"
            connectionState = .failed
            return
        }
        connectionState = .connected

        // Step 3: ask the daemon what catalog is mounted.
        let existing = await VideoRepository.shared.getCurrentCatalog()
        if existing.isOpen {
            currentCatalog = existing
            recents.touch(existing.path)
            loadAfterCatalogOpened()
        } else {
            // Step 4: nothing open. Auto-resume the most recent if it still
            // exists, otherwise prompt the user.
            if let head = recents.list().first(where: { FileManager.default.fileExists(atPath: $0) }) {
                await openCatalog(path: head)
            } else {
                openCatalogIsStartup = true
                showOpenCatalogSheet = true
            }
        }
    }

    /// Ask the daemon to switch to a new catalog and refresh the UI.
    private func openCatalog(path: String) async {
        guard let info = await VideoRepository.shared.openCatalog(path: path), info.isOpen else {
            connectionError = "Could not open catalog at \(path)"
            // Re-open the sheet so the user can pick again.
            openCatalogIsStartup = false
            showOpenCatalogSheet = true
            return
        }
        currentCatalog = info
        recents.touch(info.path)
        loadAfterCatalogOpened()
    }

    /// Close the daemon's current catalog and prompt for another.
    private func closeCurrentCatalog() async {
        gridViewModel.stopCatalogEventStream()
        _ = await VideoRepository.shared.closeCatalog()
        currentCatalog = .closed
        gridViewModel.clearState()
        openCatalogIsStartup = false
        showOpenCatalogSheet = true
    }

    /// Load library data after a successful catalog open.
    private func loadAfterCatalogOpened() {
        gridViewModel.loadVideos()
        gridViewModel.loadLibraryLocations()
        gridViewModel.loadTags()
        gridViewModel.loadCollections()
        gridViewModel.refreshMetadataFacets()
        // Per-catalog grid layout — the four top-of-card stat slots.
        gridViewModel.loadGridSettings()
        // Pre-load both location-related data sources so the global-map
        // and location-picker sheets can open with the camera framed on
        // real data, not the (25, 0) global fallback.
        gridViewModel.loadVideoLocations()
        gridViewModel.loadNamedLocations()
        // Open the long-lived CatalogEvents subscription so the grid
        // refreshes when the server's file-watcher picks up new footage.
        // Cheap if live updates are disabled — the server just sends a
        // single WATCHER_DISABLED greeting and idles the stream.
        gridViewModel.startCatalogEventStream()
    }
}

struct ConnectingView: View {
    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
                .controlSize(.large)
            Text("Connecting to ReelVault…")
                .font(.body)
                .foregroundColor(.secondary)
            Text("Reaching out to the backend on localhost:50051")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.windowBackgroundColor))
    }
}

/// Snapshot of modifier keys held at the moment of the most recent mouse-down.
/// SwiftUI's `.onTapGesture` only fires on mouse-UP, by which time the user may
/// have already released the modifier. We capture down-time state here and the
/// tap handler reads it.
enum ModifierSnapshot {
    nonisolated(unsafe) static var lastMouseDownModifiers: NSEvent.ModifierFlags = []
    /// Number of rapid clicks in the most-recent mouse-down burst (1 for single-click, 2 for double-click, etc.).
    /// Captured in the mouse-down monitor so the tap handler can distinguish single vs double without relying on
    /// SwiftUI's `.onTapGesture(count: 2)`, which introduces a recognition delay that blocks child Button actions.
    nonisolated(unsafe) static var lastMouseDownClickCount: Int = 1
}

/// Installs an app-level NSEvent monitor that captures Tab and Cmd+G regardless
/// of which SwiftUI view holds focus, plus a mouse-down monitor that snapshots
/// modifier keys for the click handler.
struct GlobalKeyboardShortcuts: ViewModifier {
    let onTab: () -> Void
    let onGroupSelected: () -> Void
    let onSelectAll: () -> Void
    let onDeselectAll: () -> Void
    /// Plain 'g' — switch to grid view mode.
    let onSetGridMode: () -> Void
    /// Plain 'l' — switch to list view mode.
    let onSetListMode: () -> Void
    /// Plain 'd' — switch to detail (loupe) view mode.
    let onSetDetailMode: () -> Void
    /// Plain 'm' — switch to the map view mode.
    let onSetMapMode: () -> Void
    /// Plain 'i' — cycle the info overlay through none → camera → file → none.
    let onCycleInfoOverlay: () -> Void
    /// Plain 'f' — toggle full-screen video playback (detail mode only).
    let onToggleFullScreen: () -> Void
    /// Space bar — toggle inline playback (grid) or play/pause (detail).
    let onSpaceBar: () -> Void
    /// Digits 0..5 — set a star rating on every selected video. 0 clears.
    let onSetRating: (Int) -> Void
    /// Digits 6..9 — apply a colour label (red / yellow / green / blue) to
    /// every selected video. The receiver translates the digit into the
    /// colour name; purple has no shortcut by design (right-click only).
    let onSetColorLabel: (String) -> Void
    /// Arrow keys — move the grid / list selection in the given direction.
    /// Arrow keys — move the selection; the Bool is true when Shift is held
    /// (extend a range from the anchor).
    let onArrow: (MoveDirection, Bool) -> Void

    @State private var keyMonitor: Any?
    @State private var mouseMonitor: Any?

    func body(content: Content) -> some View {
        content
            .onAppear {
                installMonitorIfNeeded()
            }
            .onDisappear {
                if let m = keyMonitor {
                    NSEvent.removeMonitor(m)
                    keyMonitor = nil
                }
                if let m = mouseMonitor {
                    NSEvent.removeMonitor(m)
                    mouseMonitor = nil
                }
            }
    }

    private func installMonitorIfNeeded() {
        // Mouse-down monitor: capture modifier state at click time so the
        // tap handler can read it from `ModifierSnapshot`.
        if mouseMonitor == nil {
            mouseMonitor = NSEvent.addLocalMonitorForEvents(
                matching: [.leftMouseDown, .rightMouseDown]
            ) { event in
                ModifierSnapshot.lastMouseDownModifiers = event.modifierFlags
                ModifierSnapshot.lastMouseDownClickCount = event.clickCount
                return event  // never consume; SwiftUI still needs the event
            }
        }

        guard keyMonitor == nil else { return }
        // keyCodes: Tab = 48, G = 5, A = 0, D = 2, Escape = 53
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            let mods = event.modifierFlags.intersection(.deviceIndependentFlagsMask)

            // Escape: if a text field is editing, blur it. This is intentionally
            // handled BEFORE the isEditingTextField guard so the user can escape
            // out of the search field to free up Tab for panel toggling.
            if event.keyCode == 53 && mods.isEmpty {
                if isEditingTextField() {
                    NSApp.keyWindow?.makeFirstResponder(nil)
                    return nil  // consume — we already handled it
                }
                return event  // not editing → let escape propagate (closes sheets etc.)
            }

            // For all other shortcuts, don't steal keys while the user is typing —
            // we'd block normal text input otherwise.
            if isEditingTextField() {
                return event
            }

            // Tab (no modifiers) → toggle panels
            if event.keyCode == 48 && mods.isEmpty {
                onTab()
                return nil  // consume
            }

            // Cmd+G → group selected
            if event.keyCode == 5 && mods == .command {
                onGroupSelected()
                return nil
            }

            // Cmd+A → select all currently-visible videos. The
            // `isEditingTextField` guard above means this only fires
            // when no text field is focused — so Cmd+A in the search
            // bar or notes field still selects the field's text as
            // users expect.
            if event.keyCode == 0 && mods == .command {
                onSelectAll()
                return nil
            }

            // Cmd+D → deselect every selected video. Same text-field
            // policy: only fires when no field is focused.
            if event.keyCode == 2 && mods == .command {
                onDeselectAll()
                return nil
            }

            // Plain single-letter shortcuts (no modifiers). The
            // isEditingTextField guard above keeps these from firing
            // while the user is typing in the search box, notes, etc.
            //   Space (keyCode 49) → toggle inline playback
            //   G (keyCode 5)  → grid mode
            //   L (keyCode 37) → list mode
            //   D (keyCode 2)  → detail mode
            //   I (keyCode 34) → cycle info overlay
            //   0..5           → Lightroom-style star rating (0 clears)
            //   6..9           → Lightroom-style colour label
            //   ` (50)         → clear colour label
            if mods.isEmpty {
                switch event.keyCode {
                case 49:
                    onSpaceBar()
                    return nil
                case 5:
                    onSetGridMode()
                    return nil
                case 37:
                    onSetListMode()
                    return nil
                case 2:
                    onSetDetailMode()
                    return nil
                case 46:
                    onSetMapMode()
                    return nil
                case 34:
                    onCycleInfoOverlay()
                    return nil
                case 3:
                    onToggleFullScreen()
                    return nil
                // Number-row digits. macOS keyCodes: 1=18, 2=19, 3=20, 4=21,
                // 5=23, 6=22, 7=26, 8=28, 9=25, 0=29. Yes, 5 and 6 are
                // out-of-order in the hardware map — that's Apple, not us.
                case 18: onSetRating(1); return nil
                case 19: onSetRating(2); return nil
                case 20: onSetRating(3); return nil
                case 21: onSetRating(4); return nil
                case 23: onSetRating(5); return nil
                case 29: onSetRating(0); return nil
                case 22: onSetColorLabel("red"); return nil
                case 26: onSetColorLabel("yellow"); return nil
                case 28: onSetColorLabel("green"); return nil
                case 25: onSetColorLabel("blue"); return nil
                // Backtick (keyCode 50, the key left of the 1 on US layouts)
                // clears the colour label. The right-click menu has a
                // universal fallback for keyboards where this key is awkward.
                case 50:
                    onSetColorLabel("")
                    return nil
                default:
                    break
                }
            }

            // Arrow keys → move the grid / list selection; with Shift, extend a
            // range from the anchor. Handled outside the `mods.isEmpty` block so
            // Shift+arrow is caught too. The isEditingTextField guard above keeps
            // these from firing while a text field is focused.
            //   left = 123, right = 124, down = 125, up = 126
            //
            // macOS reports arrow keys with the .function and .numericPad
            // modifier flags set, so mask those out before deciding "bare press
            // vs. Shift" — otherwise neither branch ever matches and the arrows
            // appear dead.
            let arrowMods = mods.subtracting([.function, .numericPad])
            if arrowMods.isEmpty || arrowMods == .shift {
                let extend = arrowMods.contains(.shift)
                switch event.keyCode {
                case 123: onArrow(.left, extend); return nil
                case 124: onArrow(.right, extend); return nil
                case 125: onArrow(.down, extend); return nil
                case 126: onArrow(.up, extend); return nil
                default: break
                }
            }

            return event
        }
    }

    private func isEditingTextField() -> Bool {
        guard let window = NSApp.keyWindow,
              let firstResponder = window.firstResponder else {
            return false
        }
        // Any text-input control will set the field editor as first responder.
        if firstResponder.isKind(of: NSTextView.self) { return true }
        if firstResponder.isKind(of: NSTextField.self) { return true }
        return false
    }
}

/// Compact row of filter dropdowns next to the search field. Each dropdown
/// hides itself if there's no data for its column. "---" at the top of any
/// dropdown clears that filter.
/// Tiny `Identifiable` wrapper so `.sheet(item:)` can drive the LocationPicker
/// off `[String]?` — SwiftUI requires a single hashable identity for the
/// sheet item, and bare arrays aren't `Identifiable`.
private struct LocationPickerTargets: Identifiable {
    let ids: [String]
    var id: String { ids.joined(separator: ",") }
}

/// Drives the map's "Name / Rename location" sheet off `.sheet(item:)`. Carries
/// the right-clicked coordinate and the named location already there (if any),
/// so the sheet pre-fills the current name and a save re-uses its row.
private struct RenameLocationTarget: Identifiable {
    let coordinate: CLLocationCoordinate2D
    let existing: NamedLocation?
    var id: String {
        existing?.id ?? "\(coordinate.latitude),\(coordinate.longitude)"
    }
}

// MARK: - Resizable side panels

/// Per-view-mode panel preferences persisted to `UserDefaults`.
///
/// Lightroom keeps the left/right panel widths and open states
/// independent for each view (Library / Develop / Print …); we apply
/// the same pattern across our three modes (Grid / List / Detail).
/// Six keys per side per mode = 12 total entries. Reads default to
/// the sensible value (`defaultWidth` / open) when no prior write
/// exists, so first launch behaves like the old hard-coded layout.
enum PanelPrefs {
    enum Side: String { case left, right }
    static let defaultWidth: CGFloat = 240
    static let minWidth: CGFloat = 180
    static let maxWidth: CGFloat = 600

    /// Clamp a candidate width into the legal range.
    static func clamp(_ value: CGFloat) -> CGFloat {
        max(minWidth, min(maxWidth, value))
    }

    private static func widthKey(_ side: Side, _ mode: ContentView.ViewMode) -> String {
        "reelvault.panel.\(side.rawValue).width.\(String(describing: mode))"
    }
    private static func openKey(_ side: Side, _ mode: ContentView.ViewMode) -> String {
        "reelvault.panel.\(side.rawValue).open.\(String(describing: mode))"
    }

    static func loadWidths(side: Side) -> [ContentView.ViewMode: CGFloat] {
        var out: [ContentView.ViewMode: CGFloat] = [:]
        for mode in [ContentView.ViewMode.grid, .list, .detail] {
            let raw = UserDefaults.standard.double(forKey: widthKey(side, mode))
            // UserDefaults returns 0 when no entry exists — treat that
            // as "use the default" rather than as a literal zero width.
            out[mode] = raw > 0 ? clamp(CGFloat(raw)) : defaultWidth
        }
        return out
    }

    static func loadExpandeds(side: Side) -> [ContentView.ViewMode: Bool] {
        var out: [ContentView.ViewMode: Bool] = [:]
        for mode in [ContentView.ViewMode.grid, .list, .detail] {
            let k = openKey(side, mode)
            // `object(forKey:)` distinguishes "no value set" (nil) from
            // a stored `false`, so panels open by default on first
            // launch and respect the user's last close on subsequent
            // launches.
            if let b = UserDefaults.standard.object(forKey: k) as? Bool {
                out[mode] = b
            } else {
                out[mode] = true
            }
        }
        return out
    }

    static func saveWidth(side: Side, mode: ContentView.ViewMode, value: CGFloat) {
        UserDefaults.standard.set(Double(value), forKey: widthKey(side, mode))
    }
    static func saveExpanded(side: Side, mode: ContentView.ViewMode, value: Bool) {
        UserDefaults.standard.set(value, forKey: openKey(side, mode))
    }
}

/// Slim vertical drag handle that resizes its owning panel.
///
/// Behaviour:
/// - Hover: the cursor switches to the macOS horizontal-resize affordance
///   so the handle is discoverable without a visible chrome.
/// - Drag: each `DragGesture` event reports a cumulative translation
///   from the gesture's start. We snapshot the panel's width on the
///   first `onChanged` and add the (possibly negated) translation to
///   it; the parent clamps the result into the legal `min…max` range.
/// - `isLeftPanel`: drives the sign of the delta. For the left panel
///   a rightward drag widens (positive). For the right panel a
///   rightward drag *narrows*, so we negate.
struct PanelResizeHandle: View {
    let isLeftPanel: Bool
    let currentWidth: CGFloat
    let onWidthChange: (CGFloat) -> Void

    /// Width recorded when the current drag began. `nil` between gestures.
    @State private var startWidth: CGFloat? = nil

    var body: some View {
        Rectangle()
            .fill(Color.clear)
            .frame(width: 6)
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            // Don't capture the cursor permanently — the resize cursor
            // should be active only while the pointer is over the
            // handle, restored as soon as the user moves away.
            .onHover { hovered in
                if hovered {
                    NSCursor.resizeLeftRight.push()
                } else {
                    NSCursor.pop()
                }
            }
            // `coordinateSpace: .global` is the fix for the "panel
            // jumps back and forth while dragging" bug. With the
            // default `.local` space, SwiftUI reports `translation`
            // relative to the handle's own coordinate system — and
            // because the handle moves with the panel's edge as the
            // panel resizes, the local origin keeps shifting,
            // turning a smooth drag into a jittery feedback loop
            // where each event applies an inconsistent delta. Global
            // coordinates stay fixed to the window, so the
            // translation accumulates cleanly from the gesture's
            // start position.
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .global)
                    .onChanged { value in
                        if startWidth == nil { startWidth = currentWidth }
                        guard let start = startWidth else { return }
                        let signed = isLeftPanel
                            ? value.translation.width
                            : -value.translation.width
                        onWidthChange(start + signed)
                    }
                    .onEnded { _ in startWidth = nil }
            )
    }
}

#Preview {
    ContentView()
}

private struct SmartCollectionNameSheet: View {
    @Binding var name: String
    let onSave: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Save as Smart Collection")
                .font(.headline)
            Text("Captures the current filter settings (camera, codec, rating, etc.) as a smart collection that updates automatically.")
                .font(.caption)
                .foregroundColor(.secondary)
            TextField("Collection name", text: $name)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Cancel", action: onCancel)
                    .keyboardShortcut(.cancelAction)
                Button("Save") {
                    let trimmed = name.trimmingCharacters(in: .whitespaces)
                    if !trimmed.isEmpty { onSave(trimmed) }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(24)
        .frame(minWidth: 360)
    }
}
