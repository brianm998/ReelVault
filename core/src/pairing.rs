// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Shared one-time device-pairing state.
//!
//! A 6-digit code is minted either by a desktop/loopback client (the
//! `StartPairing` gRPC RPC) or by a remote device asking to pair (the media
//! server's `POST /pair/start`). Both write the *same* pending-code cell so the
//! redeem step (`POST /pair`) validates against it regardless of who generated
//! it. The code is also surfaced for headless admins via the daemon log and a
//! `pairing.txt` file in the data dir.

use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use tokio::sync::Mutex;

/// How long a freshly-issued code is accepted.
pub const CODE_TTL: Duration = Duration::from_secs(300);

/// A pending one-time pairing code (set when issued, consumed by `POST /pair`).
#[derive(Clone)]
pub struct PendingPairing {
    pub code: String,
    pub expires: Instant,
    /// Wrong PINs submitted against this code; it is invalidated by wrong PIN number `MAX_ATTEMPTS + 1` (the 6th).
    pub failed_attempts: u32,
}

/// Wrong guesses tolerated; the next (6th) wrong one discards the pending code.
pub const MAX_ATTEMPTS: u32 = 5;

/// Longest `device_name` we keep (characters).
pub const MAX_DEVICE_NAME: usize = 64;

/// Trim, strip control characters and cap a client-supplied device name.
pub fn sanitize_device_name(raw: Option<&str>) -> Option<String> {
    let n: String = raw?
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_DEVICE_NAME)
        .collect();
    let n = n.trim().to_string();
    if n.is_empty() {
        None
    } else {
        Some(n)
    }
}

/// Constant-time equality for the PIN comparison.
pub fn ct_eq(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    let mut diff = (a.len() ^ b.len()) as u8;
    for i in 0..a.len().max(b.len()) {
        diff |= a.get(i).copied().unwrap_or(0) ^ b.get(i).copied().unwrap_or(0);
    }
    diff == 0
}

/// Result of trying to redeem a PIN.
#[derive(Debug, PartialEq, Eq)]
pub enum Redeem {
    Ok,
    /// Wrong PIN; the code stays pending (`remaining` attempts left).
    Wrong { remaining: u32 },
    /// Too many wrong PINs: the code was just discarded.
    Invalidated,
    Expired,
    NoCode,
}

/// Try to redeem `entered` against the pending code (single-use on success).
pub async fn redeem(state: &PairingState, entered: &str) -> Redeem {
    let mut p = state.lock().await;
    let Some(pp) = p.as_mut() else {
        return Redeem::NoCode;
    };
    if pp.expires <= Instant::now() {
        *p = None;
        return Redeem::Expired;
    }
    if ct_eq(&pp.code, entered) {
        *p = None;
        return Redeem::Ok;
    }
    pp.failed_attempts += 1;
    if pp.failed_attempts > MAX_ATTEMPTS {
        *p = None;
        return Redeem::Invalidated;
    }
    Redeem::Wrong {
        remaining: MAX_ATTEMPTS - pp.failed_attempts,
    }
}

/// Per-client sliding-window rate limiter (in memory).
pub struct RateLimiter {
    max: usize,
    window: Duration,
    hits: std::sync::Mutex<std::collections::HashMap<String, Vec<Instant>>>,
}

impl RateLimiter {
    pub fn new(max: usize, window: Duration) -> Self {
        Self {
            max,
            window,
            hits: std::sync::Mutex::new(std::collections::HashMap::new()),
        }
    }

    /// Record a hit for `key`; false if it exceeds the limit.
    pub fn allow(&self, key: &str) -> bool {
        let now = Instant::now();
        let mut m = self.hits.lock().unwrap_or_else(|e| e.into_inner());
        if m.len() > 4096 {
            let w = self.window;
            m.retain(|_, v| v.last().is_some_and(|t| now.duration_since(*t) < w));
        }
        let v = m.entry(key.to_string()).or_default();
        v.retain(|t| now.duration_since(*t) < self.window);
        if v.len() >= self.max {
            return false;
        }
        v.push(now);
        true
    }
}

/// Shared pending-code cell, cloned (Arc) into both the gRPC service and the
/// media server so a code minted on one path is redeemable on the other.
pub type PairingState = Arc<Mutex<Option<PendingPairing>>>;

/// A fresh empty pairing cell.
pub fn new_state() -> PairingState {
    Arc::new(Mutex::new(None))
}

/// A 6-digit code derived from random UUID bytes (no extra RNG dependency).
pub fn gen_code() -> String {
    let b = uuid::Uuid::new_v4().into_bytes();
    let n = u32::from_le_bytes([b[0], b[1], b[2], b[3]]) % 1_000_000;
    format!("{n:06}")
}

/// Mint a new code (replacing any prior one), store it with a 5-minute TTL,
/// surface it for headless admins (daemon log + `<data_dir>/pairing.txt`), and
/// return `(code, expires_at_unix_ms)` for display in a client.
pub async fn issue_code(state: &PairingState, data_dir: &Path) -> (String, i64) {
    issue_code_inner(state, data_dir, true).await.expect("replace always issues")
}

/// Like [`issue_code`] but leaves a still-valid pending code untouched
/// (returns `None`). Used by the unauthenticated `POST /pair/start` so a LAN
/// peer can't keep replacing the code the owner is about to type.
pub async fn issue_code_if_none(state: &PairingState, data_dir: &Path) -> Option<(String, i64)> {
    issue_code_inner(state, data_dir, false).await
}

async fn issue_code_inner(
    state: &PairingState,
    data_dir: &Path,
    replace: bool,
) -> Option<(String, i64)> {
    let code = gen_code();
    {
        let mut p = state.lock().await;
        if !replace {
            if let Some(pp) = p.as_ref() {
                if pp.expires > Instant::now() {
                    return None;
                }
            }
        }
        *p = Some(PendingPairing {
            code: code.clone(),
            expires: Instant::now() + CODE_TTL,
            failed_attempts: 0,
        });
    }
    let expires_at_ms = (SystemTime::now() + CODE_TTL)
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0);
    tracing::info!("PAIRING CODE: {code} — enter on the device within 5 minutes");
    write_private(&data_dir.join("pairing.txt"), format!("{code}\n").as_bytes());
    Some((code, expires_at_ms))
}

/// Write `bytes` to `path` with owner-only (0600) permissions on Unix.
pub fn write_private(path: &Path, bytes: &[u8]) {
    use std::io::Write;
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    if let Ok(mut f) = opts.open(path) {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = f.set_permissions(std::fs::Permissions::from_mode(0o600));
        }
        let _ = f.write_all(bytes);
    }
}
