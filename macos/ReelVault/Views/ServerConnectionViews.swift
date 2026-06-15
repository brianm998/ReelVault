// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The media endpoint of the currently-connected REMOTE daemon, or `nil` in local
/// mode. The detail player reads this: when set, it streams video over HLS (via the
/// kit's pinned loopback proxy) instead of opening a local file path (which only
/// exists for a daemon on this machine). Set by `ContentView` on every connect.
@MainActor
final class RemoteConnection: ObservableObject {
    static let shared = RemoteConnection()
    @Published var mediaEndpoint: MediaClient.Endpoint?
}

// MARK: - Startup arbitration model

/// A connection option offered at startup when more than one daemon is reachable
/// (the local loopback daemon vs. one or more remote daemons found over mDNS).
enum MacServerChoice: Identifiable, Equatable {
    case local(port: Int)
    case remote(DiscoveredServer)

    var id: String {
        switch self {
        case .local(let p): return "local:\(p)"
        case .remote(let s): return "remote:\(s.id)"
        }
    }
    var title: String {
        switch self {
        case .local: return "This Computer"
        case .remote(let s): return s.catalogName ?? s.name
        }
    }
    var subtitle: String {
        switch self {
        case .local(let p): return "Local library · localhost:\(p)"
        case .remote(let s):
            return "\(s.host):\(s.grpcPort)" + (s.requiresPairing ? " · pairing required" : "")
        }
    }
    var systemImage: String {
        switch self {
        case .local: return "desktopcomputer"
        case .remote: return "network"
        }
    }
}

/// Persisted startup default so the next launch connects straight to the chosen
/// source instead of re-arbitrating. The bearer token (for a remote) lives in the
/// Keychain (`TokenStore`, keyed by fingerprint); only address + pin are stored here.
struct StoredDefaultServer: Codable {
    enum Kind: String, Codable { case local, remote }
    var kind: Kind
    var host: String
    var grpcPort: Int
    var mediaPort: Int
    var fingerprintHex: String?
    var requiresPairing: Bool

    private static let key = "mac.defaultServer"

    static func load() -> StoredDefaultServer? {
        guard let d = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(StoredDefaultServer.self, from: d)
    }
    func save() {
        if let d = try? JSONEncoder().encode(self) { UserDefaults.standard.set(d, forKey: Self.key) }
    }
    static func clear() { UserDefaults.standard.removeObject(forKey: key) }

    static func local(port: Int) -> StoredDefaultServer {
        StoredDefaultServer(kind: .local, host: "127.0.0.1", grpcPort: port,
                            mediaPort: 50052, fingerprintHex: nil, requiresPairing: false)
    }
    static func remote(_ s: DiscoveredServer) -> StoredDefaultServer {
        StoredDefaultServer(kind: .remote, host: s.host, grpcPort: s.grpcPort,
                            mediaPort: s.mediaPort ?? 50052, fingerprintHex: s.fingerprintHex,
                            requiresPairing: s.requiresPairing)
    }
    var asDiscoveredServer: DiscoveredServer {
        DiscoveredServer(name: host, host: host, grpcPort: grpcPort, mediaPort: mediaPort,
                         fingerprintHex: fingerprintHex, catalogName: nil,
                         requiresPairing: requiresPairing, source: .manual)
    }
}

/// This machine's non-loopback IPv4 addresses, used to de-dupe a same-machine
/// `--remote` daemon (advertised over mDNS on a LAN IP) against the loopback
/// daemon we'd reach on 127.0.0.1 — they're the same process, not two servers.
func localIPv4Addresses() -> Set<String> {
    var out = Set<String>()
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0 else { return out }
    defer { freeifaddrs(ifaddr) }
    var ptr = ifaddr
    while let p = ptr {
        defer { ptr = p.pointee.ifa_next }
        let flags = Int32(p.pointee.ifa_flags)
        guard (flags & (IFF_UP | IFF_RUNNING)) == (IFF_UP | IFF_RUNNING),
              let addr = p.pointee.ifa_addr,
              addr.pointee.sa_family == sa_family_t(AF_INET)
        else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count),
                       nil, 0, NI_NUMERICHOST) == 0 {
            let s = String(cString: host)
            if !s.hasPrefix("127.") { out.insert(s) }
        }
    }
    return out
}

/// Whether `host` is this machine — either a literal local IP, or a name (e.g.
/// `<box>.local` advertised over mDNS) that resolves to one of our interface IPs.
/// Used to drop a same-machine `--remote` daemon from the remote list (we'd reach
/// the same process on loopback). Blocking DNS — call off the main actor.
func hostIsLocalMachine(_ host: String, localIPs: Set<String>) -> Bool {
    if localIPs.contains(host) { return true }
    var res: UnsafeMutablePointer<addrinfo>?
    guard getaddrinfo(host, nil, nil, &res) == 0 else { return false }
    defer { freeaddrinfo(res) }
    var p = res
    while let cur = p {
        defer { p = cur.pointee.ai_next }
        guard let addr = cur.pointee.ai_addr,
              addr.pointee.sa_family == sa_family_t(AF_INET) else { continue }
        var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(addr, cur.pointee.ai_addrlen, &buf, socklen_t(buf.count),
                       nil, 0, NI_NUMERICHOST) == 0,
           localIPs.contains(String(cString: buf)) {
            return true
        }
    }
    return false
}

/// Probe the loopback gRPC port and collect mDNS-advertised remotes (excluding a
/// same-machine `--remote` daemon — see `hostIsLocalMachine`). Returns whether
/// loopback is reachable + the remote list.
///
/// The scan window adapts to keep launch snappy: when a LOCAL daemon is already
/// reachable we only need a brief mDNS sweep (remotes that exist answer fast, and
/// we have a local fallback anyway); when there's NO local daemon we scan longer
/// and let the subnet port-scan fallback run before giving up and spawning one.
@MainActor
func scanForServers() async -> (loopbackReachable: Bool, remotes: [DiscoveredServer]) {
    let loopback = ServerLauncher.shared.isReachable(host: "127.0.0.1", port: 50051)
    let localIPs = localIPv4Addresses()
    let discovery = ServerDiscovery()
    // mDNS-only short sweep when we have a local fallback; longer + port-scan when
    // we don't (portScanAfter < window so the fallback actually fires).
    let window: TimeInterval = loopback ? 1.8 : 3.5
    let portScanAfter: TimeInterval = loopback ? 99 : 1.5
    // Detached (off the main actor) so the blocking same-machine DNS resolution
    // doesn't freeze the UI. Collect into a Task-local dictionary (no shared
    // mutation) so cancelling after the window returns the partial set.
    let collector = Task.detached { () -> [DiscoveredServer] in
        var found: [String: DiscoveredServer] = [:]
        for await s in discovery.discover(portScanAfter: portScanAfter) {
            if !hostIsLocalMachine(s.host, localIPs: localIPs) { found[s.id] = s }
        }
        return Array(found.values)
    }
    try? await Task.sleep(nanoseconds: UInt64(window * 1_000_000_000))
    collector.cancel()
    discovery.stop()
    let remotes = await collector.value
    return (loopback, remotes)
}

// MARK: - Views

/// Shown while the startup scan (mDNS + loopback probe) runs.
struct DiscoveringView: View {
    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text("Looking for ReelVault servers…")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Asked when both a local and a remote daemon (or several remotes) are found:
/// pick which to connect to, optionally as the default for next launch.
struct ServerPickerView: View {
    let choices: [MacServerChoice]
    /// (chosen, makeDefault)
    let onChoose: (MacServerChoice, Bool) -> Void
    @State private var makeDefault = false

    var body: some View {
        VStack(spacing: 20) {
            VStack(spacing: 6) {
                Image(systemName: "rectangle.connected.to.line.below")
                    .font(.system(size: 40)).foregroundStyle(.tint)
                Text("Choose a Library").font(.title2).bold()
                Text("More than one ReelVault server is available.")
                    .foregroundStyle(.secondary)
            }
            VStack(spacing: 10) {
                ForEach(choices) { choice in
                    Button {
                        onChoose(choice, makeDefault)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: choice.systemImage)
                                .font(.title3).frame(width: 28)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(choice.title).font(.headline)
                                Text(choice.subtitle).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                }
            }
            Toggle("Remember my choice (skip this next time)", isOn: $makeDefault)
                .toggleStyle(.checkbox)
        }
        .padding(32)
        .frame(maxWidth: 460)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// macOS is the CLIENT here: it enters the 6-digit code the operator reveals on
/// the server (a connected client's Allow banner, or `reelvault-core
/// pairing-code` / the daemon log). Mirrors the iOS pairing-code entry screen.
struct PairingCodeEntryView: View {
    let server: DiscoveredServer
    let onSubmit: (String) -> Void
    let onCancel: () -> Void
    @State private var code = ""

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "lock.shield").font(.system(size: 40)).foregroundStyle(.tint)
            Text("Pair with \(server.catalogName ?? server.name)").font(.title2).bold()
            Text("Enter the 6-digit code shown on the server. Reveal it there with "
                 + "“Pair a New Device”, the daemon log, or `reelvault-core pairing-code`.")
                .font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).frame(maxWidth: 420)
            TextField("000000", text: $code)
                .textFieldStyle(.roundedBorder)
                .font(.system(.title2, design: .monospaced))
                .multilineTextAlignment(.center)
                .frame(width: 160)
                .onSubmit { if !code.isEmpty { onSubmit(code) } }
            HStack(spacing: 12) {
                Button("Cancel", role: .cancel) { onCancel() }
                Button("Pair") { onSubmit(code) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(code.trimmingCharacters(in: .whitespaces).count < 4)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
