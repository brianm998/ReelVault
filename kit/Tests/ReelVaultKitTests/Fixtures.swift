// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import XCTest

/// Loads the JSON fixtures in `<repo>/testdata`, shared with the Kotlin suite.
enum Fixtures {
    static let dir: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("testdata")

    static func load(_ file: String) throws -> [String: Any] {
        let data = try Data(contentsOf: dir.appendingPathComponent(file))
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    static func cases(_ file: String, _ key: String) throws -> [[String: Any]] {
        try XCTUnwrap(try load(file)[key] as? [[String: Any]])
    }

    static func named(_ file: String, _ key: String, _ name: String) throws -> [String: Any] {
        try XCTUnwrap(try cases(file, key).first { $0["name"] as? String == name },
                      "fixture \(file)/\(key) has no case \(name)")
    }

    /// Order-independent canonical text with every number as a Double, so the
    /// Swift and Kotlin sides can compare trees without int/double drift.
    static func canonical(_ v: Any) -> String {
        if let n = v as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
            return String(n.doubleValue)
        }
        if let s = v as? String { return "\"" + s + "\"" }
        if let a = v as? [Any] { return "[" + a.map(canonical).joined(separator: ",") + "]" }
        if let d = v as? [String: Any] {
            return "{" + d.keys.sorted().map { "\"\($0)\":" + canonical(d[$0]!) }.joined(separator: ",") + "}"
        }
        return "null"
    }
}
