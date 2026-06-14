// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import XCTest
@testable import ReelVaultKit

final class ServerEndpointTests: XCTestCase {
    func testLoopbackDefaults() {
        let e = ServerEndpoint.loopback()
        XCTAssertEqual(e.host, "localhost")
        XCTAssertEqual(e.port, 50051)
        XCTAssertEqual(e.security, .plaintext)
    }

    func testPinnedTLSEquatable() {
        let a = ServerEndpoint(host: "10.0.0.5", port: 50051, security: .pinnedTLS(fingerprintSHA256Hex: "ab"))
        let b = ServerEndpoint(host: "10.0.0.5", port: 50051, security: .pinnedTLS(fingerprintSHA256Hex: "ab"))
        XCTAssertEqual(a, b)
        XCTAssertNotEqual(a.security, .plaintext)
    }
}
