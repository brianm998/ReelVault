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

Measured (`cargo bench --bench catalog_queries`, 100k synthetic catalog):
| | before | after |
|---|---|---|
| grid page 2+ (count + page) | 212 ms | 69 ms |
| unfiltered count (same index, joins on vs off) | 91 ms | 34 ms |
| 5-column facet request | 1285 ms | 632 ms |
Before-index unfiltered count was ~150 ms (212 ms page-2 minus 60 ms page).
