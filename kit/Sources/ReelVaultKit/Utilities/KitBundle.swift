import Foundation

extension Bundle {
    /// Crash-safe replacement for the SwiftPM-synthesized `Bundle.module`.
    ///
    /// The synthesized accessor differs by toolchain: some versions only look at
    /// the app root (and a hardcoded build-machine path), never at
    /// `Contents/Resources` where macOS packaging must place resource bundles to
    /// keep the code signature valid. A miss is a `fatalError`, so `Bundle.module`
    /// killed the packaged macOS app on its first localized string.
    ///
    /// We search the places a bundle can legitimately live and, if none has it,
    /// degrade to untranslated strings via `Bundle.main` rather than crashing.
    static let reelVaultKit: Bundle = {
        let name = "ReelVaultKit_ReelVaultKit.bundle"
        let candidates = [
            Bundle.main.resourceURL,          // macOS .app: Contents/Resources
            Bundle.main.bundleURL,            // iOS .app root, command-line tools
            Bundle(for: KitBundleFinder.self).resourceURL,
        ]
        for candidate in candidates {
            if let url = candidate?.appendingPathComponent(name),
               let bundle = Bundle(url: url) {
                return bundle
            }
        }
        // Inside a packaged app, never touch `.module` (it would fatalError).
        if Bundle.main.bundleURL.pathExtension == "app" {
            return Bundle.main
        }
        // `swift run` / tests: `.module` resolves via the build directory.
        return Bundle.module
    }()
}

private final class KitBundleFinder {}
