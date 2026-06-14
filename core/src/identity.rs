// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Self-signed TLS identity for the LAN-facing gRPC + media listeners.
//!
//! When `--remote` is set the daemon presents a self-signed certificate to LAN
//! clients. There is no CA and no hostname trust chain: the iOS client pins the
//! certificate by its SHA-256 fingerprint, which the daemon advertises over
//! mDNS. The cert+key are generated once and persisted under the per-OS data
//! directory so the fingerprint is stable across restarts (clients don't have
//! to re-pin on every launch).

use anyhow::{Context, Result};
use base64::Engine as _;
use sha2::{Digest, Sha256};
use std::fs;
use std::path::Path;

/// A persisted self-signed certificate and its pin.
pub struct Identity {
    /// PEM-encoded certificate (what the TLS server presents).
    pub cert_pem: String,
    /// PEM-encoded private key.
    pub key_pem: String,
    /// Lowercase-hex SHA-256 of the certificate DER — the value the client pins
    /// (advertised in the mDNS TXT record).
    pub fingerprint_hex: String,
}

/// Load the persisted identity from `<data_dir>/identity/`, or generate and
/// persist a new one on first run. `sans` are the subject-alternative names
/// baked into a freshly-generated cert (hostname, `localhost`, the LAN IP, …);
/// they're advisory only — the client pins by fingerprint, not by name, so a
/// SAN/IP mismatch is non-fatal.
pub fn load_or_create(data_dir: &Path, sans: &[String]) -> Result<Identity> {
    let dir = data_dir.join("identity");
    let cert_path = dir.join("cert.pem");
    let key_path = dir.join("key.pem");
    let fp_path = dir.join("fingerprint.txt");

    if let (Ok(cert_pem), Ok(key_pem), Ok(fp)) = (
        fs::read_to_string(&cert_path),
        fs::read_to_string(&key_path),
        fs::read_to_string(&fp_path),
    ) {
        let fp = fp.trim().to_string();
        if fp.len() == 64 && fp.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Ok(Identity {
                cert_pem,
                key_pem,
                fingerprint_hex: fp,
            });
        }
        // Otherwise fall through and regenerate.
    }

    fs::create_dir_all(&dir).with_context(|| format!("create {}", dir.display()))?;
    let (cert_pem, key_pem, fingerprint_hex) = generate(sans)?;
    fs::write(&cert_path, &cert_pem)?;
    fs::write(&key_path, &key_pem)?;
    fs::write(&fp_path, &fingerprint_hex)?;
    restrict_key_permissions(&key_path);
    Ok(Identity {
        cert_pem,
        key_pem,
        fingerprint_hex,
    })
}

/// Generate a fresh self-signed cert+key and its SHA-256 fingerprint.
fn generate(sans: &[String]) -> Result<(String, String, String)> {
    let names: Vec<String> = if sans.is_empty() {
        vec!["localhost".to_string()]
    } else {
        sans.to_vec()
    };
    let cert =
        rcgen::generate_simple_self_signed(names).context("generate self-signed certificate")?;
    // Serialize the DER exactly once: rcgen re-signs on every serialize call
    // (ECDSA uses a random nonce), so a separate serialize_pem()/serialize_der()
    // would yield two different certificates. We fingerprint this DER and derive
    // the PEM from the same bytes, guaranteeing the presented cert matches the
    // advertised fingerprint.
    let der = cert.serialize_der().context("serialize certificate DER")?;
    let fingerprint_hex = hex::encode(Sha256::digest(&der));
    let cert_pem = der_to_pem("CERTIFICATE", &der);
    let key_pem = cert.serialize_private_key_pem();
    Ok((cert_pem, key_pem, fingerprint_hex))
}

/// Wrap DER bytes as a standard 64-column PEM block.
fn der_to_pem(label: &str, der: &[u8]) -> String {
    let b64 = base64::engine::general_purpose::STANDARD.encode(der);
    let mut out = format!("-----BEGIN {label}-----\n");
    for chunk in b64.as_bytes().chunks(64) {
        out.push_str(std::str::from_utf8(chunk).unwrap_or(""));
        out.push('\n');
    }
    out.push_str(&format!("-----END {label}-----\n"));
    out
}

#[cfg(unix)]
fn restrict_key_permissions(key_path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    if let Ok(meta) = fs::metadata(key_path) {
        let mut perms = meta.permissions();
        perms.set_mode(0o600);
        let _ = fs::set_permissions(key_path, perms);
    }
}

#[cfg(not(unix))]
fn restrict_key_permissions(_key_path: &Path) {}
