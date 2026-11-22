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

/// Outcome of a token check.
#[derive(Debug, PartialEq, Eq)]
pub enum AuthResult {
    Authorized,
    Unauthorized,
    /// The paired-device list couldn't be read (database busy/closed): the
    /// caller must answer UNAVAILABLE / 503, not "not paired".
    Unavailable,
}

/// Check an `Authorization` header value (`"Bearer <token>"`) against the
/// paired devices (in-memory; no SQLite write).
pub fn check(db: &Database, authorization: Option<&str>) -> AuthResult {
    let token = match authorization.and_then(|s| s.strip_prefix("Bearer ")) {
        Some(t) if !t.is_empty() => t,
        _ => return AuthResult::Unauthorized,
    };
    match db.is_paired_token(&token_hash(token)) {
        Ok(true) => AuthResult::Authorized,
        Ok(false) => AuthResult::Unauthorized,
        Err(e) => {
            tracing::warn!("auth: paired-device lookup failed: {e}");
            AuthResult::Unavailable
        }
    }
}

/// True if the header belongs to a currently-paired device.
pub fn is_authorized(db: &Database, authorization: Option<&str>) -> bool {
    check(db, authorization) == AuthResult::Authorized
}
