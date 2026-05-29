// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

pub mod db;
pub mod metadata;
pub mod thumbnails;
pub mod search;
pub mod indexing;
pub mod config;
pub mod error;
pub mod service;
pub mod grouping;
pub mod concurrency;
pub mod watcher;
pub mod imagehash;
pub mod proxies;
pub mod post_index;
pub mod path_templates;
pub mod camera_names;
pub mod xmp;

pub use error::Result;
