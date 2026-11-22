// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import XCTest
@testable import ReelVaultKit

final class LRUBudgetTests: XCTestCase {
    func testCountBudgetNeverExceeded() {
        var lru = LRUBudget(maxCount: 5, maxBytes: .max)
        var live = Set<String>()
        for i in 0..<100 {
            live.insert("k\(i)")
            for e in lru.insert("k\(i)", cost: 1) { live.remove(e) }
            XCTAssertLessThanOrEqual(lru.count, 5)
            XCTAssertEqual(live.count, lru.count)
        }
    }

    func testByteBudgetAndRecency() {
        var lru = LRUBudget(maxCount: 100, maxBytes: 30)
        _ = lru.insert("a", cost: 10)
        _ = lru.insert("b", cost: 10)
        _ = lru.insert("c", cost: 10)
        lru.touch("a")
        let ev = lru.insert("d", cost: 10)
        XCTAssertEqual(ev, ["b"])
        XCTAssertLessThanOrEqual(lru.totalBytes, 30)
    }

    func testOversizedNewestKept() {
        var lru = LRUBudget(maxCount: 10, maxBytes: 5)
        _ = lru.insert("a", cost: 3)
        let ev = lru.insert("big", cost: 50)
        XCTAssertEqual(ev, ["a"])
        XCTAssertEqual(lru.count, 1)
    }
}
