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
            AddLibraryDialog(isPresented: $showAddLibrarySheet) { path, autoGroup in
                gridViewModel.addLibraryAndScan(path: path, autoGroup: autoGroup)
            }
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
            Text("VideoRoom")
                .font(.system(size: 18, weight: .semibold))

            TextField("Search videos…", text: $gridViewModel.searchQuery)
                .textFieldStyle(.roundedBorder)
                .frame(width: 300)

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
            } label: {
                Image(systemName: "arrow.up.arrow.down")
            }
            .menuStyle(.borderlessButton)
            .frame(width: 30)
            .help("Sort by…")

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
            .help("Group selected videos (⌘G)")

            // Add Library
            Button {
                showAddLibrarySheet = true
            } label: {
                Image(systemName: "folder.badge.plus")
            }
            .buttonStyle(.borderless)
            .help("Add library location")

            // Dark mode toggle
            Button {
                isDarkMode.toggle()
            } label: {
                Image(systemName: isDarkMode ? "sun.max" : "moon")
            }
            .buttonStyle(.borderless)
            .help("Toggle theme")
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
                thumbnailMinWidth: thumbnailWidth
            )
            .frame(maxWidth: .infinity)

            Divider()

            // Right detail panel
            if rightPanelExpanded {
                DetailView(
                    viewModel: detailViewModel,
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

    private func setupConnection() async {
        connectionState = .connecting
        let connected = await VideoRepository.shared.connect()
        if connected {
            connectionState = .connected
            gridViewModel.loadVideos()
            gridViewModel.loadLibraryLocations()
        } else {
            connectionError = "Failed to connect to VideoRoom backend on localhost:50051"
            connectionState = .failed
        }
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

#Preview {
    ContentView()
}
