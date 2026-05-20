use crate::error::{Result, VideoRoomError};
use chrono::{DateTime, Utc};
use rusqlite::{params, Connection, OptionalExtension};
use std::path::{Path, PathBuf};
use uuid::Uuid;

pub struct Database {
    path: PathBuf,
}

impl Database {
    pub fn new(path: &Path) -> Result<Self> {
        Ok(Database {
            path: path.to_path_buf(),
        })
    }

    pub async fn initialize(&self) -> Result<()> {
        let conn = self.get_connection()?;

        // Set WAL mode for better concurrency - must be before execute_batch
        let _ = conn.pragma_update(None, "journal_mode", "WAL");

        // Set synchronous mode for safety
        let _ = conn.pragma_update(None, "synchronous", "NORMAL");

        // Enable foreign keys
        let _ = conn.pragma_update(None, "foreign_keys", "ON");

        // Create schema from SQL file embedded at compile time
        let schema = include_str!("../schema.sql");
        conn.execute_batch(schema)
            .map_err(|e| VideoRoomError::DatabaseError(format!("Failed to initialize schema: {}", e)))?;

        tracing::info!("Database schema initialized");

        Ok(())
    }

    pub fn get_connection(&self) -> Result<Connection> {
        Connection::open(&self.path)
            .map_err(|e| VideoRoomError::DatabaseError(format!("Failed to open database: {}", e)))
    }

    // VIDEO OPERATIONS

    pub fn add_video(
        &self,
        path: &str,
        filename: &str,
        volume_id: Option<&str>,
        hash: Option<&str>,
        file_size_bytes: Option<i64>,
    ) -> Result<String> {
        let conn = self.get_connection()?;
        let video_id = Uuid::new_v4().to_string();
        let now = Utc::now();

        conn.execute(
            "INSERT INTO videos (id, path, filename, volume_id, hash, file_size_bytes, indexed_at)
             VALUES (?, ?, ?, ?, ?, ?, ?)",
            params![
                &video_id,
                path,
                filename,
                volume_id,
                hash,
                file_size_bytes,
                now.timestamp_millis()
            ],
        )
        .map_err(|e| {
            if e.to_string().contains("UNIQUE constraint failed") {
                VideoRoomError::DuplicateEntry("Video already exists".to_string())
            } else {
                VideoRoomError::DatabaseError(e.to_string())
            }
        })?;

        Ok(video_id)
    }

    pub fn get_video(&self, video_id: &str) -> Result<Option<VideoRecord>> {
        let conn = self.get_connection()?;

        let result = conn
            .query_row(
                "SELECT id, path, filename, volume_id, hash, file_size_bytes, indexed_at, is_online
                 FROM videos WHERE id = ?",
                [video_id],
                |row| {
                    Ok(VideoRecord {
                        id: row.get(0)?,
                        path: row.get(1)?,
                        filename: row.get(2)?,
                        volume_id: row.get(3)?,
                        hash: row.get(4)?,
                        file_size_bytes: row.get(5)?,
                        indexed_at: row.get(6)?,
                        is_online: row.get(7)?,
                    })
                },
            )
            .optional()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(result)
    }

    pub fn get_video_by_path(&self, path: &str) -> Result<Option<VideoRecord>> {
        let conn = self.get_connection()?;

        let result = conn
            .query_row(
                "SELECT id, path, filename, volume_id, hash, file_size_bytes, indexed_at, is_online
                 FROM videos WHERE path = ?",
                [path],
                |row| {
                    Ok(VideoRecord {
                        id: row.get(0)?,
                        path: row.get(1)?,
                        filename: row.get(2)?,
                        volume_id: row.get(3)?,
                        hash: row.get(4)?,
                        file_size_bytes: row.get(5)?,
                        indexed_at: row.get(6)?,
                        is_online: row.get(7)?,
                    })
                },
            )
            .optional()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(result)
    }

    pub fn list_videos(&self, limit: i64, offset: i64) -> Result<(Vec<VideoRecord>, i64)> {
        self.list_videos_sorted(limit, offset, "indexed_at", false)
    }

    /// List videos with sorting by a specified field.
    /// Accepts: name|filename, indexed_at|date_added, duration, size|size_bytes,
    /// resolution|width|height, fps, codec|codec_video, bitrate, camera|camera_model
    pub fn list_videos_sorted(
        &self,
        limit: i64,
        offset: i64,
        sort_by: &str,
        ascending: bool,
    ) -> Result<(Vec<VideoRecord>, i64)> {
        let conn = self.get_connection()?;

        let total: i64 = conn
            .query_row("SELECT COUNT(*) FROM videos", [], |row| row.get(0))
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let direction = if ascending { "ASC" } else { "DESC" };
        // Whitelist sort columns to avoid SQL injection
        let order_by = match sort_by.to_lowercase().as_str() {
            "name" | "filename" => format!("v.filename {}", direction),
            "date_added" | "indexed_at" | "" => format!("v.indexed_at {}", direction),
            "duration" | "duration_ms" => format!("COALESCE(m.duration_ms, 0) {}", direction),
            "size" | "size_bytes" | "file_size_bytes" => format!("COALESCE(v.file_size_bytes, 0) {}", direction),
            "resolution" | "width" | "height" => {
                // Sort by total pixels
                format!("(COALESCE(m.width, 0) * COALESCE(m.height, 0)) {}", direction)
            }
            "fps" => format!("COALESCE(m.fps, 0) {}", direction),
            "codec" | "codec_video" => format!("COALESCE(m.codec_video, '') {}", direction),
            "bitrate" => format!("COALESCE(m.bitrate, 0) {}", direction),
            "camera" | "camera_model" => format!("COALESCE(m.camera_model, '') {}", direction),
            "creation_date" | "shot_date" => format!("COALESCE(m.creation_date, 0) {}", direction),
            _ => format!("v.filename {}", direction),
        };

        let sql = format!(
            "SELECT v.id, v.path, v.filename, v.volume_id, v.hash, v.file_size_bytes, v.indexed_at, v.is_online
             FROM videos v LEFT JOIN metadata m ON v.id = m.video_id
             ORDER BY {} LIMIT ? OFFSET ?",
            order_by
        );

        let mut stmt = conn
            .prepare(&sql)
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let videos = stmt
            .query_map(params![limit, offset], |row| {
                Ok(VideoRecord {
                    id: row.get(0)?,
                    path: row.get(1)?,
                    filename: row.get(2)?,
                    volume_id: row.get(3)?,
                    hash: row.get(4)?,
                    file_size_bytes: row.get(5)?,
                    indexed_at: row.get(6)?,
                    is_online: row.get(7)?,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok((videos, total))
    }

    pub fn delete_video(&self, video_id: &str) -> Result<()> {
        let conn = self.get_connection()?;

        conn.execute("DELETE FROM videos WHERE id = ?", [video_id])
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(())
    }

    // TAG OPERATIONS

    pub fn create_tag(&self, name: &str, color: Option<&str>) -> Result<String> {
        let conn = self.get_connection()?;
        let tag_id = Uuid::new_v4().to_string();

        conn.execute(
            "INSERT INTO tags (id, name, color) VALUES (?, ?, ?)",
            params![&tag_id, name, color],
        )
        .map_err(|e| {
            if e.to_string().contains("UNIQUE constraint failed") {
                VideoRoomError::DuplicateEntry(format!("Tag '{}' already exists", name))
            } else {
                VideoRoomError::DatabaseError(e.to_string())
            }
        })?;

        Ok(tag_id)
    }

    pub fn get_tag(&self, tag_id: &str) -> Result<Option<TagRecord>> {
        let conn = self.get_connection()?;

        let result = conn
            .query_row(
                "SELECT id, name, color FROM tags WHERE id = ?",
                [tag_id],
                |row| {
                    Ok(TagRecord {
                        id: row.get(0)?,
                        name: row.get(1)?,
                        color: row.get(2)?,
                    })
                },
            )
            .optional()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(result)
    }

    pub fn list_tags(&self) -> Result<Vec<TagRecord>> {
        let conn = self.get_connection()?;

        let mut stmt = conn
            .prepare("SELECT id, name, color FROM tags ORDER BY name")
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let tags = stmt
            .query_map([], |row| {
                Ok(TagRecord {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    color: row.get(2)?,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(tags)
    }

    pub fn delete_tag(&self, tag_id: &str) -> Result<()> {
        let conn = self.get_connection()?;

        conn.execute("DELETE FROM tags WHERE id = ?", [tag_id])
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(())
    }

    pub fn tag_video(&self, video_id: &str, tag_id: &str) -> Result<()> {
        let conn = self.get_connection()?;

        conn.execute(
            "INSERT OR IGNORE INTO video_tags (video_id, tag_id) VALUES (?, ?)",
            params![video_id, tag_id],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(())
    }

    pub fn untag_video(&self, video_id: &str, tag_id: &str) -> Result<()> {
        let conn = self.get_connection()?;

        conn.execute(
            "DELETE FROM video_tags WHERE video_id = ? AND tag_id = ?",
            params![video_id, tag_id],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(())
    }

    pub fn get_video_tags(&self, video_id: &str) -> Result<Vec<String>> {
        let conn = self.get_connection()?;

        let mut stmt = conn
            .prepare("SELECT tag_id FROM video_tags WHERE video_id = ?")
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let tags = stmt
            .query_map([video_id], |row| row.get(0))
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(tags)
    }

    // COLLECTION OPERATIONS

    pub fn create_collection(&self, name: &str, is_smart: bool, filter_json: Option<&str>) -> Result<String> {
        let conn = self.get_connection()?;
        let collection_id = Uuid::new_v4().to_string();

        conn.execute(
            "INSERT INTO collections (id, name, is_smart, filter_json) VALUES (?, ?, ?, ?)",
            params![&collection_id, name, is_smart as i32, filter_json],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(collection_id)
    }

    pub fn list_collections(&self) -> Result<Vec<CollectionRecord>> {
        let conn = self.get_connection()?;

        let mut stmt = conn
            .prepare("SELECT id, name, is_smart, filter_json FROM collections ORDER BY name")
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let collections = stmt
            .query_map([], |row| {
                Ok(CollectionRecord {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    is_smart: row.get::<_, i32>(2)? != 0,
                    filter_json: row.get(3)?,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(collections)
    }

    pub fn delete_collection(&self, collection_id: &str) -> Result<()> {
        let conn = self.get_connection()?;

        conn.execute("DELETE FROM collections WHERE id = ?", [collection_id])
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(())
    }

    pub fn add_to_collection(&self, collection_id: &str, video_id: &str) -> Result<()> {
        let conn = self.get_connection()?;

        conn.execute(
            "INSERT OR IGNORE INTO collection_members (collection_id, video_id) VALUES (?, ?)",
            params![collection_id, video_id],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(())
    }

    pub fn remove_from_collection(&self, collection_id: &str, video_id: &str) -> Result<()> {
        let conn = self.get_connection()?;

        conn.execute(
            "DELETE FROM collection_members WHERE collection_id = ? AND video_id = ?",
            params![collection_id, video_id],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(())
    }

    pub fn get_collection_videos(&self, collection_id: &str) -> Result<Vec<String>> {
        let conn = self.get_connection()?;

        let mut stmt = conn
            .prepare("SELECT video_id FROM collection_members WHERE collection_id = ?")
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let videos = stmt
            .query_map([collection_id], |row| row.get(0))
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(videos)
    }

    // NOTES OPERATIONS

    pub fn update_notes(&self, video_id: &str, notes: &str) -> Result<()> {
        let conn = self.get_connection()?;

        conn.execute(
            "INSERT INTO video_notes (video_id, notes, updated_at) VALUES (?, ?, ?)
             ON CONFLICT(video_id) DO UPDATE SET notes = ?, updated_at = ?",
            params![video_id, notes, Utc::now().timestamp_millis(), notes, Utc::now().timestamp_millis()],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(())
    }

    pub fn get_notes(&self, video_id: &str) -> Result<Option<String>> {
        let conn = self.get_connection()?;

        let result = conn
            .query_row(
                "SELECT notes FROM video_notes WHERE video_id = ?",
                [video_id],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(result)
    }

    // LIBRARY LOCATION OPERATIONS

    pub fn add_library_location(&self, path: &str, recursive: bool) -> Result<String> {
        let conn = self.get_connection()?;
        let location_id = Uuid::new_v4().to_string();

        conn.execute(
            "INSERT INTO library_locations (id, path, recursive) VALUES (?, ?, ?)",
            params![&location_id, path, recursive as i32],
        )
        .map_err(|e| {
            if e.to_string().contains("UNIQUE constraint failed") {
                VideoRoomError::DuplicateEntry(format!("Library location '{}' already exists", path))
            } else {
                VideoRoomError::DatabaseError(e.to_string())
            }
        })?;

        Ok(location_id)
    }

    pub fn list_library_locations(&self) -> Result<Vec<LibraryLocationRecord>> {
        let conn = self.get_connection()?;

        let mut stmt = conn
            .prepare("SELECT id, path, recursive, enabled, last_scanned FROM library_locations ORDER BY path")
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let locations = stmt
            .query_map([], |row| {
                Ok(LibraryLocationRecord {
                    id: row.get(0)?,
                    path: row.get(1)?,
                    recursive: row.get::<_, i32>(2)? != 0,
                    enabled: row.get::<_, i32>(3)? != 0,
                    last_scanned: row.get(4)?,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(locations)
    }

    pub fn remove_library_location(&self, path: &str) -> Result<()> {
        let conn = self.get_connection()?;

        conn.execute("DELETE FROM library_locations WHERE path = ?", [path])
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(())
    }
}

// Data structures

#[derive(Debug, Clone)]
pub struct VideoRecord {
    pub id: String,
    pub path: String,
    pub filename: String,
    pub volume_id: Option<String>,
    pub hash: Option<String>,
    pub file_size_bytes: Option<i64>,
    pub indexed_at: i64,
    pub is_online: i32,
}

#[derive(Debug, Clone)]
pub struct TagRecord {
    pub id: String,
    pub name: String,
    pub color: Option<String>,
}

#[derive(Debug, Clone)]
pub struct CollectionRecord {
    pub id: String,
    pub name: String,
    pub is_smart: bool,
    pub filter_json: Option<String>,
}

#[derive(Debug, Clone)]
pub struct LibraryLocationRecord {
    pub id: String,
    pub path: String,
    pub recursive: bool,
    pub enabled: bool,
    pub last_scanned: Option<i64>,
}
