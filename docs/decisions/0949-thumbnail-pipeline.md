# 0949: Thumbnail pipeline

- Extract one frame at up to 800 px (never upscaled); it is the large still verbatim. Small and medium are
  resized from it with the `image` crate (all platforms), so no double JPEG encode of large and no resize launches.
- Scrub frames: one ffmpeg launch with N fast-seeked inputs and N outputs; the normal path is 2 launches per new
  video (1 frame + 1 scrub), counting every launch: availability is checked by resolving ffmpeg on PATH, never by
  launching `ffmpeg -version`. The batch is all-or-nothing: if it fails nothing is
  published (no scrub frames), temp files are removed and the failure backoff is recorded. The budget is at most 2
  launches per new video, failure included; there is no per-frame salvage or retry.
  The backend trait gets `extract_frames` (default loops `extract_frame`).
- All cache writes go to a unique `{id}_..tmpPID-N.jpg` sibling and are renamed into place.
- Cleanup: `remove_library_location` (hard delete) deletes the video's files; `sweep_orphans` runs once at
  service start (max 5000 files, files younger than 10 min skipped). `prune_photos` soft-deletes the row
  and also removes its thumbnails (they regenerate if the row returns). The sweep treats
  soft-deleted rows as known, so it never deletes their files; files go only when the row is really removed.
- The sweep runs at service start and on `OpenCatalog` (desktop daemons start with no catalog). A catalog-specific
  `thumbnail_cache_path` is swept against that catalog's ids. The shared per-user default dir keeps a registry
  (`.catalogs`, paths of every catalog that used it, plus `.catalogs_since`); the sweep unions ids across all
  registered catalogs, only considers files modified after the registry began (earlier files cannot be attributed
  to a catalog), and skips entirely if any registered catalog can't be read (e.g. unmounted drive).
- The ProRes RAW poster-reuse scrub fallback copies through a temp file and renames, like every other publish.
- Rows are never hard-deleted by maintenance: soft-delete stays reversible (restoring keeps ratings, tags, notes). Only
  files of rows that are really gone are swept (bounded to 5000 files per run).
- Every cache dir, custom or default, is swept via the `.catalogs` registry (appended with O_APPEND, no read-modify-write),
  because the open catalog may not be the one whose config chose the directory.
- Library-root prefix matching uses the base's byte range (`path >= prefix AND path < prefix_end`, decision 0963), not LIKE; `video_ids_under` uses the same rule.
- Large is "at most 800 px wide, never upscaled": narrower sources give a narrower large still.
- Publishing uses `rename`, atomic on Linux/macOS; on Windows a rename over a file a reader has open can fail
  with a sharing violation, in which case generation fails for that attempt and is retried on the next request.
- Probes (ffprobe) are not counted among "ffmpeg launches".
- Cache version: each video gets `{id}_thumbv.ok` (content = `STILL_VERSION`, now "2") written after its stills.
  Stills without a current marker (made by the old pipeline: blurry upscaled large) are regenerated lazily: on
  the next index, or on the next small/medium/large GetThumbnail (old bytes are served if regeneration fails).
  No startup job. Bump `STILL_VERSION` to force regeneration after future output changes. Markers are swept and
  cleaned up like jpgs.
- Registry entries whose catalog file no longer exists (its directory does) are skipped in memory on each sweep (and
  duplicates deduped); the sweep never rewrites `.catalogs`, so it cannot race `register_catalog`'s O_APPEND writes.
  A missing directory (unmounted drive) still skips the sweep.
- The sweep logs one WARN naming the catalog when it is skipped because a registered catalog cannot be read.
- Known limits (owner-accepted): orphans older than the registry (`.catalogs_since`) are not swept.
- "Large still" criterion means: source width, capped at 800 px, never upscaled.
- Failure backoff: when a scrub batch fails (the CLI `extract_frames` returns the error; it is all-or-nothing) or a
  still regeneration fails, `{id}_scrubfail_thumbv.ok` / `{id}_stillfail_thumbv.ok` records
  `STILL_VERSION`, the time and the source's size+mtime. While it is younger than 24 h, for the same cache version
  and an unchanged source, no ffmpeg is launched (stills serve the stale bytes, scrub serves nothing). Success clears
  it; the `_at` variants take an injected clock for tests. Markers are swept/cleaned like other cache files.
- No dependency or Cargo.lock change is part of this diff.
- Backoff by cause (owner rules, 2026-11-22): backend unavailable (ffmpeg missing) writes no marker, so it retries once
  ffmpeg exists; timeout/I-O error: 10 min; decode failure (ffmpeg ran and failed, or exited 0 without writing every
  requested frame): 24 h. The marker stores the reason; an unknown reason counts as expired. There is no per-frame
  salvage or retry of a failed scrub batch. Indexing probes color once and passes it to stills and scrub.
- Risk (owner-accepted): one bad seek point drops all 10 scrub frames for that video until the backoff expires.
