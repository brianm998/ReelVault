// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import SwiftUI
import UIKit
import ReelVaultKit

/// Drives the launch flow: discover servers on the LAN, connect over pinned TLS,
/// or fall back to an error/manual-entry screen. No embedded daemon — if nothing
/// is found the user is told to run a core daemon.
@MainActor
final class AppRouter: ObservableObject {
    enum Phase {
        case discovering
        case picker([DiscoveredServer])
        case noServer
        case connecting(DiscoveredServer)
        case needsPairing(DiscoveredServer)
        case connected
        case failed(String)
    }

    /// Everything the media client needs to stream from the connected server.
    struct ConnectionInfo: Equatable {
        var host: String
        var mediaPort: Int
        var fingerprintHex: String
        var bearerToken: String?
    }

    @Published var phase: Phase = .discovering
    @Published var discovered: [DiscoveredServer] = []
    @Published var connection: ConnectionInfo?

    private let discovery = ServerDiscovery()
    private var discoverTask: Task<Void, Never>?
    private var collectTask: Task<Void, Never>?
    /// Server + resolved fingerprint awaiting a pairing code.
    private var pending: (server: DiscoveredServer, fingerprint: String)?

    /// Begin (or restart) discovery.
    func start() {
        discoverTask?.cancel()
        collectTask?.cancel()
        discovery.stop()
        phase = .discovering
        discovered = []
        discoverTask = Task { [weak self] in await self?.runDiscovery() }
    }

    func retry() { start() }

    private func runDiscovery() async {
        let stream = discovery.discover()
        // Keep collecting in the background so the picker updates live.
        collectTask = Task { [weak self] in
            for await server in stream {
                guard let self else { return }
                if !self.discovered.contains(where: { $0.id == server.id }) {
                    self.discovered.append(server)
                }
            }
        }
        // Discovery window.
        try? await Task.sleep(nanoseconds: 5_000_000_000)
        guard case .discovering = phase else { return }
        switch discovered.count {
        case 0: phase = .noServer
        case 1: await connect(to: discovered[0])
        default: phase = .picker(discovered)
        }
    }

    func connect(to server: DiscoveredServer) async {
        collectTask?.cancel()
        discovery.stop()
        phase = .connecting(server)

        // Resolve a fingerprint to pin. From mDNS/manual we may already have one;
        // otherwise fetch the presented cert (trust-on-first-use) and pin it.
        var fingerprint = server.fingerprintHex
        if fingerprint == nil || fingerprint?.isEmpty == true {
            if let der = await PinnedTLS.fetchServerCertificate(
                host: server.host, port: server.grpcPort, expectedFingerprintHex: nil
            ) {
                fingerprint = PinnedTLS.fingerprint(ofDER: der)
            }
        }
        guard let pin = fingerprint, !pin.isEmpty else {
            phase = .failed("Could not reach a TLS server at \(server.host):\(server.grpcPort).")
            return
        }

        // Use a stored token if we have one; otherwise pair if the server asks.
        let token = TokenStore.load(for: pin)
        if token == nil && server.requiresPairing {
            _ = await PairingClient().startPairing(
                host: server.host, mediaPort: server.mediaPort ?? 50052, fingerprintHex: pin
            )
            pending = (server, pin)
            phase = .needsPairing(server)
            return
        }
        await finishConnect(server: server, fingerprint: pin, token: token)
    }

    /// Submit the code the user entered on the pairing screen.
    func submitPairingCode(_ code: String) {
        guard let (server, pin) = pending else { return }
        phase = .connecting(server)
        Task { [weak self] in
            guard let self else { return }
            let token = await PairingClient().pair(
                host: server.host, mediaPort: server.mediaPort ?? 50052,
                fingerprintHex: pin, pin: code, deviceName: Self.deviceName()
            )
            guard let token else {
                self.phase = .failed("Pairing failed — check the code and try again.")
                return
            }
            TokenStore.save(token, for: pin)
            self.pending = nil
            await self.finishConnect(server: server, fingerprint: pin, token: token)
        }
    }

    func cancelPairing() {
        pending = nil
        start()
    }

    private func finishConnect(server: DiscoveredServer, fingerprint: String, token: String?) async {
        let endpoint = ServerEndpoint(
            host: server.host, port: server.grpcPort,
            security: .pinnedTLS(fingerprintSHA256Hex: fingerprint),
            bearerToken: token
        )
        let ok = await VideoRepository.shared.connect(to: endpoint)
        if ok {
            connection = ConnectionInfo(
                host: server.host,
                mediaPort: server.mediaPort ?? 50052,
                fingerprintHex: fingerprint,
                bearerToken: token
            )
            phase = .connected
        } else {
            // A stale/revoked token will fail auth — drop it so we re-pair next time.
            if token != nil { TokenStore.delete(for: fingerprint) }
            phase = .failed("Could not connect to \(server.host):\(server.grpcPort).")
        }
    }

    /// Connect to a manually-entered server (from the error screen). Assumes the
    /// server requires pairing (the daemon's LAN bind is auth=pin by default).
    func connectManually(host: String, port: Int, fingerprintHex: String?) {
        let fp = (fingerprintHex?.isEmpty == false) ? fingerprintHex : nil
        let server = DiscoveredServer(
            name: host, host: host, grpcPort: port,
            fingerprintHex: fp, requiresPairing: true, source: .manual
        )
        Task { await connect(to: server) }
    }

    private static func deviceName() -> String {
        UIDevice.current.name
    }
}
