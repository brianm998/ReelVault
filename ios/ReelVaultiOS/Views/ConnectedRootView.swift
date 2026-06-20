// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The connected app. Owns the shared `GridViewModel` and hands it to the
/// size-class-adaptive `RootSplitView` (iPad 3-column / iPhone-portrait sheets).
struct ConnectedRootView: View {
    @EnvironmentObject private var router: AppRouter
    @StateObject private var grid = GridViewModel()
    @ObservedObject private var offline = OfflineLibrary.shared

    var body: some View {
        // On-device ingest only runs in the foreground, so a thin banner tells the
        // user to keep the app open while it works. RootSplitView insets it *below*
        // the toolbar (per layout, inside the nav content) so it never covers the
        // top buttons; it auto-hides when the pass completes.
        RootSplitView(grid: grid, connection: router.connection,
                      isIngesting: router.isIngesting)
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
                // Start the live server health monitor (remote mode only; no-op
                // when connection is nil / Local mode).
                router.startConnectionMonitor()
            }
            // Switching library mode tears this view down. Stop the long-lived
            // stream and the monitor so they don't keep the connection alive.
            .onDisappear {
                grid.stopCatalogEventStream()
                router.stopConnectionMonitor()
            }
            // Prompt to go offline when the server drops and the user has
            // downloaded videos to fall back on. The alert only surfaces when
            // offline.entries is non-empty; in remote-only sessions it stays
            // hidden and the gRPC errors surface normally.
            .alert("Server Unreachable",
                   isPresented: Binding(
                    get: { router.serverUnreachable && !offline.entries.isEmpty },
                    set: { _ in }
                   )
            ) {
                Button("Go Offline") { router.enterOffline() }
                Button("Keep Waiting", role: .cancel) { router.keepWaiting() }
            } message: {
                Text("The ReelVault server is no longer reachable. You can browse your downloaded videos offline.")
            }
    }
}

/// Thin top banner shown while an on-device ingest is in progress. On-device
/// ingest has no background task (docs/IOS_CORE_PORT.md §6.8 decision), so the
/// app must stay foregrounded for it to finish.
struct IngestBanner: View {
    var body: some View {
        HStack(spacing: 6) {
            ProgressView().controlSize(.mini).tint(.white)
            Text("Importing local videos — keep ReelVault open")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.white)
                // No .minimumScaleFactor — scale-to-fit text inside a self-sizing
                // container (safeAreaInset) can feed its size back into the layout.
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity)
        .background(Color.accentColor.opacity(0.92))
    }
}
