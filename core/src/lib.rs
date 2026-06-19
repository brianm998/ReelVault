// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

pub mod db;
pub mod metadata;
pub mod thumbnails;
pub mod search;
pub mod indexing;
pub mod auth;
pub mod config;
pub mod discovery;
pub mod error;
pub mod identity;
pub mod pairing;
pub mod media_server;
pub mod service;
pub mod grouping;
pub mod concurrency;
pub mod ffmpeg;
pub mod media_backend;
pub mod watcher;
pub mod imagehash;
pub mod proxies;
pub mod post_index;
pub mod path_templates;
pub mod camera_names;
pub mod full_resolution;
pub mod sensor_cache;
pub mod xmp;
pub mod quicktime;
pub mod gpmf;
pub mod metadata_keys;

/// Platform-agnostic in-process embed logic (loopback gRPC boot, ingest, prune),
/// shared by the iOS C-ABI surface and the Android JNI surface. Compiled only for
/// the mobile targets; the desktop build never sees it.
#[cfg(any(target_os = "ios", target_os = "android"))]
mod embed;

/// C-ABI entry point that boots the core in-process inside the iOS app
/// (docs/IOS_CORE_PORT.md §7.2). Compiled only for iOS; the desktop build never
/// sees it, so there is zero behavior change off-device.
#[cfg(target_os = "ios")]
pub mod ios;

/// JNI entry point that boots the core in-process inside the Android app — the
/// Android mirror of [`ios`], delegating to [`embed`]. Compiled only for Android.
#[cfg(target_os = "android")]
pub mod android;

pub use error::Result;

/// Install the process-global rustls [`CryptoProvider`] exactly once.
///
/// rustls 0.23 ends up with *both* crypto providers compiled into our dependency
/// tree — `aws-lc-rs` (via `axum-server` and `reqwest`) and `ring` — so rustls
/// can't select one from crate features and **panics** the first time anything
/// builds a `ServerConfig`/`ClientConfig`: the `axum-server` HTTPS media listener
/// ([`media_server`]) and the `reqwest` Wikidata sensor fetch ([`sensor_cache`]).
/// Pick `aws-lc-rs` (rustls's own default) explicitly, before any TLS work.
///
/// Idempotent and thread-safe, so every entry point — the daemon, the CLI, and
/// the iOS in-process embed (`ios`) — can call it unconditionally.
///
/// [`CryptoProvider`]: rustls::crypto::CryptoProvider
pub fn install_crypto_provider() {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        // `install_default` errors only when a provider is already installed,
        // which the `Once` prevents — ignore the result so this never panics.
        let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    });
}
