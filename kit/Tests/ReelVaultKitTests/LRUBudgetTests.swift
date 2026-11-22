// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import XCTest
#if canImport(AppKit)
import AppKit
#else
import UIKit
#endif
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

    func testProtectedKeysNotEvicted() {
        var lru = LRUBudget(maxCount: 2, maxBytes: .max)
        _ = lru.insert("a", cost: 1)
        _ = lru.insert("b", cost: 1)
        XCTAssertEqual(lru.insert("c", cost: 1, protecting: ["a"]), ["b"])
        XCTAssertTrue(lru.contains("a"))
        XCTAssertFalse(lru.contains("b"))
    }
}

/// Cache-level checks: the published dictionaries stay within budget and
/// evicted keys leave both the dictionary and the LRU.
@MainActor
final class GridCacheBudgetTests: XCTestCase {
    private func image() -> PlatformImage {
        #if canImport(AppKit)
        let img = NSImage(size: NSSize(width: 2, height: 2))
        img.lockFocus(); NSColor.red.setFill(); NSRect(x: 0, y: 0, width: 2, height: 2).fill(); img.unlockFocus()
        return img
        #else
        return UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2)).image { c in
            UIColor.red.setFill(); c.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
        }
        #endif
    }

    private func makeVM(count: Int = 5, scrub: Int = 3) -> GridViewModel {
        let vm = GridViewModel()
        vm.configureCacheBudgetsForTesting(
            thumbnails: LRUBudget(maxCount: count, maxBytes: .max),
            scrub: LRUBudget(maxCount: scrub, maxBytes: .max))
        return vm
    }

    func testThumbnailsDictionaryStaysInBudget() {
        let vm = makeVM()
        for i in 0..<50 {
            vm.storeThumbnail(image(), videoId: "v\(i)")
            XCTAssertLessThanOrEqual(vm.thumbnails.count, 5)
            XCTAssertEqual(vm.thumbnails.count, vm.thumbnailLRUCount)
        }
        XCTAssertNil(vm.thumbnails["v0"])
        XCTAssertNotNil(vm.thumbnails["v49"])
    }

    func testScrubFramesDictionaryStaysInBudget() {
        let vm = makeVM()
        for i in 0..<30 {
            vm.storeScrubFrames([image(), nil], videoId: "v\(i)")
            XCTAssertLessThanOrEqual(vm.scrubFrames.count, 3)
            XCTAssertEqual(vm.scrubFrames.count, vm.scrubLRUCount)
        }
        XCTAssertNil(vm.scrubFrames["v0"])
    }

    func testVisibleCardNotEvictedAndEvictableAfterDisappear() {
        let vm = makeVM()
        vm.storeThumbnail(image(), videoId: "visible")
        vm.cardAppeared(videoId: "visible")
        for i in 0..<50 { vm.storeThumbnail(image(), videoId: "v\(i)") }
        XCTAssertNotNil(vm.thumbnails["visible"])
        XCTAssertLessThanOrEqual(vm.thumbnails.count, 6)
        vm.cardDisappeared(videoId: "visible")
        for i in 0..<10 { vm.storeThumbnail(image(), videoId: "w\(i)") }
        XCTAssertNil(vm.thumbnails["visible"])
        XCTAssertLessThanOrEqual(vm.thumbnails.count, 5)
    }
}
