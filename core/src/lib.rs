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
pub mod metadata_keys;

/// C-ABI entry point that boots the core in-process inside the iOS app
/// (docs/IOS_CORE_PORT.md §7.2). Compiled only for iOS; the desktop build never
/// sees it, so there is zero behavior change off-device.
#[cfg(target_os = "ios")]
pub mod ios;

pub use error::Result;
