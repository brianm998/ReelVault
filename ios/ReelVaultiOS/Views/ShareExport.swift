// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import UIKit
import ReelVaultKit

/// iOS replaces the desktop's drag-and-drop editor hand-off with the system
/// share sheet (per the three-client rule). This model downloads the selected
/// videos (at a chosen resolution) into the on-device cache via `MediaClient`,
/// then exposes their file URLs for `UIActivityViewController`.
@MainActor
final class ShareExportModel: ObservableObject {
    @Published var isPreparing = false
    @Published var preparedURLs: ShareableURLs?
    @Published var errorMessage: String?

    /// Wrapper so `.sheet(item:)` can present the share sheet once URLs resolve.
    struct ShareableURLs: Identifiable {
        let id = UUID()
        let urls: [URL]
    }

    /// Download each selected video at `height` (0 = original) and, when ready,
    /// publish the local file URLs to trigger the share sheet.
    func prepare(videoIds: [String], height: Int, endpoint: AppRouter.ConnectionInfo) {
        guard !videoIds.isEmpty else { return }
        isPreparing = true
        errorMessage = nil
        let mediaEndpoint = MediaClient.Endpoint(
            host: endpoint.host,
            mediaPort: endpoint.mediaPort,
            fingerprintHex: endpoint.fingerprintHex,
            bearerToken: endpoint.bearerToken
        )
        Task {
            let client = MediaClient()
            var urls: [URL] = []
            for id in videoIds {
                if let url = try? await client.localURL(videoId: id, height: height, from: mediaEndpoint) {
                    urls.append(url)
                }
            }
            self.isPreparing = false
            if urls.isEmpty {
                self.errorMessage = String(localized: "Could not download the selected videos.")
            } else {
                self.preparedURLs = ShareableURLs(urls: urls)
            }
        }
    }
}

/// Thin SwiftUI bridge to `UIActivityViewController` (the iOS share sheet).
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
