# 0914: Live-row filtering in the daemon

- `REPRESENTATIVE_FILTER` now includes `deleted_at IS NULL` and picks the preferred/MIN-id member among live members only, so grid, facets, library counts and folders share one predicate.
- Queries that bypass it (map, search, candidates, copy detection, prune listing, keyword count) add `deleted_at IS NULL` directly.
- `delete_video` keeps the first `deleted_at` (sync tombstone); `get_location_by_path` still returns tombstoned rows so re-ingest can `undelete_video`.
