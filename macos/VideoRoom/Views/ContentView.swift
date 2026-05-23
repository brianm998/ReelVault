// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI
import AppKit
import CoreLocation

struct ContentView: View {
    @StateObject private var gridViewModel = GridViewModel()
    @StateObject private var detailViewModel = DetailViewModel()
    @State private var connectionState: ConnectionState = .connecting
    @State private var connectionError: String = ""
    @State private var isDarkMode = true
    @State private var leftPanelExpanded = true
    @State private var rightPanelExpanded = true
    @State private var thumbnailWidth: CGFloat = 220
    @State private var showAddLibrarySheet = false
    @State private var showEditorsSheet = false
    @State private var showWatchSettingsSheet = false
    @State private var showPlaybackSettingsSheet = false
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
    @State private var showGlobalMapSheet = false
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
    /// Non-nil while the "Remove library location?" confirmation alert is shown.
    @State private var locationToRemove: LibraryLocation? = nil
    /// Monotonically-incrementing token passed to DetailLoupeView. Each
    /// increment triggers a play/pause toggle inside the loupe.
    @State private var detailPlayToggle: Int = 0

    @EnvironmentObject private var appState: AppState
    @ObservedObject private var recents = RecentCatalogs.shared

    enum ConnectionState { case connecting, connected, failed }
    enum ViewMode { case grid, list, detail }

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
                        ? "Failed to connect to VideoRoom backend on localhost:50051"
                        : connectionError,
                    onRetry: { Task { await setupConnection() } }
                )
            }
        }
        .frame(minWidth: 1200, minHeight: 800)
        .preferredColorScheme(isDarkMode ? .dark : .light)
        .task { await setupConnection() }
        // Install an app-level NSEvent monitor so keyboard shortcuts work even
        // when no SwiftUI view holds explicit focus. This is more reliable than
        // `.onKeyPress` for app-wide hotkeys.
        .modifier(GlobalKeyboardShortcuts(
            onTab: {
                let anyOpen = leftPanelExpanded || rightPanelExpanded
                leftPanelExpanded = !anyOpen
                rightPanelExpanded = !anyOpen
            },
            onGroupSelected: { gridViewModel.groupSelectedVideos() },
            onSelectAll: { gridViewModel.selectAllVisible() },
            onDeselectAll: { gridViewModel.clearSelection() },
            onSetGridMode: { viewMode = .grid },
            onSetListMode: { viewMode = .list },
            onSetDetailMode: { viewMode = .detail },
            onCycleInfoOverlay: {
                infoOverlay = {
                    switch infoOverlay {
                    case .none: return .camera
                    case .camera: return .file
                    case .file: return .none
                    }
                }()
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
                }
            }
        ))
        .sheet(isPresented: $showAddLibrarySheet) {
            AddLibraryDialog(isPresented: $showAddLibrarySheet) { path, recursive, autoGroup, dateFormat, datePosition in
                gridViewModel.addLibraryAndScan(
                    path: path,
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
        .sheet(isPresented: $showEditorsSheet) {
            ExternalEditorsDialog(isPresented: $showEditorsSheet)
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
        .sheet(isPresented: $showGlobalMapSheet) {
            GlobalMapView(
                locations: gridViewModel.videoLocations,
                onDismiss: { showGlobalMapSheet = false },
                onLocationPick: { lat, lon, radius in
                    gridViewModel.setLocationFilter(latitude: lat, longitude: lon, radiusKm: radius)
                    showGlobalMapSheet = false
                }
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
        .sheet(item: Binding(
            get: { datePickerTargets.map(LocationPickerTargets.init) },
            set: { datePickerTargets = $0?.ids }
        )) { targets in
            CaptureDateView(
                targetVideoIds: targets.ids,
                initialTimestampMs: datePickerInitial,
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
    }

    private var mainUI: some View {
        VStack(spacing: 0) {
            topBar
            scanBanner
            scanResultBanner
            locationFilterBanner
            mainContent
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

    private var topBar: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 0) {
                Text("VideoRoom")
                    .font(.system(size: 18, weight: .semibold))
                if currentCatalog.isOpen {
                    Text(currentCatalog.name)
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                        .help("Open catalog: \(currentCatalog.path)")
                }
            }

            // Grid / List / Detail view-mode toggle. Mirrors the 'G', 'L', and 'D'
            // keyboard shortcuts.
            Picker("", selection: $viewMode) {
                Image(systemName: "square.grid.2x2").tag(ViewMode.grid)
                Image(systemName: "list.bullet").tag(ViewMode.list)
                Image(systemName: "play.rectangle").tag(ViewMode.detail)
            }
            .pickerStyle(.segmented)
            .frame(width: 135)
            .labelsHidden()
            .help("Switch between Grid (G), List (L), and Detail (D) views.")

            TextField("Search videos…", text: $gridViewModel.searchQuery)
                .textFieldStyle(.roundedBorder)
                .frame(width: 240)
                .help("Search videos by filename, notes, or tag. Matches as you type. Press Escape to clear focus.")

            FilterDropdowns(vm: gridViewModel)

            Spacer()

            // Sort menu
            Menu {
                sortMenuItem(label: "Filename",       key: "filename")
                sortMenuItem(label: "Date Added",     key: "indexed_at")
                sortMenuItem(label: "Date Captured",  key: "creation_date")
                sortMenuItem(label: "Duration",       key: "duration")
                sortMenuItem(label: "File Size",      key: "size")
                sortMenuItem(label: "Resolution",     key: "resolution")
                sortMenuItem(label: "Frame Rate",     key: "fps")
                sortMenuItem(label: "Codec",          key: "codec")
                sortMenuItem(label: "Bitrate",        key: "bitrate")
                sortMenuItem(label: "Camera",         key: "camera")
                sortMenuItem(label: "Lens",           key: "lens")
                sortMenuItem(label: "Keyword",        key: "keyword")
            } label: {
                Image(systemName: "arrow.up.arrow.down")
            }
            .menuStyle(.borderlessButton)
            .frame(width: 30)
            .help("Sort the video grid. Pick a field; choose the same field again to reverse direction.")

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

            // External Editors preferences
            Button {
                showEditorsSheet = true
            } label: {
                Image(systemName: "wrench.and.screwdriver")
            }
            .buttonStyle(.borderless)
            .help("Configure which external video editors are available in the right-click \"Open with\" menu. See free/paid status and download links for each supported editor.")

            // Playback + proxy resolution preferences. Distinct from
            // the watcher settings (separate concept) and from the
            // external-editors picker. Surfaces as a play/rectangle
            // icon so it visually reads as "playback".
            Button {
                showPlaybackSettingsSheet = true
            } label: {
                Image(systemName: "play.rectangle")
            }
            .buttonStyle(.borderless)
            .help("Set the inline-playback ceiling and the default proxy resolution. Videos taller than the ceiling get a \"Too large to play here\" marker and offer a one-click proxy.")

            // Live-updates pill — shows whether the server's file watcher
            // is active and offers a one-click entry into its settings.
            // Green dot = live; grey dot = paused. Tooltip explains both
            // states without surfacing the underlying RPC names.
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
                  ? "Live updates are on — VideoRoom is watching your libraries for new and changed files and will add them automatically. Click to adjust."
                  : "Live updates are off. Click to turn them on or adjust the watcher settings.")

            // World-map button — shows every geotagged video.
            Button {
                // Await the loads before the sheet appears so the map
                // frames the centroid of real data instead of (25, 0) in
                // the ocean. Typically instant thanks to the catalog-open
                // pre-load; the worst case (cold catalog) is a few hundred
                // ms of perceived button delay before the sheet animates in.
                Task {
                    await gridViewModel.loadVideoLocationsAsync()
                    await gridViewModel.loadNamedLocationsAsync()
                    showGlobalMapSheet = true
                }
            } label: {
                Image(systemName: "map")
            }
            .buttonStyle(.borderless)
            .help("Show every geotagged video on a world map. Click a pin to filter the grid to videos taken near that location.")

            // (The active location-filter affordance lives in
            // `locationFilterBanner` — a full-width strip above the grid —
            // rather than as a tiny chip up here, so users actually notice
            // why the grid is narrowed.)

            // Add Library
            Button {
                showAddLibrarySheet = true
            } label: {
                Image(systemName: "folder.badge.plus")
            }
            .buttonStyle(.borderless)
            .help("Add a folder to your library. VideoRoom will scan it for videos and extract their metadata in the background.")

            // Dark mode toggle
            Button {
                isDarkMode.toggle()
            } label: {
                Image(systemName: isDarkMode ? "sun.max" : "moon")
            }
            .buttonStyle(.borderless)
            .help(isDarkMode ? "Switch to light theme" : "Switch to dark theme")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color(.windowBackgroundColor))
        .overlay(Rectangle().frame(height: 1).foregroundColor(Color(.separatorColor)), alignment: .bottom)
    }

    @ViewBuilder
    private func sortMenuItem(label: String, key: String) -> some View {
        let selected = gridViewModel.sortBy == key
        Button {
            if selected {
                gridViewModel.setSort(key, ascending: !gridViewModel.sortAscending)
            } else {
                let defaultAsc = (key == "filename" || key == "camera" || key == "codec")
                gridViewModel.setSort(key, ascending: defaultAsc)
            }
        } label: {
            HStack {
                Text(label)
                if selected {
                    Image(systemName: gridViewModel.sortAscending
                        ? "arrow.up" : "arrow.down")
                }
            }
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
            // Left library panel — expanded or collapsed strip
            if leftPanelExpanded {
                LibraryPanel(
                    locations: gridViewModel.libraryLocations,
                    selectedPath: gridViewModel.selectedLocationPath,
                    totalVideos: gridViewModel.libraryLocations.reduce(0) { $0 + $1.videoCount },
                    onSelect: { gridViewModel.setLocationFilter($0) },
                    onAddLibrary: { showAddLibrarySheet = true },
                    onRemoveLocation: { locationToRemove = $0 },
                    onCollapse: { leftPanelExpanded = false }
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
                .frame(width: 240)
            } else {
                CollapsedPanelStrip(
                    expandIconLeft: false,
                    tooltip: "Show library panel (Tab)",
                    onClick: { leftPanelExpanded = true }
                )
            }

            Divider()

            // Middle area — grid (browse), list, or detail (single-video loupe).
            switch viewMode {
            case .grid:
                GridView(
                    viewModel: gridViewModel,
                    detailViewModel: detailViewModel,
                    thumbnailMinWidth: thumbnailWidth,
                    onConfigureEditors: { showEditorsSheet = true }
                )
                .frame(maxWidth: .infinity)
            case .list:
                ListView(
                    viewModel: gridViewModel,
                    detailViewModel: detailViewModel,
                    thumbnailHeight: thumbnailWidth / 2,
                    onConfigureEditors: { showEditorsSheet = true }
                )
                .frame(maxWidth: .infinity)
            case .detail:
                DetailLoupeView(
                    gridViewModel: gridViewModel,
                    detailViewModel: detailViewModel,
                    infoOverlay: infoOverlay,
                    playToggle: detailPlayToggle
                )
                .frame(maxWidth: .infinity)
            }

            Divider()

            // Right detail panel
            if rightPanelExpanded {
                DetailView(
                    viewModel: detailViewModel,
                    gridViewModel: gridViewModel,
                    thumbnailWidth: $thumbnailWidth,
                    onCollapse: { rightPanelExpanded = false },
                    isListMode: viewMode == .list,
                    isLoupeMode: viewMode == .detail,
                    onEditLocation: { videoIds, initial in
                        // Await before showing the sheet so the picker's
                        // init captures populated arrays and frames the
                        // bbox correctly. Without this, the sheet opens
                        // before `videoLocations` arrives over gRPC and
                        // the map centers on (25, 0) in the Atlantic.
                        Task {
                            await gridViewModel.loadVideoLocationsAsync()
                            await gridViewModel.loadNamedLocationsAsync()
                            locationPickerTargets = videoIds
                            locationPickerInitial = initial.map {
                                CLLocationCoordinate2D(latitude: $0.0, longitude: $0.1)
                            }
                        }
                    },
                    onEditCaptureDate: { videoIds, initialTs in
                        datePickerTargets = videoIds
                        datePickerInitial = initialTs
                    }
                )
                .frame(width: 240)
            } else {
                CollapsedPanelStrip(
                    expandIconLeft: true,
                    tooltip: "Show details panel (Tab)",
                    onClick: { rightPanelExpanded = true }
                )
            }
        }
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
                connectionError = "Couldn't start the VideoRoom backend. Set VIDEOROOM_CORE_BIN or build core with `cargo build`."
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
        gridViewModel.loadFilterOptions()
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
            Text("Connecting to VideoRoom…")
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
    /// Plain 'i' — cycle the info overlay through none → camera → file → none.
    let onCycleInfoOverlay: () -> Void
    /// Space bar — toggle inline playback (grid) or play/pause (detail).
    let onSpaceBar: () -> Void

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
                case 34:
                    onCycleInfoOverlay()
                    return nil
                default:
                    break
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
struct FilterDropdowns: View {
    @ObservedObject var vm: GridViewModel

    var body: some View {
        HStack(spacing: 6) {
            if !vm.filterOptions.cameras.isEmpty {
                FilterMenu(
                    label: "Camera",
                    values: vm.filterOptions.cameras,
                    selected: vm.filterCamera,
                    onSelect: { vm.setCameraFilter($0) }
                )
                .help("Show only videos captured with this camera model. Pick \"---\" to clear.")
            }
            if !vm.filterOptions.lenses.isEmpty {
                FilterMenu(
                    label: "Lens",
                    values: vm.filterOptions.lenses,
                    selected: vm.filterLens,
                    onSelect: { vm.setLensFilter($0) }
                )
                .help("Show only videos shot with this lens model. Pick \"---\" to clear.")
            }
            if !vm.tags.isEmpty {
                FilterMenu(
                    label: "Keyword",
                    values: vm.tags.map { $0.name },
                    selected: vm.tags.first(where: { $0.id == vm.filterTagId })?.name ?? "",
                    onSelect: { name in
                        let id = vm.tags.first(where: { $0.name == name })?.id ?? ""
                        vm.setTagFilter(id)
                    }
                )
                .help("Show only videos tagged with this keyword. Pick \"---\" to clear.")
            }
            if !vm.filterOptions.codecs.isEmpty {
                FilterMenu(
                    label: "Codec",
                    values: vm.filterOptions.codecs,
                    selected: vm.filterCodec,
                    onSelect: { vm.setCodecFilter($0) }
                )
                .help("Show only videos using this video codec (e.g. h264, hevc, prores). Pick \"---\" to clear.")
            }
            if !vm.filterOptions.captureYears.isEmpty {
                FilterMenu(
                    label: "Year",
                    values: vm.filterOptions.captureYears.map { String($0) },
                    selected: vm.filterCaptureYear == 0 ? "" : String(vm.filterCaptureYear),
                    onSelect: { vm.setCaptureYearFilter(Int32($0) ?? 0) }
                )
                .help("Show only videos whose capture date falls in this year. Pick \"---\" to clear.")
            }
            if anyActive {
                Button("Clear") { vm.clearAllDropdownFilters() }
                    .buttonStyle(.borderless)
                    .controlSize(.small)
                    .help("Clear all active filters (camera, lens, keyword, codec, year).")
            }
        }
    }

    private var anyActive: Bool {
        !vm.filterCamera.isEmpty || !vm.filterLens.isEmpty || !vm.filterCodec.isEmpty
            || vm.filterCaptureYear != 0 || !vm.filterTagId.isEmpty
    }
}

/// One compact dropdown menu showing label + current selection (or "---").
struct FilterMenu: View {
    let label: String
    let values: [String]
    let selected: String
    let onSelect: (String) -> Void

    private var display: String { selected.isEmpty ? "---" : selected }

    var body: some View {
        Menu {
            Button("---") { onSelect("") }
            Divider()
            ForEach(values, id: \.self) { v in
                Button(v) { onSelect(v) }
            }
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                Text(label)
                    .font(.system(size: 9))
                    .foregroundColor(.secondary)
                Text(display)
                    .font(.system(size: 11))
                    .foregroundColor(selected.isEmpty ? .primary : .accentColor)
                    .lineLimit(1)
            }
            .frame(minWidth: 70, alignment: .leading)
        }
        .menuStyle(.borderlessButton)
        .controlSize(.small)
    }
}

/// Tiny `Identifiable` wrapper so `.sheet(item:)` can drive the LocationPicker
/// off `[String]?` — SwiftUI requires a single hashable identity for the
/// sheet item, and bare arrays aren't `Identifiable`.
private struct LocationPickerTargets: Identifiable {
    let ids: [String]
    var id: String { ids.joined(separator: ",") }
}

#Preview {
    ContentView()
}
