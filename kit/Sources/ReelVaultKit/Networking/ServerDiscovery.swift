// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import Network

/// A ReelVault daemon found on the local network.
public struct DiscoveredServer: Sendable, Identifiable, Equatable {
    public var id: String { "\(host):\(grpcPort)" }
    /// Friendly name (mDNS instance name / advertised name, or the host for a scan).
    public var name: String
    /// Resolvable host (an IPv4 string, or `<name>.local`).
    public var host: String
    /// TLS gRPC port.
    public var grpcPort: Int
    /// HTTPS media port, if advertised.
    public var mediaPort: Int?
    /// Certificate SHA-256 fingerprint from the mDNS TXT record. `nil` when the
    /// server was found by port-scan — the caller must fetch+confirm it (TOFU).
    public var fingerprintHex: String?
    /// Advertised catalog display name, if any.
    public var catalogName: String?
    /// Whether the server requires one-time device pairing (TXT `auth=pin`).
    public var requiresPairing: Bool
    /// How the server was found.
    public var source: Source

    public enum Source: String, Sendable { case bonjour, portScan, manual }

    public init(
        name: String, host: String, grpcPort: Int, mediaPort: Int? = nil,
        fingerprintHex: String? = nil, catalogName: String? = nil,
        requiresPairing: Bool = false, source: Source = .manual
    ) {
        self.name = name
        self.host = host
        self.grpcPort = grpcPort
        self.mediaPort = mediaPort
        self.fingerprintHex = fingerprintHex
        self.catalogName = catalogName
        self.requiresPairing = requiresPairing
        self.source = source
    }
}

/// Discovers ReelVault daemons on the LAN. Prefers Bonjour/mDNS
/// (`_reelvault._tcp`); falls back to scanning the local IPv4 subnet for the
/// gRPC port when mDNS yields nothing (e.g. networks that block multicast).
public final class ServerDiscovery: @unchecked Sendable {
    public static let serviceType = "_reelvault._tcp"

    private let queue = DispatchQueue(label: "com.preffect.reelvault.discovery")
    private var browser: NWBrowser?

    public init() {}

    /// Stream discovered servers. mDNS results arrive first (if any); after
    /// `portScanAfter` seconds with no Bonjour result, a subnet scan on
    /// `scanPorts` runs and its hits are appended. The stream stays open until
    /// the returned task is cancelled (drop the iterator) — callers typically
    /// take the first result or present a live-updating picker.
    public func discover(
        scanPorts: [Int] = [50051],
        portScanAfter: TimeInterval = 3.0
    ) -> AsyncStream<DiscoveredServer> {
        AsyncStream { continuation in
            let browser = NWBrowser(
                for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil),
                using: NWParameters()
            )
            self.browser = browser
            let sawBonjour = AtomicFlag(false)

            browser.browseResultsChangedHandler = { results, _ in
                for result in results {
                    guard case let .service(name, _, _, _) = result.endpoint else { continue }
                    var txt: [String: String] = [:]
                    if case let .bonjour(record) = result.metadata {
                        txt = record.dictionary
                    }
                    sawBonjour.set(true)
                    let grpc = txt["grpc"].flatMap { Int($0) } ?? scanPorts.first ?? 50051
                    let host = txt["host"] ?? txt["ip"] ?? "\(name).local"
                    continuation.yield(DiscoveredServer(
                        name: txt["cat"] ?? name,
                        host: host,
                        grpcPort: grpc,
                        mediaPort: txt["media"].flatMap { Int($0) },
                        fingerprintHex: txt["fp"],
                        catalogName: txt["cat"],
                        requiresPairing: (txt["auth"] == "pin"),
                        source: .bonjour
                    ))
                }
            }
            browser.start(queue: queue)

            // Port-scan fallback if Bonjour is quiet.
            let work = DispatchWorkItem {
                if sawBonjour.get() { return }
                for port in scanPorts {
                    Self.scanLocalSubnet(port: port, queue: self.queue) { ip in
                        continuation.yield(DiscoveredServer(
                            name: ip, host: ip, grpcPort: port, source: .portScan
                        ))
                    }
                }
            }
            queue.asyncAfter(deadline: .now() + portScanAfter, execute: work)

            continuation.onTermination = { @Sendable _ in
                browser.cancel()
            }
        }
    }

    public func stop() {
        browser?.cancel()
        browser = nil
    }

    // MARK: - Port scan

    /// Probe every host on the device's primary IPv4 /24 for an open `port`,
    /// invoking `onFound` with each reachable host's IP string.
    static func scanLocalSubnet(
        port: Int,
        queue: DispatchQueue,
        onFound: @escaping @Sendable (String) -> Void
    ) {
        guard let local = primaryIPv4(), let prefix = subnetPrefix24(local) else { return }
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { return }
        for host in 1...254 {
            let ip = "\(prefix).\(host)"
            if ip == local { continue }
            let conn = NWConnection(
                host: NWEndpoint.Host(ip), port: nwPort, using: .tcp
            )
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    onFound(ip)
                    conn.cancel()
                case .failed, .cancelled:
                    conn.cancel()
                default:
                    break
                }
            }
            conn.start(queue: queue)
            // Bound each probe so the scan doesn't hang on filtered hosts.
            queue.asyncAfter(deadline: .now() + 1.0) { conn.cancel() }
        }
    }

    /// The device's primary non-loopback IPv4 address, via getifaddrs.
    static func primaryIPv4() -> String? {
        var result: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            let flags = Int32(p.pointee.ifa_flags)
            let addr = p.pointee.ifa_addr
            if let addr, (flags & IFF_UP) == IFF_UP, (flags & IFF_LOOPBACK) == 0,
               addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: p.pointee.ifa_name)
                // Prefer Wi-Fi / Ethernet interfaces (en*).
                if name.hasPrefix("en") || result == nil {
                    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(addr, socklen_t(addr.pointee.sa_len),
                                   &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                        let ip = String(cString: host)
                        if name.hasPrefix("en") { freeifaddrs(ifaddr); ifaddr = nil; return ip }
                        result = ip
                    }
                }
            }
            ptr = p.pointee.ifa_next
        }
        return result
    }

    /// The /24 prefix of an "a.b.c.d" address, i.e. "a.b.c".
    static func subnetPrefix24(_ ip: String) -> String? {
        let parts = ip.split(separator: ".")
        guard parts.count == 4 else { return nil }
        return parts.prefix(3).joined(separator: ".")
    }
}

/// A minimal lock-guarded boolean, safe to capture in `@Sendable` closures.
private final class AtomicFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Bool
    init(_ value: Bool) { self.value = value }
    func set(_ v: Bool) { lock.lock(); value = v; lock.unlock() }
    func get() -> Bool { lock.lock(); defer { lock.unlock() }; return value }
}
