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
            .task {
                // Load the user's configured top-of-card stat slots so iOS cards
                // match what they set on the desktop/macOS clients.
                grid.loadGridSettings()
                grid.loadVideos()
            }
    }
}
