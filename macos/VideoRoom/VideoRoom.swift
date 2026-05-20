import SwiftUI
import AppKit

@main
struct VideoRoomApp: App {
    /// AppDelegate is used to register the process as a regular GUI app and
    /// activate it on launch. Without this, when run via `swift run` (which
    /// launches an unbundled binary), macOS treats the process as a
    /// background CLI tool — the window opens but never receives focus, so
    /// keystrokes go to whichever app is actually frontmost (usually the
    /// terminal that launched us).
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .windowStyle(.hiddenTitleBar)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Promote this process to a regular foreground app (shows in Dock,
        // can receive focus, gets keyboard events).
        NSApp.setActivationPolicy(.regular)
        // Bring the window to the front and steal focus from the terminal.
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}
