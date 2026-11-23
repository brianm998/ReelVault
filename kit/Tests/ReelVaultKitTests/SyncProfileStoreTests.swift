// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import XCTest
@testable import ReelVaultKit

@MainActor
final class SyncProfileStoreTests: XCTestCase {
    func testProtoRoundTripPreservesFields() {
        var p = SyncProfile(
            id: "p1", name: "Phone", peerKey: "peer", direction: .mirror,
            filterJson: "{\"filterMinRating\":3}", targetHeight: 720,
            collectionResolutions: ["c": CollectionResolution(action: .rename, renameTo: "d")],
            deviceLabel: "iPhone", autoSync: true
        )
        p.lastRunMs = nil
        let proto = SyncProfileStore.proto(from: p)
        XCTAssertEqual(proto.direction, "MIRROR")
        let back = SyncProfileStore.profile(from: proto, autoSync: true)
        XCTAssertEqual(back.id, "p1")
        XCTAssertEqual(back.direction, .mirror)
        XCTAssertEqual(back.targetHeight, 720)
        XCTAssertEqual(back.collectionResolutions["c"]?.renameTo, "d")
        XCTAssertTrue(back.autoSync)
        XCTAssertNil(back.createdMs)
    }

    private func legacyDefaults(_ profiles: [SyncProfile]) throws -> UserDefaults {
        let d = UserDefaults(suiteName: "synctest-\(UUID().uuidString)")!
        d.set(try JSONEncoder().encode(profiles), forKey: "reelvault.syncProfiles")
        return d
    }

    func testLegacyProfilesMigrateOnceAndKeyIsCleared() async throws {
        let p1 = SyncProfile(id: "m1", name: "A", peerKey: "p", direction: .toRemote)
        let p2 = SyncProfile(id: "m2", name: "B", peerKey: "p", direction: .fromRemote)
        let defaults = try legacyDefaults([p1, p2])
        var sent: [String] = []
        try await SyncProfileStore.migrateLegacyProfiles(defaults: defaults) { sent.append($0.profile.id) }
        XCTAssertEqual(sent, ["m1", "m2"])
        XCTAssertNil(defaults.data(forKey: "reelvault.syncProfiles"))
        // Second run is a no-op.
        try await SyncProfileStore.migrateLegacyProfiles(defaults: defaults) { sent.append($0.profile.id) }
        XCTAssertEqual(sent.count, 2)
    }

    func testFailedMigrationKeepsLegacyProfilesForRetry() async throws {
        let p = SyncProfile(id: "m3", name: "C", peerKey: "p", direction: .toRemote)
        let defaults = try legacyDefaults([p])
        struct Boom: Error {}
        do {
            try await SyncProfileStore.migrateLegacyProfiles(defaults: defaults) { _ in throw Boom() }
            XCTFail("expected throw")
        } catch {}
        XCTAssertNotNil(defaults.data(forKey: "reelvault.syncProfiles"), "profiles must not be lost")
    }
}
