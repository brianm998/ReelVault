# 0949: Thumbnail pipeline

- Extract one frame at up to 800 px (never upscaled); it is the large still verbatim. Small and medium are
  resized from it with the `image` crate (all platforms), so no double JPEG encode of large and no resize launches.
- Scrub frames: one ffmpeg launch with N fast-seeked inputs and N outputs; the normal path is 2 launches per new
  video (1 frame + 1 scrub). If the batch fails nothing is published, temp files are removed and there is no retry:
  the bound of 2 launches holds on failure too, and the missing scrub frames regenerate on demand.
  The backend trait gets `extract_frames` (default loops `extract_frame`).
- All cache writes go to a unique `{id}_..tmpPID-N.jpg` sibling and are renamed into place.
- Cleanup: `remove_library_location` (hard delete) deletes the video's files; `sweep_orphans` runs once at
  service start (max 5000 files, files younger than 10 min skipped). `prune_photos` only soft-deletes the row
  (`deleted_at`) and deliberately KEEPS its thumbnails, so a restored row still has them. The sweep treats
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
- Library-root prefix matching uses `substr(path,1,length(?))=?` (exact, case-sensitive), not LIKE.
- The Cargo.lock change drops an unreferenced `rusqlite_migration` entry (unrelated cleanup).
- Large is "at most 800 px wide, never upscaled": narrower sources give a narrower large still.
- Publishing uses `rename`, atomic on Linux/macOS; on Windows a rename over a file a reader has open can fail
  with a sharing violation, in which case generation fails for that attempt and is retried on the next request.
- Probes (ffprobe) are not counted among "ffmpeg launches".
- Cache version: each video gets `{id}_thumbv.ok` (content = `STILL_VERSION`, now "2") written after its stills.
  Stills without a current marker (made by the old pipeline: blurry upscaled large) are regenerated lazily: on
  the next index, or on the next small/medium/large GetThumbnail (old bytes are served if regeneration fails).
  No startup job. Bump `STILL_VERSION` to force regeneration after future output changes. Markers are swept and
  cleaned up like jpgs.
- The sweep logs one WARN naming the catalog when it is skipped because a registered catalog cannot be read.
- Known limits (owner-accepted): orphans older than the registry (`.catalogs_since`) are not swept; `prune_photos`
  stays soft-delete only and keeps thumbnails, so "cleanup on prune" is deliberately only "cleanup on hard
  delete" (location removal); `remove_library_location` now matches paths case-sensitively (`substr`, not LIKE),
  so on a case-insensitive filesystem a differently-cased root string no longer matches stored paths (library
  paths are stored as the scanner emitted them, and the UI passes back the stored string, so normal use is
  unaffected; a mismatched case removes nothing rather than too much).
- "Large still" criterion means: source width, capped at 800 px, never upscaled.
