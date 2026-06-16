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
        case startingLocal
        case connected
        case failed(String)
        /// Browsing the app-private offline downloads (no daemon) — a fallback
        /// reached from the no-server screen when downloads exist.
        case offline
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
    /// True while an on-device (Local) ingest pass is running. Drives the
    /// "keep the app open" banner — on-device ingest only progresses in the
    /// foreground (no background task), so the user shouldn't leave mid-pass.
    @Published var isIngesting = false

    /// Remembers the user's last library choice so a relaunch returns to it
    /// instead of always auto-discovering a LAN server. Set when On-Device
    /// (Local) mode is entered, cleared on a successful server connect. Read at
    /// launch (see ReelVaultApp) and by the sidebar's source switcher.
    var prefersLocalLibrary: Bool {
        get { UserDefaults.standard.bool(forKey: "ios.prefersLocalLibrary") }
        set { UserDefaults.standard.set(newValue, forKey: "ios.prefersLocalLibrary") }
    }

    /// The last server we connected to, persisted across launches so the next
    /// launch reconnects DIRECTLY to its IP (no mDNS) — falling back to discovery
    /// only if that IP is unreachable / not the same server. The bearer token
    /// lives in the Keychain (TokenStore, keyed by fingerprint), so only the
    /// address + pinned fingerprint are stored here.
    private struct StoredServer: Codable {
        var host: String
        var grpcPort: Int
        var mediaPort: Int
        var fingerprintHex: String
    }
    private static let lastServerKey = "ios.lastServer"

    private func saveLastServer(_ s: StoredServer) {
        if let data = try? JSONEncoder().encode(s) {
            UserDefaults.standard.set(data, forKey: Self.lastServerKey)
        }
    }
    private func loadStoredServer() -> StoredServer? {
        guard let data = UserDefaults.standard.data(forKey: Self.lastServerKey) else { return nil }
        return try? JSONDecoder().decode(StoredServer.self, from: data)
    }

    /// Host of the last-paired server, if one is remembered (UserDefaults only —
    /// cheap to read per render). Used to decide whether to offer "Upload to
    /// server" while browsing the on-device (Local) library.
    var pairedServerHost: String? { loadStoredServer()?.host }

    /// Reconstruct the last-paired server's media endpoint — address from
    /// UserDefaults, bearer token from the Keychain — so a Local-mode video can be
    /// uploaded to it without first switching to that server. nil if no server is
    /// remembered or its token was cleared ("Forget This Server").
    func lastPairedUploadEndpoint() -> MediaClient.Endpoint? {
        guard let s = loadStoredServer(), let token = TokenStore.load(for: s.fingerprintHex) else { return nil }
        return MediaClient.Endpoint(host: s.host, mediaPort: s.mediaPort,
                                    fingerprintHex: s.fingerprintHex, bearerToken: token)
    }

    private let discovery = ServerDiscovery()
    private var discoverTask: Task<Void, Never>?
    private var collectTask: Task<Void, Never>?
    /// Server + resolved fingerprint awaiting a pairing code.
    private var pending: (server: DiscoveredServer, fingerprint: String)?
    /// The last server we successfully connected to this session (set in
    /// `finishConnect`). Lets `useServerLibrary()` reconnect straight to it on a
    /// Local→Server switch instead of re-running the discovery window.
    private var lastServer: (server: DiscoveredServer, fingerprint: String, token: String?)?

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

        // Use a stored token if we have one; otherwise prompt for the pairing
        // code. The operator approves on a connected computer; this device
        // redeems the code via POST /pair. We don't call /pair/start here — but
        // we DO ping /pair/request so the daemon pops an "allow this device?"
        // banner on the desktop/macOS clients (the operator clicks Allow, which
        // shows the code). Best-effort; the manual "Pair a New Device" flow still
        // works if the request is missed.
        let token = TokenStore.load(for: pin)
        if token == nil && server.requiresPairing {
            pending = (server, pin)
            phase = .needsPairing(server)
            let mediaPort = server.mediaPort ?? 50052
            Task {
                await PairingClient().requestPairing(
                    host: server.host, mediaPort: mediaPort,
                    fingerprintHex: pin, deviceName: Self.deviceName())
            }
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

    /// `fallbackToDiscovery`: on a failed connect, rediscover via mDNS instead of
    /// landing on the error screen (used by the direct-reconnect paths — launch
    /// and Local→Server — where discovery is a sensible silent fallback).
    private func finishConnect(
        server: DiscoveredServer, fingerprint: String, token: String?,
        fallbackToDiscovery: Bool = false
    ) async {
        let endpoint = ServerEndpoint(
            host: server.host, port: server.grpcPort,
            security: .pinnedTLS(fingerprintSHA256Hex: fingerprint),
            bearerToken: token
        )
        let ok = await VideoRepository.shared.connect(to: endpoint)
        if ok {
            // Using a server is now the remembered choice (until the user picks
            // On-Device Library again).
            prefersLocalLibrary = false
            let mediaPort = server.mediaPort ?? 50052
            // Remember it for an instant Local→Server switch this session, and
            // persist the address so the NEXT launch reconnects directly (no mDNS).
            lastServer = (server, fingerprint, token)
            saveLastServer(StoredServer(
                host: server.host, grpcPort: server.grpcPort,
                mediaPort: mediaPort, fingerprintHex: fingerprint))
            connection = ConnectionInfo(
                host: server.host,
                mediaPort: mediaPort,
                fingerprintHex: fingerprint,
                bearerToken: token
            )
            phase = .connected
        } else {
            // A stale/revoked token will fail auth — drop it so we re-pair next time.
            if token != nil { TokenStore.delete(for: fingerprint) }
            if fallbackToDiscovery {
                start()
            } else {
                phase = .failed("Could not connect to \(server.host):\(server.grpcPort).")
            }
        }
    }

    /// Launch entry point for server mode: reconnect DIRECTLY to the last server's
    /// IP (no mDNS) when we have one paired, falling back to discovery only if that
    /// IP is unreachable, on the wrong port, or now answering with a different
    /// (non-matching) certificate. A `fetchServerCertificate` probe (≤5s) is both
    /// the reachability check and the identity check (its fingerprint must match
    /// the pinned one). No stored server/token → straight to discovery.
    func startPreferringLastServer() {
        guard let s = loadStoredServer(), let token = TokenStore.load(for: s.fingerprintHex) else {
            start()
            return
        }
        let server = DiscoveredServer(
            name: s.host, host: s.host, grpcPort: s.grpcPort, mediaPort: s.mediaPort,
            fingerprintHex: s.fingerprintHex, requiresPairing: true, source: .manual)
        phase = .connecting(server)
        Task { [weak self] in
            guard let self else { return }
            let der = await PinnedTLS.fetchServerCertificate(
                host: s.host, port: s.grpcPort, expectedFingerprintHex: s.fingerprintHex)
            guard der != nil else {
                NSLog("ReelVault: cached server \(s.host):\(s.grpcPort) unreachable or changed — falling back to mDNS")
                self.start()
                return
            }
            NSLog("ReelVault: reconnecting directly to cached server \(s.host):\(s.grpcPort)")
            await self.finishConnect(server: server, fingerprint: s.fingerprintHex,
                                     token: token, fallbackToDiscovery: true)
        }
    }

    /// On-device "Local Library" mode: boot the embedded core in-process and
    /// connect to it over loopback — no LAN, no discovery, no pairing. Local mode
    /// has no media server (originals are on-device), so `connection` stays nil
    /// and streaming/upload affordances are absent (docs/IOS_CORE_PORT.md §7.4).
    func startLocal() {
        discoverTask?.cancel()
        collectTask?.cancel()
        discovery.stop()
        // Remember this choice so the next launch returns here instead of
        // auto-connecting to a LAN server.
        prefersLocalLibrary = true
        phase = .startingLocal
        Task { [weak self] in
            guard let self else { return }
            // Install the native (AVFoundation) media backend before the core
            // does any media work, so it wins over the default CLI backend.
            NativeMedia.register()
            // Booting opens SQLite + binds a loopback port; do it off the main
            // actor so the UI can show the progress state first.
            let port = await Task.detached { LocalCore.start() }.value
            guard let port else {
                NSLog("ReelVault local: embedded core failed to start")
                self.phase = .failed("Could not start the on-device library.")
                return
            }
            NSLog("ReelVault local: embedded core on port \(port); connecting…")
            let ok = await VideoRepository.shared.connect(to: .loopback(port: port))
            guard ok else {
                NSLog("ReelVault local: connect FAILED on port \(port)")
                self.phase = .failed("Started the on-device core but couldn't connect on port \(port).")
                return
            }
            NSLog("ReelVault local: connected to embedded core on \(port)")
            // Show the grid immediately with whatever's already cataloged, then
            // ingest in the BACKGROUND. Ingest re-probes every Photos asset
            // (slow on a cold first run or a large library), so awaiting it here
            // left the user staring at the "Starting…" spinner for minutes with
            // no library in sight. Each ingested video publishes a `VideoAdded`
            // event on the same bus the grid's CatalogEvents stream watches (see
            // ConnectedRootView), so freshly-added rows stream into the grid as
            // they land. `--ingest-container` (sim/device test harness) ingests
            // the app's Documents instead of the Photos library, exercising the
            // native backend without the permission prompt.
            self.connection = nil
            self.phase = .connected
            self.runLocalIngestIfIdle()
        }
    }

    /// One on-device ingest at a time. Both the post-connect kickoff and the
    /// foreground catch-up funnel through here so they can't double-ingest the
    /// whole Photos library concurrently (the launch path fires both nearly at
    /// once — startLocal and the initial scenePhase `.active`). Guard runs on the
    /// main actor (AppRouter is @MainActor), so the check/set can't race.
    private var ingestInFlight = false
    private func runLocalIngestIfIdle() {
        guard !ingestInFlight else { return }
        ingestInFlight = true
        isIngesting = true
        // Clear any cancellation requested by a previous mode switch before
        // starting a fresh ingest for this (re-entered) Local session.
        IngestCancel.reset()
        Task { [weak self] in
            if CommandLine.arguments.contains("--ingest-container") {
                await ContainerIngest.run()
            } else {
                await PhotoLibraryIngest.run()
            }
            self?.ingestInFlight = false
            self?.isIngesting = false
        }
    }

    /// Switch from On-Device Library back to a LAN server: forget the local
    /// preference and rediscover. The embedded core keeps running idle;
    /// re-entering Local mode later (`startLocal`) is idempotent. (To go the
    /// other way, call `startLocal()`, which sets the preference.)
    func useServerLibrary() {
        // Stop any in-flight on-device ingest so it doesn't keep probing the
        // whole Photos library in the background after we move to a server.
        IngestCancel.request()
        prefersLocalLibrary = false
        // If we already connected to a server earlier this session, reconnect
        // straight to it rather than paying the full mDNS discovery window again —
        // the switch back is then effectively instant. Discovery stays the
        // fallback (server moved/offline, or none connected yet this session); a
        // failed direct reconnect lands on the error screen whose Retry rediscovers.
        if let last = lastServer {
            phase = .connecting(last.server)
            Task { [weak self] in
                await self?.finishConnect(server: last.server, fingerprint: last.fingerprint,
                                          token: last.token, fallbackToDiscovery: true)
            }
        } else {
            start()
        }
    }

    /// Forget the paired-device bearer token for the connected (or last-stored)
    /// server, so the NEXT connection to it requires a fresh pairing code again.
    /// The token lives in the iOS **Keychain** (TokenStore, keyed by the server's
    /// cert fingerprint), which **survives app deletion/reinstall** — so deleting
    /// the app does NOT re-prompt for pairing; this is the only in-app way to
    /// clear it. Also drops the remembered last-server (so launch re-discovers
    /// instead of reconnecting straight to its IP) and returns to discovery.
    /// Best-effort tells the daemon to revoke the token too (POST /pair/revoke →
    /// deletes the server's paired-devices row), so the record isn't left orphaned.
    func forgetCurrentServer() {
        // Capture host/port/fingerprint/token BEFORE clearing locally, so the
        // async server-side revoke still has the credentials it needs.
        var targets: [(host: String, mediaPort: Int, fp: String, token: String)] = []
        if let c = connection, let t = c.bearerToken {
            targets.append((c.host, c.mediaPort, c.fingerprintHex, t))
        }
        if let s = loadStoredServer(), let t = TokenStore.load(for: s.fingerprintHex),
           !targets.contains(where: { $0.fp == s.fingerprintHex }) {
            targets.append((s.host, s.mediaPort, s.fingerprintHex, t))
        }
        if !targets.isEmpty {
            Task {
                for t in targets {
                    await PairingClient().revoke(
                        host: t.host, mediaPort: t.mediaPort,
                        fingerprintHex: t.fp, token: t.token)
                }
            }
        }
        // Clear the local Keychain token(s) + remembered server, then re-discover.
        if let fp = connection?.fingerprintHex { TokenStore.delete(for: fp) }
        if let s = loadStoredServer() { TokenStore.delete(for: s.fingerprintHex) }
        UserDefaults.standard.removeObject(forKey: Self.lastServerKey)
        lastServer = nil
        pending = nil
        connection = nil
        prefersLocalLibrary = false
        // Tear down the live (now-unauthorized) gRPC connection + its catalog-events
        // stream — we're abandoning this server. Without this the stale client lingers;
        // re-pairing the same server would otherwise reuse it (a token change forces a
        // rebuild in VideoRepository.connect, but dropping it now is the right hygiene).
        Task { await VideoRepository.shared.disconnect() }
        start()
    }

    /// Returning to the foreground in Local mode: re-run the (now incremental,
    /// see `reelvault_is_video_indexed`) on-device ingest so any videos added to
    /// Photos *while the app was suspended* get picked up. This is the idempotent
    /// catch-up that pairs with the live `PHPhotoLibraryChangeObserver`, which
    /// only fires while we're running. Cheap when nothing changed (every already-
    /// indexed asset is skipped). No-op outside Local mode (a remote server's own
    /// watcher + the CatalogEvents stream handle freshness there).
    func foregroundCatchUp() {
        guard case .connected = phase, connection == nil else { return }
        runLocalIngestIfIdle()
    }

    /// Browse the app-private offline downloads (no daemon needed). Reached from
    /// the no-server screen as a fallback; leave via "Look for a Server" /
    /// "Use On-Device Library" in the offline view.
    func enterOffline() {
        discoverTask?.cancel()
        collectTask?.cancel()
        discovery.stop()
        phase = .offline
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
