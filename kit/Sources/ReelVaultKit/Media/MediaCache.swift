// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

/// On-device cache of media downloaded from the daemon — original files or
/// downscaled renditions — keyed by `(videoId, height)`. Backed by the app's
/// Caches directory so the OS may purge it under storage pressure, and bounded
/// by a byte budget with least-recently-used eviction.
///
/// The cache is an `actor` so concurrent downloads/look-ups are serialized
/// safely. It stores opaque blobs; the streaming client (`PinnedMediaClient`)
/// and the share/export flow consult it before hitting the network.
public actor MediaCache {
    /// Shared instance using the default Caches location and a 2 GiB budget.
    public static let shared = MediaCache()

    private let root: URL
    private let maxBytes: Int64

    /// - Parameters:
    ///   - root: directory to store cached files in. Defaults to
    ///     `<Caches>/ReelVaultMedia`.
    ///   - maxBytes: soft byte budget; LRU eviction runs after each store.
    public init(root: URL? = nil, maxBytes: Int64 = 2 * 1024 * 1024 * 1024) {
        if let root {
            self.root = root
        } else {
            let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
            self.root = caches.appendingPathComponent("ReelVaultMedia", isDirectory: true)
        }
        self.maxBytes = maxBytes
        try? FileManager.default.createDirectory(at: self.root, withIntermediateDirectories: true)
    }

    /// Cache key for a video at a given downscale height (`0` = original). The
    /// file extension matters: `AVURLAsset`/`AVPlayerItem` infer the container
    /// format from it, so a cached blob with no extension fails to play. Callers
    /// pass the rendition's container (`mp4` for transcodes, the source
    /// extension for originals).
    public nonisolated static func key(videoId: String, height: Int, ext: String = "mp4") -> String {
        let safeId = videoId.replacingOccurrences(of: "/", with: "_")
        let suffix = ext.isEmpty ? "" : ".\(ext)"
        return "\(safeId)_h\(height)\(suffix)"
    }

    private func fileURL(_ key: String) -> URL {
        root.appendingPathComponent(key)
    }

    /// Return the cached file URL if present, refreshing its access time so LRU
    /// eviction keeps it. Returns nil on a miss.
    public func cachedURL(videoId: String, height: Int, ext: String = "mp4") -> URL? {
        let url = fileURL(Self.key(videoId: videoId, height: height, ext: ext))
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
        return url
    }

    /// Store `data` for `(videoId, height)` and return its URL. Runs LRU
    /// eviction afterward to stay within the byte budget.
    @discardableResult
    public func store(_ data: Data, videoId: String, height: Int, ext: String = "mp4") throws -> URL {
        let url = fileURL(Self.key(videoId: videoId, height: height, ext: ext))
        try data.write(to: url, options: .atomic)
        evictIfNeeded(keeping: url)
        return url
    }

    /// Adopt an already-written file (e.g. a completed download) into the cache.
    @discardableResult
    public func adopt(_ source: URL, videoId: String, height: Int, ext: String = "mp4") throws -> URL {
        let url = fileURL(Self.key(videoId: videoId, height: height, ext: ext))
        if FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.removeItem(at: url)
        }
        try FileManager.default.moveItem(at: source, to: url)
        evictIfNeeded(keeping: url)
        return url
    }

    /// Remove everything from the cache.
    public func clear() {
        let fm = FileManager.default
        if let entries = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) {
            for e in entries { try? fm.removeItem(at: e) }
        }
    }

    /// Current total size of the cache in bytes.
    public func totalBytes() -> Int64 {
        entries().reduce(0) { $0 + $1.size }
    }

    // MARK: - Eviction

    private struct Entry { let url: URL; let size: Int64; let accessed: Date }

    private func entries() -> [Entry] {
        let fm = FileManager.default
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        guard let urls = try? fm.contentsOfDirectory(
            at: root, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]
        ) else { return [] }
        return urls.compactMap { url in
            guard let v = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
            return Entry(
                url: url,
                size: Int64(v.fileSize ?? 0),
                accessed: v.contentModificationDate ?? .distantPast
            )
        }
    }

    /// Evict least-recently-used files until total size is within budget.
    ///
    /// `keeping` protects a file (typically the one just added) from eviction in
    /// this pass — without it, a single rendition larger than `maxBytes` would be
    /// deleted immediately after being stored, leaving the caller a 0-byte/missing
    /// file (a multi-GB ProRes proxy hit exactly this). The oversized file is then
    /// the oldest entry and gets evicted on the *next* insertion of a different
    /// item, so the budget is still honoured over time.
    private func evictIfNeeded(keeping protected: URL? = nil) {
        var items = entries()
        var total = items.reduce(0) { $0 + $1.size }
        guard total > maxBytes else { return }
        // Oldest first.
        items.sort { $0.accessed < $1.accessed }
        let protectedPath = protected?.standardizedFileURL.path
        let fm = FileManager.default
        for item in items {
            if total <= maxBytes { break }
            if let protectedPath, item.url.standardizedFileURL.path == protectedPath { continue }
            try? fm.removeItem(at: item.url)
            total -= item.size
        }
    }
}
