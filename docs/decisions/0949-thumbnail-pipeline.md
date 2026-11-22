# 0949: Thumbnail pipeline

- Extract one frame at up to 800 px (never upscaled); it is the large still verbatim. Small and medium are
  resized from it with the `image` crate (all platforms), so no double JPEG encode of large and no resize launches.
- Scrub frames: one ffmpeg launch with N fast-seeked inputs and N outputs; on failure, retry frame by frame.
  The backend trait gets `extract_frames` (default loops `extract_frame`).
- All cache writes go to a unique `{id}_..tmpPID-N.jpg` sibling and are renamed into place.
- Cleanup: `remove_library_location` and `prune_photos` delete the video's files; `sweep_orphans` runs once at
  service start (max 5000 files, files younger than 10 min skipped). Soft-deleted rows keep their thumbnails.
- Probes (ffprobe) are not counted among "ffmpeg launches".
