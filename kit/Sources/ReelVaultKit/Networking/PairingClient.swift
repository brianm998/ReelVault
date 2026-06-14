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
            let http = resp as? HTTPURLResponse
            let status = http?.statusCode ?? -1
            guard let http, (200..<300).contains(http.statusCode) else {
                let body = String(data: data, encoding: .utf8) ?? ""
                NSLog("ReelVault pair: POST https://\(host):\(mediaPort)/pair -> HTTP \(status): \(body)")
                return nil
            }
            let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            if let token = obj?["token"] as? String {
                NSLog("ReelVault pair: success (HTTP \(status))")
                return token
            }
            NSLog("ReelVault pair: HTTP \(status) but no token in response")
            return nil
        } catch {
            // A pinning/TLS/connection failure lands here (vs. a code rejection,
            // which is a non-2xx logged above) — important for diagnosing why a
            // device that "discovers" the server still can't pair.
            NSLog("ReelVault pair: request to https://\(host):\(mediaPort)/pair failed: \(error)")
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
