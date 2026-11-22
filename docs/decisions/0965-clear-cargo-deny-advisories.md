# 0965: clear cargo-deny advisories

The rustls-pemfile ignore is an owner-approved exception to the acceptance criterion that allowed only the chrono ignore; the chrono ignore was dropped because #984 removed chrono.

- tonic 0.12 (not later): smallest jump onto hyper 1 / h2 0.4 / tokio-rustls 0.26, removing rustls 0.22, webpki 0.102 and h2 0.3.
- rusqlite 0.41: first release bundling patched SQLite (>= 3.54.0, RUSTSEC-2026-0459).
- rustls-pemfile (unmaintained, transitive via tonic and axum-server) is ignored by id (RUSTSEC-2025-0134), owner-approved 2026-11-22; remove when tonic and axum-server stop depending on it. It is the only ignore. No `unmaintained = "workspace"` scope setting: it would hide future unmaintained transitive crates.
- No vulnerability advisory is ignored.
