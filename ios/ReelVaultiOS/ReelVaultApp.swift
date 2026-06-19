// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

@main
struct ReelVaultApp: App {
    @StateObject private var router = AppRouter()
    @Environment(\.scenePhase) private var scenePhase

    /// Persisted accent scheme — "blue" or "purple". Matches the macOS
    /// `accentScheme` key so the preference is consistent across Apple clients.
    /// Changing this rewires `.tint()` on the root view so every `.accentColor`
    /// / `.tint` reference across the whole app picks up the new value immediately.
    @AppStorage("accentScheme") private var accentScheme: String = "purple"

    private var resolvedTint: Color {
        accentScheme == "blue" ? .blue : Color(red: 0.733, green: 0.525, blue: 0.988)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(router)
                // Returning to the foreground re-runs the incremental on-device
                // ingest so videos added to Photos while suspended get caught up
                // (no-op outside Local mode). Pairs with the live observer.
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { router.foregroundCatchUp() }
                }
                // Always dark, like the macOS + desktop clients: black/near-black
                // backgrounds and white text. `.dark` makes the system
                // background/label colors resolve to their dark variants.
                .preferredColorScheme(.dark)
                // Accent color driven by the persisted scheme (blue or purple).
                // Default is purple — the classic ReelVault palette.
                .tint(resolvedTint)
                // `--autostart-local` (passed by `xcrun simctl launch` in the
                // simulator test harness) jumps straight into on-device Local
                // Library mode, skipping LAN discovery so the embedded core can
                // be exercised without a UI tap or a daemon on the network.
                .task {
                    // Return to the last-used library: On-Device if the user
                    // chose it (or the test flag forces it), else LAN discovery.
                    if CommandLine.arguments.contains("--autostart-local") || router.prefersLocalLibrary {
                        router.startLocal()
                    } else {
                        // Reconnect straight to the last server's IP (no mDNS) when
                        // we have one; falls back to discovery if it's unreachable.
                        router.startPreferringLastServer()
                    }
                }
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
        case .startingLocal:
            StartingLocalView()
        case .connected:
            ConnectedRootView()
        case .failed(let message):
            DiscoveryErrorView(error: message)
        case .offline:
            OfflineLibraryView()
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

/// Shown while the embedded core boots for on-device (Local Library) mode.
struct StartingLocalView: View {
    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text("Starting on-device library…")
                .foregroundStyle(.secondary)
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
