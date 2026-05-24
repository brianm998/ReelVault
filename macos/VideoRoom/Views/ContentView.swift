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
    @State private var thumbnailWidth: CGFloat = 220
    @State private var showAddLibrarySheet = false
    @State private var showHelpSheet = false
    @State private var showEditorsSheet = false
    @State private var showWatchSettingsSheet = false
    @State private var showPlaybackSettingsSheet = false
    @State private var showCameraNamesSheet = false
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
        // VideoRoom is dark-mode only — light mode is intentionally not offered.
        .preferredColorScheme(.dark)
        .task { await setupConnection() }
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
            },
            onSetRating: { rating in
                gridViewModel.setRatingOnSelection(rating)
            },
            onSetColorLabel: { label in
                gridViewModel.setColorLabelOnSelection(label)
            }
        ))
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
        .sheet(isPresented: $showCameraNamesSheet) {
            CameraNamesView()
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
        .onChange(of: appState.showHelpRequestToken) { _, _ in
            showHelpSheet = true
        }
        .sheet(isPresented: $showHelpSheet) {
            HelpView()
        }
    }

    private var mainUI: some View {
        VStack(spacing: 0) {
            topBar
            scanBanner
            scanResultBanner
            locationFilterBanner
            mainContent
            bottomBar
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

            TextField("Search videos…", text: $gridViewModel.searchQuery)
                .textFieldStyle(.roundedBorder)
                .frame(width: 240)
                .help("Search videos by filename, notes, or tag. Matches as you type. Press Escape to clear focus.")

            FilterDropdowns(vm: gridViewModel)

            Spacer()

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

            // Camera Names editor — opens the table of internal →
            // marketing name mappings (built-in + user overrides).
            Button {
                showCameraNamesSheet = true
            } label: {
                Image(systemName: "camera.metering.matrix")
            }
            .buttonStyle(.borderless)
            .help("Manage the table that maps internal camera model codes (e.g. \"SONY ILCE-7RM3A\") to marketing-friendly names (e.g. \"Sony a7R IIIA\"). Add custom rows for cameras not in the built-in list, or override built-in entries you'd prefer named differently.")

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
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color(.windowBackgroundColor))
        .overlay(Rectangle().frame(height: 1).foregroundColor(Color(.separatorColor)), alignment: .bottom)
    }

    /// Full-width bottom bar: view-mode toggle (left), sort controls (centre),
    /// thumbnail-size slider (right). Height matches a standard macOS toolbar row.
    private var bottomBar: some View {
        VStack(spacing: 0) {
            Divider()
            HStack(spacing: 0) {
                // Left cluster — view-mode toggle (Catalog/Detail, Grid, List)
                Picker("", selection: $viewMode) {
                    Image(systemName: "play.rectangle").tag(ViewMode.detail)
                    Image(systemName: "square.grid.2x2").tag(ViewMode.grid)
                    Image(systemName: "list.bullet").tag(ViewMode.list)
                }
                .pickerStyle(.segmented)
                .frame(width: 135)
                .labelsHidden()
                .help("Switch between Catalog/Detail (D), Grid (G), and List (L) views.")

                Spacer()

                // Centre — sort controls
                HStack(spacing: 6) {
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
                        HStack(spacing: 4) {
                            Text(sortFieldLabel(gridViewModel.sortBy))
                                .font(.system(size: 11))
                            Image(systemName: "chevron.up.chevron.down")
                                .font(.system(size: 9))
                        }
                    }
                    .menuStyle(.borderlessButton)
                    .help("Sort the video grid. Pick a field; choose the same field again to reverse direction.")

                    // Ascending / descending toggle button
                    Button {
                        gridViewModel.setSort(gridViewModel.sortBy, ascending: !gridViewModel.sortAscending)
                    } label: {
                        Image(systemName: gridViewModel.sortAscending ? "arrow.up" : "arrow.down")
                    }
                    .buttonStyle(.borderless)
                    .help(gridViewModel.sortAscending ? "Sorted ascending — click to reverse" : "Sorted descending — click to reverse")
                }

                Spacer()

                // Right cluster — thumbnail-size slider (disabled in Catalog/Detail mode)
                let sliderEnabled = viewMode != .detail
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

    /// Human-readable label for a sort field key.
    private func sortFieldLabel(_ key: String) -> String {
        switch key {
        case "filename":      return "Filename"
        case "indexed_at":    return "Date Added"
        case "creation_date": return "Date Captured"
        case "duration":      return "Duration"
        case "size":          return "File Size"
        case "resolution":    return "Resolution"
        case "fps":           return "Frame Rate"
        case "codec":         return "Codec"
        case "bitrate":       return "Bitrate"
        case "camera":        return "Camera"
        case "lens":          return "Lens"
        case "keyword":       return "Keyword"
        default:              return key
        }
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
                    onRescan: { loc in gridViewModel.rescanLibrary(path: loc.path) },
                    rescanningPaths: Set(gridViewModel.rescanningPaths),
                    onCollapse: { setLeftPanelExpanded(false) }
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
                .frame(width: leftPanelWidth)
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

            // Right detail panel — its drag handle sits on the LEFT
            // edge (between the divider and the panel body) so drags
            // toward the right narrow the panel, drags toward the
            // left widen it.
            if rightPanelExpanded {
                PanelResizeHandle(isLeftPanel: false,
                                  currentWidth: rightPanelWidth) { newWidth in
                    setRightPanelWidth(newWidth)
                }
                DetailView(
                    viewModel: detailViewModel,
                    gridViewModel: gridViewModel,
                    onCollapse: { setRightPanelExpanded(false) },
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
                .frame(width: rightPanelWidth)
            } else {
                CollapsedPanelStrip(
                    expandIconLeft: true,
                    tooltip: "Show details panel (Tab)",
                    onClick: { setRightPanelExpanded(true) }
                )
            }
        }
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
        leftPanelExpandeds[viewMode] = open
        PanelPrefs.saveExpanded(side: .left, mode: viewMode, value: open)
    }
    private func setRightPanelExpanded(_ open: Bool) {
        rightPanelExpandeds[viewMode] = open
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
    /// Digits 0..5 — set a star rating on every selected video. 0 clears.
    let onSetRating: (Int) -> Void
    /// Digits 6..9 — apply a colour label (red / yellow / green / blue) to
    /// every selected video. The receiver translates the digit into the
    /// colour name; purple has no shortcut by design (right-click only).
    let onSetColorLabel: (String) -> Void

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
                case 34:
                    onCycleInfoOverlay()
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
                    displayLabels: vm.filterOptions.cameraDisplayNames,
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
            // Lightroom-style rating filter: ≥ N stars.
            FilterMenu(
                label: "Rating",
                values: ["≥1", "≥2", "≥3", "≥4", "5"],
                selected: vm.filterMinRating == 0 ? "" : (vm.filterMinRating == 5 ? "5" : "≥\(vm.filterMinRating)"),
                onSelect: { raw in
                    let n: Int32
                    if raw.isEmpty { n = 0 }
                    else if raw == "5" { n = 5 }
                    else { n = Int32(raw.dropFirst()) ?? 0 }
                    vm.setMinRatingFilter(n)
                }
            )
            .help("Show only videos at or above this star rating. Pick \"---\" to clear.")

            // Lightroom-style colour-label filter.
            FilterMenu(
                label: "Color",
                values: ColorLabel.allCases.filter { $0 != .none }.map { $0.displayName },
                selected: ColorLabel(vm.filterColorLabel).displayName == "None"
                    ? "" : ColorLabel(vm.filterColorLabel).displayName,
                onSelect: { displayName in
                    let label = ColorLabel.allCases.first { $0.displayName == displayName } ?? .none
                    vm.setColorLabelFilter(label.rawValue)
                }
            )
            .help("Show only videos with this colour label. Pick \"---\" to clear.")

            if anyActive {
                Button("Clear") { vm.clearAllDropdownFilters() }
                    .buttonStyle(.borderless)
                    .controlSize(.small)
                    .help("Clear all active filters (camera, lens, keyword, codec, year, rating, colour).")
            }
        }
    }

    private var anyActive: Bool {
        !vm.filterCamera.isEmpty || !vm.filterLens.isEmpty || !vm.filterCodec.isEmpty
            || vm.filterCaptureYear != 0 || !vm.filterTagId.isEmpty
            || vm.filterMinRating != 0 || !vm.filterColorLabel.isEmpty
    }
}

/// One compact dropdown menu showing label + current selection (or "---").
struct FilterMenu: View {
    let label: String
    let values: [String]
    /// Optional parallel list of user-facing labels — same length and
    /// order as `values`. When provided, dropdown items + the selected
    /// chip render the label, but the internal `value` is still what
    /// gets passed to `onSelect`. Used by the Camera filter to show
    /// marketing names (e.g. "Sony a7R III") while filtering on the
    /// internal model code (e.g. "SONY ILCE-7RM3"). Pass an empty array
    /// (the default) to keep the legacy "label == value" behaviour.
    var displayLabels: [String] = []
    let selected: String
    let onSelect: (String) -> Void

    /// User-facing string shown in the chip for the currently-selected
    /// value. Resolves through `displayLabels` when one is configured.
    private var display: String {
        if selected.isEmpty { return "---" }
        if !displayLabels.isEmpty,
           let idx = values.firstIndex(of: selected),
           idx < displayLabels.count {
            return displayLabels[idx]
        }
        return selected
    }

    private func displayLabel(for index: Int, value: String) -> String {
        if !displayLabels.isEmpty && index < displayLabels.count {
            return displayLabels[index]
        }
        return value
    }

    var body: some View {
        Menu {
            Button("---") { onSelect("") }
            Divider()
            ForEach(Array(values.enumerated()), id: \.element) { idx, v in
                Button(displayLabel(for: idx, value: v)) { onSelect(v) }
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
        "videoroom.panel.\(side.rawValue).width.\(String(describing: mode))"
    }
    private static func openKey(_ side: Side, _ mode: ContentView.ViewMode) -> String {
        "videoroom.panel.\(side.rawValue).open.\(String(describing: mode))"
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
            .gesture(
                DragGesture(minimumDistance: 0)
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
