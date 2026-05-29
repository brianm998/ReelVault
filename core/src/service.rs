// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

use crate::config::Config;
use crate::db::Database;
use crate::error::{Result, VideoRoomError};
use crate::indexing::IndexingEngine;
use crate::path_templates::expand_path_templates;
use crate::search::SearchEngine;
use crate::thumbnails::ThumbnailGenerator;
use crate::watcher::{CatalogChange, LibraryWatcher};
use std::pin::Pin;
use std::sync::Arc;
use tokio::sync::broadcast;
use tokio_stream::Stream;
use tokio_stream::wrappers::ReceiverStream;
use tonic::{Request, Response, Status};

// Import generated protobuf code
pub mod videoroom {
    #![allow(clippy::doc_lazy_continuation)]
    tonic::include_proto!("videoroom");
}

use videoroom::video_room_server::{VideoRoom as VideoRoomTrait, VideoRoomServer};
use videoroom::*;

pub use videoroom::video_room_server;

pub struct VideoRoomService {
    db: Arc<Database>,
    config: Arc<Config>,
    /// Unix millis at which the currently-open catalog was opened. Reset by
    /// OpenCatalog. Used to expose `opened_at_ms` in `CatalogInfo`.
    opened_at_ms: Arc<std::sync::RwLock<i64>>,
    /// Per-video locks that prevent multiple concurrent get_thumbnail requests
    /// from each kicking off a redundant scrub-frame generation pass for the
    /// same video. Map entries hold an Arc<Mutex<()>> per video_id; the locks
    /// live as long as there are interested tasks.
    scrub_locks: Arc<tokio::sync::Mutex<std::collections::HashMap<String, Arc<tokio::sync::Mutex<()>>>>>,
    /// Broadcast bus for live `CatalogEvent`s. The watcher publishes
    /// `CatalogChange`s here as it observes disk activity; the
    /// `SubscribeCatalogEvents` RPC opens a `Receiver` per connected client.
    /// Capacity = 256: a burst larger than this lags slow subscribers (they
    /// see `Lagged(n)` and reconnect), which is the right tradeoff vs.
    /// growing memory if a client stalls.
    catalog_events: broadcast::Sender<CatalogChange>,
    /// The active watcher. Reset on `UpdateWatchSettings` or on
    /// `AddLibraryLocation` / `RemoveLibraryLocation`. `None` when the user
    /// has disabled live updates (config.watch_enabled = false).
    watcher: Arc<tokio::sync::Mutex<Option<LibraryWatcher>>>,
    /// Current watch settings — mutable via `UpdateWatchSettings`.
    /// Persisted to the config table on save. Separate from `Config`
    /// (which is immutable after load) so we don't have to clone it
    /// just to change three integers.
    watch_settings: Arc<tokio::sync::RwLock<WatchSettingsCurrent>>,
}

/// Snapshot of the watcher knobs. Live in a RwLock so the gRPC handlers
/// can mutate them on UpdateWatchSettings without rebuilding the whole
/// `Config`.
#[derive(Debug, Clone, Copy)]
pub struct WatchSettingsCurrent {
    pub enabled: bool,
    pub write_settle_ms: i64,
    pub poll_interval_ms: i64,
}

impl VideoRoomService {
    pub fn new(db: Arc<Database>, config: Arc<Config>) -> Self {
        // If `db` was constructed with an already-open catalog, treat
        // "now" as its open timestamp.
        let initial_opened = if db.current_path().is_some() {
            now_ms()
        } else {
            0
        };
        let (catalog_tx, _) = broadcast::channel::<CatalogChange>(256);

        let watch_settings = WatchSettingsCurrent {
            enabled: config.watch_enabled,
            write_settle_ms: config.watch_write_settle_ms,
            poll_interval_ms: config.watch_poll_interval_ms,
        };

        let service = VideoRoomService {
            db,
            config: Arc::clone(&config),
            opened_at_ms: Arc::new(std::sync::RwLock::new(initial_opened)),
            scrub_locks: Arc::new(tokio::sync::Mutex::new(std::collections::HashMap::new())),
            catalog_events: catalog_tx,
            watcher: Arc::new(tokio::sync::Mutex::new(None)),
            watch_settings: Arc::new(tokio::sync::RwLock::new(watch_settings)),
        };

        // Boot the watcher if config says so and a catalog is open.
        // Done on a Tokio task so `new` stays synchronous.
        if config.watch_enabled && service.db.current_path().is_some() {
            let svc = service.clone_for_watcher();
            tokio::spawn(async move {
                svc.restart_watcher().await;
            });
        }

        service
    }

    /// Light handle used by `tokio::spawn`-ed helpers that need to restart
    /// the watcher (after AddLibraryLocation, UpdateWatchSettings, etc.).
    /// Clones the Arcs only — not a deep copy.
    fn clone_for_watcher(&self) -> ServiceWatcherHandle {
        ServiceWatcherHandle {
            db: Arc::clone(&self.db),
            config: Arc::clone(&self.config),
            catalog_events: self.catalog_events.clone(),
            watcher: Arc::clone(&self.watcher),
            watch_settings: Arc::clone(&self.watch_settings),
        }
    }

    /// Get-or-insert the per-video lock used by scrub generation.
    async fn scrub_lock_for(&self, video_id: &str) -> Arc<tokio::sync::Mutex<()>> {
        let mut map = self.scrub_locks.lock().await;
        map.entry(video_id.to_string())
            .or_insert_with(|| Arc::new(tokio::sync::Mutex::new(())))
            .clone()
    }

    pub fn into_server(self) -> VideoRoomServer<Self> {
        VideoRoomServer::new(self)
    }

    /// Load the user's custom camera-name overrides from the catalog
    /// config table and pre-normalise them into the map shape that
    /// [`camera_names::marketing_name_for_with_custom`] expects.
    ///
    /// Storage: one JSON entry under the `custom_camera_names` config
    /// key, shape `[{"internal": "...", "marketing": "..."}, …]`.
    /// Missing key, blank value, or a deserialisation error all yield
    /// an empty map (i.e. fall back to built-in only) — we never want
    /// a malformed user mapping to crash the metadata pipeline.
    fn load_custom_camera_names(&self) -> std::collections::HashMap<String, String> {
        use crate::config::CustomCameraName;
        let raw: Option<String> = self
            .db
            .get_connection()
            .ok()
            .and_then(|c| {
                c.query_row(
                    "SELECT value FROM config WHERE key = ?",
                    ["custom_camera_names"],
                    |row| row.get::<_, String>(0),
                )
                .ok()
            });
        let raw = match raw {
            Some(s) if !s.trim().is_empty() => s,
            _ => return std::collections::HashMap::new(),
        };
        let parsed: Vec<CustomCameraName> = match serde_json::from_str(&raw) {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!(
                    "custom_camera_names JSON parse failed (falling back to \
                     built-in only): {}",
                    e
                );
                return std::collections::HashMap::new();
            }
        };
        crate::camera_names::build_custom_overrides(
            parsed.into_iter().map(|e| (e.internal, e.marketing)),
        )
    }

    /// Read the raw `Vec<CustomCameraName>` stored in the config table
    /// (unfiltered, unsorted). Used by the editor RPCs so we can mutate
    /// the list and write it back without losing the user's chosen
    /// `internal`-side casing.
    fn read_custom_camera_names(&self) -> Vec<crate::config::CustomCameraName> {
        let raw: Option<String> = self
            .db
            .get_connection()
            .ok()
            .and_then(|c| {
                c.query_row(
                    "SELECT value FROM config WHERE key = ?",
                    ["custom_camera_names"],
                    |row| row.get::<_, String>(0),
                )
                .ok()
            });
        match raw {
            Some(s) if !s.trim().is_empty() => {
                serde_json::from_str(&s).unwrap_or_default()
            }
            _ => Vec::new(),
        }
    }

    /// Serialise + persist a custom-camera-name list to the config
    /// table. Empty input is fine — it just stores `"[]"` which
    /// `load_custom_camera_names` treats as "no overrides".
    fn write_custom_camera_names(
        &self,
        entries: &[crate::config::CustomCameraName],
    ) -> Result<()> {
        let json = serde_json::to_string(entries).map_err(|e| {
            VideoRoomError::DatabaseError(format!("custom_camera_names serialize failed: {e}"))
        })?;
        let conn = self.db.get_connection()?;
        conn.execute(
            "INSERT INTO config (key, value) VALUES (?, ?)
             ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = CURRENT_TIMESTAMP",
            rusqlite::params!["custom_camera_names", json, json],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        Ok(())
    }

    fn get_video_metadata_sync(&self, video_id: &str) -> Result<VideoMetadata> {
        let db = self.db.as_ref();
        let video = db
            .get_video(video_id)
            .and_then(|v| v.ok_or_else(|| VideoRoomError::VideoNotFound(video_id.to_string())))?;

        let conn = db.get_connection()?;
        let metadata_row = conn
            .query_row(
                "SELECT
                    duration_ms, codec_video, codec_audio, width, height,
                    fps, bitrate, color_space, hdr, audio_channels, audio_sample_rate,
                    creation_date, camera_model, lens_model, gps_latitude, gps_longitude,
                    gps_altitude,
                    iso, aperture, exposure_time_s, focal_length_mm,
                    exposure_mode, exposure_program, white_balance
                 FROM metadata WHERE video_id = ?",
                [video_id],
                |row| {
                    Ok((
                        row.get::<_, i64>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        row.get::<_, Option<String>>(2)?,
                        row.get::<_, i32>(3)?,
                        row.get::<_, i32>(4)?,
                        row.get::<_, f64>(5)?,
                        row.get::<_, i64>(6)?,
                        row.get::<_, Option<String>>(7)?,
                        row.get::<_, i32>(8)? != 0,
                        row.get::<_, i32>(9)?,
                        row.get::<_, i32>(10)?,
                        row.get::<_, Option<i64>>(11)?,
                        row.get::<_, Option<String>>(12)?,
                        row.get::<_, Option<String>>(13)?,
                        row.get::<_, Option<f64>>(14)?,
                        row.get::<_, Option<f64>>(15)?,
                        row.get::<_, Option<f64>>(16)?,
                        row.get::<_, Option<i64>>(17)?,
                        row.get::<_, Option<f64>>(18)?,
                        row.get::<_, Option<f64>>(19)?,
                        row.get::<_, Option<f64>>(20)?,
                        row.get::<_, Option<String>>(21)?,
                        row.get::<_, Option<String>>(22)?,
                        row.get::<_, Option<String>>(23)?,
                    ))
                },
            )
            .ok();

        let tags = db.get_video_tags(video_id).unwrap_or_default();
        let notes = db.get_notes(video_id).unwrap_or_default().unwrap_or_default();
        let (rating, color_label) = db
            .get_video_user_marks(video_id)
            .unwrap_or((0, String::new()));

        let row = metadata_row;

        if row.is_none() {
            tracing::warn!("No metadata row found for video {}", video_id);
        }

        let (duration_ms, codec_video, codec_audio, width, height, fps, bitrate,
             color_space, hdr, audio_channels, audio_sample_rate, creation_date,
             camera_model, lens_model, gps_lat, gps_lon, gps_alt,
             iso, aperture, exposure_time_s, focal_length_mm,
             exposure_mode, exposure_program, white_balance) =
            row.unwrap_or((0, None, None, 0, 0, 0.0, 0, None, false, 0, 0, None, None, None, None, None, None,
                           None, None, None, None, None, None, None));

        let camera_model_str = camera_model.unwrap_or_default();
        // Resolve marketing name with the user's custom overrides
        // layered on top of the built-in mapping table; fall back to
        // the internal name when no mapping is known. Clients detect
        // "no mapping" by comparing `camera_display_name` to
        // `camera_model` — equal means no mapping, so they hide the
        // info-icon affordance that flips between names.
        let custom_overrides = self.load_custom_camera_names();
        let camera_display_name =
            crate::camera_names::marketing_name_for_with_custom(
                &camera_model_str,
                &custom_overrides,
            )
            .unwrap_or_else(|| camera_model_str.clone());

        Ok(VideoMetadata {
            id: video_id.to_string(),
            filename: video.filename,
            path: video.path,
            size_bytes: video.file_size_bytes.unwrap_or(0),
            duration_ms,
            width,
            height,
            fps,
            bitrate,
            codec_video: codec_video.unwrap_or_default(),
            color_space: color_space.unwrap_or_default(),
            hdr,
            codec_audio: codec_audio.unwrap_or_default(),
            audio_channels,
            audio_sample_rate,
            creation_date: creation_date.unwrap_or(0),
            modification_date: 0,
            indexed_at: video.indexed_at,
            camera_model: camera_model_str,
            lens_model: lens_model.unwrap_or_default(),
            gps_latitude: gps_lat.unwrap_or(0.0),
            gps_longitude: gps_lon.unwrap_or(0.0),
            gps_altitude: gps_alt.unwrap_or(0.0),
            tags,
            collections: Vec::new(),
            notes,
            volume_id: video.volume_id.unwrap_or_default(),
            is_online: video.is_online != 0,
            rating,
            color_label,
            camera_display_name,
            iso: iso.unwrap_or(0) as i32,
            aperture: aperture.unwrap_or(0.0),
            exposure_time_s: exposure_time_s.unwrap_or(0.0),
            focal_length_mm: focal_length_mm.unwrap_or(0.0),
            exposure_mode: exposure_mode.unwrap_or_default(),
            exposure_program: exposure_program.unwrap_or_default(),
            white_balance: white_balance.unwrap_or_default(),
        })
    }

    fn build_video_summary(&self, video_id: &str, filename: &str, path: &str,
                           size_bytes: i64, indexed_at: i64) -> VideoSummary {
        // Try to get metadata for the video. Camera model is included so
        // the grid's configurable "Camera" top-of-card stat slot can
        // render without a per-video VideoMetadata roundtrip.
        let conn = self.db.get_connection().ok();
        let meta = conn.and_then(|c| {
            c.query_row(
                "SELECT duration_ms, width, height, fps, codec_video, codec_audio,
                        creation_date, camera_model, gps_latitude, gps_longitude,
                        lens_model, iso, aperture, exposure_time_s, focal_length_mm
                 FROM metadata WHERE video_id = ?",
                [video_id],
                |row| {
                    Ok((
                        row.get::<_, i64>(0)?,
                        row.get::<_, i32>(1)?,
                        row.get::<_, i32>(2)?,
                        row.get::<_, f64>(3)?,
                        row.get::<_, Option<String>>(4)?,
                        row.get::<_, Option<String>>(5)?,
                        row.get::<_, Option<i64>>(6)?,
                        row.get::<_, Option<String>>(7)?,
                        row.get::<_, Option<f64>>(8)?,
                        row.get::<_, Option<f64>>(9)?,
                        row.get::<_, Option<String>>(10)?,
                        row.get::<_, Option<i64>>(11)?,
                        row.get::<_, Option<f64>>(12)?,
                        row.get::<_, Option<f64>>(13)?,
                        row.get::<_, Option<f64>>(14)?,
                    ))
                },
            ).ok()
        });

        let tags = self.db.get_video_tags(video_id).unwrap_or_default();

        let (duration_ms, width, height, fps, codec_video, codec_audio, creation_date,
             camera_model, gps_lat, gps_lon, lens_model, iso, aperture,
             exposure_time_s, focal_length_mm) =
            meta.unwrap_or((0, 0, 0, 0.0, None, None, None, None, None, None,
                            None, None, None, None, None));

        // Resolve the marketing-friendly camera name the same way
        // build_video_metadata does — user overrides on top of the
        // built-in mapping table, falling back to the raw EXIF string
        // when no mapping is known. Clients detect "no mapping" by
        // comparing the two and may hide the affordance that flips
        // between them.
        let camera_model_str = camera_model.unwrap_or_default();
        let camera_display_name = if camera_model_str.is_empty() {
            String::new()
        } else {
            let custom_overrides = self.load_custom_camera_names();
            crate::camera_names::marketing_name_for_with_custom(
                &camera_model_str,
                &custom_overrides,
            )
            .unwrap_or_else(|| camera_model_str.clone())
        };

        // Check if thumbnail exists
        let thumb_path = self.config.thumbnail_cache_path.join(format!("{}_medium.jpg", video_id));
        let has_thumbnail = thumb_path.exists();

        // Proxy info. `proxy_of` lets the grid hide proxies under their
        // source; `proxy_count` powers the "this video has proxies"
        // badge on the source's card. Both are cheap (single indexed
        // SQL each), so we surface them on every summary.
        let proxy_of = self.db.get_proxy_target(video_id).unwrap_or(None).unwrap_or_default();
        let proxy_count = self.db.list_proxies(video_id).map(|v| v.len() as i32).unwrap_or(0);

        // Group info
        let group_id_opt = self.db.get_video_group_id(video_id).unwrap_or(None);
        let (group_id, group_size, group_preferred_id, group_preferred_path) = match &group_id_opt {
            Some(gid) => {
                let size = self.db.count_group_members(gid).unwrap_or(1) as i32;
                let preferred_id = self
                    .db
                    .get_group(gid)
                    .ok()
                    .flatten()
                    .and_then(|g| g.preferred_video_id)
                    .unwrap_or_else(|| video_id.to_string());
                // Look up the path of the preferred video
                let preferred_path = self
                    .db
                    .get_video(&preferred_id)
                    .ok()
                    .flatten()
                    .map(|v| v.path)
                    .unwrap_or_default();
                (gid.clone(), size, preferred_id, preferred_path)
            }
            None => (String::new(), 1, String::new(), String::new()),
        };

        // Lightroom-style user marks (rating + color label). Defaults to
        // (0, "") when no row exists for this video.
        let (rating, color_label) = self
            .db
            .get_video_user_marks(video_id)
            .unwrap_or((0, String::new()));

        VideoSummary {
            id: video_id.to_string(),
            filename: filename.to_string(),
            path: path.to_string(),
            duration_ms,
            width,
            height,
            codec_video: codec_video.unwrap_or_default(),
            codec_audio: codec_audio.unwrap_or_default(),
            fps,
            size_bytes,
            indexed_at,
            creation_date: creation_date.unwrap_or(0),
            tags,
            has_thumbnail,
            group_id,
            group_size,
            group_preferred_id,
            group_preferred_path,
            proxy_count,
            proxy_of,
            playable_natively: self.config.max_native_playback_height == 0
                || height <= self.config.max_native_playback_height,
            rating,
            color_label,
            camera_model: camera_model_str,
            camera_display_name,
            gps_latitude: gps_lat.unwrap_or(0.0),
            gps_longitude: gps_lon.unwrap_or(0.0),
            lens_model: lens_model.unwrap_or_default(),
            iso: iso.unwrap_or(0) as i32,
            aperture: aperture.unwrap_or(0.0),
            exposure_time_s: exposure_time_s.unwrap_or(0.0),
            focal_length_mm: focal_length_mm.unwrap_or(0.0),
        }
    }
}

#[tonic::async_trait]
impl VideoRoomTrait for VideoRoomService {
    type ScanLibraryStream = Pin<Box<dyn Stream<Item = std::result::Result<ScanProgress, Status>> + Send>>;
    type GenerateProxyStream = Pin<Box<dyn Stream<Item = std::result::Result<ProxyGenerationProgress, Status>> + Send>>;
    type GetThumbnailStream = Pin<Box<dyn Stream<Item = std::result::Result<ThumbnailChunk, Status>> + Send>>;

    async fn list_videos(
        &self,
        request: Request<ListVideosRequest>,
    ) -> std::result::Result<Response<ListVideosResponse>, Status> {
        let req = request.into_inner();
        let limit = if req.limit <= 0 { 50 } else { req.limit as i64 };
        let offset = req.offset.max(0) as i64;

        // Expand tilde in location filter if provided
        let location_filter = if req.location_path.is_empty() {
            String::new()
        } else {
            expand_tilde(&req.location_path)
        };

        // Resolve filter_tags — callers may pass either tag IDs (UUIDs) or
        // human-readable tag names. We accept both: if the string is found as
        // an existing tag name, we use that tag's ID; otherwise we pass the
        // string through and let it match by ID directly.
        let tag_ids: Vec<String> = req
            .filter_tags
            .iter()
            .filter_map(|s| {
                if s.is_empty() {
                    return None;
                }
                if let Ok(Some(tag)) = self.db.get_tag_by_name(s) {
                    Some(tag.id)
                } else {
                    Some(s.clone())
                }
            })
            .collect();

        // Optional geographic proximity filter — set when the user tapped a
        // pin on the global map. Empty / zero values mean "no filter".
        let geo_filter = if req.filter_by_location {
            // Clamp radius to >= 0.01 km to keep the bounding box meaningful.
            let r = req.filter_radius_km.max(0.01);
            Some((req.filter_latitude, req.filter_longitude, r))
        } else {
            None
        };

        // Use grouped listing — returns one representative per group + ungrouped videos
        let (videos, total_count) = self
            .db
            .list_videos_grouped(
                limit,
                offset,
                &req.sort_by,
                req.sort_ascending,
                &location_filter,
                &tag_ids,
                &req.filter_camera,
                &req.filter_lens,
                &req.filter_codec,
                req.filter_capture_year,
                geo_filter,
                req.filter_min_rating,
                &req.filter_color_label,
                if req.collection_id.is_empty() { None } else { Some(req.collection_id.as_str()) },
            )
            .map_err(Status::from)?;

        let video_summaries: Vec<VideoSummary> = videos
            .iter()
            .map(|v| {
                self.build_video_summary(
                    &v.id,
                    &v.filename,
                    &v.path,
                    v.file_size_bytes.unwrap_or(0),
                    v.indexed_at,
                )
            })
            .collect();

        Ok(Response::new(ListVideosResponse {
            videos: video_summaries,
            total_count,
            has_more: (offset + limit) < total_count,
        }))
    }

    async fn search_videos(
        &self,
        request: Request<SearchRequest>,
    ) -> std::result::Result<Response<SearchResponse>, Status> {
        let req = request.into_inner();
        let limit = if req.limit <= 0 { 50 } else { req.limit as i64 };
        let offset = req.offset.max(0) as i64;

        let (results, total_count) = SearchEngine::search(
            self.db.as_ref(),
            &req.query,
            limit,
            offset,
            &req.filter_tags,
        )
        .map_err(Status::from)?;

        let video_summaries: Vec<VideoSummary> = results
            .iter()
            .map(|r| self.build_video_summary(&r.video_id, &r.filename, &r.path, 0, 0))
            .collect();

        Ok(Response::new(SearchResponse {
            videos: video_summaries,
            total_count,
        }))
    }

    async fn get_metadata(
        &self,
        request: Request<GetMetadataRequest>,
    ) -> std::result::Result<Response<VideoMetadata>, Status> {
        let req = request.into_inner();
        let metadata = self
            .get_video_metadata_sync(&req.video_id)
            .map_err(Status::from)?;

        Ok(Response::new(metadata))
    }

    async fn get_thumbnail(
        &self,
        request: Request<GetThumbnailRequest>,
    ) -> std::result::Result<Response<Self::GetThumbnailStream>, Status> {
        let req = request.into_inner();

        let mut thumbnail_data = ThumbnailGenerator::get_thumbnail(
            &self.config.thumbnail_cache_path,
            &req.video_id,
            &req.size,
        )
        .map_err(Status::from)?;

        // On-demand scrub frame generation. If a "scrub_N" frame is requested
        // but doesn't exist yet (e.g. for libraries scanned before this feature
        // existed), generate it now and return it. The work is deduplicated via
        // a per-video Mutex so 10 simultaneous "scrub_0..9" requests for the
        // same video share a single generation pass instead of starting 10.
        // Concurrent ffmpeg invocations across all generation tasks are
        // additionally bounded by the global ffmpeg semaphore.
        if thumbnail_data.is_none() && req.size.starts_with("scrub_") {
            let lock = self.scrub_lock_for(&req.video_id).await;
            let _gen_guard = lock.lock().await;

            // Double-check: another waiter may have finished generation while
            // we were queued on the mutex.
            thumbnail_data = ThumbnailGenerator::get_thumbnail(
                &self.config.thumbnail_cache_path,
                &req.video_id,
                &req.size,
            )
            .map_err(Status::from)?;

            if thumbnail_data.is_none() {
                if let Ok(Some(video)) = self.db.get_video(&req.video_id) {
                    let duration_secs = self
                        .db
                        .get_connection()
                        .ok()
                        .and_then(|c| {
                            c.query_row(
                                "SELECT duration_ms FROM metadata WHERE video_id = ?",
                                [&req.video_id],
                                |row| row.get::<_, i64>(0),
                            )
                            .ok()
                        })
                        .map(|ms| ms as f64 / 1000.0)
                        .unwrap_or(0.0);

                    if duration_secs > 0.0 {
                        let cache = self.config.thumbnail_cache_path.clone();
                        let path = video.path.clone();
                        let video_id = req.video_id.clone();
                        // Generate ALL scrub frames in one shot (next requests
                        // for sibling indices hit the cache).
                        let _ = tokio::task::spawn_blocking(move || {
                            ThumbnailGenerator::generate_scrub_thumbnails(
                                std::path::Path::new(&path),
                                &video_id,
                                &cache,
                                duration_secs,
                            )
                        })
                        .await;

                        thumbnail_data = ThumbnailGenerator::get_thumbnail(
                            &self.config.thumbnail_cache_path,
                            &req.video_id,
                            &req.size,
                        )
                        .map_err(Status::from)?;
                    }
                }
            }

            // Drop the guard before pruning the map. Use try_lock so we don't
            // race with someone who just acquired it after us; if a waiter
            // already took the lock we leave the entry in place for them.
            drop(_gen_guard);
            if Arc::strong_count(&lock) == 2 {
                // Only our reference + the map's reference remain → safe to evict.
                let mut map = self.scrub_locks.lock().await;
                if let Some(existing) = map.get(&req.video_id) {
                    if Arc::strong_count(existing) <= 2 {
                        map.remove(&req.video_id);
                    }
                }
            }
        }

        let (tx, rx) = tokio::sync::mpsc::channel(4);

        tokio::spawn(async move {
            if let Some(data) = thumbnail_data {
                let _ = tx.send(Ok(ThumbnailChunk { data })).await;
            }
        });

        let stream = ReceiverStream::new(rx);
        Ok(Response::new(Box::pin(stream) as Self::GetThumbnailStream))
    }

    async fn add_library_location(
        &self,
        request: Request<AddLocationRequest>,
    ) -> std::result::Result<Response<LocationResponse>, Status> {
        let req = request.into_inner();

        // Expand tilde to home directory first, then expand any path
        // templates (currently $YEAR). Template expansion happens on the
        // backend so the result is authoritative: the client may show a
        // local preview, but this is the source of truth.
        let tilde_expanded = expand_tilde(&req.path);
        let candidate_paths = expand_path_templates(&tilde_expanded);

        // If the template produced no paths, every candidate was either
        // non-existent or the original path (without a template) does not
        // exist. Fall back to the original single-path validation so the
        // existing "path does not exist" error message is preserved.
        if candidate_paths.is_empty() {
            return Ok(Response::new(LocationResponse {
                success: false,
                message: format!("Path does not exist: {}", tilde_expanded),
            }));
        }

        let mut added_paths: Vec<String> = Vec::new();
        let mut errors: Vec<String> = Vec::new();

        for expanded_path in &candidate_paths {
            let path_obj = std::path::Path::new(expanded_path);

            // Validate: must exist and be a directory.
            if !path_obj.exists() {
                errors.push(format!("Path does not exist: {}", expanded_path));
                continue;
            }
            if !path_obj.is_dir() {
                errors.push(format!("Path is not a directory: {}", expanded_path));
                continue;
            }

            // The DB UPSERTs — if the path already exists, the recursive
            // flag is updated in place. So this is idempotent and lets the
            // user toggle recursive on/off by re-adding.
            match self.db.add_library_location(expanded_path, req.recursive) {
                Ok(_) => added_paths.push(expanded_path.clone()),
                Err(e) => errors.push(format!("DB error for {}: {}", expanded_path, e)),
            }
        }

        // Restart the watcher once after all inserts rather than once per
        // path to keep the restart count bounded.
        if !added_paths.is_empty() {
            let handle = self.clone_for_watcher();
            tokio::spawn(async move { handle.restart_watcher().await });
        }

        if added_paths.is_empty() {
            // Nothing was added — surface the first error.
            return Ok(Response::new(LocationResponse {
                success: false,
                message: errors.into_iter().next().unwrap_or_else(|| "No paths added".to_string()),
            }));
        }

        let recursive_label = if req.recursive { "recursive" } else { "non-recursive" };
        let message = if added_paths.len() == 1 {
            format!("Added library location: {} ({})", added_paths[0], recursive_label)
        } else {
            format!(
                "Added {} library locations ({}) — e.g. {}{}",
                added_paths.len(),
                recursive_label,
                added_paths[0],
                if errors.is_empty() { String::new() }
                else { format!("; {} path(s) skipped", errors.len()) }
            )
        };

        Ok(Response::new(LocationResponse {
            success: true,
            message,
        }))
    }

    async fn remove_library_location(
        &self,
        request: Request<RemoveLocationRequest>,
    ) -> std::result::Result<Response<LocationResponse>, Status> {
        let req = request.into_inner();

        let deleted = self.db
            .remove_library_location(&req.path)
            .map_err(Status::from)?;

        // Drop this path from the watcher.
        let handle = self.clone_for_watcher();
        tokio::spawn(async move { handle.restart_watcher().await });

        Ok(Response::new(LocationResponse {
            success: true,
            message: format!(
                "Removed library location '{}' and {} video(s) from the catalog.",
                req.path, deleted
            ),
        }))
    }

    async fn list_library_locations(
        &self,
        _request: Request<ListLocationsRequest>,
    ) -> std::result::Result<Response<ListLocationsResponse>, Status> {
        let locations = self
            .db
            .list_library_locations()
            .map_err(Status::from)?;

        let location_responses: Vec<LibraryLocation> = locations
            .iter()
            .map(|l| LibraryLocation {
                path: l.path.clone(),
                recursive: l.recursive,
                enabled: l.enabled,
                video_count: self.db.count_videos_in_path(&l.path).unwrap_or(0),
                last_scanned: l.last_scanned.unwrap_or(0),
            })
            .collect();

        Ok(Response::new(ListLocationsResponse {
            locations: location_responses,
        }))
    }

    async fn scan_library(
        &self,
        request: Request<ScanLibraryRequest>,
    ) -> std::result::Result<Response<Self::ScanLibraryStream>, Status> {
        let req = request.into_inner();

        let (tx, rx) = tokio::sync::mpsc::channel(100);
        let db = Arc::clone(&self.db);
        let cache_path = self.config.thumbnail_cache_path.clone();
        let location_path = req.location_path.clone();
        let auto_group = req.auto_group;
        let filename_date_rule = crate::indexing::FilenameDateRule::from_proto(
            &req.filename_date_format,
            &req.filename_date_position,
        );

        // Tell live-updates subscribers that an explicit scan is starting,
        // and again when it finishes. The watcher would also pick up new
        // files via FSEvents, but emitting these synthesized events lets
        // clients show a banner even before any video-level events fire.
        let scan_path_for_event = if location_path.is_empty() {
            "all libraries".to_string()
        } else {
            location_path.clone()
        };
        let _ = self.catalog_events.send(CatalogChange::ScanStarted {
            path: scan_path_for_event.clone(),
        });
        let scan_events = self.catalog_events.clone();

        tokio::task::spawn_blocking(move || {
            let send_progress = |tx: &tokio::sync::mpsc::Sender<std::result::Result<ScanProgress, Status>>,
                                  p: &crate::indexing::ScanProgress| {
                let proto_progress = ScanProgress {
                    status: p.status.clone(),
                    videos_found: p.videos_found,
                    videos_indexed: p.videos_indexed,
                    current_file: p.current_file.clone(),
                    progress_percent: p.progress_percent,
                };
                let _ = tx.blocking_send(Ok(proto_progress));
            };

            // Helper to send an error status to the stream
            let send_error = |tx: &tokio::sync::mpsc::Sender<std::result::Result<ScanProgress, Status>>,
                              msg: String| {
                let _ = tx.blocking_send(Ok(ScanProgress {
                    status: "error".to_string(),
                    videos_found: 0,
                    videos_indexed: 0,
                    current_file: msg,
                    progress_percent: 0.0,
                }));
            };

            // Helper to scan a single path with validation
            let scan_one = |scan_path: &std::path::Path, recursive: bool,
                            tx: &tokio::sync::mpsc::Sender<std::result::Result<ScanProgress, Status>>| {
                // Validate path exists
                if !scan_path.exists() {
                    let msg = format!("Path does not exist: {}", scan_path.display());
                    tracing::warn!("{}", msg);
                    send_error(tx, msg);
                    return;
                }

                if !scan_path.is_dir() {
                    let msg = format!("Path is not a directory: {}", scan_path.display());
                    tracing::warn!("{}", msg);
                    send_error(tx, msg);
                    return;
                }

                match IndexingEngine::scan_directory(
                    Arc::clone(&db),
                    scan_path,
                    recursive,
                    &cache_path,
                    filename_date_rule,
                    crate::post_index::Options {
                        // Mirror the existing gate: auto-grouping
                        // is opt-in per scan request; proxy
                        // detection runs unconditionally.
                        auto_group,
                        detect_proxies: true,
                    },
                    |progress| send_progress(tx, progress),
                ) {
                    Ok(_) => {
                        // Run auto-grouping after each successful scan if requested
                        if auto_group {
                            let opts = crate::grouping::AutoGroupOptions::default();
                            match crate::grouping::auto_group(db.as_ref(), &opts) {
                                Ok((groups, videos)) if groups > 0 => {
                                    let msg = format!("Auto-grouped {} videos into {} groups", videos, groups);
                                    tracing::info!("{}", msg);
                                    let _ = tx.blocking_send(Ok(ScanProgress {
                                        status: "grouping".to_string(),
                                        videos_found: 0,
                                        videos_indexed: videos as i64,
                                        current_file: msg,
                                        progress_percent: 100.0,
                                    }));
                                }
                                Ok(_) => {} // No new groups
                                Err(e) => tracing::warn!("Auto-grouping failed: {}", e),
                            }
                        }
                    }
                    Err(e) => {
                        let msg = format!("Scan failed: {}", e);
                        tracing::error!("{}", msg);
                        send_error(tx, msg);
                    }
                }
            };

            if location_path.is_empty() {
                if let Ok(locations) = db.list_library_locations() {
                    if locations.is_empty() {
                        send_error(&tx, "No library locations configured".to_string());
                    } else {
                        for loc in locations {
                            if loc.enabled {
                                let expanded = expand_tilde(&loc.path);
                                scan_one(std::path::Path::new(&expanded), loc.recursive, &tx);
                            }
                        }
                    }
                }
            } else {
                let expanded = expand_tilde(&location_path);
                // Honor the recursive flag stored on the library_location.
                // Falls back to true if the path isn't a registered location
                // (caller-driven ad-hoc scans).
                let recursive = db
                    .list_library_locations()
                    .ok()
                    .and_then(|locs| {
                        locs.into_iter()
                            .find(|l| expand_tilde(&l.path) == expanded)
                            .map(|l| l.recursive)
                    })
                    .unwrap_or(true);
                scan_one(std::path::Path::new(&expanded), recursive, &tx);
            }

            // Auto-detect proxies. Runs after every scan because new
            // files may have unlocked previously-untestable proxy
            // candidates (a low-res clip in the catalog has nothing to
            // pair with until its high-res sibling shows up). Cheap:
            // O(n) thumbnail hashes + O(b²) within each frame-count
            // bucket, where b is typically 1–3.
            //
            // Scope detection to just the refreshed location when the
            // caller asked for a single path — re-running pairwise
            // comparisons across the whole catalog after every
            // per-folder refresh can take minutes on large libraries.
            let detect_result = if location_path.is_empty() {
                crate::proxies::detect_proxies(db.as_ref(), &cache_path)
            } else {
                let expanded = expand_tilde(&location_path);
                crate::proxies::detect_proxies_under_path(
                    db.as_ref(),
                    &cache_path,
                    std::path::Path::new(&expanded),
                )
            };
            match detect_result {
                Ok(s) => {
                    // Always log so operators can tell "nothing new to detect"
                    // from "detection didn't run at all". The pairs_compared
                    // count is especially useful for debugging false-negatives.
                    tracing::info!(
                        pairs_compared = s.pairs_compared,
                        proxies_marked = s.proxies_marked,
                        "Proxy detection complete",
                    );
                    if s.proxies_marked > 0 {
                        let _ = tx.blocking_send(Ok(ScanProgress {
                            status: "proxies".to_string(),
                            videos_found: 0,
                            videos_indexed: s.proxies_marked as i64,
                            current_file: format!(
                                "Detected {} proxy/proxies",
                                s.proxies_marked,
                            ),
                            progress_percent: 100.0,
                        }));
                    }
                }
                Err(e) => tracing::warn!("Proxy detection failed: {}", e),
            }

            // Tell live-updates subscribers we're done. `send` errors when
            // there are no subscribers — fine, we just drop.
            let _ = scan_events.send(CatalogChange::ScanCompleted {
                path: scan_path_for_event,
            });
        });

        let stream = ReceiverStream::new(rx);
        Ok(Response::new(Box::pin(stream) as Self::ScanLibraryStream))
    }

    async fn get_scan_status(
        &self,
        _request: Request<GetScanStatusRequest>,
    ) -> std::result::Result<Response<ScanStatusResponse>, Status> {
        Ok(Response::new(ScanStatusResponse {
            is_scanning: false,
            progress_percent: 0.0,
            current_activity: String::new(),
            total_videos_in_library: 0,
        }))
    }

    async fn create_tag(
        &self,
        request: Request<CreateTagRequest>,
    ) -> std::result::Result<Response<TagResponse>, Status> {
        let req = request.into_inner();

        // If a tag with this name already exists, return its existing ID
        // instead of failing. Makes the "type a new keyword" UX idempotent.
        if let Ok(Some(existing)) = self.db.get_tag_by_name(&req.name) {
            let video_count = self.db.count_videos_for_tag(&existing.id).unwrap_or(0);
            return Ok(Response::new(TagResponse {
                id: existing.id,
                name: existing.name,
                color: existing.color.unwrap_or_default(),
                video_count,
            }));
        }

        let tag_id = self
            .db
            .create_tag(&req.name, if req.color.is_empty() { None } else { Some(&req.color) })
            .map_err(Status::from)?;

        Ok(Response::new(TagResponse {
            id: tag_id,
            name: req.name,
            color: req.color,
            video_count: 0,
        }))
    }

    async fn delete_tag(
        &self,
        request: Request<DeleteTagRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();

        self.db.delete_tag(&req.tag_id).map_err(Status::from)?;

        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Tag deleted".to_string(),
            error: String::new(),
        }))
    }

    async fn list_tags(
        &self,
        _request: Request<ListTagsRequest>,
    ) -> std::result::Result<Response<ListTagsResponse>, Status> {
        let tags = self.db.list_tags().map_err(Status::from)?;

        let tag_responses = tags
            .iter()
            .map(|t| TagResponse {
                id: t.id.clone(),
                name: t.name.clone(),
                color: t.color.clone().unwrap_or_default(),
                video_count: self.db.count_videos_for_tag(&t.id).unwrap_or(0),
            })
            .collect();

        Ok(Response::new(ListTagsResponse { tags: tag_responses }))
    }

    async fn tag_videos(
        &self,
        request: Request<TagVideosRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db.tag_video(video_id, &req.tag_id).map_err(Status::from)?;
        }

        Ok(Response::new(videoroom::Response {
            success: true,
            message: format!("Tagged {} videos", req.video_ids.len()),
            error: String::new(),
        }))
    }

    async fn untag_videos(
        &self,
        request: Request<UntagVideosRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db.untag_video(video_id, &req.tag_id).map_err(Status::from)?;
        }

        Ok(Response::new(videoroom::Response {
            success: true,
            message: format!("Untagged {} videos", req.video_ids.len()),
            error: String::new(),
        }))
    }

    async fn create_collection(
        &self,
        request: Request<CreateCollectionRequest>,
    ) -> std::result::Result<Response<CollectionResponse>, Status> {
        let req = request.into_inner();

        let collection_id = self
            .db
            .create_collection(
                &req.name,
                req.is_smart,
                if req.filter_json.is_empty() { None } else { Some(&req.filter_json) },
            )
            .map_err(Status::from)?;

        Ok(Response::new(CollectionResponse {
            id: collection_id,
            name: req.name,
            is_smart: req.is_smart,
            video_count: 0,
            filter_json: req.filter_json,
        }))
    }

    async fn delete_collection(
        &self,
        request: Request<DeleteCollectionRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        self.db.delete_collection(&req.collection_id).map_err(Status::from)?;

        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Collection deleted".to_string(),
            error: String::new(),
        }))
    }

    async fn list_collections(
        &self,
        _request: Request<ListCollectionsRequest>,
    ) -> std::result::Result<Response<ListCollectionsResponse>, Status> {
        let collections = self.db.list_collections().map_err(Status::from)?;

        let collection_responses = collections
            .iter()
            .map(|c| CollectionResponse {
                id: c.id.clone(),
                name: c.name.clone(),
                is_smart: c.is_smart,
                video_count: 0,
                filter_json: c.filter_json.clone().unwrap_or_default(),
            })
            .collect();

        Ok(Response::new(ListCollectionsResponse {
            collections: collection_responses,
        }))
    }

    async fn add_to_collection(
        &self,
        request: Request<AddToCollectionRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db.add_to_collection(&req.collection_id, video_id).map_err(Status::from)?;
        }

        Ok(Response::new(videoroom::Response {
            success: true,
            message: format!("Added {} videos to collection", req.video_ids.len()),
            error: String::new(),
        }))
    }

    async fn remove_from_collection(
        &self,
        request: Request<RemoveFromCollectionRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db.remove_from_collection(&req.collection_id, video_id).map_err(Status::from)?;
        }

        Ok(Response::new(videoroom::Response {
            success: true,
            message: format!("Removed {} videos from collection", req.video_ids.len()),
            error: String::new(),
        }))
    }

    async fn update_video_notes(
        &self,
        request: Request<UpdateNotesRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();

        self.db.update_notes(&req.video_id, &req.notes).map_err(Status::from)?;

        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Notes updated".to_string(),
            error: String::new(),
        }))
    }

    async fn update_video_rating(
        &self,
        request: Request<videoroom::UpdateVideoRatingRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        if !(0..=5).contains(&req.rating) {
            return Err(Status::invalid_argument(
                "rating must be between 0 and 5 (inclusive)",
            ));
        }
        if req.video_ids.is_empty() {
            return Err(Status::invalid_argument("video_ids must be non-empty"));
        }
        let count = req.video_ids.len();
        for id in &req.video_ids {
            self.db
                .update_video_rating(id, req.rating)
                .map_err(Status::from)?;
        }
        Ok(Response::new(videoroom::Response {
            success: true,
            message: format!("Rating set to {} on {} video(s)", req.rating, count),
            error: String::new(),
        }))
    }

    async fn update_video_color_label(
        &self,
        request: Request<videoroom::UpdateVideoColorLabelRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        // Allowed label set kept in sync with the clients' `ColorLabel` enum.
        const ALLOWED: &[&str] = &["", "red", "yellow", "green", "blue", "purple"];
        if !ALLOWED.contains(&req.color_label.as_str()) {
            return Err(Status::invalid_argument(format!(
                "color_label must be one of {:?}",
                ALLOWED
            )));
        }
        if req.video_ids.is_empty() {
            return Err(Status::invalid_argument("video_ids must be non-empty"));
        }
        let count = req.video_ids.len();
        for id in &req.video_ids {
            self.db
                .update_video_color_label(id, &req.color_label)
                .map_err(Status::from)?;
        }
        let displayed_label = if req.color_label.is_empty() { "(none)" } else { req.color_label.as_str() };
        Ok(Response::new(videoroom::Response {
            success: true,
            message: format!("Color label '{}' applied to {} video(s)", displayed_label, count),
            error: String::new(),
        }))
    }

    async fn get_grid_settings(
        &self,
        _request: Request<videoroom::GetGridSettingsRequest>,
    ) -> std::result::Result<Response<videoroom::GridSettings>, Status> {
        // Catalog-scoped value lives in the existing `config` table under
        // a stable key. Serialised as a `,`-delimited list of 4 slot keys.
        //
        // First-open behaviour: when no row exists yet the daemon returns
        // a sensible set of defaults — filename, file size, resolution
        // shorthand ("1080p"), FPS — rather than four empty strings. That
        // way a brand-new catalog opens with informative stat slots
        // without each client having to ship its own fallback list (and
        // disagreeing if those lists drift).
        let default_slots: [&str; 4] = ["filename", "file_size", "resolution", "fps"];
        let raw = self
            .db
            .get_catalog_setting("grid_top_slots")
            .map_err(Status::from)?;
        let mut slots: Vec<String> = match raw {
            Some(s) if !s.is_empty() => s.split(',').map(|t| t.to_string()).collect(),
            _ => default_slots.iter().map(|s| s.to_string()).collect(),
        };
        // Pad / truncate to exactly four entries so the client doesn't have
        // to defend against malformed values.
        while slots.len() < 4 {
            slots.push(String::new());
        }
        slots.truncate(4);
        Ok(Response::new(videoroom::GridSettings { top_slots: slots }))
    }

    async fn update_grid_settings(
        &self,
        request: Request<videoroom::GridSettings>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        if req.top_slots.len() != 4 {
            return Err(Status::invalid_argument(
                "top_slots must contain exactly four entries (use \"\" for blank)",
            ));
        }
        let serialised = req.top_slots.join(",");
        self.db
            .set_catalog_setting("grid_top_slots", &serialised)
            .map_err(Status::from)?;
        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Grid settings saved".to_string(),
            error: String::new(),
        }))
    }

    async fn delete_video(
        &self,
        request: Request<DeleteVideoRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();

        if req.delete_file {
            if let Ok(Some(video)) = self.db.get_video(&req.video_id) {
                let _ = std::fs::remove_file(&video.path);
            }
        }

        self.db.delete_video(&req.video_id).map_err(Status::from)?;

        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Video deleted".to_string(),
            error: String::new(),
        }))
    }

    async fn list_group_members(
        &self,
        request: Request<ListGroupMembersRequest>,
    ) -> std::result::Result<Response<ListGroupMembersResponse>, Status> {
        let req = request.into_inner();
        let member_ids = self.db.list_group_member_ids(&req.group_id).map_err(Status::from)?;

        let mut members = Vec::with_capacity(member_ids.len());
        for vid in &member_ids {
            if let Ok(Some(video)) = self.db.get_video(vid) {
                members.push(self.build_video_summary(
                    &video.id,
                    &video.filename,
                    &video.path,
                    video.file_size_bytes.unwrap_or(0),
                    video.indexed_at,
                ));
            }
        }

        let preferred = self
            .db
            .get_group(&req.group_id)
            .map_err(Status::from)?
            .and_then(|g| g.preferred_video_id)
            .unwrap_or_default();

        Ok(Response::new(ListGroupMembersResponse {
            members,
            preferred_video_id: preferred,
        }))
    }

    async fn create_group(
        &self,
        request: Request<CreateGroupRequest>,
    ) -> std::result::Result<Response<GroupResponse>, Status> {
        let req = request.into_inner();
        let preferred = if req.preferred_video_id.is_empty() {
            None
        } else {
            Some(req.preferred_video_id.as_str())
        };
        let name = if req.name.is_empty() { None } else { Some(req.name.as_str()) };

        let group_id = self
            .db
            .create_group(name, None, &req.video_ids, preferred)
            .map_err(Status::from)?;

        let size = self.db.count_group_members(&group_id).unwrap_or(0) as i32;
        let preferred_id = self
            .db
            .get_group(&group_id)
            .map_err(Status::from)?
            .and_then(|g| g.preferred_video_id)
            .unwrap_or_default();

        Ok(Response::new(GroupResponse {
            id: group_id,
            name: req.name,
            size,
            preferred_video_id: preferred_id,
        }))
    }

    async fn ungroup_video(
        &self,
        request: Request<UngroupVideoRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        self.db.ungroup_video(&req.video_id).map_err(Status::from)?;
        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Video ungrouped".to_string(),
            error: String::new(),
        }))
    }

    async fn set_group_preferred(
        &self,
        request: Request<SetGroupPreferredRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        self.db
            .set_group_preferred(&req.group_id, &req.video_id)
            .map_err(Status::from)?;
        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Preferred video set".to_string(),
            error: String::new(),
        }))
    }

    async fn auto_group_videos(
        &self,
        request: Request<AutoGroupRequest>,
    ) -> std::result::Result<Response<AutoGroupResponse>, Status> {
        let req = request.into_inner();
        let options = crate::grouping::AutoGroupOptions {
            same_directory_only: req.same_directory_only,
            match_duration: req.match_duration,
            match_fps: req.match_fps,
        };

        let (groups_created, videos_grouped) = crate::grouping::auto_group(self.db.as_ref(), &options)
            .map_err(Status::from)?;

        Ok(Response::new(AutoGroupResponse {
            groups_created,
            videos_grouped,
            message: format!("Created {} groups containing {} videos", groups_created, videos_grouped),
        }))
    }

    async fn generate_proxy(
        &self,
        request: Request<GenerateProxyRequest>,
    ) -> std::result::Result<Response<Self::GenerateProxyStream>, Status> {
        let req = request.into_inner();
        let (tx, rx) = tokio::sync::mpsc::channel::<std::result::Result<ProxyGenerationProgress, Status>>(16);

        if req.video_id.is_empty() {
            return Err(Status::invalid_argument("video_id is required"));
        }
        let video = self
            .db
            .get_video(&req.video_id)
            .map_err(Status::from)?
            .ok_or_else(|| Status::not_found(format!("video {}", req.video_id)))?;

        let target_height = if req.target_height > 0 {
            req.target_height
        } else {
            self.config.proxy_target_height
        }
        .clamp(144, 4320) as u32;

        let output_path = if req.output_path.is_empty() {
            crate::proxies::proxy_path_beside(std::path::Path::new(&video.path), target_height)
        } else {
            std::path::PathBuf::from(req.output_path)
        };
        let source = std::path::PathBuf::from(video.path.clone());
        let source_id = video.id.clone();
        let db = Arc::clone(&self.db);
        let cache = self.config.thumbnail_cache_path.clone();
        let events = self.catalog_events.clone();

        tokio::task::spawn_blocking(move || {
            let send = |status: &str, percent: f64, message: String, proxy_id: String| {
                let _ = tx.blocking_send(Ok(ProxyGenerationProgress {
                    status: status.to_string(),
                    progress_percent: percent,
                    message,
                    proxy_video_id: proxy_id,
                }));
            };

            // The actual work. Forward progress from the proxies module
            // through the gRPC stream so the client can paint a real
            // progress bar.
            let progress_tx = tx.clone();
            let result = crate::proxies::create_proxy(
                db.as_ref(),
                &source_id,
                &source,
                &output_path,
                target_height,
                &cache,
                true,
                |p| {
                    let _ = progress_tx.blocking_send(Ok(ProxyGenerationProgress {
                        status: p.status.clone(),
                        progress_percent: p.progress_percent,
                        message: p.message.clone(),
                        proxy_video_id: String::new(),
                    }));
                },
            );

            match result {
                Ok(proxy_id) => {
                    // Tell live-update subscribers that a new video row
                    // exists so the grid refreshes to show the proxy
                    // badge on the source video's card.
                    let _ = events.send(crate::watcher::CatalogChange::VideoAdded {
                        video_id: proxy_id.clone(),
                        path: output_path.clone(),
                    });
                    send("complete", 100.0, "Proxy ready".into(), proxy_id);
                }
                Err(e) => {
                    send("error", 0.0, format!("Proxy generation failed: {}", e), String::new());
                }
            }
        });

        let stream = ReceiverStream::new(rx);
        Ok(Response::new(Box::pin(stream) as Self::GenerateProxyStream))
    }

    async fn list_proxies(
        &self,
        request: Request<ListProxiesRequest>,
    ) -> std::result::Result<Response<ListProxiesResponse>, Status> {
        let req = request.into_inner();
        if req.video_id.is_empty() {
            return Err(Status::invalid_argument("video_id is required"));
        }
        let rows = self.db.list_proxies(&req.video_id).map_err(Status::from)?;
        let proxies = rows
            .into_iter()
            .map(|r| ProxyInfo {
                id: r.id,
                filename: r.filename,
                path: r.path,
                size_bytes: r.file_size_bytes.unwrap_or(0),
                width: r.width,
                height: r.height,
                confidence: r.proxy_confidence,
                auto_detected: r.auto_detected,
            })
            .collect();
        Ok(Response::new(ListProxiesResponse { proxies }))
    }

    async fn set_proxy_of(
        &self,
        request: Request<SetProxyOfRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        if req.proxy_id.is_empty() {
            return Err(Status::invalid_argument("proxy_id is required"));
        }
        if req.original_id.is_empty() {
            self.db.clear_proxy_of(&req.proxy_id).map_err(Status::from)?;
            return Ok(Response::new(videoroom::Response {
                success: true,
                message: "Proxy link cleared".into(),
                error: String::new(),
            }));
        }
        // Manual mark — confidence = 1.0, auto_detected = false.
        self.db
            .set_proxy_of(&req.proxy_id, &req.original_id, 1.0, false)
            .map_err(Status::from)?;
        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Proxy link saved".into(),
            error: String::new(),
        }))
    }

    async fn remove_proxy_link(
        &self,
        request: Request<RemoveProxyLinkRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        if req.master_id.is_empty() || req.proxy_id.is_empty() {
            return Err(Status::invalid_argument(
                "master_id and proxy_id are both required",
            ));
        }
        self.db
            .remove_proxy_link(&req.master_id, &req.proxy_id)
            .map_err(Status::from)?;
        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Proxy link removed".into(),
            error: String::new(),
        }))
    }

    async fn detect_proxies(
        &self,
        _request: Request<DetectProxiesRequest>,
    ) -> std::result::Result<Response<DetectProxiesResponse>, Status> {
        let db = Arc::clone(&self.db);
        let cache = self.config.thumbnail_cache_path.clone();
        let summary = tokio::task::spawn_blocking(move || {
            crate::proxies::detect_proxies(db.as_ref(), &cache)
        })
        .await
        .map_err(|e| Status::internal(format!("join error: {}", e)))?
        .map_err(Status::from)?;
        Ok(Response::new(DetectProxiesResponse {
            pairs_compared: summary.pairs_compared as i32,
            proxies_marked: summary.proxies_marked as i32,
            message: format!(
                "Compared {} pairs; marked {} proxies",
                summary.pairs_compared, summary.proxies_marked,
            ),
        }))
    }

    async fn get_filter_options(
        &self,
        _request: Request<GetFilterOptionsRequest>,
    ) -> std::result::Result<Response<FilterOptions>, Status> {
        let cameras = self.db.list_distinct_cameras().unwrap_or_default();
        let lenses = self.db.list_distinct_lenses().unwrap_or_default();
        let codecs = self.db.list_distinct_codecs().unwrap_or_default();
        let years = self.db.list_distinct_capture_years().unwrap_or_default();
        // Parallel list of marketing-friendly camera names — same length
        // and order as `cameras`. Falls back to the internal name when
        // no mapping (built-in or custom) exists so the two lists stay
        // in lockstep.
        let custom_overrides = self.load_custom_camera_names();
        let camera_display_names: Vec<String> = cameras
            .iter()
            .map(|internal| {
                crate::camera_names::marketing_name_for_with_custom(
                    internal,
                    &custom_overrides,
                )
                .unwrap_or_else(|| internal.clone())
            })
            .collect();
        Ok(Response::new(FilterOptions {
            cameras,
            lenses,
            codecs,
            capture_years: years,
            camera_display_names,
        }))
    }

    async fn get_status(
        &self,
        _request: Request<GetStatusRequest>,
    ) -> std::result::Result<Response<StatusResponse>, Status> {
        // GetStatus is the client's liveness probe — it must succeed whenever
        // the daemon is running, *including* the "no catalog mounted yet"
        // state right after `--no-catalog` startup. If we let the SQL error
        // bubble up here the client would treat a perfectly healthy daemon
        // as broken and refuse to render the OpenCatalog dialog.
        let total = self.db.list_videos(1, 0).map(|(_, t)| t).unwrap_or(0);

        Ok(Response::new(StatusResponse {
            running: true,
            total_videos: total,
            total_library_size_bytes: 0,
            cache_size_bytes: 0,
            uptime_seconds: 0,
            version: env!("CARGO_PKG_VERSION").to_string(),
        }))
    }

    async fn get_config(
        &self,
        _request: Request<GetConfigRequest>,
    ) -> std::result::Result<Response<ConfigResponse>, Status> {
        Ok(Response::new(ConfigResponse {
            proxy_threshold_scale: self.config.proxy_threshold_scale,
            thumbnail_cache_path: self.config.thumbnail_cache_path.to_string_lossy().to_string(),
            max_concurrent_jobs: self.config.max_concurrent_jobs,
            enable_auto_tagging: self.config.enable_auto_tagging,
            max_native_playback_height: self.config.max_native_playback_height,
            proxy_target_height: self.config.proxy_target_height,
        }))
    }

    async fn update_config(
        &self,
        request: Request<UpdateConfigRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        // Persist the bits clients can actually change. The full Config
        // struct stays as the load-time snapshot — the values that drive
        // request-time decisions (max_native_playback_height,
        // proxy_target_height) are re-read from the config table on every
        // GenerateProxy, so this UPSERT is enough for them.
        if let Ok(conn) = self.db.get_connection() {
            let pairs: &[(&str, String)] = &[
                ("max_native_playback_height", req.max_native_playback_height.clamp(0, 7680).to_string()),
                ("proxy_target_height", req.proxy_target_height.clamp(144, 4320).to_string()),
                ("max_concurrent_jobs", req.max_concurrent_jobs.max(0).to_string()),
                ("enable_auto_tagging", req.enable_auto_tagging.to_string()),
            ];
            for (key, value) in pairs {
                let _ = conn.execute(
                    "INSERT INTO config (key, value) VALUES (?, ?)
                     ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = CURRENT_TIMESTAMP",
                    rusqlite::params![key, value, value],
                );
            }
        }
        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Config updated".to_string(),
            error: String::new(),
        }))
    }

    async fn open_catalog(
        &self,
        request: Request<OpenCatalogRequest>,
    ) -> std::result::Result<Response<CatalogInfo>, Status> {
        let raw = request.into_inner().path;
        let expanded = expand_tilde(&raw);
        if expanded.trim().is_empty() {
            return Err(Status::invalid_argument("catalog path is empty"));
        }
        let path = std::path::PathBuf::from(&expanded);
        self.db
            .set_path(&path)
            .map_err(|e| Status::internal(format!("Failed to open catalog: {}", e)))?;
        let opened = now_ms();
        if let Ok(mut g) = self.opened_at_ms.write() {
            *g = opened;
        }

        // A different catalog means a different set of library locations.
        // Restart the watcher so it observes those instead of the old set.
        let handle = self.clone_for_watcher();
        tokio::spawn(async move { handle.restart_watcher().await });

        Ok(Response::new(catalog_info_from(self.db.as_ref(), opened)))
    }

    async fn close_catalog(
        &self,
        _request: Request<CloseCatalogRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        self.db.clear_path();
        if let Ok(mut g) = self.opened_at_ms.write() {
            *g = 0;
        }
        // Tear down the watcher — it was tied to the closed catalog's
        // library locations. A subsequent OpenCatalog will rebuild it.
        {
            let mut guard = self.watcher.lock().await;
            *guard = None;
        }
        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Catalog closed".to_string(),
            error: String::new(),
        }))
    }

    async fn get_current_catalog(
        &self,
        _request: Request<GetCurrentCatalogRequest>,
    ) -> std::result::Result<Response<CatalogInfo>, Status> {
        let opened = self.opened_at_ms.read().map(|g| *g).unwrap_or(0);
        Ok(Response::new(catalog_info_from(self.db.as_ref(), opened)))
    }

    async fn update_video_location(
        &self,
        request: Request<UpdateVideoLocationRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        if req.video_id.is_empty() {
            return Err(Status::invalid_argument("video_id is required"));
        }
        // Sanity-check the coordinates so we don't accept "the moon".
        if !(-90.0..=90.0).contains(&req.latitude)
            || !(-180.0..=180.0).contains(&req.longitude)
        {
            return Err(Status::invalid_argument(
                "latitude must be -90..=90 and longitude must be -180..=180",
            ));
        }

        // Update the catalog first — that's the source of truth for the UI.
        self.db
            .update_gps_coordinates(&req.video_id, req.latitude, req.longitude, req.altitude)
            .map_err(Status::from)?;

        // Best-effort write-back into the actual video file. Failure here is
        // logged but does NOT fail the RPC — the catalog already reflects the
        // new location and that's what matters for browsing.
        let mut file_write_message = String::new();
        if req.write_to_file {
            if let Ok(Some(v)) = self.db.get_video(&req.video_id) {
                match crate::metadata::MetadataExtractor::write_location_tag(
                    &v.path,
                    req.latitude,
                    req.longitude,
                    req.altitude,
                ) {
                    Ok(()) => {
                        file_write_message = format!(" (embedded in {})", v.filename);
                    }
                    Err(e) => {
                        tracing::warn!(
                            "Failed to embed location into {}: {}", v.path, e
                        );
                        file_write_message =
                            format!(" (catalog updated, but file write failed: {})", e);
                    }
                }
            }
        }

        Ok(Response::new(videoroom::Response {
            success: true,
            message: format!(
                "Location set to {:.6}, {:.6}{}",
                req.latitude, req.longitude, file_write_message
            ),
            error: String::new(),
        }))
    }

    async fn list_videos_with_locations(
        &self,
        _request: Request<ListVideosWithLocationsRequest>,
    ) -> std::result::Result<Response<VideoLocationsResponse>, Status> {
        let rows = self.db.list_videos_with_locations().map_err(Status::from)?;
        let locations = rows
            .into_iter()
            .map(|r| VideoLocation {
                id: r.id,
                filename: r.filename,
                path: r.path,
                latitude: r.latitude,
                longitude: r.longitude,
                altitude: r.altitude,
                has_thumbnail: r.has_thumbnail,
            })
            .collect();
        Ok(Response::new(VideoLocationsResponse { locations }))
    }

    async fn update_video_capture_date(
        &self,
        request: Request<UpdateVideoCaptureDateRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        if req.video_id.is_empty() {
            return Err(Status::invalid_argument("video_id is required"));
        }
        // Plausible-timestamp guardrail: between 1970-01-01 and ~2100. Block
        // 0 explicitly so a missing field doesn't silently zero out a row.
        const MAX_TS: i64 = 4_102_444_800_000; // 2100-01-01T00:00:00Z
        if req.timestamp_ms <= 0 || req.timestamp_ms > MAX_TS {
            return Err(Status::invalid_argument(
                "timestamp_ms must be a Unix-millisecond value between 1970 and 2100",
            ));
        }

        self.db
            .update_capture_date(&req.video_id, req.timestamp_ms)
            .map_err(Status::from)?;

        // Best-effort write-back into the file's container metadata.
        let mut file_write_message = String::new();
        if req.write_to_file {
            if let Ok(Some(v)) = self.db.get_video(&req.video_id) {
                match crate::metadata::MetadataExtractor::write_creation_time_tag(
                    &v.path,
                    req.timestamp_ms,
                ) {
                    Ok(()) => {
                        file_write_message = format!(" (embedded in {})", v.filename);
                    }
                    Err(e) => {
                        tracing::warn!(
                            "Failed to embed creation_time into {}: {}", v.path, e
                        );
                        file_write_message =
                            format!(" (catalog updated, but file write failed: {})", e);
                    }
                }
            }
        }

        Ok(Response::new(videoroom::Response {
            success: true,
            message: format!("Capture date set{}", file_write_message),
            error: String::new(),
        }))
    }

    async fn list_named_locations(
        &self,
        _request: Request<ListNamedLocationsRequest>,
    ) -> std::result::Result<Response<NamedLocationsResponse>, Status> {
        let rows = self.db.list_named_locations().map_err(Status::from)?;
        let locations = rows
            .into_iter()
            .map(|r| NamedLocation {
                id: r.id,
                name: r.name,
                latitude: r.latitude,
                longitude: r.longitude,
                radius_m: r.radius_m,
                created_at_ms: r.created_at_ms,
                updated_at_ms: r.updated_at_ms,
            })
            .collect();
        Ok(Response::new(NamedLocationsResponse { locations }))
    }

    async fn upsert_named_location(
        &self,
        request: Request<UpsertNamedLocationRequest>,
    ) -> std::result::Result<Response<NamedLocationResponse>, Status> {
        let req = request.into_inner();
        let trimmed = req.name.trim();
        if trimmed.is_empty() {
            return Err(Status::invalid_argument("name must not be empty"));
        }
        if !(-90.0..=90.0).contains(&req.latitude)
            || !(-180.0..=180.0).contains(&req.longitude)
        {
            return Err(Status::invalid_argument(
                "latitude must be -90..=90 and longitude must be -180..=180",
            ));
        }
        let saved = self
            .db
            .upsert_named_location(
                &req.id,
                trimmed,
                req.latitude,
                req.longitude,
                req.radius_m,
            )
            .map_err(Status::from)?;
        let proto = NamedLocation {
            id: saved.id.clone(),
            name: saved.name.clone(),
            latitude: saved.latitude,
            longitude: saved.longitude,
            radius_m: saved.radius_m,
            created_at_ms: saved.created_at_ms,
            updated_at_ms: saved.updated_at_ms,
        };
        Ok(Response::new(NamedLocationResponse {
            success: true,
            message: format!("Named location '{}' saved", saved.name),
            location: Some(proto),
        }))
    }

    async fn delete_named_location(
        &self,
        request: Request<DeleteNamedLocationRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        if req.id.is_empty() {
            return Err(Status::invalid_argument("id is required"));
        }
        self.db.delete_named_location(&req.id).map_err(Status::from)?;
        Ok(Response::new(videoroom::Response {
            success: true,
            message: "Named location deleted".into(),
            error: String::new(),
        }))
    }

    type SubscribeCatalogEventsStream =
        Pin<Box<dyn Stream<Item = std::result::Result<CatalogEvent, Status>> + Send>>;

    async fn subscribe_catalog_events(
        &self,
        _request: Request<SubscribeCatalogEventsRequest>,
    ) -> std::result::Result<Response<Self::SubscribeCatalogEventsStream>, Status> {
        let mut rx = self.catalog_events.subscribe();
        let (tx, out_rx) = tokio::sync::mpsc::channel::<std::result::Result<CatalogEvent, Status>>(64);

        // Greet new subscribers with the current watcher state so the
        // client can render its "live updates: on/off" indicator without
        // a separate GetWatchSettings round-trip.
        let settings = *self.watch_settings.read().await;
        let greeting_kind = if settings.enabled {
            videoroom::catalog_event::Kind::WatcherStarted as i32
        } else {
            videoroom::catalog_event::Kind::WatcherDisabled as i32
        };
        let _ = tx
            .send(Ok(CatalogEvent {
                kind: greeting_kind,
                video_id: String::new(),
                path: String::new(),
                at_ms: now_ms(),
                message: String::new(),
            }))
            .await;

        // Pump broadcast → mpsc. On `Lagged` we let the client know with
        // a synthetic event so it can do a full re-list; on `Closed` we
        // end the stream.
        tokio::spawn(async move {
            loop {
                match rx.recv().await {
                    Ok(change) => {
                        let event = change_to_event(&change);
                        if tx.send(Ok(event)).await.is_err() {
                            break;
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                        let _ = tx
                            .send(Ok(CatalogEvent {
                                kind: videoroom::catalog_event::Kind::ScanCompleted as i32,
                                video_id: String::new(),
                                path: String::new(),
                                at_ms: now_ms(),
                                message: format!(
                                    "watcher event stream lagged {} events — please refresh",
                                    n
                                ),
                            }))
                            .await;
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
        });

        let stream = ReceiverStream::new(out_rx);
        Ok(Response::new(
            Box::pin(stream) as Self::SubscribeCatalogEventsStream
        ))
    }

    async fn get_watch_settings(
        &self,
        _request: Request<GetWatchSettingsRequest>,
    ) -> std::result::Result<Response<WatchSettings>, Status> {
        let s = *self.watch_settings.read().await;
        Ok(Response::new(WatchSettings {
            enabled: s.enabled,
            write_settle_ms: s.write_settle_ms,
            poll_interval_ms: s.poll_interval_ms,
        }))
    }

    async fn update_watch_settings(
        &self,
        request: Request<WatchSettings>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();

        // Guard against absurd values that would either make the
        // watcher useless (settle == 0 → indexes half-written files) or
        // hammer the SAN (poll == 1ms). The clients clamp too, but the
        // server is the source of truth.
        let settle = req.write_settle_ms.clamp(0, 10 * 60 * 1000);
        let poll = req.poll_interval_ms.clamp(0, 24 * 60 * 60 * 1000);

        {
            let mut guard = self.watch_settings.write().await;
            guard.enabled = req.enabled;
            guard.write_settle_ms = settle;
            guard.poll_interval_ms = poll;
        }

        // Persist to the config table so the new settings survive
        // restarts. Best-effort — failure to write doesn't fail the RPC,
        // we already have the in-memory values doing the right thing.
        if let Ok(conn) = self.db.get_connection() {
            let _ = conn.execute(
                "INSERT INTO config (key, value) VALUES (?, ?)
                 ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = CURRENT_TIMESTAMP",
                rusqlite::params!["watch_enabled", req.enabled.to_string(), req.enabled.to_string()],
            );
            let _ = conn.execute(
                "INSERT INTO config (key, value) VALUES (?, ?)
                 ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = CURRENT_TIMESTAMP",
                rusqlite::params!["watch_write_settle_ms", settle.to_string(), settle.to_string()],
            );
            let _ = conn.execute(
                "INSERT INTO config (key, value) VALUES (?, ?)
                 ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = CURRENT_TIMESTAMP",
                rusqlite::params!["watch_poll_interval_ms", poll.to_string(), poll.to_string()],
            );
        }

        // Restart the watcher with the new knobs. If `enabled` flipped
        // from true→false this tears it down; false→true builds a fresh
        // one. settle/poll changes also just re-build.
        let handle = self.clone_for_watcher();
        tokio::spawn(async move { handle.restart_watcher().await });

        Ok(Response::new(videoroom::Response {
            success: true,
            message: format!(
                "Watch settings updated (enabled={}, settle={}ms, poll={}ms)",
                req.enabled, settle, poll
            ),
            error: String::new(),
        }))
    }

    async fn list_camera_name_mappings(
        &self,
        _request: Request<ListCameraNameMappingsRequest>,
    ) -> std::result::Result<Response<ListCameraNameMappingsResponse>, Status> {
        // 1. Custom overrides keyed by normalised internal name. We
        //    also keep the raw (user-supplied) internal string so the
        //    editor displays it back exactly as typed.
        let raw_customs = self.read_custom_camera_names();
        let mut custom_by_normalised: std::collections::HashMap<String, (String, String)> =
            std::collections::HashMap::new();
        for c in &raw_customs {
            let key = crate::camera_names::normalise(&c.internal);
            let marketing = c.marketing.trim();
            if key.is_empty() || marketing.is_empty() {
                continue;
            }
            custom_by_normalised.insert(key, (c.internal.clone(), marketing.to_string()));
        }

        // 2. Walk every built-in entry. If a custom override exists
        //    for the same normalised key, the custom marketing wins and
        //    we mark both flags. We also pop the entry out of
        //    `custom_by_normalised` so step 3 only sees custom-only
        //    rows.
        let mut mappings = Vec::new();
        let mut builtin_entries: Vec<(&'static str, &'static str)> =
            crate::camera_names::builtin_entries().collect();
        builtin_entries.sort_by_key(|a| a.0.to_ascii_uppercase());
        for (internal, marketing) in builtin_entries {
            let key = crate::camera_names::normalise(internal);
            let overridden = custom_by_normalised.remove(&key);
            let (final_internal, final_marketing, is_custom) = match overridden {
                Some((raw_internal, custom_marketing)) => {
                    (raw_internal, custom_marketing, true)
                }
                None => (internal.to_string(), marketing.to_string(), false),
            };
            mappings.push(CameraNameMapping {
                internal: final_internal,
                marketing: final_marketing,
                is_builtin: true,
                is_custom,
            });
        }

        // 3. Any custom-only entries (no matching built-in) come last,
        //    alphabetised by internal name so the editor's table is
        //    stable across calls.
        let mut custom_only: Vec<(String, String)> =
            custom_by_normalised.into_values().collect();
        custom_only.sort_by_key(|a| a.0.to_ascii_uppercase());
        for (raw_internal, marketing) in custom_only {
            mappings.push(CameraNameMapping {
                internal: raw_internal,
                marketing,
                is_builtin: false,
                is_custom: true,
            });
        }

        Ok(Response::new(ListCameraNameMappingsResponse { mappings }))
    }

    async fn set_camera_name_mapping(
        &self,
        request: Request<SetCameraNameMappingRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
        let req = request.into_inner();
        let internal_raw = req.internal.trim().to_string();
        let marketing = req.marketing.trim().to_string();

        if internal_raw.is_empty() {
            return Ok(Response::new(videoroom::Response {
                success: false,
                message: String::new(),
                error: "internal name must not be blank".to_string(),
            }));
        }

        let normalised_key = crate::camera_names::normalise(&internal_raw);
        let mut entries = self.read_custom_camera_names();
        // Drop any pre-existing entry with the same normalised internal
        // key, regardless of casing/whitespace the user originally
        // typed. Keeps the list deduped and lets the new entry win.
        entries.retain(|e| crate::camera_names::normalise(&e.internal) != normalised_key);

        if !marketing.is_empty() {
            entries.push(crate::config::CustomCameraName {
                internal: internal_raw.clone(),
                marketing: marketing.clone(),
            });
        }

        self.write_custom_camera_names(&entries)
            .map_err(|e| Status::internal(format!("Failed to save mappings: {e}")))?;

        let message = if marketing.is_empty() {
            format!("Removed custom mapping for \"{internal_raw}\"")
        } else {
            format!("Saved custom mapping: \"{internal_raw}\" → \"{marketing}\"")
        };
        Ok(Response::new(videoroom::Response {
            success: true,
            message,
            error: String::new(),
        }))
    }
}

/// Current Unix time in milliseconds. Used to stamp `opened_at_ms` on
/// CatalogInfo so clients can show "opened 5 minutes ago" if they care.
fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Build a `CatalogInfo` proto from the current `db` state. Returns the
/// "no catalog open" shape when the database hasn't been pointed at a file.
fn catalog_info_from(db: &Database, opened_at_ms: i64) -> CatalogInfo {
    let path = db.current_path();
    match path {
        Some(p) => {
            let name = p
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or("Untitled")
                .to_string();
            // best-effort video count; ignore errors so we never fail to
            // return info just because the count query hiccupped.
            let video_count = db
                .list_videos(1, 0)
                .map(|(_, total)| total)
                .unwrap_or(0);
            CatalogInfo {
                path: p.to_string_lossy().into_owned(),
                name,
                video_count,
                opened_at_ms,
            }
        }
        None => CatalogInfo::default(),
    }
}

/// Expand `~` at the start of a path to the user's home directory.
fn expand_tilde(path: &str) -> String {
    if path.starts_with("~/") || path == "~" {
        if let Some(home) = dirs::home_dir() {
            return path.replacen('~', &home.to_string_lossy(), 1);
        }
    }
    path.to_string()
}

/// Cheap clone-able bundle of the watcher-related Arcs. Lets us
/// `tokio::spawn` watcher work from RPC handlers without moving `self`.
#[derive(Clone)]
struct ServiceWatcherHandle {
    db: Arc<Database>,
    config: Arc<Config>,
    catalog_events: broadcast::Sender<CatalogChange>,
    watcher: Arc<tokio::sync::Mutex<Option<LibraryWatcher>>>,
    watch_settings: Arc<tokio::sync::RwLock<WatchSettingsCurrent>>,
}

impl ServiceWatcherHandle {
    /// Tear down any existing watcher and (if `enabled` and a catalog is
    /// open with at least one library location) start a new one with the
    /// current settings. Idempotent — calling twice in a row is fine.
    async fn restart_watcher(self) {
        // Drop any existing watcher first. Its Drop impl sends Shutdown
        // and joins the thread, so by the time `take()` returns, the old
        // watcher is gone — no notify handler races on the way in.
        {
            let mut guard = self.watcher.lock().await;
            *guard = None;
        }

        let settings = *self.watch_settings.read().await;
        if !settings.enabled {
            tracing::info!("Watcher disabled in settings — not starting.");
            return;
        }
        if self.db.current_path().is_none() {
            tracing::debug!("No catalog open — skipping watcher start.");
            return;
        }

        let locations = match self.db.list_library_locations() {
            Ok(l) => l,
            Err(e) => {
                tracing::warn!("Could not list library_locations for watcher: {}", e);
                return;
            }
        };
        let initial_paths: Vec<(std::path::PathBuf, bool)> = locations
            .into_iter()
            .filter(|l| l.enabled)
            .map(|l| {
                let expanded = expand_tilde(&l.path);
                (std::path::PathBuf::from(expanded), l.recursive)
            })
            .collect();

        if initial_paths.is_empty() {
            tracing::info!("No library locations registered — watcher idle.");
            return;
        }

        match LibraryWatcher::start(
            Arc::clone(&self.db),
            self.config.thumbnail_cache_path.clone(),
            self.catalog_events.clone(),
            settings.write_settle_ms.max(0) as u64,
            settings.poll_interval_ms.max(0) as u64,
            initial_paths,
        ) {
            Ok(w) => {
                let mut guard = self.watcher.lock().await;
                *guard = Some(w);
            }
            Err(e) => {
                tracing::warn!("Failed to start watcher: {}", e);
            }
        }
    }
}

/// Convert a watcher `CatalogChange` into a wire-level proto `CatalogEvent`.
fn change_to_event(change: &CatalogChange) -> videoroom::CatalogEvent {
    use videoroom::catalog_event::Kind;
    let at_ms = now_ms();
    match change {
        CatalogChange::VideoAdded { video_id, path } => videoroom::CatalogEvent {
            kind: Kind::VideoAdded as i32,
            video_id: video_id.clone(),
            path: path.to_string_lossy().into_owned(),
            at_ms,
            message: String::new(),
        },
        CatalogChange::VideoModified { video_id, path } => videoroom::CatalogEvent {
            kind: Kind::VideoModified as i32,
            video_id: video_id.clone(),
            path: path.to_string_lossy().into_owned(),
            at_ms,
            message: String::new(),
        },
        CatalogChange::VideoRemoved { video_id, path } => videoroom::CatalogEvent {
            kind: Kind::VideoRemoved as i32,
            video_id: video_id.clone().unwrap_or_default(),
            path: path.to_string_lossy().into_owned(),
            at_ms,
            message: path
                .file_name()
                .and_then(|n| n.to_str())
                .map(str::to_string)
                .unwrap_or_default(),
        },
        CatalogChange::ScanStarted { path } => videoroom::CatalogEvent {
            kind: Kind::ScanStarted as i32,
            video_id: String::new(),
            path: path.clone(),
            at_ms,
            message: String::new(),
        },
        CatalogChange::ScanCompleted { path } => videoroom::CatalogEvent {
            kind: Kind::ScanCompleted as i32,
            video_id: String::new(),
            path: path.clone(),
            at_ms,
            message: String::new(),
        },
    }
}
