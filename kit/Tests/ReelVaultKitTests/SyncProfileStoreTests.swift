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
}
