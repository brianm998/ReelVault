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

/// Build the half-open `[lower, upper)` byte range that contains every
/// string starting with `prefix`.  Used by per-video post-index queries
/// to find all videos in a given parent directory: a range query on
/// `videos.path` is guaranteed to use `idx_videos_path` (where a `LIKE`
/// would only do so under `case_sensitive_like = ON`, which this
/// catalog doesn't enable).
///
/// Returns `(lower, upper)` where `lower = prefix` and `upper = prefix`
/// with its last byte incremented by 1.  Callers in this module pass
/// `parent_dir + '/'` so the upper bound naturally lands on
/// `parent_dir + '0'` (`/` is 0x2F, `0` is 0x30) — i.e. every string
/// lexicographically between the two starts with `parent_dir/`.
fn path_prefix_range(prefix: &str) -> (String, String) {
    let lower = prefix.to_string();
    let mut upper_bytes = lower.clone().into_bytes();
    if let Some(last) = upper_bytes.last_mut() {
        // Saturating: a prefix ending in 0xFF is unreachable for real
        // filesystem paths but if it ever happens, falling back to an
        // empty upper bound would match nothing — same effect as no
        // bucket-mates.
        *last = last.saturating_add(1);
    }
    // Safe: incrementing one ASCII byte (the path separator from the
    // caller) keeps the string valid UTF-8.
    let upper = String::from_utf8(upper_bytes).unwrap_or_else(|_| lower.clone());
    (lower, upper)
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
            // Proxy relation. `proxy_of` is the video this row is a
            // lower-resolution stand-in for; `proxy_confidence` is the
            // thumbnail-similarity score (0..=1) we used when
            // auto-detecting; `proxy_auto_detected` is true if VideoRoom
            // inferred the link (false if the user marked it manually
            // or VideoRoom *generated* the proxy file via
            // `GenerateProxy`). Indexed for fast "list proxies of X"
            // lookups.
            ("videos.proxy_of", "ALTER TABLE videos ADD COLUMN proxy_of TEXT"),
            ("videos.proxy_confidence", "ALTER TABLE videos ADD COLUMN proxy_confidence REAL"),
            ("videos.proxy_auto_detected", "ALTER TABLE videos ADD COLUMN proxy_auto_detected INTEGER DEFAULT 0"),
            ("idx_videos_proxy_of", "CREATE INDEX IF NOT EXISTS idx_videos_proxy_of ON videos(proxy_of)"),
            // Junction table for the proxy ↔ master many-to-many
            // relationship. Each row links one proxy video to one
            // master video; a proxy can show up under multiple masters
            // (and vice versa) which is needed for clusters of nearly-
            // identical processed variants that all share the same
            // low-res preview.
            //
            // `videos.proxy_of` is kept as a denormalized "any master"
            // pointer to keep existing `WHERE proxy_of IS NULL` filters
            // cheap and to give the simple "is this row a proxy" check
            // a fast O(1) answer.
            ("proxy_links table", "CREATE TABLE IF NOT EXISTS proxy_links (
                master_id TEXT NOT NULL,
                proxy_id TEXT NOT NULL,
                confidence REAL,
                auto_detected INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(master_id, proxy_id),
                FOREIGN KEY(master_id) REFERENCES videos(id) ON DELETE CASCADE,
                FOREIGN KEY(proxy_id) REFERENCES videos(id) ON DELETE CASCADE
            )"),
            ("idx_proxy_links_proxy", "CREATE INDEX IF NOT EXISTS idx_proxy_links_proxy ON proxy_links(proxy_id)"),
            ("idx_proxy_links_master", "CREATE INDEX IF NOT EXISTS idx_proxy_links_master ON proxy_links(master_id)"),
            // Backfill: for catalogs whose single-column relation
            // pre-dates the junction table, mirror every (proxy,
            // master) pair into proxy_links. INSERT OR IGNORE keeps
            // this idempotent across re-runs of `initialize`.
            ("proxy_links backfill", "INSERT OR IGNORE INTO proxy_links (master_id, proxy_id, confidence, auto_detected)
                SELECT proxy_of, id, proxy_confidence, COALESCE(proxy_auto_detected, 0)
                FROM videos
                WHERE proxy_of IS NOT NULL"),
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

    // GPS / GEOLOCATION OPERATIONS

    /// Write GPS coordinates onto a video's metadata row. Inserts a metadata
    /// row if one doesn't exist (defensive — the indexer should have created
    /// it already but we don't want a missing metadata row to silently drop
    /// the user-supplied location). `altitude` of 0 means "unknown" but is
    /// stored as 0 so we can distinguish "geotagged at sea level" from
    /// "geotagged with no altitude info" using a separate column policy if
    /// we ever want to.
    pub fn update_gps_coordinates(
        &self,
        video_id: &str,
        latitude: f64,
        longitude: f64,
        altitude: f64,
    ) -> Result<()> {
        let conn = self.get_connection()?;
        let affected = conn
            .execute(
                "UPDATE metadata SET gps_latitude = ?, gps_longitude = ?, gps_altitude = ? \
                 WHERE video_id = ?",
                params![latitude, longitude, altitude, video_id],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        if affected == 0 {
            // No metadata row yet — insert a sparse one so the GPS sticks.
            conn.execute(
                "INSERT INTO metadata (video_id, gps_latitude, gps_longitude, gps_altitude) \
                 VALUES (?, ?, ?, ?)",
                params![video_id, latitude, longitude, altitude],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        }
        Ok(())
    }

    /// Set the capture timestamp on a video's metadata row. Same insert-if-
    /// missing semantics as [`update_gps_coordinates`] so a video with no
    /// metadata row yet (rare but possible) still picks up the user-supplied
    /// timestamp. `timestamp_ms` is Unix millis, UTC.
    pub fn update_capture_date(&self, video_id: &str, timestamp_ms: i64) -> Result<()> {
        let conn = self.get_connection()?;
        let affected = conn
            .execute(
                "UPDATE metadata SET creation_date = ? WHERE video_id = ?",
                params![timestamp_ms, video_id],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        if affected == 0 {
            conn.execute(
                "INSERT INTO metadata (video_id, creation_date) VALUES (?, ?)",
                params![video_id, timestamp_ms],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        }
        Ok(())
    }

    /// Enumerate every video that has known GPS coordinates. Used by the
    /// global-map view in both clients to render pins.
    pub fn list_videos_with_locations(&self) -> Result<Vec<VideoLocationRecord>> {
        let conn = self.get_connection()?;
        let mut stmt = conn
            .prepare(
                "SELECT v.id, v.filename, v.path, \
                        m.gps_latitude, m.gps_longitude, \
                        COALESCE(m.gps_altitude, 0), \
                        EXISTS(SELECT 1 FROM thumbnails t WHERE t.video_id = v.id) \
                 FROM videos v \
                 JOIN metadata m ON m.video_id = v.id \
                 WHERE m.gps_latitude IS NOT NULL \
                   AND m.gps_longitude IS NOT NULL \
                   AND NOT (m.gps_latitude = 0 AND m.gps_longitude = 0)",
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let rows = stmt
            .query_map([], |row| {
                Ok(VideoLocationRecord {
                    id: row.get(0)?,
                    filename: row.get(1)?,
                    path: row.get(2)?,
                    latitude: row.get(3)?,
                    longitude: row.get(4)?,
                    altitude: row.get(5)?,
                    has_thumbnail: row.get::<_, i64>(6)? != 0,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?);
        }
        Ok(out)
    }

    // NAMED-LOCATION OPERATIONS

    /// List every named location in the catalog, ordered by name. The
    /// clients fetch this once per dialog open and resolve each video's
    /// GPS into a name client-side — the list is small (a few hundred at
    /// most for typical libraries) so we don't bother with server-side
    /// proximity queries.
    pub fn list_named_locations(&self) -> Result<Vec<NamedLocationRecord>> {
        let conn = self.get_connection()?;
        let mut stmt = conn
            .prepare(
                "SELECT id, name, latitude, longitude, radius_m, \
                        COALESCE(strftime('%s', created_at) * 1000, 0), \
                        COALESCE(strftime('%s', updated_at) * 1000, 0) \
                 FROM named_locations \
                 ORDER BY name COLLATE NOCASE",
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let rows = stmt
            .query_map([], |row| {
                Ok(NamedLocationRecord {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    latitude: row.get(2)?,
                    longitude: row.get(3)?,
                    radius_m: row.get(4)?,
                    created_at_ms: row.get::<_, i64>(5)?,
                    updated_at_ms: row.get::<_, i64>(6)?,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?);
        }
        Ok(out)
    }

    /// Insert or update a named location. Pass an empty `id` to create a
    /// new row (a UUID will be assigned); pass an existing `id` to update.
    /// `radius_m <= 0` falls back to the default 250 m.
    ///
    /// Returns the persisted row so the caller can echo it back to the
    /// client (clients need the assigned id and timestamps).
    pub fn upsert_named_location(
        &self,
        id: &str,
        name: &str,
        latitude: f64,
        longitude: f64,
        radius_m: f64,
    ) -> Result<NamedLocationRecord> {
        if name.trim().is_empty() {
            return Err(VideoRoomError::DatabaseError(
                "name must not be empty".into(),
            ));
        }
        let conn = self.get_connection()?;
        let effective_radius = if radius_m > 0.0 { radius_m } else { 250.0 };
        let resolved_id = if id.is_empty() {
            let new_id = Uuid::new_v4().to_string();
            conn.execute(
                "INSERT INTO named_locations \
                    (id, name, latitude, longitude, radius_m) \
                 VALUES (?, ?, ?, ?, ?)",
                params![&new_id, name.trim(), latitude, longitude, effective_radius],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
            new_id
        } else {
            let affected = conn
                .execute(
                    "UPDATE named_locations \
                        SET name = ?, latitude = ?, longitude = ?, \
                            radius_m = ?, updated_at = CURRENT_TIMESTAMP \
                        WHERE id = ?",
                    params![name.trim(), latitude, longitude, effective_radius, id],
                )
                .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
            if affected == 0 {
                // No existing row — treat as an insert with the caller-
                // supplied id so the client's local cache stays consistent.
                conn.execute(
                    "INSERT INTO named_locations \
                        (id, name, latitude, longitude, radius_m) \
                     VALUES (?, ?, ?, ?, ?)",
                    params![id, name.trim(), latitude, longitude, effective_radius],
                )
                .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
            }
            id.to_string()
        };

        // Re-read the row so the response carries the canonical timestamps
        // the DB picked.
        conn.query_row(
            "SELECT id, name, latitude, longitude, radius_m, \
                    COALESCE(strftime('%s', created_at) * 1000, 0), \
                    COALESCE(strftime('%s', updated_at) * 1000, 0) \
             FROM named_locations WHERE id = ?",
            [&resolved_id],
            |row| {
                Ok(NamedLocationRecord {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    latitude: row.get(2)?,
                    longitude: row.get(3)?,
                    radius_m: row.get(4)?,
                    created_at_ms: row.get::<_, i64>(5)?,
                    updated_at_ms: row.get::<_, i64>(6)?,
                })
            },
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))
    }

    /// Delete a named location. Idempotent — deleting a missing id is a
    /// no-op rather than an error, matching `delete_tag`'s behavior.
    pub fn delete_named_location(&self, id: &str) -> Result<()> {
        let conn = self.get_connection()?;
        conn.execute("DELETE FROM named_locations WHERE id = ?", [id])
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(())
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

    pub fn remove_library_location(&self, path: &str) -> Result<usize> {
        let conn = self.get_connection()?;

        // Delete all videos whose path lives inside this library root.
        // Use a path prefix with a trailing slash so "/home/user/Videos"
        // doesn't accidentally match "/home/user/Videos2".
        let prefix = format!("{}/", path.trim_end_matches('/'));
        let deleted = conn.execute(
            "DELETE FROM videos WHERE path LIKE ? || '%'",
            [&prefix],
        ).map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        conn.execute("DELETE FROM library_locations WHERE path = ?", [path])
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        Ok(deleted)
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
        // Geographic proximity filter. When `Some`, restricts results to
        // videos whose recorded GPS coordinates fall inside a bounding box
        // computed from (latitude, longitude, radius_km). A bounding box is
        // used instead of true Haversine because SQLite's math functions
        // aren't guaranteed to be compiled in everywhere, and at radii
        // < 100km the approximation differs by < 1% from a great-circle
        // computation — well within "videos near this pin" tolerance.
        filter_location: Option<(f64, f64, f64)>,
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
        //   - Proxies are always hidden from the grid and the total count.
        //     They only surface through the "P×N" badge and the ListProxies RPC.
        //   - If video is ungrouped: it represents itself
        //   - If grouped: use the group's preferred_video_id (falling back to itself if it IS that video)
        let representative_filter = "v.proxy_of IS NULL \
             AND (v.group_id IS NULL \
             OR v.id = (SELECT preferred_video_id FROM video_groups WHERE id = v.group_id) \
             OR (SELECT preferred_video_id FROM video_groups WHERE id = v.group_id) IS NULL \
                AND v.id = (SELECT MIN(v2.id) FROM videos v2 WHERE v2.group_id = v.group_id))";

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

        // Geo proximity bounding box. 1° latitude ≈ 111 km everywhere; 1°
        // longitude ≈ 111·cos(lat) km, so longitude span widens near the
        // equator and shrinks at the poles. We clamp the cos at 0.01 to
        // avoid blowing up exactly at the pole.
        let geo_bounds: Option<(f64, f64, f64, f64)> = filter_location.map(|(lat, lon, radius_km)| {
            let lat_delta = (radius_km / 111.0).abs();
            let cos_lat = lat.to_radians().cos().abs().max(0.01);
            let lon_delta = (radius_km / (111.0 * cos_lat)).abs();
            (lat - lat_delta, lat + lat_delta, lon - lon_delta, lon + lon_delta)
        });
        let geo_clause = if geo_bounds.is_some() {
            " AND m.gps_latitude IS NOT NULL AND m.gps_longitude IS NOT NULL \
             AND m.gps_latitude BETWEEN ? AND ? AND m.gps_longitude BETWEEN ? AND ?"
        } else {
            ""
        };

        // Helper to bind all dynamic params in order:
        // [location_param?, tag_id_1, tag_id_2, ..., tag_count?, camera?, lens?, codec?, year?, geo_min_lat?, geo_max_lat?, geo_min_lon?, geo_max_lon?]
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
        if let Some((min_lat, max_lat, min_lon, max_lon)) = geo_bounds {
            bind.push(Box::new(min_lat));
            bind.push(Box::new(max_lat));
            bind.push(Box::new(min_lon));
            bind.push(Box::new(max_lon));
        }

        // ---- COUNT(*) ----
        let count_sql = format!(
            "SELECT COUNT(*) FROM videos v
             LEFT JOIN metadata m ON v.id = m.video_id
             WHERE ({}){}{}{}{}{}{}{}",
            representative_filter,
            location_clause,
            tag_clause,
            camera_clause,
            lens_clause,
            codec_clause,
            year_clause,
            geo_clause
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
             WHERE ({}){}{}{}{}{}{}{}
             ORDER BY {} LIMIT ? OFFSET ?",
            representative_filter,
            location_clause,
            tag_clause,
            camera_clause,
            lens_clause,
            codec_clause,
            year_clause,
            geo_clause,
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
        // Mirror the representative_filter used by list_videos_grouped so the
        // count reflects the same set of items the grid actually shows:
        //   • Proxies are excluded — they surface only through the "P×N" badge.
        //   • Each stack counts as one video (the group representative).
        let count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM videos v
                 WHERE v.path LIKE ?
                   AND v.proxy_of IS NULL
                   AND (
                     v.group_id IS NULL
                     OR v.id = (SELECT preferred_video_id FROM video_groups WHERE id = v.group_id)
                     OR (
                       (SELECT preferred_video_id FROM video_groups WHERE id = v.group_id) IS NULL
                       AND v.id = (SELECT MIN(v2.id) FROM videos v2 WHERE v2.group_id = v.group_id)
                     )
                   )",
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

    // --- Proxy relationships ---

    /// Link `proxy_id` to `original_id` in the proxy_links junction
    /// table. `confidence` is the thumb-similarity score (or 1.0 for
    /// VideoRoom-generated / user-marked proxies). `auto_detected`
    /// distinguishes scanner inferences from user/system marks so the
    /// UI can render them differently.
    ///
    /// Additive: a proxy can be linked to many masters. Re-calling
    /// with the same pair updates the confidence + auto_detected flag
    /// in place (`INSERT OR REPLACE`).
    ///
    /// Also writes a denormalized pointer into `videos.proxy_of` so
    /// `WHERE proxy_of IS NULL` (the cheap "is this row a proxy" gate
    /// used by detection filters) keeps working. The denormalized
    /// pointer reflects whichever master was most recently linked —
    /// callers that need the full master set should query the
    /// junction table directly via [`Self::list_masters_for_proxy`].
    pub fn set_proxy_of(
        &self,
        proxy_id: &str,
        original_id: &str,
        confidence: f64,
        auto_detected: bool,
    ) -> Result<()> {
        let conn = self.get_connection()?;
        conn.execute(
            "INSERT OR REPLACE INTO proxy_links (master_id, proxy_id, confidence, auto_detected)
             VALUES (?, ?, ?, ?)",
            params![original_id, proxy_id, confidence, auto_detected as i32],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        conn.execute(
            "UPDATE videos
             SET proxy_of = ?, proxy_confidence = ?, proxy_auto_detected = ?
             WHERE id = ?",
            params![original_id, confidence, auto_detected as i32, proxy_id],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(())
    }

    /// Remove one specific master/proxy pair from the junction table.
    /// If no links remain for `proxy_id` afterwards, the denormalized
    /// `videos.proxy_of` columns are cleared as well so the row stops
    /// reading as a proxy. When some links remain, `videos.proxy_of`
    /// is re-pointed to any remaining master (lexicographically
    /// smallest, for determinism).
    pub fn remove_proxy_link(&self, master_id: &str, proxy_id: &str) -> Result<()> {
        let conn = self.get_connection()?;
        conn.execute(
            "DELETE FROM proxy_links WHERE master_id = ? AND proxy_id = ?",
            params![master_id, proxy_id],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        // Find a remaining master (if any) to keep the denormalized
        // pointer in sync.  The MIN() picks deterministically so
        // multiple clients see the same primary.
        let remaining: Option<(String, Option<f64>, i32)> = conn
            .query_row(
                "SELECT master_id, confidence, auto_detected
                 FROM proxy_links
                 WHERE proxy_id = ?
                 ORDER BY master_id
                 LIMIT 1",
                [proxy_id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .optional()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        if let Some((m, conf, auto)) = remaining {
            conn.execute(
                "UPDATE videos
                 SET proxy_of = ?, proxy_confidence = ?, proxy_auto_detected = ?
                 WHERE id = ?",
                params![m, conf, auto, proxy_id],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        } else {
            conn.execute(
                "UPDATE videos
                 SET proxy_of = NULL, proxy_confidence = NULL, proxy_auto_detected = 0
                 WHERE id = ?",
                [proxy_id],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        }
        Ok(())
    }

    /// List every proxy linked to `video_id` via the proxy_links
    /// junction table. Returns the proxy rows + the link's confidence
    /// + auto-detected flag, sorted by descending pixel count so the
    /// highest-res proxy comes first.
    pub fn list_proxies(&self, video_id: &str) -> Result<Vec<ProxyRecord>> {
        let conn = self.get_connection()?;
        let mut stmt = conn
            .prepare(
                "SELECT v.id, v.filename, v.path, v.file_size_bytes,
                        COALESCE(m.width, 0), COALESCE(m.height, 0),
                        pl.confidence, pl.auto_detected
                 FROM proxy_links pl
                 JOIN videos v ON pl.proxy_id = v.id
                 LEFT JOIN metadata m ON v.id = m.video_id
                 WHERE pl.master_id = ?
                 ORDER BY (COALESCE(m.width,0) * COALESCE(m.height,0)) DESC",
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        let rows = stmt
            .query_map([video_id], |row| {
                Ok(ProxyRecord {
                    id: row.get(0)?,
                    filename: row.get(1)?,
                    path: row.get(2)?,
                    file_size_bytes: row.get(3)?,
                    width: row.get(4)?,
                    height: row.get(5)?,
                    proxy_confidence: row.get::<_, Option<f64>>(6)?.unwrap_or(0.0),
                    auto_detected: row.get::<_, i32>(7)? != 0,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(rows)
    }

    /// List every master that `proxy_video_id` is linked to. The
    /// inverse of [`Self::list_proxies`]. Sorted by descending pixel
    /// count so the highest-res master comes first.
    pub fn list_masters_for_proxy(&self, proxy_video_id: &str) -> Result<Vec<ProxyRecord>> {
        let conn = self.get_connection()?;
        let mut stmt = conn
            .prepare(
                "SELECT v.id, v.filename, v.path, v.file_size_bytes,
                        COALESCE(m.width, 0), COALESCE(m.height, 0),
                        pl.confidence, pl.auto_detected
                 FROM proxy_links pl
                 JOIN videos v ON pl.master_id = v.id
                 LEFT JOIN metadata m ON v.id = m.video_id
                 WHERE pl.proxy_id = ?
                 ORDER BY (COALESCE(m.width,0) * COALESCE(m.height,0)) DESC",
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        let rows = stmt
            .query_map([proxy_video_id], |row| {
                Ok(ProxyRecord {
                    id: row.get(0)?,
                    filename: row.get(1)?,
                    path: row.get(2)?,
                    file_size_bytes: row.get(3)?,
                    width: row.get(4)?,
                    height: row.get(5)?,
                    proxy_confidence: row.get::<_, Option<f64>>(6)?.unwrap_or(0.0),
                    auto_detected: row.get::<_, i32>(7)? != 0,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(rows)
    }

    /// What is this video a proxy for, if anything?
    pub fn get_proxy_target(&self, video_id: &str) -> Result<Option<String>> {
        let conn = self.get_connection()?;
        let result: Option<String> = conn
            .query_row(
                "SELECT proxy_of FROM videos WHERE id = ?",
                [video_id],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .flatten();
        Ok(result)
    }

    /// Clear *all* proxy links for `video_id` (the row stops being a
    /// proxy of anything). Used when the user fully un-marks a proxy
    /// — for breaking just one specific master/proxy pair see
    /// [`Self::remove_proxy_link`].
    pub fn clear_proxy_of(&self, video_id: &str) -> Result<()> {
        let conn = self.get_connection()?;
        conn.execute(
            "DELETE FROM proxy_links WHERE proxy_id = ?",
            [video_id],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        conn.execute(
            "UPDATE videos
             SET proxy_of = NULL, proxy_confidence = NULL, proxy_auto_detected = 0
             WHERE id = ?",
            [video_id],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(())
    }

    /// Candidate rows used by the proxy auto-detector. Returns every
    /// video in the catalog regardless of existing proxy_links: with
    /// many-to-many proxy relationships, a video that's already a
    /// proxy of one master can still be linked to additional masters
    /// that arrive later, and a master can pick up more proxies as
    /// new lower-resolution variants appear. The detection algorithm
    /// itself decides masters vs proxies from pixel counts.
    pub fn list_for_proxy_detection(&self) -> Result<Vec<ProxyDetectCandidate>> {
        let conn = self.get_connection()?;
        let mut stmt = conn
            .prepare(
                "SELECT v.id, v.filename, v.path, v.group_id,
                        COALESCE(m.width, 0), COALESCE(m.height, 0),
                        COALESCE(m.fps, 0), COALESCE(m.frame_count, 0),
                        COALESCE(m.camera_model, ''),
                        COALESCE(v.file_size_bytes, 0)
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
                Ok(ProxyDetectCandidate {
                    id: row.get(0)?,
                    filename: row.get(1)?,
                    path,
                    parent_dir,
                    group_id: row.get(3)?,
                    width: row.get(4)?,
                    height: row.get(5)?,
                    fps: row.get(6)?,
                    frame_count: row.get(7)?,
                    camera_model: row.get(8)?,
                    file_size_bytes: row.get(9)?,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(rows)
    }

    /// Fetch the proxy-detection candidate row for a single video — the
    /// per-video equivalent of [`Self::list_for_proxy_detection`].
    /// Returns `None` when the row is missing entirely. Already-linked
    /// proxies still come back so the incremental worker can consider
    /// them as candidates for new master pairings.
    pub fn get_proxy_candidate(&self, video_id: &str) -> Result<Option<ProxyDetectCandidate>> {
        let conn = self.get_connection()?;
        let result = conn
            .query_row(
                "SELECT v.id, v.filename, v.path, v.group_id,
                        COALESCE(m.width, 0), COALESCE(m.height, 0),
                        COALESCE(m.fps, 0), COALESCE(m.frame_count, 0),
                        COALESCE(m.camera_model, ''),
                        COALESCE(v.file_size_bytes, 0)
                 FROM videos v LEFT JOIN metadata m ON v.id = m.video_id
                 WHERE v.id = ?",
                [video_id],
                |row| {
                    let path: String = row.get(2)?;
                    let parent_dir = std::path::Path::new(&path)
                        .parent()
                        .and_then(|p| p.to_str())
                        .unwrap_or("")
                        .to_string();
                    Ok(ProxyDetectCandidate {
                        id: row.get(0)?,
                        filename: row.get(1)?,
                        path,
                        parent_dir,
                        group_id: row.get(3)?,
                        width: row.get(4)?,
                        height: row.get(5)?,
                        fps: row.get(6)?,
                        frame_count: row.get(7)?,
                        camera_model: row.get(8)?,
                        file_size_bytes: row.get(9)?,
                    })
                },
            )
            .optional()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(result)
    }

    /// Proxy-detection candidates that share a parent directory with
    /// the given path. Used by the incremental post-index worker to
    /// find a new video's bucket-mates without re-loading the entire
    /// catalog. Excludes the video itself; *includes* rows already
    /// linked as proxies so the worker can attach a freshly-indexed
    /// master to existing proxies it matches.
    ///
    /// Uses a half-open range query on `v.path` so SQLite can use
    /// `idx_videos_path` rather than a full table scan — important
    /// when this is called per-video during a scan. The Rust-side
    /// post-filter eliminates rows in *nested* subdirectories that
    /// share the same prefix.
    pub fn list_proxy_candidates_in_dir(
        &self,
        parent_dir: &str,
        exclude_id: &str,
    ) -> Result<Vec<ProxyDetectCandidate>> {
        let conn = self.get_connection()?;
        let (lower, upper) = path_prefix_range(&(parent_dir.to_string() + "/"));
        let mut stmt = conn
            .prepare(
                "SELECT v.id, v.filename, v.path, v.group_id,
                        COALESCE(m.width, 0), COALESCE(m.height, 0),
                        COALESCE(m.fps, 0), COALESCE(m.frame_count, 0),
                        COALESCE(m.camera_model, ''),
                        COALESCE(v.file_size_bytes, 0)
                 FROM videos v LEFT JOIN metadata m ON v.id = m.video_id
                 WHERE v.id != ?
                   AND v.path >= ? AND v.path < ?",
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        let rows = stmt
            .query_map(params![exclude_id, lower, upper], |row| {
                let path: String = row.get(2)?;
                let parent_dir = std::path::Path::new(&path)
                    .parent()
                    .and_then(|p| p.to_str())
                    .unwrap_or("")
                    .to_string();
                Ok(ProxyDetectCandidate {
                    id: row.get(0)?,
                    filename: row.get(1)?,
                    path,
                    parent_dir,
                    group_id: row.get(3)?,
                    width: row.get(4)?,
                    height: row.get(5)?,
                    fps: row.get(6)?,
                    frame_count: row.get(7)?,
                    camera_model: row.get(8)?,
                    file_size_bytes: row.get(9)?,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        // The range query also matches nested subdirectories — keep
        // only rows whose computed parent_dir matches exactly.
        let rows = rows
            .into_iter()
            .filter(|c| c.parent_dir == parent_dir)
            .collect();
        Ok(rows)
    }

    /// Proxy-detection candidates that belong to a given group.
    /// Excludes the video itself; includes already-linked proxies so
    /// the worker can attach additional masters where appropriate.
    pub fn list_proxy_candidates_in_group(
        &self,
        group_id: &str,
        exclude_id: &str,
    ) -> Result<Vec<ProxyDetectCandidate>> {
        let conn = self.get_connection()?;
        let mut stmt = conn
            .prepare(
                "SELECT v.id, v.filename, v.path, v.group_id,
                        COALESCE(m.width, 0), COALESCE(m.height, 0),
                        COALESCE(m.fps, 0), COALESCE(m.frame_count, 0),
                        COALESCE(m.camera_model, ''),
                        COALESCE(v.file_size_bytes, 0)
                 FROM videos v LEFT JOIN metadata m ON v.id = m.video_id
                 WHERE v.group_id = ?
                   AND v.id != ?",
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        let rows = stmt
            .query_map(params![group_id, exclude_id], |row| {
                let path: String = row.get(2)?;
                let parent_dir = std::path::Path::new(&path)
                    .parent()
                    .and_then(|p| p.to_str())
                    .unwrap_or("")
                    .to_string();
                Ok(ProxyDetectCandidate {
                    id: row.get(0)?,
                    filename: row.get(1)?,
                    path,
                    parent_dir,
                    group_id: row.get(3)?,
                    width: row.get(4)?,
                    height: row.get(5)?,
                    fps: row.get(6)?,
                    frame_count: row.get(7)?,
                    camera_model: row.get(8)?,
                    file_size_bytes: row.get(9)?,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(rows)
    }

    /// Fetch the auto-grouping candidate row for a single video — the
    /// per-video equivalent of [`Self::list_for_auto_grouping`].
    pub fn get_group_candidate(&self, video_id: &str) -> Result<Option<AutoGroupCandidate>> {
        let conn = self.get_connection()?;
        let result = conn
            .query_row(
                "SELECT v.id, v.filename, v.path, v.group_id,
                        COALESCE(m.duration_ms, 0), COALESCE(m.frame_count, 0),
                        COALESCE(m.width, 0), COALESCE(m.height, 0),
                        COALESCE(m.fps, 0),
                        COALESCE(m.camera_model, ''),
                        COALESCE(strftime('%s', v.modified_at) * 1000, 0)
                 FROM videos v LEFT JOIN metadata m ON v.id = m.video_id
                 WHERE v.id = ? AND v.proxy_of IS NULL",
                [video_id],
                |row| {
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
                        camera_model: row.get(9)?,
                        modified_at_ms: row.get(10)?,
                    })
                },
            )
            .optional()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(result)
    }

    /// Auto-grouping candidates that share a parent directory — used by
    /// the post-index worker to evaluate a single new video against its
    /// potential siblings without scanning the whole catalog.
    /// Excludes the video itself; includes already-grouped videos (so
    /// the worker can choose to join an existing group).
    ///
    /// Uses the same half-open range query on `v.path` as
    /// [`Self::list_proxy_candidates_in_dir`] so SQLite can use
    /// `idx_videos_path` and the call is cheap per-video.
    pub fn list_group_candidates_in_dir(
        &self,
        parent_dir: &str,
        exclude_id: &str,
    ) -> Result<Vec<AutoGroupCandidate>> {
        let conn = self.get_connection()?;
        let (lower, upper) = path_prefix_range(&(parent_dir.to_string() + "/"));
        let mut stmt = conn
            .prepare(
                "SELECT v.id, v.filename, v.path, v.group_id,
                        COALESCE(m.duration_ms, 0), COALESCE(m.frame_count, 0),
                        COALESCE(m.width, 0), COALESCE(m.height, 0),
                        COALESCE(m.fps, 0),
                        COALESCE(m.camera_model, ''),
                        COALESCE(strftime('%s', v.modified_at) * 1000, 0)
                 FROM videos v LEFT JOIN metadata m ON v.id = m.video_id
                 WHERE v.proxy_of IS NULL
                   AND v.id != ?
                   AND v.path >= ? AND v.path < ?",
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        let rows = stmt
            .query_map(params![exclude_id, lower, upper], |row| {
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
                    camera_model: row.get(9)?,
                    modified_at_ms: row.get(10)?,
                })
            })
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        let rows = rows
            .into_iter()
            .filter(|c| c.parent_dir == parent_dir)
            .collect();
        Ok(rows)
    }

    /// Add a video to an existing group. No-op if the video is already
    /// a member. Used by the incremental post-index worker when a new
    /// video matches an existing group instead of forming a fresh one.
    pub fn add_video_to_group(&self, group_id: &str, video_id: &str) -> Result<()> {
        let conn = self.get_connection()?;
        conn.execute(
            "UPDATE videos SET group_id = ? WHERE id = ?",
            params![group_id, video_id],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(())
    }

    /// Re-point every proxy currently linked to `old_anchor_id` so
    /// that it's *also* linked to `new_anchor_id`. Used when the
    /// incremental post-index worker promotes a freshly-indexed
    /// higher-resolution sibling above an existing anchor.
    ///
    /// Additive semantics now that proxies can have many masters:
    /// existing `(old_anchor, proxy)` rows are preserved, and new
    /// `(new_anchor, proxy)` rows are inserted alongside them. The
    /// denormalized `videos.proxy_of` field still gets rewritten so
    /// callers who only look at the single-master pointer see the new
    /// anchor.
    ///
    /// Returns the number of new junction rows inserted (i.e. the
    /// number of old proxies that didn't already have a link to the
    /// new anchor).
    pub fn repoint_proxies(&self, old_anchor_id: &str, new_anchor_id: &str) -> Result<usize> {
        let conn = self.get_connection()?;
        let n = conn
            .execute(
                "INSERT OR IGNORE INTO proxy_links (master_id, proxy_id, confidence, auto_detected)
                 SELECT ?, proxy_id, confidence, auto_detected
                 FROM proxy_links
                 WHERE master_id = ?",
                params![new_anchor_id, old_anchor_id],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        conn.execute(
                "UPDATE videos SET proxy_of = ? WHERE proxy_of = ?",
                params![new_anchor_id, old_anchor_id],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(n)
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
                        COALESCE(m.fps, 0),
                        COALESCE(m.camera_model, ''),
                        COALESCE(strftime('%s', v.modified_at) * 1000, 0)
                 FROM videos v LEFT JOIN metadata m ON v.id = m.video_id
                 WHERE v.proxy_of IS NULL",
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
                    camera_model: row.get(9)?,
                    modified_at_ms: row.get(10)?,
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

/// A user-defined named place (e.g. "Home", "Yosemite Valley Visitor
/// Center"). Clients resolve a video's GPS into one of these by picking
/// the nearest entry within `radius_m`. The timestamps are Unix
/// milliseconds (UTC); SQLite returns them as 0 when unset.
#[derive(Debug, Clone)]
pub struct NamedLocationRecord {
    pub id: String,
    pub name: String,
    pub latitude: f64,
    pub longitude: f64,
    pub radius_m: f64,
    pub created_at_ms: i64,
    pub updated_at_ms: i64,
}

/// One geotagged video — what the global-map view needs to render a pin.
#[derive(Debug, Clone)]
pub struct VideoLocationRecord {
    pub id: String,
    pub filename: String,
    pub path: String,
    pub latitude: f64,
    pub longitude: f64,
    pub altitude: f64,
    pub has_thumbnail: bool,
}

#[derive(Debug, Clone)]
pub struct VideoGroupRecord {
    pub id: String,
    pub name: Option<String>,
    pub base_name: Option<String>,
    pub preferred_video_id: Option<String>,
}

/// One row returned by [`Database::list_proxies`]. Bundles enough info
/// for the client to render a "proxies of this video" sub-list: filename,
/// path, resolution, on-disk size, plus the auto-detection metadata.
#[derive(Debug, Clone)]
pub struct ProxyRecord {
    pub id: String,
    pub filename: String,
    pub path: String,
    pub file_size_bytes: Option<i64>,
    pub width: i32,
    pub height: i32,
    /// dHash similarity at detection time (1.0 for user-marked or
    /// VideoRoom-generated proxies; ~0.9–1.0 for auto-detected).
    pub proxy_confidence: f64,
    pub auto_detected: bool,
}

/// Candidate row used by the proxy auto-detector. Only videos that
/// aren't *already* marked as proxies appear here, because a proxy of a
/// proxy makes no sense (the auto-detector instead pivots through the
/// original).
#[derive(Debug, Clone)]
pub struct ProxyDetectCandidate {
    pub id: String,
    pub filename: String,
    pub path: String,
    pub parent_dir: String,
    /// If this video belongs to an auto-detected group, this is the group ID.
    /// Proxy detection uses the group as the bucket when available — auto-grouping
    /// already validated that the members share the same clip identity.
    pub group_id: Option<String>,
    pub width: i32,
    pub height: i32,
    pub fps: f64,
    pub frame_count: i64,
    pub camera_model: String,
    /// Raw byte size of the file on disk. Used to distinguish "same-resolution
    /// lower-bitrate codec proxy" (e.g. ProRes-444 UHQ vs ProRes-422 MQ) from
    /// true duplicates — a ≥ 2.5× size ratio at the same resolution is a
    /// strong signal for a codec-quality proxy relationship.
    pub file_size_bytes: i64,
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
    /// Camera model from the file's EXIF (empty when unknown). Used by
    /// the auto-grouper to refuse to mix variants from different cameras
    /// even when the filenames happen to look similar.
    pub camera_model: String,
    /// File mtime in Unix-ms. Used by the auto-grouper to pick the most
    /// recently written copy as the stack's preferred leader (ties
    /// broken on resolution, see `grouping.rs`).
    pub modified_at_ms: i64,
}
