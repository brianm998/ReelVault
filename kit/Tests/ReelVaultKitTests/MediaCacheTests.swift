// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import XCTest
@testable import ReelVaultKit

final class MediaCacheTests: XCTestCase {
    func testStoreAndRetrieve() async throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("rvtest-\(UUID().uuidString)", isDirectory: true)
        let cache = MediaCache(root: tmp, maxBytes: 10_000)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let data = Data(repeating: 7, count: 256)
        _ = try await cache.store(data, videoId: "vid-1", height: 720)
        let url = await cache.cachedURL(videoId: "vid-1", height: 720)
        XCTAssertNotNil(url)
        if let url { XCTAssertEqual(try Data(contentsOf: url), data) }
        let miss = await cache.cachedURL(videoId: "vid-1", height: 1080) // different key
        XCTAssertNil(miss)
    }

    func testEvictionKeepsWithinBudget() async throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("rvtest-\(UUID().uuidString)", isDirectory: true)
        let cache = MediaCache(root: tmp, maxBytes: 1000)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let chunk = Data(repeating: 0, count: 400)
        _ = try await cache.store(chunk, videoId: "a", height: 0)
        _ = try await cache.store(chunk, videoId: "b", height: 0)
        _ = try await cache.store(chunk, videoId: "c", height: 0) // 1200 > 1000 budget

        let total = await cache.totalBytes()
        XCTAssertLessThanOrEqual(total, 1000, "eviction must keep the cache within budget")
        XCTAssertGreaterThan(total, 0)
    }
}

final class PinnedTLSTests: XCTestCase {
    func testFingerprintOfEmptyDataIsKnownSHA256() {
        // SHA-256 of the empty string is a well-known constant.
        XCTAssertEqual(
            PinnedTLS.fingerprint(ofDER: Data()),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
    }
}
