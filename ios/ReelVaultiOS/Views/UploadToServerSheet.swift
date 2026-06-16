// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Uploads videos picked from the on-device (Local) catalog to the last-paired
/// server. The counterpart of `ImportSheet`, but the source is the local catalog
/// rather than a Photos/Files picker: each video is materialized to a temp file
/// (`LocalVideoExport`) and streamed via the shared `UploadManager` over the same
/// pinned, token-gated `/upload` endpoint. Like `ImportSheet`, the uploader is a
/// `@StateObject`, so dismissing the sheet cancels in-flight transfers.
struct UploadToServerSheet: View {
    let endpoint: MediaClient.Endpoint
    let serverName: String
    let videos: [VideoSummary]
    /// Called after at least one upload finishes (lets the caller refresh, etc.).
    var onUploaded: () -> Void = {}

    @StateObject private var uploader: UploadManager
    @State private var preparing = true
    @State private var skipped = 0
    /// Temp files we created; removed on dismiss so multi-GB exports don't linger.
    @State private var tempURLs: [URL] = []
    @Environment(\.dismiss) private var dismiss

    init(endpoint: MediaClient.Endpoint, serverName: String, videos: [VideoSummary],
         onUploaded: @escaping () -> Void = {}) {
        self.endpoint = endpoint
        self.serverName = serverName
        self.videos = videos
        self.onUploaded = onUploaded
        _uploader = StateObject(wrappedValue: UploadManager(endpoint: endpoint))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Uploading \(videos.count) video\(videos.count == 1 ? "" : "s") to \(serverName).")
                        .font(.callout)
                } footer: {
                    if skipped > 0 {
                        Text("\(skipped) skipped — only Photos-library videos can be uploaded right now.")
                    }
                }
                if preparing {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Preparing…").foregroundStyle(.secondary)
                    }
                }
                if !uploader.jobs.isEmpty {
                    Section("Uploads") {
                        ForEach(uploader.jobs) { UploadRow(job: $0) }
                    }
                }
            }
            .navigationTitle("Upload to Server")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .task { await prepareAndUpload() }
            .onChange(of: uploader.jobs) { _, jobs in
                if jobs.contains(where: { $0.status == .finished }) { onUploaded() }
            }
            .onDisappear {
                for url in tempURLs { try? FileManager.default.removeItem(at: url) }
            }
        }
    }

    /// Resolve each selected video to a temp file and start its upload as soon as
    /// it's ready (sequential resolve so we don't materialize every multi-GB file
    /// at once). Non-Photos videos can't be resolved and are counted as skipped.
    private func prepareAndUpload() async {
        for video in videos {
            if let (url, name) = await LocalVideoExport.export(video) {
                tempURLs.append(url)
                uploader.upload(fileURL: url, filename: name)
            } else {
                skipped += 1
            }
        }
        preparing = false
    }
}
