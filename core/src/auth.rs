// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Bearer-token authorization for the remote (LAN) surface.
//!
//! Remote clients pair once (over the HTTPS media server) and receive a
//! long-lived bearer token; the daemon stores only its SHA-256. Every LAN gRPC
//! call (via a tonic interceptor) and every protected media request carries the
//! token as `Authorization: Bearer <token>` and is checked here. The loopback
//! gRPC bind is never wrapped, so desktop clients are exempt.

use crate::db::Database;
use sha2::{Digest, Sha256};

/// SHA-256 (lowercase hex) of a bearer token — what we persist and compare.
pub fn token_hash(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

/// True if an `Authorization` header value (`"Bearer <token>"`) belongs to a
/// currently-paired device.
pub fn is_authorized(db: &Database, authorization: Option<&str>) -> bool {
    let token = match authorization.and_then(|s| s.strip_prefix("Bearer ")) {
        Some(t) if !t.is_empty() => t,
        _ => return false,
    };
    db.is_paired_token(&token_hash(token)).unwrap_or(false)
}
