// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import ReelVaultKit

/// Upload videos from the device to the server's import directory. Sources are
/// the photo library (PhotosUI) and the Files app (`.fileImporter`); both feed
/// the shared `UploadManager`, which streams to the core's pinned, token-gated
/// `/upload` endpoint (A6) and reports per-file progress.
struct ImportSheet: View {
    let endpoint: AppRouter.ConnectionInfo
    /// Called after at least one upload finishes, so the grid can refresh.
    var onUploaded: () -> Void

    @StateObject private var uploader: UploadManager
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showFiles = false
    @Environment(\.dismiss) private var dismiss

    init(endpoint: AppRouter.ConnectionInfo, onUploaded: @escaping () -> Void) {
        self.endpoint = endpoint
        self.onUploaded = onUploaded
        let mediaEndpoint = MediaClient.Endpoint(
            host: endpoint.host,
            mediaPort: endpoint.mediaPort,
            fingerprintHex: endpoint.fingerprintHex,
            bearerToken: endpoint.bearerToken
        )
        _uploader = StateObject(wrappedValue: UploadManager(endpoint: mediaEndpoint))
    }

    var body: some View {
        NavigationStack {
            listContent
                .navigationTitle("Add Videos")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") { dismiss() }
                    }
                }
                .onChange(of: photoItems) { _, items in
                    guard !items.isEmpty else { return }
                    let picked = items
                    photoItems = []
                    Task { await importFromPhotos(picked) }
                }
                .fileImporter(
                    isPresented: $showFiles,
                    allowedContentTypes: [.movie, .video, .mpeg4Movie, .quickTimeMovie],
                    allowsMultipleSelection: true
                ) { result in
                    if case .success(let urls) = result { importFromFiles(urls) }
                }
                .onChange(of: uploader.jobs) { _, jobs in
                    if jobs.contains(where: { $0.status == .finished }) { onUploaded() }
                }
        }
    }

    @ViewBuilder private var listContent: some View {
        List {
            sourcesSection
            if !uploader.jobs.isEmpty {
                Section("Uploads") {
                    ForEach(uploader.jobs) { job in
                        UploadRow(job: job)
                    }
                }
            }
        }
    }

    @ViewBuilder private var sourcesSection: some View {
        Section {
            PhotosPicker(selection: $photoItems, matching: .videos) {
                Label("Choose from Photos", systemImage: "photo.on.rectangle")
            }
            Button {
                showFiles = true
            } label: {
                Label("Choose from Files", systemImage: "folder")
            }
        } footer: {
            Text("Videos upload at full resolution to the server's import folder, then are indexed into the catalog.")
        }
    }

    private func importFromPhotos(_ items: [PhotosPickerItem]) async {
        for item in items {
            if let movie = try? await item.loadTransferable(type: Movie.self) {
                uploader.upload(fileURL: movie.url, filename: movie.url.lastPathComponent)
            }
        }
    }

    private func importFromFiles(_ urls: [URL]) {
        for url in urls {
            // Files returns security-scoped URLs; copy into our temp dir to own a
            // stable file the background upload session can read.
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString + "-" + url.lastPathComponent)
            do {
                try? FileManager.default.removeItem(at: dest)
                try FileManager.default.copyItem(at: url, to: dest)
                uploader.upload(fileURL: dest, filename: url.lastPathComponent)
            } catch {
                continue
            }
        }
    }
}

/// One row in the upload progress list.
private struct UploadRow: View {
    let job: UploadManager.Job

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(job.filename).lineLimit(1)
                Spacer()
                statusIcon
            }
            switch job.status {
            case .uploading:
                ProgressView(value: job.progress)
            case .failed(let message):
                Text(message).font(.caption).foregroundStyle(.red)
            case .finished:
                EmptyView()
            }
        }
    }

    @ViewBuilder private var statusIcon: some View {
        switch job.status {
        case .uploading:
            Text("\(Int(job.progress * 100))%").font(.caption).foregroundStyle(.secondary)
        case .finished:
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        case .failed:
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
        }
    }
}

/// Transferable wrapper that copies a picked movie out of the Photos sandbox
/// into a temp file the upload session can stream from.
struct Movie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString + "-" + received.file.lastPathComponent)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: received.file, to: dest)
            return Movie(url: dest)
        }
    }
}
