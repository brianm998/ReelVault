# 0967 — Replace chrono with `time`

chrono is unmaintained (RUSTSEC-2026-0543). We chose `time` 0.3 over `jiff`: `time` was already in Cargo.lock (no new crate), rusqlite interoperates with it, and our needs are tiny (now as Unix ms, RFC3339/naive parsing, one ISO format). Shared helpers (`now_ms`, `unix_ms`, `parse_naive_utc_ms`) live in db.rs. The DB stores i64 millis, so no schema change.
