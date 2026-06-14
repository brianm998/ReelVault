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
    let code = gen_code();
    {
        let mut p = state.lock().await;
        *p = Some(PendingPairing {
            code: code.clone(),
            expires: Instant::now() + CODE_TTL,
        });
    }
    let expires_at_ms = (SystemTime::now() + CODE_TTL)
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0);
    tracing::info!("PAIRING CODE: {code} — enter on the device within 5 minutes");
    let _ = std::fs::write(data_dir.join("pairing.txt"), format!("{code}\n"));
    (code, expires_at_ms)
}
