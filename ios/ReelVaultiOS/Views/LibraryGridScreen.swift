// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit
import UniformTypeIdentifiers

/// The browse surface for Grid and List view modes: the filter/search/sort +
/// thumbnail-size top bar, the grid or list itself, and the action affordances
/// (add/upload, multi-select, share). Shared by both the iPad 3-column and
/// iPhone layouts so the toolbar and sheets aren't duplicated.
struct LibraryGridScreen: View {
    @ObservedObject var grid: GridViewModel
    let connection: AppRouter.ConnectionInfo?
    /// Grid or List (the only two modes this screen renders).
    var listMode: Bool = false
    @Binding var thumbnailWidth: Double
    var keyboardEnabled: Bool = false
    /// Activate a card when not selecting: select into the inspector (regular)
    /// or push the detail screen (compact).
    var onActivate: (VideoSummary) -> Void

    @State private var selecting = false
    @State private var showImport = false
    @State private var showLocalImport = false
    @State private var showShareResolution = false
    @StateObject private var share = ShareExportModel()

    var body: some View {
        VStack(spacing: 0) {
            LibraryTopBar(grid: grid, thumbnailWidth: $thumbnailWidth)
            Divider()
            content
        }
        .toolbar { toolbarContent }
        .sheet(isPresented: $showImport) {
            if let connection {
                ImportSheet(endpoint: connection) { grid.loadVideos() }
            }
        }
        // Local mode (no server): import videos from the Files app via a
        // security-scoped bookmark (D7). Remote mode uses ImportSheet (upload).
        .fileImporter(
            isPresented: $showLocalImport,
            allowedContentTypes: [.movie, .video, .quickTimeMovie, .mpeg4Movie],
            allowsMultipleSelection: true
        ) { result in handleLocalImport(result) }
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

    @ViewBuilder private var content: some View {
        if listMode {
            VideoListView(
                grid: grid,
                thumbnailHeight: CGFloat(thumbnailWidth) * 0.4,
                selecting: selecting,
                onActivate: onActivate
            )
        } else {
            VideoGridView(
                grid: grid,
                minCardWidth: CGFloat(thumbnailWidth),
                keyboardEnabled: keyboardEnabled && !selecting,
                selecting: selecting,
                onActivate: onActivate
            )
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
                    // Remote: upload to the server's import dir. Local: pull a
                    // video in from the Files app (no server to upload to).
                    if connection != nil { showImport = true } else { showLocalImport = true }
                } label: {
                    Label("Add", systemImage: "plus")
                }
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

    /// Files-app import for Local mode: capture a security-scoped bookmark for
    /// each picked video (the picker URLs are scoped on this callback), then
    /// ingest off-main — `reelvault_ingest_bookmark` blocks and re-enters the
    /// native media shim, which re-resolves the bookmark to probe + thumbnail.
    private func handleLocalImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, !urls.isEmpty else { return }
        var bookmarks: [(Data, String)] = []
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let bm = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
                bookmarks.append((bm, url.lastPathComponent))
            }
        }
        guard !bookmarks.isEmpty else { return }
        DispatchQueue.global(qos: .userInitiated).async {
            var ok = 0
            for (bm, name) in bookmarks {
                let rc = bm.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Int32 in
                    guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return -9 }
                    return name.withCString { reelvault_ingest_bookmark(base, UInt(bm.count), $0) }
                }
                if rc == 0 { ok += 1 } else {
                    NSLog("ReelVault local: ingest_bookmark rc=\(rc) for \(name)")
                }
            }
            NativeMedia.clearAssetCache()
            NSLog("ReelVault local: imported \(ok)/\(bookmarks.count) Files video(s)")
            Task { @MainActor in grid.loadVideos() }
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
