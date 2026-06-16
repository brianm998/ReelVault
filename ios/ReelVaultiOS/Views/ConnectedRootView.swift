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
        // On-device ingest only runs in the foreground, so a thin banner tells the
        // user to keep the app open while it works. It's a plain sibling ABOVE the
        // whole nav container (a VStack), NOT a `safeAreaInset` around the grid's
        // ScrollView — that inset drove an exponential `sizeThatFits` layout loop
        // (and, during nav transitions, a `ResolvedStyledText` interpolation loop)
        // that froze the app on every local-catalog load. As a sibling it just
        // pushes the UI down, never overlays the toolbar, and can't perturb the
        // grid's sizing. Auto-hides when the pass completes.
        VStack(spacing: 0) {
            if router.isIngesting {
                IngestBanner()
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            RootSplitView(grid: grid, connection: router.connection)
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
        HStack(spacing: 6) {
            ProgressView().controlSize(.mini).tint(.white)
            Text("Importing local videos — keep ReelVault open")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.white)
                // NB: no .minimumScaleFactor here — scale-to-fit text inside a
                // safeAreaInset feeds the inset size back into the text layout, and
                // during a nav-bar transition that loop never converges (infinite
                // ResolvedStyledText interpolation → main-thread hang). lineLimit(1)
                // alone is deterministic; the string fits at caption2 on every device.
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
