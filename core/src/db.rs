// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

use crate::error::{Result, VideoRoomError};
use chrono::{DateTime, Utc};
use rusqlite::{params, Connection, OptionalExtension};
use std::path::{Path, PathBuf};
use std::sync::RwLock;
use uuid::Uuid;

/// SQLite catalog the daemon currently serves. The path is interior-mutable
/// so the gRPC `OpenCatalog` / `CloseCatalog` RPCs can swap which file backs
/// the server without restarting the process. `None` means "no catalog open"
/// — every method that touches SQL returns
/// [`VideoRoomError::DatabaseError`] in that state, and the service layer
/// turns those into `FailedPrecondition` for the client.
pub struct Database {
    path: RwLock<Option<PathBuf>>,
}

impl Database {
    /// Build a `Database` that already points at `path`. Callers should still
    /// invoke [`Database::initialize`] before serving traffic so the schema
    /// is created.
    pub fn new(path: &Path) -> Result<Self> {
        Ok(Database {
            path: RwLock::new(Some(path.to_path_buf())),
        })
    }

    /// Build a `Database` with no catalog selected yet. The first call to
    /// [`Database::set_path`] will pick one and initialize its schema.
    pub fn new_empty() -> Self {
        Database {
            path: RwLock::new(None),
        }
    }

    /// Switch to a different SQLite file. The new file is created if it
    /// doesn't exist and the schema/migrations run synchronously. On success
    /// the internal path is updated atomically.
    pub fn set_path(&self, new_path: &Path) -> Result<()> {
        // Ensure parent directory exists.
        if let Some(parent) = new_path.parent() {
            if !parent.as_os_str().is_empty() {
                let _ = std::fs::create_dir_all(parent);
            }
        }
        // Open + initialize before we swap so a bad path doesn't leave the
        // daemon in a half-broken state.
        let conn = Connection::open(new_path).map_err(|e| {
            VideoRoomError::DatabaseError(format!("Failed to open database: {}", e))
        })?;
        Self::initialize_conn(&conn)?;
        drop(conn);

        let mut guard = self.path.write().map_err(|_| {
            VideoRoomError::DatabaseError("Database path lock poisoned".to_string())
        })?;
        *guard = Some(new_path.to_path_buf());
        tracing::info!("Catalog opened: {}", new_path.display());
        Ok(())
    }

    /// Drop the current catalog so future SQL calls fail until a new
    /// `set_path` succeeds.
    pub fn clear_path(&self) {
        if let Ok(mut guard) = self.path.write() {
            if let Some(p) = guard.take() {
                tracing::info!("Catalog closed: {}", p.display());
            }
        }
    }

    /// The path currently backing this database, if any.
    pub fn current_path(&self) -> Option<PathBuf> {
        self.path.read().ok().and_then(|g| g.clone())
    }

    /// Initialize the schema (and run migrations) for the currently selected
    /// catalog. Kept async for API compatibility with [`main.rs`]; the actual
    /// work is synchronous.
    pub async fn initialize(&self) -> Result<()> {
        let conn = self.get_connection()?;
        Self::initialize_conn(&conn)?;
        tracing::info!("Database schema initialized");
        Ok(())
    }

    /// Schema setup + migrations on an already-open [`Connection`]. Used both
    /// by [`Database::set_path`] (where we need this to run before swapping
    /// paths) and by [`Database::initialize`].
    fn initialize_conn(conn: &Connection) -> Result<()> {
        let _ = conn.pragma_update(None, "journal_mode", "WAL");
        let _ = conn.pragma_update(None, "synchronous", "NORMAL");
        let _ = conn.pragma_update(None, "foreign_keys", "ON");

        let schema = include_str!("../schema.sql");
        conn.execute_batch(schema)
            .map_err(|e| VideoRoomError::DatabaseError(format!("Failed to initialize schema: {}", e)))?;

        // Migration: add columns to existing tables. SQLite has no
        // `ALTER TABLE ADD COLUMN IF NOT EXISTS`, so we try and ignore the
        // "duplicate column" error.
        let migrations: &[(&str, &str)] = &[
            ("videos.group_id", "ALTER TABLE videos ADD COLUMN group_id TEXT"),
            ("metadata.frame_count", "ALTER TABLE metadata ADD COLUMN frame_count INTEGER DEFAULT 0"),
        ];
        for (label, sql) in migrations {
            match conn.execute(sql, []) {
                Ok(_) => tracing::info!("Migration: added {}", label),
                Err(e) => {
                    let msg = e.to_string();
                    if !msg.contains("duplicate column") {
                        tracing::warn!("Migration for {} failed (likely already applied): {}", label, msg);
                    }
                }
            }
        }
        Ok(())
    }

    pub fn get_connection(&self) -> Result<Connection> {
        let path = self
            .path
            .read()
            .map_err(|_| VideoRoomError::DatabaseError("Database path lock poisoned".to_string()))?
            .clone()
            .ok_or_else(|| {
                VideoRoomError::DatabaseError("No catalog is currently open".to_string())
            })?;
        Connection::open(&path)
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

    /// Return the *names* of tags applied to a video (joined through video_tags).
    /// Used to populate the `tags` field on VideoSummary / VideoMetadata.
    pub fn get_video_tags(&self, video_id: &str) -> Result<Vec<String>> {
        let conn = self.get_connection()?;

        let mut stmt = conn
            .prepare(
                "SELECT t.name FROM video_tags vt
                 JOIN tags t ON t.id = vt.tag_id
                 WHERE vt.video_id = ?
                 ORDER BY t.name",
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let tags = stmt
            .query_map([video_id], |row| row.get(0))
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(tags)
    }

    /// Look up a tag by name (case-sensitive). Returns None if no such tag.
    pub fn get_tag_by_name(&self, name: &str) -> Result<Option<TagRecord>> {
        let conn = self.get_connection()?;
        let result = conn
            .query_row(
                "SELECT id, name, color FROM tags WHERE name = ?",
                [name],
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

    /// Count how many videos currently have the given tag.
    pub fn count_videos_for_tag(&self, tag_id: &str) -> Result<i64> {
        let conn = self.get_connection()?;
        let count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM video_tags WHERE tag_id = ?",
                [tag_id],
                |row| row.get(0),
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(count)
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

        // UPSERT — if the path already exists, update its recursive flag.
        // This lets the user re-add the same library with a different setting.
        conn.execute(
            "INSERT INTO library_locations (id, path, recursive) VALUES (?, ?, ?)
             ON CONFLICT(path) DO UPDATE SET recursive = excluded.recursive",
            params![&location_id, path, recursive as i32],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        // Return the actual ID (may be the existing one if we just updated).
        let id: String = conn
            .query_row(
                "SELECT id FROM library_locations WHERE path = ?",
                [path],
                |row| row.get(0),
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(id)
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

    // VIDEO GROUPS (Lightroom-style "stacks")

    /// Create a new group, set members' group_id, and return the new group's ID.
    pub fn create_group(
        &self,
        name: Option<&str>,
        base_name: Option<&str>,
        video_ids: &[String],
        preferred_video_id: Option<&str>,
    ) -> Result<String> {
        if video_ids.is_empty() {
            return Err(VideoRoomError::InvalidRequest("Group must contain at least one video".to_string()));
        }
        let group_id = Uuid::new_v4().to_string();
        let preferred = preferred_video_id.unwrap_or(&video_ids[0]);
        let conn = self.get_connection()?;

        conn.execute(
            "INSERT INTO video_groups (id, name, base_name, preferred_video_id) VALUES (?, ?, ?, ?)",
            params![group_id, name, base_name, preferred],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        // Assign group_id to each video, clearing any existing group membership
        for vid in video_ids {
            conn.execute(
                "UPDATE videos SET group_id = ? WHERE id = ?",
                params![group_id, vid],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        }

        Ok(group_id)
    }

    /// Remove a video from its group. If the group becomes empty (or has one remaining member),
    /// the group is deleted entirely.
    pub fn ungroup_video(&self, video_id: &str) -> Result<()> {
        let conn = self.get_connection()?;
        let group_id: Option<String> = conn
            .query_row(
                "SELECT group_id FROM videos WHERE id = ?",
                [video_id],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .flatten();

        let Some(group_id) = group_id else {
            return Ok(()); // Not in a group, nothing to do
        };

        conn.execute("UPDATE videos SET group_id = NULL WHERE id = ?", [video_id])
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        // Count remaining members
        let remaining: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM videos WHERE group_id = ?",
                [&group_id],
                |row| row.get(0),
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        // If only one or zero members remain, dissolve the group entirely
        if remaining <= 1 {
            conn.execute("UPDATE videos SET group_id = NULL WHERE group_id = ?", [&group_id])
                .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
            conn.execute("DELETE FROM video_groups WHERE id = ?", [&group_id])
                .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        }

        Ok(())
    }

    pub fn set_group_preferred(&self, group_id: &str, video_id: &str) -> Result<()> {
        let conn = self.get_connection()?;
        conn.execute(
            "UPDATE video_groups SET preferred_video_id = ? WHERE id = ?",
            params![video_id, group_id],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(())
    }

    pub fn get_group(&self, group_id: &str) -> Result<Option<VideoGroupRecord>> {
        let conn = self.get_connection()?;
        let result = conn
            .query_row(
                "SELECT id, name, base_name, preferred_video_id FROM video_groups WHERE id = ?",
                [group_id],
                |row| {
                    Ok(VideoGroupRecord {
                        id: row.get(0)?,
                        name: row.get(1)?,
                        base_name: row.get(2)?,
                        preferred_video_id: row.get(3)?,
                    })
                },
            )
            .optional()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(result)
    }

    /// Get all member video IDs for a group.
    pub fn list_group_member_ids(&self, group_id: &str) -> Result<Vec<String>> {
        let conn = self.get_connection()?;
        let mut stmt = conn
            .prepare("SELECT id FROM videos WHERE group_id = ? ORDER BY filename")
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        let ids = stmt
            .query_map([group_id], |row| row.get::<_, String>(0))
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(ids)
    }

    pub fn count_group_members(&self, group_id: &str) -> Result<i64> {
        let conn = self.get_connection()?;
        let count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM videos WHERE group_id = ?",
                [group_id],
                |row| row.get(0),
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(count)
    }

    /// List videos as group representatives only. For each group, returns the
    /// preferred video (if set) or the first by filename. Ungrouped videos are
    /// returned individually.
    ///
    /// All filters AND together — a video must pass every non-empty filter to
    /// be included. `filter_capture_year == 0` means "no year filter".
    pub fn list_videos_grouped(
        &self,
        limit: i64,
        offset: i64,
        sort_by: &str,
        ascending: bool,
        location_filter: &str,
        filter_tag_ids: &[String],
        filter_camera: &str,
        filter_lens: &str,
        filter_codec: &str,
        filter_capture_year: i32,
    ) -> Result<(Vec<VideoRecord>, i64)> {
        let conn = self.get_connection()?;

        // Build the ORDER BY clause (same logic as list_videos_sorted)
        let direction = if ascending { "ASC" } else { "DESC" };
        let order_by = match sort_by.to_lowercase().as_str() {
            "name" | "filename" => format!("v.filename {}", direction),
            "date_added" | "indexed_at" | "" => format!("v.indexed_at {}", direction),
            "duration" | "duration_ms" => format!("COALESCE(m.duration_ms, 0) {}", direction),
            "size" | "size_bytes" | "file_size_bytes" => format!("COALESCE(v.file_size_bytes, 0) {}", direction),
            "resolution" | "width" | "height" => {
                format!("(COALESCE(m.width, 0) * COALESCE(m.height, 0)) {}", direction)
            }
            "fps" => format!("COALESCE(m.fps, 0) {}", direction),
            "codec" | "codec_video" => format!("COALESCE(m.codec_video, '') {}", direction),
            "bitrate" => format!("COALESCE(m.bitrate, 0) {}", direction),
            "camera" | "camera_model" => format!("COALESCE(m.camera_model, '') {}", direction),
            "lens" | "lens_model" => format!("COALESCE(m.lens_model, '') {}", direction),
            "creation_date" | "shot_date" | "capture_date" => format!("COALESCE(m.creation_date, 0) {}", direction),
            // Sort by alphabetically-first keyword attached to the video.
            "keyword" | "tag" => format!(
                "COALESCE((SELECT MIN(t.name) FROM video_tags vt \
                            JOIN tags t ON t.id = vt.tag_id \
                            WHERE vt.video_id = v.id), '') {}",
                direction
            ),
            _ => format!("v.filename {}", direction),
        };

        // Representative selection:
        //   - If video is ungrouped: it represents itself
        //   - If grouped: use the group's preferred_video_id (falling back to itself if it IS that video)
        let representative_filter = "v.group_id IS NULL \
             OR v.id = (SELECT preferred_video_id FROM video_groups WHERE id = v.group_id) \
             OR (SELECT preferred_video_id FROM video_groups WHERE id = v.group_id) IS NULL \
                AND v.id = (SELECT MIN(v2.id) FROM videos v2 WHERE v2.group_id = v.group_id)";

        // Build the optional location-prefix filter. We match the directory plus
        // a trailing slash to avoid spurious matches (so `/foo/bar` doesn't match
        // `/foo/barbaz/...`).
        let location_param: Option<String> = if location_filter.is_empty() {
            None
        } else {
            let mut prefix = location_filter.to_string();
            if !prefix.ends_with('/') {
                prefix.push('/');
            }
            Some(format!("{}%", prefix))
        };
        let location_clause = if location_param.is_some() {
            " AND v.path LIKE ?"
        } else {
            ""
        };

        // Tag filter: a video must have ALL specified tag IDs. We dedup just in
        // case the caller passes the same id twice.
        let tag_ids: Vec<String> = {
            let mut seen = std::collections::HashSet::new();
            filter_tag_ids
                .iter()
                .filter(|id| !id.is_empty() && seen.insert((*id).clone()))
                .cloned()
                .collect()
        };
        let (tag_clause, tag_count_param) = if tag_ids.is_empty() {
            (String::new(), 0i64)
        } else {
            let placeholders = std::iter::repeat("?")
                .take(tag_ids.len())
                .collect::<Vec<_>>()
                .join(", ");
            // EXISTS subquery — counts how many of the requested tags this
            // video has and requires it to equal the requested count.
            let clause = format!(
                " AND (SELECT COUNT(DISTINCT vt.tag_id) FROM video_tags vt \
                       WHERE vt.video_id = v.id AND vt.tag_id IN ({})) = ?",
                placeholders
            );
            (clause, tag_ids.len() as i64)
        };

        // Per-field metadata filters. Each is appended only when set so the
        // generated SQL stays clean.
        let camera_clause = if filter_camera.is_empty() { "" } else { " AND m.camera_model = ?" };
        let lens_clause = if filter_lens.is_empty() { "" } else { " AND m.lens_model = ?" };
        let codec_clause = if filter_codec.is_empty() { "" } else { " AND m.codec_video = ?" };
        // creation_date is stored as Unix-ms; we compute year via SQLite's
        // strftime on the ISO conversion. SQLite epoch helpers expect seconds,
        // so divide.
        let year_clause = if filter_capture_year > 0 {
            " AND CAST(strftime('%Y', m.creation_date / 1000, 'unixepoch') AS INTEGER) = ?"
        } else {
            ""
        };

        // Helper to bind all dynamic params in order:
        // [location_param?, tag_id_1, tag_id_2, ..., tag_count?, camera?, lens?, codec?, year?]
        let mut bind: Vec<Box<dyn rusqlite::ToSql>> = Vec::new();
        if let Some(p) = &location_param {
            bind.push(Box::new(p.clone()));
        }
        if !tag_ids.is_empty() {
            for id in &tag_ids {
                bind.push(Box::new(id.clone()));
            }
            bind.push(Box::new(tag_count_param));
        }
        if !filter_camera.is_empty() {
            bind.push(Box::new(filter_camera.to_string()));
        }
        if !filter_lens.is_empty() {
            bind.push(Box::new(filter_lens.to_string()));
        }
        if !filter_codec.is_empty() {
            bind.push(Box::new(filter_codec.to_string()));
        }
        if filter_capture_year > 0 {
            bind.push(Box::new(filter_capture_year));
        }

        // ---- COUNT(*) ----
        let count_sql = format!(
            "SELECT COUNT(*) FROM videos v
             LEFT JOIN metadata m ON v.id = m.video_id
             WHERE ({}){}{}{}{}{}{}",
            representative_filter,
            location_clause,
            tag_clause,
            camera_clause,
            lens_clause,
            codec_clause,
            year_clause
        );
        let count_params: Vec<&dyn rusqlite::ToSql> =
            bind.iter().map(|b| b.as_ref() as &dyn rusqlite::ToSql).collect();
        let total: i64 = conn
            .query_row(&count_sql, count_params.as_slice(), |row| row.get(0))
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        // ---- SELECT page ----
        let sql = format!(
            "SELECT v.id, v.path, v.filename, v.volume_id, v.hash, v.file_size_bytes, v.indexed_at, v.is_online
             FROM videos v LEFT JOIN metadata m ON v.id = m.video_id
             WHERE ({}){}{}{}{}{}{}
             ORDER BY {} LIMIT ? OFFSET ?",
            representative_filter,
            location_clause,
            tag_clause,
            camera_clause,
            lens_clause,
            codec_clause,
            year_clause,
            order_by
        );

        let mut bind_with_limit: Vec<Box<dyn rusqlite::ToSql>> = bind;
        bind_with_limit.push(Box::new(limit));
        bind_with_limit.push(Box::new(offset));
        let query_params: Vec<&dyn rusqlite::ToSql> =
            bind_with_limit.iter().map(|b| b.as_ref() as &dyn rusqlite::ToSql).collect();

        let mut stmt = conn
            .prepare(&sql)
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let videos = stmt
            .query_map(query_params.as_slice(), |row| {
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

    /// Distinct non-empty values for a single metadata column.
    /// Used to populate the top-bar filter dropdowns.
    fn list_distinct_metadata_strings(&self, column: &str) -> Result<Vec<String>> {
        // The column name is hard-coded by callers (not user-supplied), so the
        // format!-into-SQL here is safe.
        let conn = self.get_connection()?;
        let sql = format!(
            "SELECT DISTINCT {col} FROM metadata
             WHERE {col} IS NOT NULL AND {col} != ''
             ORDER BY {col}",
            col = column
        );
        let mut stmt = conn
            .prepare(&sql)
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| row.get::<_, String>(0))
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(rows)
    }

    pub fn list_distinct_cameras(&self) -> Result<Vec<String>> {
        self.list_distinct_metadata_strings("camera_model")
    }

    pub fn list_distinct_lenses(&self) -> Result<Vec<String>> {
        self.list_distinct_metadata_strings("lens_model")
    }

    pub fn list_distinct_codecs(&self) -> Result<Vec<String>> {
        self.list_distinct_metadata_strings("codec_video")
    }

    /// Distinct years (4-digit Gregorian) that any video was captured in.
    /// Returned in descending order so the dropdown shows recent years first.
    pub fn list_distinct_capture_years(&self) -> Result<Vec<i32>> {
        let conn = self.get_connection()?;
        let mut stmt = conn
            .prepare(
                "SELECT DISTINCT CAST(strftime('%Y', creation_date / 1000, 'unixepoch') AS INTEGER) AS y
                 FROM metadata
                 WHERE creation_date IS NOT NULL AND creation_date > 0
                 ORDER BY y DESC",
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| row.get::<_, i32>(0))
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(rows)
    }

    /// Count videos whose path starts with the given directory (recursive).
    pub fn count_videos_in_path(&self, path: &str) -> Result<i64> {
        let conn = self.get_connection()?;
        let mut prefix = path.to_string();
        if !prefix.ends_with('/') {
            prefix.push('/');
        }
        let pattern = format!("{}%", prefix);
        let count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM videos WHERE path LIKE ?",
                params![pattern],
                |row| row.get(0),
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(count)
    }

    /// Get the group_id for a video (None if ungrouped).
    pub fn get_video_group_id(&self, video_id: &str) -> Result<Option<String>> {
        let conn = self.get_connection()?;
        let result: Option<String> = conn
            .query_row(
                "SELECT group_id FROM videos WHERE id = ?",
                [video_id],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .flatten();
        Ok(result)
    }

    /// Fetch all (video_id, filename, base_name_candidate, duration_ms, width, height, fps,
    /// parent_dir, group_id) tuples — used by auto-grouping.
    pub fn list_for_auto_grouping(&self) -> Result<Vec<AutoGroupCandidate>> {
        let conn = self.get_connection()?;
        let mut stmt = conn
            .prepare(
                "SELECT v.id, v.filename, v.path, v.group_id,
                        COALESCE(m.duration_ms, 0), COALESCE(m.frame_count, 0),
                        COALESCE(m.width, 0), COALESCE(m.height, 0),
                        COALESCE(m.fps, 0)
                 FROM videos v LEFT JOIN metadata m ON v.id = m.video_id",
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let rows = stmt
            .query_map([], |row| {
                let path: String = row.get(2)?;
                let parent_dir = std::path::Path::new(&path)
                    .parent()
                    .and_then(|p| p.to_str())
                    .unwrap_or("")
                    .to_string();
                Ok(AutoGroupCandidate {
                    id: row.get(0)?,
                    filename: row.get(1)?,
                    parent_dir,
                    group_id: row.get(3)?,
                    duration_ms: row.get(4)?,
                    frame_count: row.get(5)?,
                    width: row.get(6)?,
                    height: row.get(7)?,
                    fps: row.get(8)?,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(rows)
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

#[derive(Debug, Clone)]
pub struct VideoGroupRecord {
    pub id: String,
    pub name: Option<String>,
    pub base_name: Option<String>,
    pub preferred_video_id: Option<String>,
}

#[derive(Debug, Clone)]
pub struct AutoGroupCandidate {
    pub id: String,
    pub filename: String,
    pub parent_dir: String,
    pub group_id: Option<String>,
    pub duration_ms: i64,
    pub frame_count: i64,
    pub width: i32,
    pub height: i32,
    pub fps: f64,
}
