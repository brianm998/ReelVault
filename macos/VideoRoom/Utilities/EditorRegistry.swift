import AppKit
import Foundation
import Combine

/// Per-user configuration for one editor. Persisted in `UserDefaults`.
struct EditorConfig: Equatable {
    /// Whether this editor shows up in the right-click "Open with" menu.
    var enabled: Bool
    /// Custom executable / `.app` bundle path (overrides catalog defaults).
    var customPath: String

    static let `default` = EditorConfig(enabled: true, customPath: "")
}

/// Detection + persistence + launch for external editors.
///
/// Detection is filesystem-only: checks `defaultPaths` plus any configured
/// custom path, returning the first one that exists. No sub-process calls.
///
/// Configuration is stored in `UserDefaults` under per-editor keys
/// (`editor.<id>.enabled`, `editor.<id>.path`). `ObservableObject` so SwiftUI
/// preferences views auto-refresh after each change.
@MainActor
final class EditorRegistry: ObservableObject {
    static let shared = EditorRegistry()

    /// Bumped after every mutation; views observing `@Published` properties
    /// will re-read the registry. Cheaper than emitting per-editor changes.
    @Published private(set) var revision: Int = 0

    private let defaults = UserDefaults.standard
    private let fm = FileManager.default

    /// Currently loaded config for one editor.
    func config(for editor: ExternalEditor) -> EditorConfig {
        // Default to enabled=true so newly-supported editors light up
        // automatically when the user has them installed.
        let enabledKey = "editor.\(editor.id).enabled"
        let pathKey = "editor.\(editor.id).path"
        let enabled = (defaults.object(forKey: enabledKey) as? Bool) ?? true
        let path = defaults.string(forKey: pathKey) ?? ""
        return EditorConfig(enabled: enabled, customPath: path)
    }

    /// Persist a config change for one editor.
    func update(_ editor: ExternalEditor, _ config: EditorConfig) {
        defaults.set(config.enabled, forKey: "editor.\(editor.id).enabled")
        if config.customPath.isEmpty {
            defaults.removeObject(forKey: "editor.\(editor.id).path")
        } else {
            defaults.set(config.customPath, forKey: "editor.\(editor.id).path")
        }
        revision += 1
    }

    // MARK: - Detection

    /// Resolve which path VideoRoom should launch for `editor`, or `nil` if it
    /// isn't installed anywhere we know to look.
    func resolvedPath(for editor: ExternalEditor) -> String? {
        let cfg = config(for: editor)
        if !cfg.customPath.isEmpty, fm.fileExists(atPath: cfg.customPath) {
            return cfg.customPath
        }
        let candidates = editor.defaultPaths[.macOS] ?? []
        return candidates.first(where: { fm.fileExists(atPath: $0) })
    }

    func isInstalled(_ editor: ExternalEditor) -> Bool {
        resolvedPath(for: editor) != nil
    }

    /// Editors that should appear in the right-click "Open with" menu right
    /// now — enabled by the user and present on disk.
    var availableEditors: [(editor: ExternalEditor, path: String)] {
        EditorCatalog.forCurrentPlatform.compactMap { editor in
            guard config(for: editor).enabled else { return nil }
            guard let path = resolvedPath(for: editor) else { return nil }
            return (editor, path)
        }
    }

    // MARK: - Launching

    /// Launch `editor` with the given file paths. On macOS this uses
    /// `NSWorkspace.open(_:withApplicationAt:configuration:completionHandler:)`
    /// which respects Launch Services, sandbox prompts, and Apple Events
    /// permission. Returns `true` on a best-effort spawn.
    @discardableResult
    func launch(_ editor: ExternalEditor, files: [String]) -> Bool {
        guard let path = resolvedPath(for: editor) else { return false }
        let appURL = URL(fileURLWithPath: path)
        let fileURLs: [URL] = editor.supportsFileArgs
            ? files.map { URL(fileURLWithPath: $0) }
            : []
        let config = NSWorkspace.OpenConfiguration()
        config.activates = true
        if fileURLs.isEmpty {
            NSWorkspace.shared.openApplication(at: appURL, configuration: config, completionHandler: nil)
        } else {
            NSWorkspace.shared.open(fileURLs, withApplicationAt: appURL, configuration: config, completionHandler: nil)
        }
        return true
    }

    /// Hand a file off to macOS's default video player.
    @discardableResult
    func openWithDefault(_ filePath: String) -> Bool {
        let url = URL(fileURLWithPath: filePath)
        guard fm.fileExists(atPath: filePath) else { return false }
        return NSWorkspace.shared.open(url)
    }

    /// Reveal a file in the Finder.
    @discardableResult
    func revealInFinder(_ filePath: String) -> Bool {
        let url = URL(fileURLWithPath: filePath)
        guard fm.fileExists(atPath: filePath) else { return false }
        NSWorkspace.shared.activateFileViewerSelecting([url])
        return true
    }

    /// Open the editor's homepage in the user's default browser.
    @discardableResult
    func openHomepage(_ editor: ExternalEditor) -> Bool {
        guard let url = URL(string: editor.homepage) else { return false }
        return NSWorkspace.shared.open(url)
    }

    // MARK: - Browse picker

    /// Show an NSOpenPanel for choosing an app bundle. Returns the picked
    /// path or `nil` if the user cancelled.
    func pickCustomPath(for editor: ExternalEditor) -> String? {
        let panel = NSOpenPanel()
        panel.title = "Choose \(editor.name)"
        panel.prompt = "Select"
        panel.canChooseFiles = true
        panel.canChooseDirectories = true   // .app bundles are technically dirs
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = []      // accept anything
        panel.directoryURL = URL(fileURLWithPath: "/Applications")
        if panel.runModal() == .OK, let url = panel.url {
            return url.path
        }
        return nil
    }
}
