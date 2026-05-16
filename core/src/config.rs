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
        let conn = db.get_connection()?;

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

        let editors_json = Self::get_config_value(&conn, "external_editors", "{}")?;
        let external_editors: Vec<ExternalEditor> = serde_json::from_str(&editors_json)
            .unwrap_or_else(|_| Vec::new());

        std::fs::create_dir_all(&thumbnail_cache_path)
            .map_err(|e| VideoRoomError::ConfigError(format!("Failed to create cache directory: {}", e)))?;

        Ok(Config {
            proxy_threshold_scale: proxy_threshold,
            thumbnail_cache_path,
            max_concurrent_jobs: max_jobs,
            enable_auto_tagging: auto_tagging,
            external_editors,
        })
    }

    pub fn save(&self, db: &Database) -> Result<()> {
        let conn = db.get_connection()?;

        Self::set_config_value(&conn, "proxy_threshold_scale", &self.proxy_threshold_scale.to_string())?;
        Self::set_config_value(&conn, "max_concurrent_jobs", &self.max_concurrent_jobs.to_string())?;
        Self::set_config_value(&conn, "enable_auto_tagging", &self.enable_auto_tagging.to_string())?;

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
