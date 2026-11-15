// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.sync

/**
 * Rewrites tag UUIDs in a smart-collection filter_json from source-catalog
 * IDs to destination-catalog IDs, matched by tag name.  Tags with no name
 * match on the destination are dropped silently.
 *
 * The filter_json format stores tag IDs in a JSON array keyed "tagIds":
 *   {"tagIds":["uuid-a","uuid-b"],"minRating":0,...}
 *
 * This function uses the same regex-based approach as [SmartCollectionFilters]
 * so the shared/ module needs no additional JSON library dependency.
 *
 * @param filterJson the JSON string from the source catalog
 * @param srcTags    map of tag-id → tag-name from the source catalog
 * @param dstTags    map of tag-id → tag-name from the destination catalog
 * @return           the rewritten JSON string (unchanged on blank input or
 *                   parse error)
 */
fun rewriteSmartFilterTagIds(
    filterJson: String,
    srcTags: Map<String, String>,
    dstTags: Map<String, String>,
): String {
    if (filterJson.isBlank()) return filterJson

    // Extract the raw "tagIds" array content, e.g. ["uuid-a","uuid-b"]
    val arrayContentRegex = Regex(""""tagIds"\s*:\s*\[([^\]]*)]""")
    val match = arrayContentRegex.find(filterJson) ?: return filterJson

    val arrayContent = match.groupValues[1]

    // Parse individual quoted UUID strings out of the array content.
    val uuidRegex = Regex(""""((?:[^"\\]|\\.)*)"""")
    val srcIds = uuidRegex.findAll(arrayContent).map { it.groupValues[1] }.toList()

    // Build name → dstId lookup.
    val nameToDst: Map<String, String> = dstTags.entries.associate { (id, name) -> name to id }

    // Remap: src-id → src-name → dst-id (drop if no dst match).
    val remapped = srcIds.mapNotNull { srcId ->
        val name = srcTags[srcId] ?: return@mapNotNull null
        nameToDst[name]
    }

    // Rebuild the JSON array string and splice it back into filterJson.
    val newArray = remapped.joinToString(",") { "\"$it\"" }
    return filterJson.replace(match.value, "\"tagIds\":[$newArray]")
}
