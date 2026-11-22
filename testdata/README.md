# Shared test fixtures

JSON fixtures loaded by **both** client test suites, so the Swift kit
(`kit/Tests/ReelVaultKitTests/FixtureTests.swift`) and the Kotlin shared
library (`shared/src/test/kotlin/com/reelvault/FixtureTest.kt`) must agree
on the same inputs and outputs. To change shared behaviour, edit the
fixture and make both suites pass.

| File | Covers |
|------|--------|
| `smart_collection_filters.json` | smart-collection `filter_json` parsing and round trips |
| `metadata_wire.json` | metadata-filter wire encoding (0x1F join, 0x1E negate) and derived attribute filters |
| `local_filter.json` | client-side `matchesLocalFilters` (mirrors the daemon's `build_filter_clauses`) |
| `tag_rewrite.json` | smart-collection tag-id rewrite used by catalog sync |

Case names are stable identifiers; each Swift test method refers to a case by name.
Missing `expected` fields in `smart_collection_filters.json` take the file's `defaults`.
