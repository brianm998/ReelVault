// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

@main
struct ReelVaultApp: App {
    @StateObject private var router = AppRouter()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(router)
                .task { router.start() }
        }
    }
}

/// Routes between the discovery/connection phases and the connected app.
struct RootView: View {
    @EnvironmentObject private var router: AppRouter

    var body: some View {
        switch router.phase {
        case .discovering:
            DiscoveringView()
        case .picker(let servers):
            ServerPickerView(servers: servers)
        case .noServer:
            DiscoveryErrorView(error: nil)
        case .connecting(let server):
            ConnectingView(server: server)
        case .needsPairing(let server):
            PairingCodeView(server: server)
        case .connected:
            ConnectedRootView()
        case .failed(let message):
            DiscoveryErrorView(error: message)
        }
    }
}

/// Shown while the initial discovery window is open.
struct DiscoveringView: View {
    @EnvironmentObject private var router: AppRouter
    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text("Looking for a ReelVault server…")
                .foregroundStyle(.secondary)
            if !router.discovered.isEmpty {
                Text("\(router.discovered.count) found")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding()
    }
}

/// Shown while connecting to a chosen server.
struct ConnectingView: View {
    let server: DiscoveredServer
    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text("Connecting to \(server.name)…")
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}
