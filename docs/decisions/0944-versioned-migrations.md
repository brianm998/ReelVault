# 0944: Hand-rolled user_version migrations

Chose a hand-rolled `PRAGMA user_version` loop in `core/src/db.rs` over `rusqlite_migration` (removed from Cargo.toml). v1 = baseline schema + idempotent column/index/trigger statements (duplicate-column tolerated for legacy catalogs); v2 = one-time backfills. Whole open is one transaction; any other error aborts the open. Newer-than-known versions are refused. Future changes (e.g. index work) add a version step.
