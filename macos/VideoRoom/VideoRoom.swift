import SwiftUI

@main
struct VideoRoomApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(GridViewModel())
                .environmentObject(DetailViewModel())
        }
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandGroup(replacing: .appSettings) {
                Button("Preferences") {
                    NSApp.sendAction(Selector(("showPreferencesWindow:")), to: nil, from: nil)
                }
                .keyboardShortcut(",", modifiers: .command)
            }
        }
    }
}
