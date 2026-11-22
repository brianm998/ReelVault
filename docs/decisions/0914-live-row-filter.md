# 0914: Live-row filtering in the daemon

- `REPRESENTATIVE_FILTER` now includes `deleted_at IS NULL` and picks the preferred/MIN-id member among live members only, so grid, facets, library counts and folders share one predicate.
- Queries that bypass it (map, search, candidates, copy detection, prune listing, keyword count) add `deleted_at IS NULL` directly.
- `delete_video` keeps the first `deleted_at` (sync tombstone); `get_location_by_path` still returns tombstoned rows so re-ingest can `undelete_video`.

## Addenda

- Search (`search`, `advanced_search`, and their totals) and the grid stack-size
  count filter `deleted_at IS NULL`.
- Intended restore behaviour: a tombstoned `photos://` row reports not-indexed,
  so the Photos enumerator re-ingests it and clears `deleted_at` while the asset
  is still in the Photos library. A video deleted in ReelVault whose Photos asset
  remains will therefore come back on the next ingest pass; deleting it
  permanently requires deleting the Photos asset. Accepted as the cost of
  restoring assets from Recently Deleted.
