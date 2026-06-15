// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import Photos

/// Cooperative cancellation for the on-device ingest loops (Photos enumerate,
/// container, live observer). Set when the user switches *away* from Local mode
/// so a long first-run ingest doesn't keep probing the whole library in the
/// background (battery/heat) after they've moved to a server. Checked each
/// iteration; cleared right before a fresh ingest starts (AppRouter).
enum IngestCancel {
    private static let lock = NSLock()
    private static var flag = false
    static func request() { lock.lock(); flag = true; lock.unlock() }
    static func reset() { lock.lock(); flag = false; lock.unlock() }
    static var isRequested: Bool { lock.lock(); defer { lock.unlock() }; return flag }
}

/// Pause briefly when the device is thermally stressed (Phase 4 / docs §6.11) so
/// a large first-run ingest — serial AVFoundation probe + thumbnail per asset —
/// doesn't drive the phone into sustained throttling. No-op at nominal/fair
/// temperatures; runs on the ingest's background queue so the sleep is safe.
fileprivate func thermalThrottle() {
    switch ProcessInfo.processInfo.thermalState {
    case .serious: Thread.sleep(forTimeInterval: 0.5)
    case .critical: Thread.sleep(forTimeInterval: 2.0)
    default: break
    }
}

/// On-device ingest of the Photos video library into the embedded catalog
/// (docs/IOS_CORE_PORT.md §6.9 / Phase 3). Enumerates video `PHAsset`s and feeds
/// each to the core via `reelvault_ingest_photo`, which probes + thumbnails it
/// through the native backend and adds a `photos://<localId>` row.
enum PhotoLibraryIngest {
    /// Request Photos access, then ingest every video asset. Returns the number
    /// ingested. Must be called after the embedded server is up.
    @discardableResult
    static func run() async -> Int {
        let status = await requestAuthorization()
        guard status == .authorized || status == .limited else {
            NSLog("ReelVault local: Photos access not granted (status \(status.rawValue)); skipping ingest")
            return 0
        }

        // Two phases: a fast serial enumeration (collect the present-set + the
        // not-yet-indexed work list), then a parallel ingest of the work list.
        let scan = await enumerate()
        let ok = await ingestConcurrently(scan.todo)
        NativeMedia.clearAssetCache()
        // Reconcile removals: prune catalog rows for videos no longer in Photos.
        // Only on a COMPLETE enumeration — a cancelled or `--ingest-limit`-capped
        // pass would wrongly delete the rest.
        if !scan.cancelled && ingestLimit() == nil {
            pruneMissingPhotos(present: scan.present)
        }
        // Now that we have access + a baseline, watch for videos added/removed in
        // Photos and reconcile live (Phase 3 acceptance).
        PhotoLibraryObserver.shared.start()
        return ok
    }

    /// Serial enumeration on a background queue: every video asset's localId (the
    /// complete present-set for pruning), plus the (id, filename) of those not yet
    /// fully indexed (the parallel-ingest work list). The per-asset is-indexed
    /// check is a cheap SQLite EXISTS, so this stays serial; the expensive
    /// AVFoundation probe/thumbnail work is parallelized in `ingestConcurrently`.
    private static func enumerate() async -> (todo: [(id: String, name: String)], present: [String], cancelled: Bool) {
        await withCheckedContinuation { (cont: CheckedContinuation<(todo: [(id: String, name: String)], present: [String], cancelled: Bool), Never>) in
            DispatchQueue.global(qos: .utility).async {
                let options = PHFetchOptions()
                options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
                // `--ingest-limit N` (test harness): only scan the N most-recent.
                if let limit = ingestLimit() { options.fetchLimit = limit }
                let assets = PHAsset.fetchAssets(with: .video, options: options)
                var todo: [(id: String, name: String)] = []
                var present: [String] = []
                var cancelled = false
                var skipped = 0
                assets.enumerateObjects { asset, _, stop in
                    if IngestCancel.isRequested { stop.pointee = true; cancelled = true; return }
                    present.append(asset.localIdentifier)
                    let displayPath = "photos://\(asset.localIdentifier)"
                    if displayPath.withCString({ reelvault_is_video_indexed($0) }) == 1 {
                        skipped += 1
                        return
                    }
                    let filename = originalFilename(of: asset) ?? "\(asset.localIdentifier).mov"
                    todo.append((asset.localIdentifier, filename))
                }
                NSLog("ReelVault local: \(todo.count) Photos video(s) to ingest, \(skipped) already-indexed of \(assets.count)")
                cont.resume(returning: (todo, present, cancelled))
            }
        }
    }

    /// Ingest the work list through a bounded `TaskGroup` so independent assets'
    /// probe/thumbnail work overlaps. The core already caps native decode via its
    /// own ffmpeg permit (≈2 on iOS); a small window keeps that pipeline full —
    /// overlapping (possibly iCloud) asset resolution with decode — without
    /// over-launching. Returns the count successfully ingested.
    private static func ingestConcurrently(_ todo: [(id: String, name: String)]) async -> Int {
        guard !todo.isEmpty else { return 0 }
        let maxConcurrent = max(2, min(4, ProcessInfo.processInfo.activeProcessorCount - 1))
        var ok = 0
        var next = 0
        await withTaskGroup(of: Bool.self) { group in
            func addNext() {
                guard next < todo.count, !IngestCancel.isRequested else { return }
                let item = todo[next]; next += 1
                group.addTask { await ingestOne(id: item.id, filename: item.name) }
            }
            for _ in 0..<maxConcurrent { addNext() }
            for await success in group {
                if success { ok += 1 }
                addNext()
            }
        }
        NSLog("ReelVault local: ingested \(ok) new of \(todo.count) Photos video(s)")
        return ok
    }

    /// Ingest one asset. Runs the blocking `reelvault_ingest_photo` (it re-enters
    /// the native media callbacks synchronously) off the cooperative pool via a
    /// DispatchQueue, so a TaskGroup of these doesn't starve Swift concurrency.
    private static func ingestOne(id: String, filename: String) async -> Bool {
        await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            DispatchQueue.global(qos: .utility).async {
                thermalThrottle()
                let rc = id.withCString { idC in
                    filename.withCString { fnC in reelvault_ingest_photo(idC, fnC) }
                }
                if rc != 0 { NSLog("ReelVault local: ingest rc=\(rc) for \(id)") }
                cont.resume(returning: rc == 0)
            }
        }
    }

    /// Prune catalog rows for Photos videos no longer present. `present` must be
    /// a complete set of current video localIdentifiers (see the core's
    /// `reelvault_prune_photos` safety note). Runs on the caller's background
    /// thread (the FFI is synchronous).
    static func pruneMissingPhotos(present: [String]) {
        guard let data = try? JSONSerialization.data(withJSONObject: present),
              let json = String(data: data, encoding: .utf8) else { return }
        let removed = json.withCString { reelvault_prune_photos($0) }
        if removed > 0 { NSLog("ReelVault local: pruned \(removed) Photos video(s) deleted from the library") }
    }

    private static func requestAuthorization() async -> PHAuthorizationStatus {
        let current = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if current != .notDetermined { return current }
        return await withCheckedContinuation { cont in
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { cont.resume(returning: $0) }
        }
    }

    private static func originalFilename(of asset: PHAsset) -> String? {
        PHAssetResource.assetResources(for: asset).first?.originalFilename
    }

    /// `--ingest-limit N` from the launch arguments, if present and valid.
    private static func ingestLimit() -> Int? {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "--ingest-limit"), i + 1 < args.count,
              let n = Int(args[i + 1]), n > 0 else { return nil }
        return n
    }
}

/// Live Photos updates (Phase 3): once the initial ingest has run, watch the
/// Photos library and reconcile as it changes — ingest newly-added videos and
/// prune rows for videos deleted from Photos (via `reelvault_prune_photos`) — so
/// the grid stays in sync without a relaunch. Held as a singleton so
/// PHPhotoLibrary's *weak* observer reference stays alive for the app's lifetime.
final class PhotoLibraryObserver: NSObject, PHPhotoLibraryChangeObserver {
    static let shared = PhotoLibraryObserver()
    private var fetchResult: PHFetchResult<PHAsset>?
    private var registered = false
    private let queue = DispatchQueue(label: "reelvault.photo-observer", qos: .utility)

    /// Begin observing. Idempotent; call only with Photos access granted.
    func start() {
        queue.async {
            guard !self.registered else { return }
            let opts = PHFetchOptions()
            opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            self.fetchResult = PHAsset.fetchAssets(with: .video, options: opts)
            PHPhotoLibrary.shared().register(self)
            self.registered = true
            NSLog("ReelVault local: watching Photos for new videos")
        }
    }

    func stop() {
        queue.async {
            guard self.registered else { return }
            PHPhotoLibrary.shared().unregisterChangeObserver(self)
            self.registered = false
            self.fetchResult = nil
        }
    }

    func photoLibraryDidChange(_ changeInstance: PHChange) {
        queue.async {
            guard let current = self.fetchResult,
                  let details = changeInstance.changeDetails(for: current) else { return }
            self.fetchResult = details.fetchResultAfterChanges
            // Live removals: when videos are deleted from Photos, reconcile the
            // catalog against the now-current set so their rows disappear without
            // waiting for the next foreground full-ingest.
            if !details.removedObjects.isEmpty, let current = self.fetchResult {
                var present: [String] = []
                current.enumerateObjects { asset, _, _ in present.append(asset.localIdentifier) }
                PhotoLibraryIngest.pruneMissingPhotos(present: present)
            }
            let inserted = details.insertedObjects
            guard !inserted.isEmpty else { return }
            NSLog("ReelVault local: Photos added \(inserted.count) video(s); ingesting")
            var ok = 0
            for asset in inserted {
                if IngestCancel.isRequested { break }
                let displayPath = "photos://\(asset.localIdentifier)"
                if displayPath.withCString({ reelvault_is_video_indexed($0) }) == 1 { continue }
                thermalThrottle()
                let filename = PHAssetResource.assetResources(for: asset).first?.originalFilename
                    ?? "\(asset.localIdentifier).mov"
                let rc = asset.localIdentifier.withCString { idC in
                    filename.withCString { fnC in reelvault_ingest_photo(idC, fnC) }
                }
                if rc == 0 { ok += 1 }
            }
            NativeMedia.clearAssetCache()
            NSLog("ReelVault local: live-ingested \(ok)/\(inserted.count) new Photos video(s)")
        }
    }
}

/// Ingest videos sitting in the app container's Documents directory via
/// `reelvault_ingest_path` (the Files-app / bookmark path, D7 "Files second").
/// Used by the simulator harness (`--ingest-container`) to exercise the native
/// backend without the Photos-permission prompt, and by Files imports later.
enum ContainerIngest {
    @discardableResult
    static func run() async -> Int {
        guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return 0
        }
        return await withCheckedContinuation { (cont: CheckedContinuation<Int, Never>) in
            DispatchQueue.global(qos: .utility).async {
                let exts: Set<String> = ["mov", "mp4", "m4v"]
                let files = (try? FileManager.default.contentsOfDirectory(
                    at: docs, includingPropertiesForKeys: nil)) ?? []
                let videos = files.filter { exts.contains($0.pathExtension.lowercased()) }
                NSLog("ReelVault local: ingesting \(videos.count) container video(s)")
                var ok = 0
                var skipped = 0
                for url in videos {
                    if IngestCancel.isRequested { break }
                    if url.path.withCString({ reelvault_is_video_indexed($0) }) == 1 {
                        skipped += 1
                        continue
                    }
                    thermalThrottle()
                    let rc = url.path.withCString { pathC in
                        url.lastPathComponent.withCString { nameC in
                            reelvault_ingest_path(pathC, nameC)
                        }
                    }
                    if rc == 0 { ok += 1 } else {
                        NSLog("ReelVault local: ingest_path rc=\(rc) for \(url.lastPathComponent)")
                    }
                }
                NSLog("ReelVault local: ingested \(ok) new, skipped \(skipped) already-indexed of \(videos.count) container video(s)")
                NativeMedia.clearAssetCache()
                cont.resume(returning: ok)
            }
        }
    }
}
