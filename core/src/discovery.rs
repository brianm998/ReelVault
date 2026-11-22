// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! mDNS / Bonjour advertisement of the daemon's remote surface.
//!
//! When `--remote` is set the daemon advertises `_reelvault._tcp` so LAN
//! clients (the iOS app) discover it without manual configuration. The TXT
//! record carries everything the client needs to connect and pin: the
//! certificate fingerprint, the gRPC + media ports, the LAN IP, the catalog
//! name, version, feature flags, and whether pairing is required.
//!
//! Pure Rust (`mdns-sd`) — one implementation across macOS, Linux and Windows.

use anyhow::{Context, Result};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use std::net::Ipv4Addr;

const SERVICE_TYPE: &str = "_reelvault._tcp.local.";

/// A live mDNS advertisement. Unregisters on drop.
pub struct Advertisement {
    daemon: ServiceDaemon,
    fullname: String,
}

#[allow(clippy::too_many_arguments)]
impl Advertisement {
    /// Register the `_reelvault._tcp` service on the LAN.
    pub fn start(
        name: &str,
        lan_ip: Ipv4Addr,
        grpc_port: u16,
        media_port: u16,
        fingerprint_hex: &str,
        catalog_name: &str,
        version: &str,
        auth: &str,
        features: &str,
    ) -> Result<Self> {
        let daemon = ServiceDaemon::new().context("create mDNS daemon")?;

        let instance = sanitize_instance(name);
        let host_name = format!("{instance}.local.");

        let mut props: HashMap<String, String> = HashMap::new();
        props.insert("fp".into(), fingerprint_hex.to_string());
        props.insert("grpc".into(), grpc_port.to_string());
        props.insert("media".into(), media_port.to_string());
        props.insert("ip".into(), lan_ip.to_string());
        props.insert("host".into(), lan_ip.to_string());
        props.insert("cat".into(), catalog_name.to_string());
        props.insert("ver".into(), version.to_string());
        props.insert("auth".into(), auth.to_string());
        props.insert("feat".into(), features.to_string());

        // mdns-sd's AsIpAddrs is implemented for &str (not Ipv4Addr directly).
        let ip_str = lan_ip.to_string();
        let info = ServiceInfo::new(
            SERVICE_TYPE,
            &instance,
            &host_name,
            ip_str.as_str(),
            grpc_port,
            props,
        )
        .context("build mDNS service info")?;

        let fullname = info.get_fullname().to_string();
        daemon.register(info).context("register mDNS service")?;
        Ok(Advertisement { daemon, fullname })
    }
}

impl Drop for Advertisement {
    fn drop(&mut self) {
        // Best-effort: tell peers we're going away.
        let _ = self.daemon.unregister(&self.fullname);
    }
}

/// mDNS instance names must avoid `.` (label separator); keep it tidy.
fn sanitize_instance(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| if c == '.' || c.is_whitespace() { '-' } else { c })
        .collect();
    let trimmed = cleaned.trim_matches('-');
    if trimmed.is_empty() {
        "ReelVault".to_string()
    } else {
        trimmed.to_string()
    }
}
