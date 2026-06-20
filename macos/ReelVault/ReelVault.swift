// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit
import AppKit

@main
struct ReelVaultApp: App {
    /// AppDelegate is used to register the process as a regular GUI app and
    /// activate it on launch. Without this, when run via `swift run` (which
    /// launches an unbundled binary), macOS treats the process as a
    /// background CLI tool — the window opens but never receives focus, so
    /// keystrokes go to whichever app is actually frontmost (usually the
    /// terminal that launched us).
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    /// Shared state between the menu-bar commands (defined at App scope) and
    /// the ContentView (which actually owns the connection + catalog state).
    /// ContentView assigns the action closures during .onAppear so File menu
    /// items can drive it.
    @StateObject private var appState = AppState()

    /// Persisted accent scheme. "blue" matches the macOS system default;
    /// "purple" is the classic ReelVault palette. Changing this rewires
    /// the `.accentColor()` on the root view so every `.accentColor`
    /// reference across the whole app picks up the new tint immediately.
    @AppStorage("accentScheme") private var accentScheme: String = "blue"

    private var resolvedAccentColor: Color {
        accentScheme == "purple" ? .purple : .blue
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appState)
                .accentColor(resolvedAccentColor)
                // Title shows the current catalog name. SwiftUI's
                // `navigationTitle` propagates up to the window chrome.
                .navigationTitle(appState.currentCatalogName.isEmpty
                                 ? "ReelVault"
                                 : "ReelVault — \(appState.currentCatalogName)")
        }
        // We DO want the title bar visible now so the user can see which
        // catalog they're in — ReelVault is intentionally a multi-catalog app
        // and the title is the natural place to surface that.
        .commands {
            // Replace the default "New" command-group with ReelVault's File menu.
            CommandGroup(replacing: .newItem) {
                Button("Open Catalog…") {
                    appState.requestOpenCatalog()
                }
                .keyboardShortcut("o", modifiers: [.command])

                Button("Close Catalog") {
                    appState.requestCloseCatalog()
                }
                .keyboardShortcut("w", modifiers: [.command, .shift])
                .disabled(appState.currentCatalogName.isEmpty)

                Divider()

                Menu("Open Recent") {
                    if appState.recents.isEmpty {
                        Text("No recent catalogs").disabled(true) as? Text
                    }
                    ForEach(appState.recents.prefix(10), id: \.self) { path in
                        let display = URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent
                        Button(display.isEmpty ? path : display) {
                            appState.requestOpenRecent(path)
                        }
                    }
                    if !appState.recents.isEmpty {
                        Divider()
                        Button("Clear Recent") {
                            appState.requestClearRecents()
                        }
                    }
                }

                Divider()

                // Mint a one-time code so a phone/tablet can pair with this
                // server. The code is shown in a sheet (see ContentView).
                Button("Pair a New Device…") {
                    appState.requestPairDevice()
                }

                Divider()

                // Catalog sync: disabled on desktop — requires an embedded local
                // core, which the desktop client does not yet have (docs/CATALOG_SYNC.md).
                Button("Sync to Remote…") {}
                    .disabled(true)
                    .help("Desktop sync requires a local catalog (coming soon)")
                Button("Sync from Remote…") {}
                    .disabled(true)
                    .help("Desktop sync requires a local catalog (coming soon)")
            }

            // Replace the default Help menu so we can launch our own panel.
            CommandGroup(replacing: .help) {
                Button("ReelVault Help") {
                    appState.requestShowHelp()
                }
                .keyboardShortcut("?", modifiers: [.command])
            }
        }
    }
}

/// Shared state plumbed between the menu-bar commands (App scope) and
/// `ContentView` (which owns the connection + repository). The menu items
/// call into `request*` to fire an event token; ContentView watches those
/// tokens via `onChange` and acts on them.
@MainActor
final class AppState: ObservableObject {
    /// Display name of the currently-open catalog. Empty when nothing is open.
    @Published var currentCatalogName: String = ""
    /// Recent catalog paths newest-first. Mirrored from `RecentCatalogs`
    /// because SwiftUI menu items need a plain published array to bind to.
    @Published var recents: [String] = []

    // Event tokens — incrementing forces an `onChange` to fire even when
    // the underlying value (a path string) is the same as last time.
    @Published private(set) var openCatalogRequestToken: Int = 0
    @Published private(set) var closeCatalogRequestToken: Int = 0
    @Published private(set) var openRecentRequest: (token: Int, path: String) = (0, "")
    @Published private(set) var clearRecentsRequestToken: Int = 0
    @Published private(set) var showHelpRequestToken: Int = 0
    @Published private(set) var pairDeviceRequestToken: Int = 0

    func requestOpenCatalog() { openCatalogRequestToken += 1 }
    func requestCloseCatalog() { closeCatalogRequestToken += 1 }
    func requestOpenRecent(_ path: String) {
        openRecentRequest = (openRecentRequest.token + 1, path)
    }
    func requestClearRecents() { clearRecentsRequestToken += 1 }
    func requestShowHelp() { showHelpRequestToken += 1 }
    func requestPairDevice() { pairDeviceRequestToken += 1 }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Promote this process to a regular foreground app (shows in Dock,
        // can receive focus, gets keyboard events).
        NSApp.setActivationPolicy(.regular)
        // Bring the window to the front and steal focus from the terminal.
        NSApp.activate(ignoringOtherApps: true)

        // Stamp the Dock tile with our icon. For a packaged .app this is
        // redundant — macOS reads CFBundleIconFile from Info.plist before
        // the process even starts — but `swift run` and a raw .build
        // binary have no bundle metadata, so the Dock shows the generic
        // green "exec" tile until we override it here.
        if let url = Bundle.module.url(forResource: "AppIcon", withExtension: "icns"),
           let icon = NSImage(contentsOf: url) {
            NSApp.applicationIconImage = icon
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    /// Kill the core daemon we spawned (if any) when the app quits, so it
    /// doesn't linger as an orphan. `shutdown()` is a no-op when we connected
    /// to an externally-running daemon (we only track a daemon we started).
    func applicationWillTerminate(_ notification: Notification) {
        ServerLauncher.shared.shutdown()
    }
}
