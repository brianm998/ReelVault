// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

// Mirrors Kotlin's `rewriteSmartFilterTagIds` (shared/); both are driven by
// `testdata/tag_rewrite.json`.

/// Rewrites tag UUIDs in a smart-collection filter_json from source-catalog IDs to
/// destination-catalog IDs, matched by tag name; unmatched tags are dropped.
/// Returns the input unchanged if blank, unparseable, or without `tagIds`.
func rewriteSmartFilterTagIds(
    _ filterJson: String,
    srcTags: [String: String],
    dstTags: [String: String]
) -> String {
    guard !filterJson.isEmpty,
          let data = filterJson.data(using: .utf8),
          var dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let tagIds = dict["tagIds"] as? [String]
    else { return filterJson }

    // Build name → dst-id lookup so we can resolve each source UUID to a name,
    // then find the matching UUID on the destination side.
    let nameToDst = Dictionary(uniqueKeysWithValues: dstTags.map { ($0.value, $0.key) })

    let remapped: [String] = tagIds.compactMap { srcId in
        guard let name = srcTags[srcId],
              let dstId = nameToDst[name] else { return nil }
        return dstId
    }
    dict["tagIds"] = remapped

    guard let out = try? JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys]),
          let str = String(data: out, encoding: .utf8)
    else { return filterJson }
    return str
}
