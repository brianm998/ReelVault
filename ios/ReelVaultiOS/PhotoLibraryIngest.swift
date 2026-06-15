// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import Photos

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

        // Enumerate + ingest on a plain background queue (not the Swift
        // concurrency pool): `reelvault_ingest_photo` blocks the calling thread
        // while it re-enters the native media callbacks, and we don't want to
        // tie up cooperative-pool threads.
        return await withCheckedContinuation { (cont: CheckedContinuation<Int, Never>) in
            DispatchQueue.global(qos: .utility).async {
                let options = PHFetchOptions()
                options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
                // `--ingest-limit N` (test harness): only ingest the N most-recent
                // videos so a first run on a large library is fast. Ingest is
                // currently synchronous-before-grid; lifting the cap waits on
                // incremental/background ingest (a follow-up).
                if let limit = ingestLimit() { options.fetchLimit = limit }
                let assets = PHAsset.fetchAssets(with: .video, options: options)
                NSLog("ReelVault local: ingesting \(assets.count) Photos video(s)")
                var ok = 0
                var skipped = 0
                assets.enumerateObjects { asset, _, _ in
                    // Incremental: skip assets already in the catalog so a
                    // relaunch over an unchanged library doesn't re-run the
                    // (expensive) AVFoundation probe + thumbnail for every one.
                    let displayPath = "photos://\(asset.localIdentifier)"
                    if displayPath.withCString({ reelvault_is_video_indexed($0) }) == 1 {
                        skipped += 1
                        return
                    }
                    thermalThrottle()
                    let filename = originalFilename(of: asset) ?? "\(asset.localIdentifier).mov"
                    let rc = asset.localIdentifier.withCString { idC in
                        filename.withCString { fnC in
                            reelvault_ingest_photo(idC, fnC)
                        }
                    }
                    if rc == 0 {
                        ok += 1
                    } else {
                        NSLog("ReelVault local: ingest rc=\(rc) for \(asset.localIdentifier)")
                    }
                }
                NSLog("ReelVault local: ingested \(ok) new, skipped \(skipped) already-indexed of \(assets.count) Photos video(s)")
                NativeMedia.clearAssetCache()
                // Now that we have access + a baseline, watch for videos added to
                // Photos and ingest them live (Phase 3 acceptance).
                PhotoLibraryObserver.shared.start()
                cont.resume(returning: ok)
            }
        }
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
/// Photos library and ingest newly-added videos as they appear, so the grid
/// updates without a relaunch. **Additions only** for now — removals need a
/// core remove-by-localId FFI (a deleted asset's row stays until the next full
/// reconcile). Held as a singleton so PHPhotoLibrary's *weak* observer
/// reference stays alive for the app's lifetime.
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
            let inserted = details.insertedObjects
            guard !inserted.isEmpty else { return }
            NSLog("ReelVault local: Photos added \(inserted.count) video(s); ingesting")
            var ok = 0
            for asset in inserted {
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
