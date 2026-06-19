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
    /// Double-tap a card to open Detail mode (iPad). nil on iPhone, where a single
    /// tap already opens detail.
    var onDoubleTap: ((VideoSummary) -> Void)? = nil
    /// Activate a card when not selecting: select into the inspector (regular)
    /// or push the detail screen (compact). Declared LAST so the call site's
    /// trailing closure binds to it (not onDoubleTap).
    var onActivate: (VideoSummary) -> Void

    @State private var selecting = false
    @State private var showImport = false
    @State private var showLocalImport = false
    @State private var showShareResolution = false
    @State private var showDownloadResolution = false
    @StateObject private var share = ShareExportModel()
    @ObservedObject private var offline = OfflineLibrary.shared
    @EnvironmentObject private var router: AppRouter
    /// Local→server upload (Local mode only): targets + the resolved last-paired
    /// endpoint, captured when the user taps Upload so the sheet has a stable value.
    @State private var showUploadToServer = false
    @State private var uploadTargets: [VideoSummary] = []
    @State private var uploadEndpoint: MediaClient.Endpoint?
    /// Batch-organize sheet: rate, label, keyword, and add-to-collection over
    /// all selected videos. IDs are captured at tap time so the count is stable
    /// while the sheet is open.
    @State private var showBatchOrganize = false
    @State private var batchOrganizeIds: [String] = []

    var body: some View {
        VStack(spacing: 0) {
            LibraryTopBar(grid: grid, thumbnailWidth: $thumbnailWidth)
            Divider()
            scanStatusBanner
            scanResultBanner
            content
        }
        .toolbar { toolbarContent }
        .sheet(isPresented: $showImport) {
            if let connection {
                ImportSheet(endpoint: connection) { grid.loadVideos() }
            }
        }
        .sheet(isPresented: $showUploadToServer) {
            if let endpoint = uploadEndpoint {
                UploadToServerSheet(endpoint: endpoint,
                                    serverName: router.pairedServerHost ?? "the server",
                                    videos: uploadTargets)
            }
        }
        .sheet(isPresented: $showBatchOrganize) {
            BatchOrganizeSheet(grid: grid, videoIds: batchOrganizeIds)
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
        .overlay(alignment: .bottom) {
            if !offline.downloading.isEmpty {
                Label("Downloading \(offline.downloading.count) for offline…", systemImage: "arrow.down.circle")
                    .font(.caption)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.bottom, 12)
            }
        }
        .confirmationDialog("Share resolution", isPresented: $showShareResolution, titleVisibility: .visible) {
            Button("Original") { startShare(height: 0) }
            Button("720p (smaller)") { startShare(height: 720) }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Download quality", isPresented: $showDownloadResolution, titleVisibility: .visible) {
            Button("Original") { startOfflineDownload(height: 0) }
            Button("720p (smaller)") { startOfflineDownload(height: 720) }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Download failed", isPresented: Binding(
            get: { offline.lastError != nil },
            set: { if !$0 { offline.lastError = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(offline.lastError ?? "")
        }
    }

    /// Thin progress strip shown while a scan or rescan is running.
    @ViewBuilder private var scanStatusBanner: some View {
        if let status = grid.scanStatus {
            HStack(spacing: 8) {
                ProgressView()
                    .scaleEffect(0.75)
                Text(status)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color.accentColor.opacity(0.12))
        }
    }

    /// Dismissable result banner shown once a scan completes (success or failure).
    @ViewBuilder private var scanResultBanner: some View {
        if let result = grid.scanResult {
            HStack(spacing: 8) {
                Image(systemName: result.success
                      ? (result.videosFound == 0 ? "exclamationmark.triangle" : "checkmark.circle.fill")
                      : "xmark.octagon.fill")
                    .foregroundStyle(result.success
                        ? (result.videosFound == 0 ? Color.orange : Color.green)
                        : Color.red)
                Text(result.message)
                    .font(.caption)
                    .lineLimit(2)
                Spacer()
                Button {
                    grid.clearScanResult()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(result.success
                ? (result.videosFound == 0 ? Color.orange.opacity(0.12) : Color.green.opacity(0.12))
                : Color.red.opacity(0.12))
        }
    }

    @ViewBuilder private var content: some View {
        if listMode {
            VideoListView(
                grid: grid,
                thumbnailHeight: CGFloat(thumbnailWidth) * 0.4,
                selecting: selecting,
                onActivate: onActivate,
                onDoubleTap: onDoubleTap
            )
        } else {
            VideoGridView(
                grid: grid,
                minCardWidth: CGFloat(thumbnailWidth),
                keyboardEnabled: keyboardEnabled && !selecting,
                selecting: selecting,
                onActivate: onActivate,
                onDoubleTap: onDoubleTap
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
            // Offline download only makes sense in remote mode (Local already has
            // the originals on-device; there's nothing to cache from a server).
            if connection != nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showDownloadResolution = true
                    } label: {
                        Label("Download", systemImage: "arrow.down.circle")
                    }
                    .disabled(grid.selectedVideoIds.isEmpty)
                }
            }
            // The inverse, in Local mode: push the selected on-device originals up
            // to the last-paired server (shown only once a server has been paired).
            if connection == nil, router.pairedServerHost != nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        startUploadToServer()
                    } label: {
                        Label("Upload", systemImage: "arrow.up.circle")
                    }
                    .disabled(grid.selectedVideoIds.isEmpty)
                }
            }
            // Batch organize: rating / color label / keywords / collections over
            // all selected videos. Matches macOS multi-selection context menu.
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    batchOrganizeIds = grid.selectedVideoIds
                    showBatchOrganize = true
                } label: {
                    Label("Organize", systemImage: "tag")
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

    /// Download the selected videos into the app-private offline library at the
    /// chosen resolution (each downloads independently in the background).
    private func startOfflineDownload(height: Int) {
        guard let connection else { return }
        let ids = Set(grid.selectedVideoIds)
        // Resolve ids against both representatives and expanded stack members —
        // a checked member id lives only in expandedGroupMembers, so filtering
        // grid.videos alone would silently drop it.
        var byId: [String: VideoSummary] = [:]
        for v in grid.videos { byId[v.id] = v }
        for members in grid.expandedGroupMembers.values { for m in members { byId[m.id] = m } }
        let targets = ids.compactMap { byId[$0] }
        selecting = false
        grid.clearSelection()
        for video in targets {
            offline.download(video, height: height, endpoint: connection)
        }
    }

    /// Upload the selected on-device videos to the last-paired server (Local mode).
    /// Resolves ids against representatives + expanded stack members (same as the
    /// offline download) and hands them to the upload sheet, which materializes each
    /// to a temp file and streams it up.
    private func startUploadToServer() {
        guard let endpoint = router.lastPairedUploadEndpoint() else { return }
        let ids = Set(grid.selectedVideoIds)
        var byId: [String: VideoSummary] = [:]
        for v in grid.videos { byId[v.id] = v }
        for members in grid.expandedGroupMembers.values { for m in members { byId[m.id] = m } }
        uploadTargets = ids.compactMap { byId[$0] }
        guard !uploadTargets.isEmpty else { return }
        uploadEndpoint = endpoint
        selecting = false
        grid.clearSelection()
        showUploadToServer = true
    }
}
