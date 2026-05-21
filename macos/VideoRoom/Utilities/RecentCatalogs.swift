import Foundation

/// Most-recently-opened catalog list. Persists to `UserDefaults` under the
/// key `recentCatalogs`. Entries are stored newest-first and capped at
/// [maxEntries]. The macOS client mirrors the same behavior as the Kotlin
/// desktop client so the workflow is consistent across platforms.
///
/// Does NOT validate that the files still exist — the UI shows them and
/// lets the user pick one even if it was moved; the open call will surface
/// a clear error if the path is now invalid.
@MainActor
final class RecentCatalogs: ObservableObject {
    static let shared = RecentCatalogs()

    /// Hard cap on stored entries.
    static let maxEntries = 10

    private let key = "recentCatalogs"
    private let defaults = UserDefaults.standard

    /// Bumped after every mutation so SwiftUI views observing this object
    /// re-read the list.
    @Published private(set) var revision: Int = 0

    private init() {}

    /// Current list, newest-first. Filters out blanks.
    func list() -> [String] {
        let raw = (defaults.array(forKey: key) as? [String]) ?? []
        return raw.filter { !$0.isEmpty }
    }

    /// Move `path` to the head of the list. Duplicates collapse; the list
    /// is capped at [maxEntries].
    func touch(_ path: String) {
        guard !path.isEmpty else { return }
        var items = list()
        items.removeAll { $0 == path }
        items.insert(path, at: 0)
        if items.count > Self.maxEntries {
            items = Array(items.prefix(Self.maxEntries))
        }
        defaults.set(items, forKey: key)
        revision += 1
    }

    /// Drop `path` from the list entirely.
    func remove(_ path: String) {
        var items = list()
        let before = items.count
        items.removeAll { $0 == path }
        if items.count != before {
            defaults.set(items, forKey: key)
            revision += 1
        }
    }
}
