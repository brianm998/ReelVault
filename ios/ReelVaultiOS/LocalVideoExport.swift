// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import Photos
import ReelVaultKit

/// Materialize a Local-Library video as a temporary file that can be uploaded to
/// a server. The on-device catalog stores camera-roll videos with
/// `path = "photos://<localIdentifier>"` (see PhotoLibraryIngest); we resolve that
/// to the original `PHAssetResource` and write its bytes (full resolution, no
/// re-encode) to a temp file the `UploadManager` can stream.
///
/// Files/bookmark-sourced local videos aren't supported yet (they return nil and
/// are skipped by the caller) — their security-scoped bookmark isn't exposed on
/// the VideoSummary, so there's nothing to resolve here.
enum LocalVideoExport {
    /// Returns the temp file URL + a sensible filename, or nil if the video isn't a
    /// Photos-library asset or its data can't be written.
    static func export(_ video: VideoSummary) async -> (url: URL, filename: String)? {
        guard video.path.hasPrefix("photos://") else { return nil }
        let localId = String(video.path.dropFirst("photos://".count))
        guard !localId.isEmpty,
              let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localId], options: nil).firstObject
        else { return nil }

        // Prefer the untouched original; fall back to a rendered/paired video.
        let resources = PHAssetResource.assetResources(for: asset)
        guard let resource = resources.first(where: { $0.type == .video })
                ?? resources.first(where: { $0.type == .fullSizeVideo })
                ?? resources.first(where: { $0.type == .pairedVideo })
        else { return nil }

        let filename = resource.originalFilename
        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("rv-upload-\(UUID().uuidString)-\(filename)")
        try? FileManager.default.removeItem(at: dest)

        let opts = PHAssetResourceRequestOptions()
        opts.isNetworkAccessAllowed = true  // pull from iCloud if the local copy is evicted
        do {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                PHAssetResourceManager.default().writeData(for: resource, toFile: dest, options: opts) { error in
                    if let error { cont.resume(throwing: error) } else { cont.resume() }
                }
            }
            return (dest, filename)
        } catch {
            NSLog("ReelVault upload: export failed for \(localId): \(error.localizedDescription)")
            return nil
        }
    }
}
