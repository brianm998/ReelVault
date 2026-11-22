# 0929: Media subprocess timeouts

Every media subprocess goes through `core/src/ffmpeg.rs` (`output_timeout`/`status_timeout`).
Deadlines: probe 60 s, single frame 120 s, remux 30 min, loudness 30 min, full transcode/proxy 6 h.
Constants rather than duration-scaled values, because most call sites lack the duration; they are
generous for long media and still bound a hang. Expiry returns an `ErrorKind::TimedOut` error that
callers already treat as an ordinary failure (so the watcher's failed-file map records it).
The proxy encode (streamed progress) uses a watchdog thread that kills the child instead.
