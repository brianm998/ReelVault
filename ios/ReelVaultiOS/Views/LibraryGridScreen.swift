// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The video grid plus its action affordances (add/upload, multi-select, share),
/// shared by both the iPad 3-column and iPhone-portrait layouts so the toolbar
/// and sheets aren't duplicated. The surrounding navigation chrome differs per
/// layout; this is the common middle.
struct LibraryGridScreen: View {
    @ObservedObject var grid: GridViewModel
    let connection: AppRouter.ConnectionInfo?
    var keyboardEnabled: Bool = false
    /// Activate a card when not selecting: select into the inspector (regular)
    /// or push the detail screen (compact).
    var onActivate: (VideoSummary) -> Void

    @State private var selecting = false
    @State private var showImport = false
    @State private var showShareResolution = false
    @StateObject private var share = ShareExportModel()

    var body: some View {
        VideoGridView(
            grid: grid,
            keyboardEnabled: keyboardEnabled && !selecting,
            selecting: selecting,
            onActivate: onActivate
        )
        .toolbar { toolbarContent }
        .sheet(isPresented: $showImport) {
            if let connection {
                ImportSheet(endpoint: connection) { grid.loadVideos() }
            }
        }
        .sheet(item: $share.preparedURLs) { prepared in
            ActivityView(items: prepared.urls)
        }
        .overlay {
            if share.isPreparing {
                ProgressView("Preparing…")
                    .padding(24)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .confirmationDialog("Share resolution", isPresented: $showShareResolution, titleVisibility: .visible) {
            Button("Original") { startShare(height: 0) }
            Button("720p (smaller)") { startShare(height: 720) }
            Button("Cancel", role: .cancel) {}
        }
    }

    @ToolbarContentBuilder private var toolbarContent: some ToolbarContent {
        if selecting {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showShareResolution = true
                } label: {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
                .disabled(grid.selectedVideoIds.isEmpty)
            }
            ToolbarItem(placement: .topBarLeading) {
                Button("Cancel") {
                    selecting = false
                    grid.clearSelection()
                }
            }
        } else {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showImport = true
                } label: {
                    Label("Add", systemImage: "plus")
                }
                .disabled(connection == nil)
            }
            ToolbarItem(placement: .primaryAction) {
                Button {
                    grid.clearSelection()
                    selecting = true
                } label: {
                    Label("Select", systemImage: "checkmark.circle")
                }
            }
        }
    }

    private func startShare(height: Int) {
        guard let connection else { return }
        let ids = grid.selectedVideoIds
        selecting = false
        share.prepare(videoIds: ids, height: height, endpoint: connection)
        grid.clearSelection()
    }
}
