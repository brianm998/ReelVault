// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

/// Performs the one-time device pairing handshake over the daemon's pinned
/// HTTPS media server: `POST /pair/start` asks the daemon to display a code,
/// then `POST /pair` exchanges the user-entered code for a long-lived bearer
/// token. Reuses the fingerprint-pinned URLSession from the media client.
public struct PairingClient {
    public init() {}

    /// Ask the daemon to generate + display a pairing code. Returns true on 2xx.
    public func startPairing(host: String, mediaPort: Int, fingerprintHex: String) async -> Bool {
        guard let url = URL(string: "https://\(host):\(mediaPort)/pair/start") else { return false }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 10
        let session = pinnedSession(fingerprintHex)
        defer { session.finishTasksAndInvalidate() }
        do {
            let (_, resp) = try await session.data(for: req)
            return (resp as? HTTPURLResponse).map { (200..<300).contains($0.statusCode) } ?? false
        } catch {
            return false
        }
    }

    /// Exchange the entered code for a bearer token, or nil if it was rejected.
    public func pair(
        host: String, mediaPort: Int, fingerprintHex: String,
        pin: String, deviceName: String
    ) async -> String? {
        guard let url = URL(string: "https://\(host):\(mediaPort)/pair") else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 10
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(
            withJSONObject: ["pin": pin, "device_name": deviceName]
        )
        let session = pinnedSession(fingerprintHex)
        defer { session.finishTasksAndInvalidate() }
        do {
            let (data, resp) = try await session.data(for: req)
            guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return nil
            }
            let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            return obj?["token"] as? String
        } catch {
            return nil
        }
    }

    private func pinnedSession(_ fingerprintHex: String) -> URLSession {
        URLSession(
            configuration: .ephemeral,
            delegate: FingerprintPinningDelegate(expectedFingerprintHex: fingerprintHex),
            delegateQueue: nil
        )
    }
}
