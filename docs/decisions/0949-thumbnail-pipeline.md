# 0949: Thumbnail pipeline

- Extract one frame at up to 800 px (never upscaled); it is the large still verbatim. Small and medium are
  resized from it with the `image` crate (all platforms), so no double JPEG encode of large and no resize launches.
- Scrub frames: one ffmpeg launch with N fast-seeked inputs and N outputs; on failure there is NO per-frame retry, so
  the bound of 2 launches per new video (1 frame + 1 scrub) holds on failure too; a failed run leaves the scrub
  frames absent (best-effort, regenerated on demand).
  The backend trait gets `extract_frames` (default loops `extract_frame`).
- All cache writes go to a unique `{id}_..tmpPID-N.jpg` sibling and are renamed into place.
- Cleanup: `remove_library_location` and `prune_photos` delete the video's files; `sweep_orphans` runs once at
  service start (max 5000 files, files younger than 10 min skipped). `prune_photos` soft-deletes the row (`deleted_at`) but
  deliberately removes its thumbnails too (the asset is gone); if a row is restored they regenerate on demand.
  The sweep treats soft-deleted rows as known, so it never deletes their files itself.
- Publishing uses `rename`, atomic on Linux/macOS; on Windows a rename over a file a reader has open can fail
  with a sharing violation, in which case generation fails for that attempt and is retried on the next request.
- Probes (ffprobe) are not counted among "ffmpeg launches".
