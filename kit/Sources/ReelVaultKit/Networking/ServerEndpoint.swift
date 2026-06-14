// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

/// How a client secures its connection to a core daemon.
///
/// The macOS app spawns a daemon on loopback and talks to it in the clear
/// (`.plaintext`) — there is no MITM surface on `127.0.0.1`. The iOS app
/// connects to a daemon across the LAN and pins the daemon's self-signed
/// certificate by SHA-256 fingerprint (advertised over mDNS), ignoring the
/// system trust chain.
public enum EndpointSecurity: Sendable, Equatable {
    case plaintext
    /// Fingerprint-pinned TLS. `fingerprintSHA256Hex` is the lowercase-hex
    /// SHA-256 of the server's leaf certificate (DER). Wired up in the iOS
    /// networking work (PinnedTLS); on macOS only `.plaintext` is ever used.
    case pinnedTLS(fingerprintSHA256Hex: String)
}

/// A resolved core-daemon endpoint: where it is and how to secure the link.
public struct ServerEndpoint: Sendable, Equatable {
    public var host: String
    public var port: Int
    public var security: EndpointSecurity

    public init(host: String, port: Int, security: EndpointSecurity = .plaintext) {
        self.host = host
        self.port = port
        self.security = security
    }

    /// The loopback endpoint the macOS app uses today (plaintext localhost).
    public static func loopback(port: Int = 50051) -> ServerEndpoint {
        ServerEndpoint(host: "localhost", port: port, security: .plaintext)
    }
}
