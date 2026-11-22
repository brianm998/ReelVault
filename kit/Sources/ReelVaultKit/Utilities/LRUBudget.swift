// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

/// Recency + budget bookkeeping for an image cache that lives in a published
/// dictionary. It stores no values: callers `touch` keys when a card shows
/// them, `insert` with a byte cost, and remove the returned keys from their
/// dictionary. Least-recently-touched entries go first, so off-screen cards
/// are evicted before the ones being scrolled over.
public struct LRUBudget: Sendable {
    public let maxCount: Int
    public let maxBytes: Int
    private var stamps: [String: UInt64] = [:]
    private var costs: [String: Int] = [:]
    private var clock: UInt64 = 0
    public private(set) var totalBytes = 0

    public init(maxCount: Int, maxBytes: Int) {
        self.maxCount = maxCount
        self.maxBytes = maxBytes
    }

    public var count: Int { costs.count }

    public mutating func touch(_ key: String) {
        guard stamps[key] != nil else { return }
        clock += 1
        stamps[key] = clock
    }

    /// Record `key` (replacing any prior cost) and return keys to evict. The
    /// just-inserted key and any key in `protecting` (cards currently on
    /// screen) are never evicted, so the budget may be exceeded only by the
    /// protected set.
    public mutating func insert(_ key: String, cost: Int, protecting: Set<String> = []) -> [String] {
        totalBytes -= costs[key] ?? 0
        costs[key] = cost
        totalBytes += cost
        clock += 1
        stamps[key] = clock
        guard costs.count > maxCount || totalBytes > maxBytes else { return [] }
        var evicted: [String] = []
        for (k, _) in stamps.sorted(by: { $0.value < $1.value }) {
            if costs.count <= maxCount && totalBytes <= maxBytes { break }
            if k == key || protecting.contains(k) { continue }
            totalBytes -= costs.removeValue(forKey: k) ?? 0
            stamps.removeValue(forKey: k)
            evicted.append(k)
        }
        return evicted
    }

    public func contains(_ key: String) -> Bool { costs[key] != nil }

    public mutating func removeAll() {
        stamps = [:]
        costs = [:]
        totalBytes = 0
    }
}

extension PlatformImage {
    /// Approximate decoded size in bytes (RGBA), from pixel dimensions. Uses the
    /// CGImage because `size` is in points (scaled on iOS) and is `.zero` for
    /// images wrapped by `fromCGImage` on macOS.
    var approxBytes: Int {
        #if canImport(AppKit)
        var rect = CGRect(origin: .zero, size: size)
        if let cg = cgImage(forProposedRect: &rect, context: nil, hints: nil) {
            return max(1, cg.width * cg.height * 4)
        }
        #else
        if let cg = cgImage { return max(1, cg.width * cg.height * 4) }
        #endif
        return max(1, Int(size.width * size.height * 4))
    }
}
