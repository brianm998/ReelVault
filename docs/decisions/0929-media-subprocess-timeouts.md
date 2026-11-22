# 0929: Media subprocess timeouts

Every media subprocess goes through `core/src/ffmpeg.rs` (`output_timeout`/`status_timeout`).
Deadlines: probe 60 s, single frame 120 s, remux 30 min, loudness 30 min, HLS/other transcodes 6 h.
The proxy encode has its frame count, so its deadline scales (`transcode_deadline`: 0.5 s/frame,
floor 10 min, cap 6 h). Other sites lack the duration and use generous constants that still bound a hang.
Remux calls use `output_timeout` so ffmpeg's stderr stays in the error message. Expiry returns an `ErrorKind::TimedOut` error that
callers already treat as an ordinary failure (so the watcher's failed-file map records it).
The proxy encode (streamed progress) uses a watchdog thread that kills the child instead.
