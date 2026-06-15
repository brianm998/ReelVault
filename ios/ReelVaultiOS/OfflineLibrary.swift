// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import ReelVaultKit

/// App-private offline library: a subset of a remote catalog downloaded onto the
/// device so it can be browsed and played when no core daemon is reachable.
///
/// Files and a small JSON index live under Application Support (NOT the purgeable
/// Caches dir the streaming `MediaCache` uses), so a download survives until the
/// user removes it. These videos are deliberately kept *separate* from the user's
/// catalog/connection — they're a private on-device copy, not added to the
/// server unless the user uploads them.
@MainActor
final class OfflineLibrary: ObservableObject {
    static let shared = OfflineLibrary()

    /// One downloaded video: enough of its `VideoSummary` to render a card and a
    /// detail screen offline, plus the on-disk file/thumbnail names.
    struct Entry: Codable, Identifiable, Hashable {
        let id: String          // the catalog video id
        let filename: String
        let renditionHeight: Int // 0 = original, else the downscaled height
        let pixelWidth: Int
        let pixelHeight: Int
        let durationMs: Int
        let sizeBytes: Int
        let codecVideo: String
        let fileName: String     // relative video file name in the offline dir
        let thumbName: String?   // relative thumbnail file name (jpg)
        let addedAt: Double      // unix seconds
    }

    @Published private(set) var entries: [Entry] = []
    /// Video ids with a download in flight (drives progress UI).
    @Published private(set) var downloading: Set<String> = []
    @Published var lastError: String?

    private let dir: URL
    private let indexURL: URL

    init() {
        let base = (try? FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        dir = base.appendingPathComponent("ReelVaultOffline", isDirectory: true)
        indexURL = dir.appendingPathComponent("index.json")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        load()
    }

    // MARK: Queries

    func isDownloaded(_ videoId: String) -> Bool { entries.contains { $0.id == videoId } }

    func fileURL(_ videoId: String) -> URL? {
        guard let e = entries.first(where: { $0.id == videoId }) else { return nil }
        return dir.appendingPathComponent(e.fileName)
    }

    func thumbnail(_ videoId: String) -> PlatformImage? {
        guard let e = entries.first(where: { $0.id == videoId }), let t = e.thumbName,
              let data = try? Data(contentsOf: dir.appendingPathComponent(t)) else { return nil }
        return PlatformImage.fromData(data)
    }

    /// Off-main thumbnail load for the offline grid — reads the JPEG bytes off the
    /// main thread so scrolling a large download list doesn't hitch (the disk read
    /// is the blocking part). Decode happens on the caller's actor.
    func loadThumbnailAsync(_ videoId: String) async -> PlatformImage? {
        guard let e = entries.first(where: { $0.id == videoId }), let t = e.thumbName else { return nil }
        let url = dir.appendingPathComponent(t)
        let data = await Task.detached(priority: .utility) { try? Data(contentsOf: url) }.value
        guard let data else { return nil }
        return PlatformImage.fromData(data)
    }

    var totalBytes: Int { entries.reduce(0) { $0 + $1.sizeBytes } }

    // MARK: Mutations

    /// Download `video` at `height` (0 = original) from the connected server into
    /// app-private storage, plus a poster thumbnail, and index it. Idempotent per
    /// video id (a second download replaces the first).
    func download(_ video: VideoSummary, height: Int, endpoint: AppRouter.ConnectionInfo) {
        guard !downloading.contains(video.id) else { return }
        downloading.insert(video.id)
        let mediaEndpoint = MediaClient.Endpoint(
            host: endpoint.host, mediaPort: endpoint.mediaPort,
            fingerprintHex: endpoint.fingerprintHex, bearerToken: endpoint.bearerToken)
        let safeId = video.id.replacingOccurrences(of: "/", with: "_")
        // Original (height 0) is served in its native container, so keep the real
        // extension — AVPlayer infers the format from it; a non-MP4 original named
        // .mp4 won't play. Transcoded renditions (height > 0) are always mp4.
        let ext = height == 0 ? Self.originalExt(video.filename) : "mp4"
        let fileName = "\(safeId)_h\(height).\(ext)"
        let thumbName = "\(safeId).jpg"
        let dir = self.dir

        Task {
            defer { downloading.remove(video.id) }
            do {
                // Stream the rendition to the (Caches) media cache, then copy the
                // file into our persistent offline dir so it can't be purged.
                let dest = dir.appendingPathComponent(fileName)
                do {
                    let cached = try await MediaClient().localURL(
                        videoId: video.id, height: height, ext: ext, from: mediaEndpoint)
                    try await Self.copyOut(cached, to: dest)
                } catch {
                    // The cached source may have been LRU-evicted between download
                    // and copy; re-fetch once and retry before giving up.
                    let cached = try await MediaClient().localURL(
                        videoId: video.id, height: height, ext: ext, from: mediaEndpoint)
                    try await Self.copyOut(cached, to: dest)
                }
                let size = ((try? FileManager.default.attributesOfItem(atPath: dest.path))?[.size] as? NSNumber)?.intValue ?? video.sizeBytes

                // Poster thumbnail for the offline grid (best-effort).
                var savedThumb: String?
                if let img = await VideoRepository.shared.getThumbnailHiRes(
                    videoId: video.id, size: "large", maxWidth: 640),
                   let data = img.jpegData(compressionQuality: 0.8) {
                    let turl = dir.appendingPathComponent(thumbName)
                    if (try? data.write(to: turl)) != nil { savedThumb = thumbName }
                }

                let entry = Entry(
                    id: video.id, filename: video.filename, renditionHeight: height,
                    pixelWidth: video.width, pixelHeight: video.height,
                    durationMs: video.durationMs, sizeBytes: size,
                    codecVideo: video.codecVideo, fileName: fileName,
                    thumbName: savedThumb, addedAt: Date().timeIntervalSince1970)
                entries.removeAll { $0.id == video.id }
                entries.append(entry)
                entries.sort { $0.addedAt > $1.addedAt }
                persist()
            } catch {
                lastError = "Couldn't download \(video.filename): \(error.localizedDescription)"
                NSLog("ReelVault offline: download failed for \(video.id): \(error)")
            }
        }
    }

    /// The source file's container extension (lowercased), defaulting to "mov".
    private static func originalExt(_ filename: String) -> String {
        let e = (filename as NSString).pathExtension.lowercased()
        return e.isEmpty ? "mov" : e
    }

    /// Copy a (possibly large) file off the main actor.
    private static func copyOut(_ src: URL, to dest: URL) async throws {
        try await Task.detached(priority: .utility) {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: src, to: dest)
        }.value
    }

    func remove(_ videoId: String) {
        guard let e = entries.first(where: { $0.id == videoId }) else { return }
        try? FileManager.default.removeItem(at: dir.appendingPathComponent(e.fileName))
        if let t = e.thumbName { try? FileManager.default.removeItem(at: dir.appendingPathComponent(t)) }
        entries.removeAll { $0.id == videoId }
        persist()
    }

    func removeAll() {
        for e in entries {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(e.fileName))
            if let t = e.thumbName { try? FileManager.default.removeItem(at: dir.appendingPathComponent(t)) }
        }
        entries.removeAll()
        persist()
    }

    // MARK: Persistence

    private func load() {
        guard let data = try? Data(contentsOf: indexURL),
              let list = try? JSONDecoder().decode([Entry].self, from: data) else { return }
        // Drop index rows whose file went missing (e.g. manual deletion).
        entries = list.filter { FileManager.default.fileExists(atPath: dir.appendingPathComponent($0.fileName).path) }
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(entries) {
            try? data.write(to: indexURL)
        }
    }
}
