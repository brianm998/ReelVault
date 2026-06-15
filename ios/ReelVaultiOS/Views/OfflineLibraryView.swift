// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AVKit
import ReelVaultKit
import SwiftUI

/// The offline browse surface: the videos downloaded into app-private storage,
/// playable with no daemon. Reached from the no-server screen as a fallback.
struct OfflineLibraryView: View {
    @EnvironmentObject private var router: AppRouter
    @ObservedObject private var library = OfflineLibrary.shared
    @State private var pushed: OfflineLibrary.Entry?

    private let columns = [GridItem(.adaptive(minimum: 160), spacing: 10)]

    var body: some View {
        NavigationStack {
            Group {
                if library.entries.isEmpty {
                    ContentUnavailableView(
                        "No Downloads",
                        systemImage: "arrow.down.circle",
                        description: Text("While connected to a server, choose “Download for Offline” on a video to watch it here without a connection.")
                    )
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 10) {
                            ForEach(library.entries) { entry in
                                OfflineCard(entry: entry) { pushed = entry }
                            }
                        }
                        .padding(10)
                    }
                }
            }
            .navigationTitle("Downloaded")
            .navigationDestination(item: $pushed) { OfflineDetailView(entry: $0) }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button { router.start() } label: { Label("Look for a Server", systemImage: "network") }
                        Button { router.startLocal() } label: { Label("Use On-Device Library", systemImage: "iphone") }
                    } label: {
                        Label("Library", systemImage: "sidebar.left")
                    }
                }
                if !library.entries.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Text(ByteCountFormatter.string(fromByteCount: Int64(library.totalBytes), countStyle: .file))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

private struct OfflineCard: View {
    let entry: OfflineLibrary.Entry
    var onTap: () -> Void
    @ObservedObject private var library = OfflineLibrary.shared
    @State private var image: PlatformImage?

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Color(white: 0.15)
                if let image {
                    Image(uiImage: image).resizable().scaledToFit()
                } else {
                    Image(systemName: "film").font(.title2).foregroundStyle(.secondary)
                }
            }
            .aspectRatio(1, contentMode: .fit)
            .clipped()
            // Load the thumbnail off-main once per entry (not synchronously in
            // body on every scroll/redraw).
            .task(id: entry.id) { image = await library.loadThumbnailAsync(entry.id) }
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.filename).font(.caption).lineLimit(1)
                Text(secondary).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(6)
        }
        .background(Color(white: 0.11))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .contentShape(Rectangle())
        .onTapGesture { onTap() }
        .contextMenu {
            Button(role: .destructive) { library.remove(entry.id) } label: {
                Label("Remove Download", systemImage: "trash")
            }
        }
    }

    private var secondary: String {
        var parts: [String] = []
        if entry.pixelHeight > 0 { parts.append("\(entry.pixelHeight)p") }
        if entry.durationMs > 0 { parts.append(durationString(entry.durationMs)) }
        parts.append(ByteCountFormatter.string(fromByteCount: Int64(entry.sizeBytes), countStyle: .file))
        return parts.joined(separator: " · ")
    }

    private func durationString(_ ms: Int) -> String {
        let s = ms / 1000
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

/// Plays a downloaded video from its local file (no streaming, no daemon).
struct OfflineDetailView: View {
    let entry: OfflineLibrary.Entry
    @ObservedObject private var library = OfflineLibrary.shared
    @State private var player: AVPlayer?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10).fill(.black)
                    if let player {
                        VideoPlayer(player: player).clipShape(RoundedRectangle(cornerRadius: 10))
                    } else {
                        Text("This download is missing.").foregroundStyle(.secondary)
                    }
                }
                .aspectRatio(16.0 / 9.0, contentMode: .fit)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Details").font(.headline)
                    row("File", entry.filename)
                    if entry.pixelWidth > 0 && entry.pixelHeight > 0 {
                        row("Resolution", "\(entry.pixelWidth)×\(entry.pixelHeight)")
                    }
                    if !entry.codecVideo.isEmpty { row("Codec", entry.codecVideo) }
                    row("Downloaded", entry.renditionHeight == 0 ? "Original" : "\(entry.renditionHeight)p")
                    row("Size", ByteCountFormatter.string(fromByteCount: Int64(entry.sizeBytes), countStyle: .file))
                }
            }
            .padding()
        }
        .navigationTitle(entry.filename)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: entry.id) {
            if let url = library.fileURL(entry.id) { player = AVPlayer(url: url) }
        }
        .onDisappear { player?.pause() }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label).foregroundStyle(.secondary).frame(width: 90, alignment: .leading)
            Text(value).textSelection(.enabled)
            Spacer(minLength: 0)
        }
        .font(.callout)
    }
}

/// Per-video "Download for Offline" control for the detail view (remote mode
/// only). Reflects downloaded / downloading / not-downloaded state.
struct OfflineDownloadButton: View {
    let video: VideoSummary
    let endpoint: AppRouter.ConnectionInfo?
    @ObservedObject private var library = OfflineLibrary.shared
    @State private var showResolution = false

    var body: some View {
        if let endpoint {
            VStack(alignment: .leading, spacing: 8) {
                if library.isDownloaded(video.id) {
                    Label("Available offline", systemImage: "checkmark.circle.fill")
                        .font(.caption).foregroundStyle(.green)
                    Button(role: .destructive) { library.remove(video.id) } label: {
                        Label("Remove Download", systemImage: "trash").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                } else if library.downloading.contains(video.id) {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.small)
                        Text("Downloading…").font(.caption).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                } else {
                    Button { showResolution = true } label: {
                        Label("Download for Offline", systemImage: "arrow.down.circle").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .confirmationDialog("Download quality", isPresented: $showResolution, titleVisibility: .visible) {
                        Button("Original") { library.download(video, height: 0, endpoint: endpoint) }
                        Button("720p (smaller)") { library.download(video, height: 720, endpoint: endpoint) }
                        Button("Cancel", role: .cancel) {}
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
