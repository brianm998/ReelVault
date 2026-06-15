// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Shown when no server was found (or a connection failed). Offers retry and a
/// manual host/port/fingerprint entry. ReelVault does not embed a daemon — the
/// user must run a core daemon somewhere on the network.
struct DiscoveryErrorView: View {
    let error: String?
    @EnvironmentObject private var router: AppRouter
    @State private var host = ""
    @State private var port = "50051"
    @State private var fingerprint = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label(error ?? "No ReelVault server found on this network.",
                          systemImage: "wifi.exclamationmark")
                    Text("Start a ReelVault core daemon (run it with --remote) on a "
                         + "computer on this Wi-Fi network, then retry.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Retry") { router.retry() }
                }
                Section("On this device") {
                    Text("Open a library stored on this device — no daemon needed. "
                         + "The core runs inside the app.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Open On-Device Library") { router.startLocal() }
                }
                if !OfflineLibrary.shared.entries.isEmpty {
                    Section("Downloaded") {
                        Text("Watch videos you downloaded for offline use — no connection needed.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Button("View Downloaded Videos (\(OfflineLibrary.shared.entries.count))") {
                            router.enterOffline()
                        }
                    }
                }
                Section("Connect manually") {
                    TextField("Host or IP", text: $host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Port", text: $port)
                        .keyboardType(.numberPad)
                    TextField("Cert fingerprint (sha256, optional)", text: $fingerprint)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.footnote, design: .monospaced))
                    Button("Connect") {
                        router.connectManually(
                            host: host.trimmingCharacters(in: .whitespaces),
                            port: Int(port) ?? 50051,
                            fingerprintHex: fingerprint.trimmingCharacters(in: .whitespaces)
                        )
                    }
                    .disabled(host.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .navigationTitle("No Server")
        }
    }
}

/// One-time pairing: the daemon shows a 6-digit code (in a connected desktop
/// client, the daemon log, or <data_dir>/pairing.txt); the user enters it here.
struct PairingCodeView: View {
    let server: DiscoveredServer
    @EnvironmentObject private var router: AppRouter
    @State private var code = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label("Pair with \(server.name)", systemImage: "lock.shield")
                    Text("On the computer running ReelVault, choose File ▸ Pair a "
                         + "New Device to show a 6-digit code (a headless server "
                         + "also logs it). Enter it below to pair this device — you "
                         + "only do this once.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section {
                    TextField("6-digit code", text: $code)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .font(.system(.title2, design: .monospaced))
                    Button("Pair") {
                        router.submitPairingCode(code.trimmingCharacters(in: .whitespaces))
                    }
                    .disabled(code.trimmingCharacters(in: .whitespaces).count < 4)
                }
                Section {
                    Button("Cancel", role: .cancel) { router.cancelPairing() }
                }
            }
            .navigationTitle("Enter Pairing Code")
        }
    }
}

/// Shown when more than one server was discovered.
struct ServerPickerView: View {
    let servers: [DiscoveredServer]
    @EnvironmentObject private var router: AppRouter

    var body: some View {
        NavigationStack {
            List(servers) { server in
                Button {
                    Task { await router.connect(to: server) }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(server.name).font(.headline)
                        Text("\(server.host):\(server.grpcPort)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if server.requiresPairing {
                            Text("Requires pairing")
                                .font(.caption2)
                                .foregroundStyle(.orange)
                        }
                    }
                }
            }
            .navigationTitle("Choose a Server")
            .toolbar {
                Button("Rescan") { router.retry() }
            }
        }
    }
}
