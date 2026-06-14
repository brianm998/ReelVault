// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

use crate::db::Database;
use crate::error::{Result, ReelVaultError};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub proxy_threshold_scale: i32,
    pub thumbnail_cache_path: PathBuf,
    pub max_concurrent_jobs: i32,
    pub enable_auto_tagging: bool,
    /// When true, the post-index pipeline applies the "timelapse" tag
    /// to any video whose resolution exceeds its camera's max in-camera
    /// video resolution (see [`crate::full_resolution::is_likely_timelapse`]).
    /// **On by default.** The heuristic is conservative (matches only
    /// non-standard resolutions known to be unreachable as in-camera
    /// video for that body), so false positives are rare and users
    /// usually want the auto-tag. Once a video is auto-tagged, removing
    /// the tag manually is permanent — the auto-tagger consults
    /// `auto_tag_history` and never re-applies to the same video.
    pub auto_tag_timelapses: bool,
    /// Maximum number of concurrent ffmpeg/ffprobe processes the server will
    /// run at once. Bounds disk/network bandwidth — important for SAN-backed
    /// libraries. 0 disables the throttle. Default = 4.
    pub max_concurrent_ffmpeg: i32,

    // --- Proxy playback / generation ---
    /// Largest video height (px) we'll attempt to play natively in-grid.
    /// Anything taller is shown with a "too large to play here" marker
    /// and the user is offered to make a proxy. Default = 2160 (4K UHD).
    pub max_native_playback_height: i32,
    /// Default height (px) used by `GenerateProxy` when the caller passes
    /// 0. Default = 720.
    pub proxy_target_height: i32,

    // --- Real-time library watching ---
    /// Master switch — when false, no watcher is started and the rest of the
    /// `watch_*` fields are ignored. Default = true.
    pub watch_enabled: bool,
    /// How long a file's `size` must remain unchanged before the watcher
    /// believes the writer is finished and triggers a single-file scan.
    /// Bumps prevent the watcher from indexing a half-written recording
    /// from OBS, ffmpeg, a network upload, etc. Default = 5000 ms.
    pub watch_write_settle_ms: i64,
    /// Fallback poll interval used when the OS-level watcher can't deliver
    /// events for a given path (most commonly NFS / SMB / SAN mounts where
    /// FSEvents/inotify see nothing). On those paths the watcher does a
    /// shallow `readdir` every `watch_poll_interval_ms` and folds any new
    /// or changed files into the same settle queue. Set to 0 to disable
    /// the poll fallback entirely. Default = 30 000 ms (30 s).
    pub watch_poll_interval_ms: i64,

    // --- Upload ---
    /// Directory where uploaded videos are written and then indexed. Set via
    /// `--import-dir` (persisted) or left None to refuse uploads. Should sit
    /// inside an enabled library location so the file is watched/served.
    pub import_dir: Option<PathBuf>,
}

/// A user-supplied override for the built-in camera-name mapping table
/// in [`crate::camera_names`]. `internal` is stored as the raw string
/// the user typed (so it round-trips back into the editor unchanged);
/// lookups normalise both sides via `camera_names::normalise`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CustomCameraName {
    pub internal: String,
    pub marketing: String,
}

/// A user-supplied display-name override for a lens. `raw` is the lens
/// string as stored in the catalog (the value extracted from
/// `exifEX:LensModel`), kept verbatim so it round-trips back into the
/// editor; `alias` is what the user wants shown instead. Lookups
/// normalise the `raw` side via `camera_names::normalise`. There is no
/// built-in lens table — these overrides are the only mapping layer.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CustomLensName {
    pub raw: String,
    pub alias: String,
}

impl Config {
    pub async fn load(db: &Database) -> Result<Self> {
        // When the daemon was started with `--no-catalog` there's no DB to
        // read settings from. Return an all-defaults Config so startup
        // succeeds — the client can still open a catalog later and any
        // per-catalog overrides will be picked up the first time we touch
        // SQL after OpenCatalog.
        let conn = match db.get_connection() {
            Ok(c) => c,
            Err(_) => return Ok(Self::defaults()),
        };

        // Load from database with defaults
        let proxy_threshold = Self::get_config_value(&conn, "proxy_threshold_scale", "4")?
            .parse::<i32>()
            .unwrap_or(4);

        let cache_path = Self::get_config_value(&conn, "thumbnail_cache_path", "")?;
        let thumbnail_cache_path = if cache_path.is_empty() {
            Self::default_cache_path()?
        } else {
            PathBuf::from(cache_path)
        };

        let max_jobs = Self::get_config_value(&conn, "max_concurrent_jobs", "4")?
            .parse::<i32>()
            .unwrap_or(4);

        let auto_tagging = Self::get_config_value(&conn, "enable_auto_tagging", "false")?
            .parse::<bool>()
            .unwrap_or(false);

        // Default to the number of CPU cores on this machine. Stored as a
        // string in the config table so we keep the existing get/set pattern.
        let cpu_default = crate::concurrency::default_max_concurrent_ffmpeg() as i32;
        let max_ffmpeg = Self::get_config_value(
            &conn,
            "max_concurrent_ffmpeg",
            &cpu_default.to_string(),
        )?
            .parse::<i32>()
            .unwrap_or(cpu_default);

        let max_native_playback_height = Self::get_config_value(&conn, "max_native_playback_height", "2160")?
            .parse::<i32>()
            .unwrap_or(2160)
            .max(0);
        let proxy_target_height = Self::get_config_value(&conn, "proxy_target_height", "720")?
            .parse::<i32>()
            .unwrap_or(720)
            .max(144);

        let watch_enabled = Self::get_config_value(&conn, "watch_enabled", "true")?
            .parse::<bool>()
            .unwrap_or(true);
        let watch_write_settle_ms = Self::get_config_value(&conn, "watch_write_settle_ms", "5000")?
            .parse::<i64>()
            .unwrap_or(5000)
            .max(0);
        let watch_poll_interval_ms = Self::get_config_value(&conn, "watch_poll_interval_ms", "30000")?
            .parse::<i64>()
            .unwrap_or(30000)
            .max(0);

        let auto_tag_timelapses = Self::get_config_value(&conn, "auto_tag_timelapses", "true")?
            .parse::<bool>()
            .unwrap_or(true);

        let import_dir_str = Self::get_config_value(&conn, "import_dir", "")?;

        std::fs::create_dir_all(&thumbnail_cache_path)
            .map_err(|e| ReelVaultError::ConfigError(format!("Failed to create cache directory: {}", e)))?;

        Ok(Config {
            proxy_threshold_scale: proxy_threshold,
            thumbnail_cache_path,
            max_concurrent_jobs: max_jobs,
            enable_auto_tagging: auto_tagging,
            max_concurrent_ffmpeg: max_ffmpeg,
            max_native_playback_height,
            proxy_target_height,
            watch_enabled,
            watch_write_settle_ms,
            watch_poll_interval_ms,
            auto_tag_timelapses,
            import_dir: if import_dir_str.is_empty() {
                None
            } else {
                Some(PathBuf::from(import_dir_str))
            },
        })
    }

    pub fn save(&self, db: &Database) -> Result<()> {
        let conn = db.get_connection()?;

        Self::set_config_value(&conn, "proxy_threshold_scale", &self.proxy_threshold_scale.to_string())?;
        Self::set_config_value(&conn, "max_concurrent_jobs", &self.max_concurrent_jobs.to_string())?;
        Self::set_config_value(&conn, "enable_auto_tagging", &self.enable_auto_tagging.to_string())?;
        Self::set_config_value(&conn, "max_concurrent_ffmpeg", &self.max_concurrent_ffmpeg.to_string())?;
        Self::set_config_value(&conn, "max_native_playback_height", &self.max_native_playback_height.to_string())?;
        Self::set_config_value(&conn, "proxy_target_height", &self.proxy_target_height.to_string())?;
        Self::set_config_value(&conn, "watch_enabled", &self.watch_enabled.to_string())?;
        Self::set_config_value(&conn, "watch_write_settle_ms", &self.watch_write_settle_ms.to_string())?;
        Self::set_config_value(&conn, "watch_poll_interval_ms", &self.watch_poll_interval_ms.to_string())?;
        Self::set_config_value(&conn, "auto_tag_timelapses", &self.auto_tag_timelapses.to_string())?;
        Self::set_config_value(
            &conn,
            "import_dir",
            &self.import_dir.as_ref().map(|p| p.to_string_lossy().to_string()).unwrap_or_default(),
        )?;

        Ok(())
    }

    fn get_config_value(conn: &rusqlite::Connection, key: &str, default: &str) -> Result<String> {
        match conn.query_row(
            "SELECT value FROM config WHERE key = ?",
            [key],
            |row| row.get::<_, String>(0),
        ) {
            Ok(value) => Ok(value),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(default.to_string()),
            Err(e) => Err(ReelVaultError::DatabaseError(e.to_string())),
        }
    }

    fn set_config_value(conn: &rusqlite::Connection, key: &str, value: &str) -> Result<()> {
        conn.execute(
            "INSERT INTO config (key, value) VALUES (?, ?)
             ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = CURRENT_TIMESTAMP",
            rusqlite::params![key, value, value],
        )
        .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;
        Ok(())
    }

    /// Build a Config with all-default values. Used when no catalog is open
    /// yet (e.g. server started with `--no-catalog`). The thumbnail cache
    /// path is platform-default and gets created on disk.
    fn defaults() -> Self {
        let cache = Self::default_cache_path().unwrap_or_else(|_| PathBuf::from("./cache"));
        Config {
            proxy_threshold_scale: 4,
            thumbnail_cache_path: cache,
            max_concurrent_jobs: 4,
            enable_auto_tagging: false,
            max_concurrent_ffmpeg: crate::concurrency::default_max_concurrent_ffmpeg() as i32,
            max_native_playback_height: 2160,
            proxy_target_height: 720,
            watch_enabled: true,
            watch_write_settle_ms: 5000,
            watch_poll_interval_ms: 30000,
            auto_tag_timelapses: true,
            import_dir: None,
        }
    }

    fn default_cache_path() -> Result<PathBuf> {
        let cache_dir = if cfg!(target_os = "macos") {
            dirs::home_dir()
                .ok_or_else(|| ReelVaultError::ConfigError("Could not find home directory".to_string()))?
                .join("Library")
                .join("Caches")
                .join("ReelVault")
        } else if cfg!(target_os = "windows") {
            dirs::cache_dir()
                .ok_or_else(|| ReelVaultError::ConfigError("Could not find cache directory".to_string()))?
                .join("ReelVault")
        } else {
            dirs::cache_dir()
                .ok_or_else(|| ReelVaultError::ConfigError("Could not find cache directory".to_string()))?
                .join("reelvault")
        };

        std::fs::create_dir_all(&cache_dir)
            .map_err(|e| ReelVaultError::ConfigError(format!("Failed to create cache directory: {}", e)))?;

        Ok(cache_dir)
    }
}
