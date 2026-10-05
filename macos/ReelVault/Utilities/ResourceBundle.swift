import Foundation

/// Crash-safe lookup for the SwiftPM resource bundle.
///
/// SwiftPM's synthesized `Bundle.module` calls `fatalError` when it can't find
/// `ReelVault_ReelVault.bundle` — and in a packaged .app it only looks at the
/// app's root, while `release-macos.sh` places the bundle in `Contents/Resources`.
/// Touching `Bundle.module` there kills the app at launch, so inside a .app we
/// never evaluate it; we search `Bundle.main` and the embedded bundle instead.
enum ResourceBundle {
    private static let bundleName = "ReelVault_ReelVault"

    static func url(forResource name: String, withExtension ext: String) -> URL? {
        if let url = Bundle.main.url(forResource: name, withExtension: ext) {
            return url
        }
        if let embedded = Bundle.main.url(forResource: bundleName, withExtension: "bundle"),
           let url = Bundle(url: embedded)?.url(forResource: name, withExtension: ext) {
            return url
        }
        // `swift run` / raw .build binary: Bundle.module resolves via the build
        // directory there. Never reached inside a packaged .app.
        if Bundle.main.bundleURL.pathExtension != "app" {
            return Bundle.module.url(forResource: name, withExtension: ext)
        }
        return nil
    }
}
