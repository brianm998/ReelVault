import Foundation

/// Master switch for catalog sync (push/pull/mirror).
///
/// OFF until the transfer path actually exists (audit item O1 / O2): push never
/// sent its upload and pull requested a route the server doesn't have. While
/// this is false every client hides its sync entry points and auto-sync does
/// not run. Keep in step with `CatalogSyncFeature.ENABLED` in `shared/`.
public enum CatalogSyncFeature {
    public static let isEnabled = false
}
