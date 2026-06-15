// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The connected app. Owns the shared `GridViewModel` and hands it to the
/// size-class-adaptive `RootSplitView` (iPad 3-column / iPhone-portrait sheets).
struct ConnectedRootView: View {
    @EnvironmentObject private var router: AppRouter
    @StateObject private var grid = GridViewModel()

    var body: some View {
        RootSplitView(grid: grid, connection: router.connection)
            // On-device ingest only runs in the foreground, so tell the user to
            // keep the app open while it works; the banner auto-hides when done.
            .safeAreaInset(edge: .top, spacing: 0) {
                if router.isIngesting {
                    IngestBanner()
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.25), value: router.isIngesting)
            .task {
                // Load the user's configured top-of-card stat slots so iOS cards
                // match what they set on the desktop/macOS clients.
                grid.loadGridSettings()
                grid.loadVideos()
                // Open the long-lived CatalogEvents subscription (parity with the
                // macOS client) so the grid refreshes as videos are added/changed
                // — by a remote server's file-watcher/uploads, or by Local mode's
                // background Photos ingest, which streams in row-by-row.
                grid.startCatalogEventStream()
            }
            // Switching library mode tears this view down. Stop the long-lived
            // stream so it (a) doesn't keep the connection's runConnections()
            // alive — which would hang the disconnect/mode-switch — and (b)
            // releases its strong `self`, letting the view-model dealloc.
            .onDisappear { grid.stopCatalogEventStream() }
    }
}

/// Thin top banner shown while an on-device ingest is in progress. On-device
/// ingest has no background task (docs/IOS_CORE_PORT.md §6.8 decision), so the
/// app must stay foregrounded for it to finish.
struct IngestBanner: View {
    var body: some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small).tint(.white)
            Text("Importing local videos — keep ReelVault open")
                .font(.footnote.weight(.medium))
                .foregroundStyle(.white)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(Color.accentColor.opacity(0.92))
    }
}
