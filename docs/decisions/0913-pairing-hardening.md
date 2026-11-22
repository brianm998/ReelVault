# 0913: Pairing hardening

Decision: a pending code is tolerates 5 wrong attempts and is invalidated by the 6th; `/pair`, `/pair/start` and `/pair/request` share a 20-requests-per-minute per-IP limiter; unauthenticated `/pair/start` only mints a code when none is pending (desktop `StartPairing` over gRPC still replaces); paired token hashes are cached in memory and `last_seen` is flushed periodically.

Why: any LAN peer could mint and brute-force a 6-digit code for a permanent bearer token, and every request wrote to SQLite, so a busy database rejected paired devices.
