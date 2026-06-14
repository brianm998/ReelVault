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
