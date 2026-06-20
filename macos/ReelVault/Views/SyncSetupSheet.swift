// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Sheet for configuring and running a one-off catalog sync on macOS.
///
/// Presented from the File menu when a local daemon is connected
/// (isLocalCatalog == true). The macOS sync path requires the user to supply
/// the remote device's address and token, since the macOS client does not yet
/// store a "last paired mobile endpoint" the way the iOS client does.
///
/// Phase 6 scaffold: UI is complete; the actual SyncManager run is wired up
/// when a remote host + token are provided. When those fields are empty the
/// Start button stays disabled and the sheet explains what to fill in.
struct SyncSetupSheet: View {
    let direction: SyncDirection
    @Environment(\.dismiss) private var dismiss

    @State private var remoteHost: String = ""
    @State private var remotePortStr: String = "50051"
    @State private var remoteToken: String = ""
    @State private var targetHeight: Int32 = 1080
    @State private var isRunning = false
    @State private var runResult: SyncRunResult?
    @State private var errorMessage: String?

    private var remotePort: Int { Int(remotePortStr) ?? 50051 }
    private var canStart: Bool {
        !remoteHost.trimmingCharacters(in: .whitespaces).isEmpty && !isRunning && runResult == nil
    }

    var body: some View {
        VStack(spacing: 0) {
            // Title bar area
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(direction == .toRemote ? "Sync to Remote" : "Sync from Remote")
                        .font(.headline)
                    Text(direction == .toRemote
                         ? "Push local catalog videos to a paired remote device"
                         : "Pull remote device videos into the local catalog")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: direction == .toRemote
                      ? "arrow.up.to.line.circle.fill"
                      : "arrow.down.to.line.circle.fill")
                    .font(.title)
                    .foregroundStyle(Color.accentColor)
            }
            .padding([.horizontal, .top], 20)
            .padding(.bottom, 12)

            Divider()

            Form {
                Section("Remote Device") {
                    TextField("Host or IP address", text: $remoteHost)
                        .textFieldStyle(.roundedBorder)
                    HStack {
                        Text("gRPC Port")
                        Spacer()
                        TextField("50051", text: $remotePortStr)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 80)
                            .multilineTextAlignment(.trailing)
                    }
                    SecureField("Bearer token (leave blank if unpaired)", text: $remoteToken)
                        .textFieldStyle(.roundedBorder)
                }

                if direction == .fromRemote {
                    Section("Quality") {
                        Picker("Target Height", selection: $targetHeight) {
                            Text("720p").tag(Int32(720))
                            Text("1080p (Recommended)").tag(Int32(1080))
                            Text("4K (2160p)").tag(Int32(2160))
                            Text("Original").tag(Int32(0))
                        }
                        .pickerStyle(.menu)
                    }
                }

                if isRunning {
                    Section("Progress") {
                        HStack(spacing: 12) {
                            ProgressView()
                                .controlSize(.small)
                            Text("Syncing…")
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                if let result = runResult {
                    Section("Results") {
                        Label("\(result.completed) of \(result.total) synced",
                              systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        if result.failed > 0 {
                            Label("\(result.failed) failed",
                                  systemImage: "exclamationmark.triangle.fill")
                                .foregroundStyle(.red)
                        }
                        if result.conflicts > 0 {
                            Label("\(result.conflicts) conflicts",
                                  systemImage: "arrow.triangle.branch")
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

                if remoteHost.trimmingCharacters(in: .whitespaces).isEmpty {
                    Section {
                        Text("Enter the remote device's IP address to enable sync. " +
                             "Find it in the ReelVault status bar on the remote device.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .formStyle(.grouped)
            .frame(maxHeight: .infinity)

            Divider()

            // Bottom button row
            HStack {
                Button("Cancel") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                    .disabled(isRunning)
                Spacer()
                if runResult != nil {
                    Button("Done") { dismiss() }
                        .keyboardShortcut(.defaultAction)
                } else {
                    Button("Start Sync") {
                        Task { await startSync() }
                    }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!canStart)
                }
            }
            .padding(16)
        }
        .frame(width: 420, height: 460)
    }

    // MARK: - Private

    private func startSync() async {
        let host = remoteHost.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty else {
            errorMessage = "Enter the remote device's host or IP address."
            return
        }

        // macOS local daemon runs on loopback at 50051 (plaintext, no token).
        // This is the same port connectLocal() wires VideoRepository to.
        let localPort = 50051

        isRunning = true
        errorMessage = nil
        defer { isRunning = false }

        let profile = SyncProfile(
            name: "Quick Sync",
            peerKey: host,
            direction: direction,
            targetHeight: targetHeight
        )

        let manager = SyncManager(
            localPort: localPort,
            remoteHost: host,
            remotePort: remotePort,
            remoteMediaPort: remotePort + 1,
            token: remoteToken,
            fingerprint: ""
        )

        await manager.startSync(profile: profile)
        runResult = manager.lastResult
    }
}

#if DEBUG
#Preview {
    SyncSetupSheet(direction: .fromRemote)
}
#endif
