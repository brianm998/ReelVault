// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Sheet for configuring and running a one-off catalog sync operation.
///
/// Presented from LibraryGridScreen when the user taps "Sync to Remote" or
/// "Sync from Remote" in multi-select mode (both require canSync == true, i.e.
/// local embedded core is running AND a paired remote endpoint is stored).
///
/// Layout rules:
/// - NEVER wrap a NavigationStack in a VStack (breaks push transition).
/// - NEVER apply .safeAreaInset at window level (floats over UIKit nav bar).
/// - Progress banners use .safeAreaInset on content INSIDE the NavigationStack.
struct SyncSetupSheet: View {
    let direction: SyncDirection
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var router: AppRouter

    @State private var targetHeight: Int32 = 1080
    @State private var isRunning = false
    @State private var runResult: SyncRunResult?
    @State private var errorMessage: String?
    @State private var selectedTagId: String = ""
    @State private var selectedCollectionId: String = ""
    @State private var filterMinRating: Int32 = 0
    @State private var filterColorLabel: String = ""
    @State private var availableTags: [Tag] = []
    @State private var availableCollections: [Collection] = []

    var body: some View {
        NavigationStack {
            Form {
                Section("Sync Direction") {
                    Label(
                        direction == .toRemote
                            ? "Push local videos to remote server"
                            : "Pull remote videos to this device",
                        systemImage: direction == .toRemote
                            ? "arrow.up.to.line.circle"
                            : "arrow.down.to.line.circle"
                    )
                    .foregroundStyle(.secondary)
                }

                if direction == .fromRemote {
                    Section("Quality") {
                        Picker("Target Height", selection: $targetHeight) {
                            Text("720p").tag(Int32(720))
                            Text("1080p (Recommended)").tag(Int32(1080))
                            Text("Original").tag(Int32(0))
                        }
                        .pickerStyle(.menu)
                    }
                }

                Section("Filter (optional)") {
                    Picker("Tag", selection: $selectedTagId) {
                        Text("Any tag").tag("")
                        ForEach(availableTags) { tag in
                            Text(tag.name).tag(tag.id)
                        }
                    }
                    Picker("Collection", selection: $selectedCollectionId) {
                        Text("Any collection").tag("")
                        ForEach(availableCollections) { coll in
                            Text(coll.name).tag(coll.id)
                        }
                    }
                    Picker("Min Rating", selection: $filterMinRating) {
                        Text("Any rating").tag(Int32(0))
                        Text("★ or better").tag(Int32(1))
                        Text("★★ or better").tag(Int32(2))
                        Text("★★★ or better").tag(Int32(3))
                        Text("★★★★ or better").tag(Int32(4))
                        Text("★★★★★ only").tag(Int32(5))
                    }
                    Picker("Color Label", selection: $filterColorLabel) {
                        Text("Any color").tag("")
                        Text("Red").tag("red")
                        Text("Yellow").tag("yellow")
                        Text("Green").tag("green")
                        Text("Blue").tag("blue")
                        Text("Purple").tag("purple")
                    }
                }

                if isRunning {
                    Section("Progress") {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Syncing…")
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                if let result = runResult {
                    Section("Results") {
                        Label("\(result.completed) of \(result.total) synced", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        if result.failed > 0 {
                            Label("\(result.failed) failed", systemImage: "exclamationmark.triangle.fill")
                                .foregroundStyle(.red)
                        }
                        if result.conflicts > 0 {
                            Label("\(result.conflicts) conflicts", systemImage: "arrow.triangle.branch")
                                .foregroundStyle(.orange)
                        }
                    }

                    if !result.errors.isEmpty {
                        Section("Errors") {
                            ForEach(result.errors, id: \.self) { err in
                                Text(err)
                                    .font(.caption)
                                    .foregroundStyle(.red)
                            }
                        }
                    }
                }

                if let msg = errorMessage {
                    Section {
                        Label(msg, systemImage: "xmark.octagon.fill")
                            .foregroundStyle(.red)
                    }
                }
            }
            .task { await loadFilterOptions() }
            .navigationTitle(direction == .toRemote ? "Sync to Remote" : "Sync from Remote")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(isRunning)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if runResult != nil {
                        Button("Done") { dismiss() }
                    } else {
                        Button("Start Sync") {
                            Task { await startSync() }
                        }
                        .disabled(isRunning)
                    }
                }
            }
        }
    }

    // MARK: - Private

    private func startSync() async {
        guard let endpoint = router.lastPairedUploadEndpoint() else {
            errorMessage = "No paired server found. Pair a server first."
            return
        }
        isRunning = true
        errorMessage = nil
        defer { isRunning = false }

        // Build a quick-sync profile (no saved profile required for the one-shot UI).
        let profile = SyncProfile(
            name: "Quick Sync",
            peerKey: endpoint.fingerprintHex ?? "",
            direction: direction,
            filterJson: buildFilterJson(),
            targetHeight: targetHeight
        )

        // SyncManager requires the loopback port from the running local core.
        // LocalCore.port is set when startLocal() boots the embedded core.
        guard let localPort = LocalCore.port else {
            errorMessage = "Local core is not running."
            return
        }

        let manager = SyncManager(
            localPort: localPort,
            remoteHost: endpoint.host,
            remotePort: endpoint.mediaPort,   // gRPC port is mediaPort by convention
            remoteMediaPort: endpoint.mediaPort + 1,
            token: endpoint.bearerToken ?? "",
            fingerprint: endpoint.fingerprintHex ?? ""
        )

        await manager.startSync(profile: profile)
        runResult = manager.lastResult
    }

    private func buildFilterJson() -> String {
        var dict: [String: Any] = [:]
        if !selectedTagId.isEmpty { dict["filterTags"] = [selectedTagId] }
        if !selectedCollectionId.isEmpty { dict["collectionId"] = selectedCollectionId }
        if filterMinRating > 0 { dict["filterMinRating"] = Int(filterMinRating) }
        if !filterColorLabel.isEmpty { dict["filterColorLabel"] = filterColorLabel }
        guard !dict.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: dict, options: .sortedKeys),
              let str = String(data: data, encoding: .utf8)
        else { return "" }
        return str
    }

    private func loadFilterOptions() async {
        if let tags = try? await VideoRepository.shared.listTags() {
            availableTags = tags.sorted { $0.name < $1.name }
        }
        if let colls = try? await VideoRepository.shared.listCollections() {
            availableCollections = colls.filter { !$0.isSmart }.sorted { $0.name < $1.name }
        }
    }
}

#if DEBUG
#Preview {
    SyncSetupSheet(direction: .fromRemote)
        .environmentObject(AppRouter())
}
#endif
