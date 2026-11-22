# 0970: Grid count memoised per filter; unused joins pruned

Status: accepted

At 100k videos the grid's COUNT cost 0.5-0.7 s per page request and each Library
Filter facet column 0.55-0.67 s, mostly from LEFT JOINs to `metadata` and
`video_user_marks` that SQLite keeps even when nothing references them.

Decision:
- `optional_joins` emits each join only when the WHERE / ORDER BY / select text
  references its alias (a false positive only keeps a join). Used by the grid
  page, the count and the facet queries.
- `list_videos_grouped` memoises `total_count` per `{FilterSpec:?}`, invalidated
  by a fingerprint (size + mtime of the catalog file and its `-wal`, plus path).
  The wire contract is unchanged; pages after the first reuse the total.
- Connections get `cache_size = -65536` (64 MB) and `mmap_size = 256 MB`; the
  grid page statement uses `prepare_cached`.
- Schema v3 drops `idx_videos_path` / `idx_videos_hash` (UNIQUE autoindexes
  already cover them).

Not done: computing one representative set per facet request. Each column is
filtered by the columns to its left, so the sets differ per column and cannot
be shared. `prepare_cached` is applied only to the grid page query, not the
other ~50 statements.

Also in schema v3: `idx_videos_rep(proxy_of, deleted_at, group_id, id)` covers
the representative test so counts scan a narrow index.

Measured (`cargo bench --bench catalog_queries`, 100k synthetic catalog, same
session and machine; "before" = the same bench run on develop 213f558 with only
`REPRESENTATIVE_FILTER` made `pub` so it compiles; criterion midpoints):
| | before (develop) | after |
|---|---|---|
| unfiltered count (develop SQL: joins on, old schema vs new SQL: pruned, idx_videos_rep) | 137 ms | 26 ms (19%) |
| grid page 2+ (count + page) | 276 ms | 46 ms |
| grid first page, count miss | 336 ms | 213 ms |
| 5-column facet request | 970 ms | 430 ms (2.3x faster) |
(The machine was noisy; the in-tree `count_unfiltered_with_joins` row runs the
old SQL on the new index and is not the baseline.)

Memory note: `cache_size = -65536` applies per pooled connection and the pool
keeps idle connections, so N idle connections may hold up to N x 64 MB. That is
fine for the desktop daemon; the embedded iOS/Android cores are not bounded by
it and should cap the pool or lower the cache if memory pressure appears.

The count memo is neither read nor written when the catalog fingerprint is
`None` (file can't be stat'ed), so a total never outlives writes.
