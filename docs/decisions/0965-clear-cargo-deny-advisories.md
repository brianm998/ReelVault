# 0965: clear cargo-deny advisories

- tonic 0.12 (not later): smallest jump onto hyper 1 / h2 0.4 / tokio-rustls 0.26, removing rustls 0.22, webpki 0.102 and h2 0.3.
- rusqlite 0.41: first release bundling patched SQLite (>= 3.54.0, RUSTSEC-2026-0459).
- chrono is not migrated; RUSTSEC-2026-0543 (unmaintained) is ignored with a reason, migration to jiff/time is a follow-up.
- rustls-pemfile (unmaintained, transitive via tonic and axum-server) is not ignored: `unmaintained = "workspace"` limits unmaintained notices to direct dependencies.
- No vulnerability advisory is ignored.
