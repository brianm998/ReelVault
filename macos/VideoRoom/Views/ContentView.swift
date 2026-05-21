import SwiftUI
import AppKit

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
    @State private var showOpenCatalogSheet = false
    @State private var openCatalogIsStartup = false
    @State private var currentCatalog: CatalogInfo = .closed

    @EnvironmentObject private var appState: AppState
    @ObservedObject private var recents = RecentCatalogs.shared

    enum ConnectionState { case connecting, connected, failed }

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
            onGroupSelected: { gridViewModel.groupSelectedVideos() }
        ))
        .sheet(isPresented: $showAddLibrarySheet) {
            AddLibraryDialog(isPresented: $showAddLibrarySheet) { path, recursive, autoGroup in
                gridViewModel.addLibraryAndScan(path: path, recursive: recursive, autoGroup: autoGroup)
            }
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
            mainContent
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
        if let status = gridViewModel.scanStatus {
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
                    onCollapse: { leftPanelExpanded = false }
                )
                .frame(width: 240)
            } else {
                CollapsedPanelStrip(
                    expandIconLeft: false,
                    tooltip: "Show library panel (Tab)",
                    onClick: { leftPanelExpanded = true }
                )
            }

            Divider()

            // Middle — grid, fills remaining space
            GridView(
                viewModel: gridViewModel,
                detailViewModel: detailViewModel,
                thumbnailMinWidth: thumbnailWidth,
                onConfigureEditors: { showEditorsSheet = true }
            )
            .frame(maxWidth: .infinity)

            Divider()

            // Right detail panel
            if rightPanelExpanded {
                DetailView(
                    viewModel: detailViewModel,
                    gridViewModel: gridViewModel,
                    thumbnailWidth: $thumbnailWidth,
                    onCollapse: { rightPanelExpanded = false }
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
}

/// Installs an app-level NSEvent monitor that captures Tab and Cmd+G regardless
/// of which SwiftUI view holds focus, plus a mouse-down monitor that snapshots
/// modifier keys for the click handler.
struct GlobalKeyboardShortcuts: ViewModifier {
    let onTab: () -> Void
    let onGroupSelected: () -> Void

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
                return event  // never consume; SwiftUI still needs the event
            }
        }

        guard keyMonitor == nil else { return }
        // keyCodes: Tab = 48, G = 5, Escape = 53
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

#Preview {
    ContentView()
}
