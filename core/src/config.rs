// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

use crate::db::Database;
use crate::error::{Result, VideoRoomError};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub proxy_threshold_scale: i32,
    pub thumbnail_cache_path: PathBuf,
    pub max_concurrent_jobs: i32,
    pub enable_auto_tagging: bool,
    pub external_editors: Vec<ExternalEditor>,
    /// Maximum number of concurrent ffmpeg/ffprobe processes the server will
    /// run at once. Bounds disk/network bandwidth — important for SAN-backed
    /// libraries. 0 disables the throttle. Default = 4.
    pub max_concurrent_ffmpeg: i32,

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
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExternalEditor {
    pub id: String,
    pub name: String,
    pub executable_path: String,
    pub arguments: Vec<String>,
    pub platforms: Vec<String>,
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

        let editors_json = Self::get_config_value(&conn, "external_editors", "{}")?;
        let external_editors: Vec<ExternalEditor> = serde_json::from_str(&editors_json)
            .unwrap_or_else(|_| Vec::new());

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

        std::fs::create_dir_all(&thumbnail_cache_path)
            .map_err(|e| VideoRoomError::ConfigError(format!("Failed to create cache directory: {}", e)))?;

        Ok(Config {
            proxy_threshold_scale: proxy_threshold,
            thumbnail_cache_path,
            max_concurrent_jobs: max_jobs,
            enable_auto_tagging: auto_tagging,
            external_editors,
            max_concurrent_ffmpeg: max_ffmpeg,
            watch_enabled,
            watch_write_settle_ms,
            watch_poll_interval_ms,
        })
    }

    pub fn save(&self, db: &Database) -> Result<()> {
        let conn = db.get_connection()?;

        Self::set_config_value(&conn, "proxy_threshold_scale", &self.proxy_threshold_scale.to_string())?;
        Self::set_config_value(&conn, "max_concurrent_jobs", &self.max_concurrent_jobs.to_string())?;
        Self::set_config_value(&conn, "enable_auto_tagging", &self.enable_auto_tagging.to_string())?;
        Self::set_config_value(&conn, "max_concurrent_ffmpeg", &self.max_concurrent_ffmpeg.to_string())?;
        Self::set_config_value(&conn, "watch_enabled", &self.watch_enabled.to_string())?;
        Self::set_config_value(&conn, "watch_write_settle_ms", &self.watch_write_settle_ms.to_string())?;
        Self::set_config_value(&conn, "watch_poll_interval_ms", &self.watch_poll_interval_ms.to_string())?;

        if let Ok(editors_json) = serde_json::to_string(&self.external_editors) {
            Self::set_config_value(&conn, "external_editors", &editors_json)?;
        }

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
            Err(e) => Err(VideoRoomError::DatabaseError(e.to_string())),
        }
    }

    fn set_config_value(conn: &rusqlite::Connection, key: &str, value: &str) -> Result<()> {
        conn.execute(
            "INSERT INTO config (key, value) VALUES (?, ?)
             ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = CURRENT_TIMESTAMP",
            rusqlite::params![key, value, value],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
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
            external_editors: Vec::new(),
            max_concurrent_ffmpeg: crate::concurrency::default_max_concurrent_ffmpeg() as i32,
            watch_enabled: true,
            watch_write_settle_ms: 5000,
            watch_poll_interval_ms: 30000,
        }
    }

    fn default_cache_path() -> Result<PathBuf> {
        let cache_dir = if cfg!(target_os = "macos") {
            dirs::home_dir()
                .ok_or_else(|| VideoRoomError::ConfigError("Could not find home directory".to_string()))?
                .join("Library")
                .join("Caches")
                .join("VideoRoom")
        } else if cfg!(target_os = "windows") {
            dirs::cache_dir()
                .ok_or_else(|| VideoRoomError::ConfigError("Could not find cache directory".to_string()))?
                .join("VideoRoom")
        } else {
            dirs::cache_dir()
                .ok_or_else(|| VideoRoomError::ConfigError("Could not find cache directory".to_string()))?
                .join("videoroom")
        };

        std::fs::create_dir_all(&cache_dir)
            .map_err(|e| VideoRoomError::ConfigError(format!("Failed to create cache directory: {}", e)))?;

        Ok(cache_dir)
    }
}
