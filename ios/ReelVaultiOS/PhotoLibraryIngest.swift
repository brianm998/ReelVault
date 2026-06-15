// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import Photos

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
                assets.enumerateObjects { asset, _, _ in
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
                NSLog("ReelVault local: ingested \(ok)/\(assets.count) Photos video(s)")
                NativeMedia.clearAssetCache()
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
                for url in videos {
                    let rc = url.path.withCString { pathC in
                        url.lastPathComponent.withCString { nameC in
                            reelvault_ingest_path(pathC, nameC)
                        }
                    }
                    if rc == 0 { ok += 1 } else {
                        NSLog("ReelVault local: ingest_path rc=\(rc) for \(url.lastPathComponent)")
                    }
                }
                NSLog("ReelVault local: ingested \(ok)/\(videos.count) container video(s)")
                NativeMedia.clearAssetCache()
                cont.resume(returning: ok)
            }
        }
    }
}
