use crate::config::Config;
use crate::db::Database;
use crate::error::{Result, VideoRoomError};
use crate::indexing::IndexingEngine;
use crate::search::SearchEngine;
use crate::thumbnails::ThumbnailGenerator;
use std::pin::Pin;
use std::sync::Arc;
use tokio_stream::Stream;
use tokio_stream::wrappers::ReceiverStream;
use tonic::{Request, Response, Status};

// Import generated protobuf code
pub mod videoroom {
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
        VideoRoomService {
            db,
            config,
            opened_at_ms: Arc::new(std::sync::RwLock::new(initial_opened)),
            scrub_locks: Arc::new(tokio::sync::Mutex::new(std::collections::HashMap::new())),
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
                    gps_altitude
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
                    ))
                },
            )
            .ok();

        let tags = db.get_video_tags(video_id).unwrap_or_default();
        let notes = db.get_notes(video_id).unwrap_or_default().unwrap_or_default();

        let row = metadata_row;

        if row.is_none() {
            tracing::warn!("No metadata row found for video {}", video_id);
        }

        let (duration_ms, codec_video, codec_audio, width, height, fps, bitrate,
             color_space, hdr, audio_channels, audio_sample_rate, creation_date,
             camera_model, lens_model, gps_lat, gps_lon, gps_alt) =
            row.unwrap_or((0, None, None, 0, 0, 0.0, 0, None, false, 0, 0, None, None, None, None, None, None));

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
            camera_model: camera_model.unwrap_or_default(),
            lens_model: lens_model.unwrap_or_default(),
            gps_latitude: gps_lat.unwrap_or(0.0),
            gps_longitude: gps_lon.unwrap_or(0.0),
            gps_altitude: gps_alt.unwrap_or(0.0),
            tags,
            collections: Vec::new(),
            notes,
            volume_id: video.volume_id.unwrap_or_default(),
            is_online: video.is_online != 0,
        })
    }

    fn build_video_summary(&self, video_id: &str, filename: &str, path: &str,
                           size_bytes: i64, indexed_at: i64) -> VideoSummary {
        // Try to get metadata for the video
        let conn = self.db.get_connection().ok();
        let meta = conn.and_then(|c| {
            c.query_row(
                "SELECT duration_ms, width, height, fps, codec_video, codec_audio, creation_date
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
                    ))
                },
            ).ok()
        });

        let tags = self.db.get_video_tags(video_id).unwrap_or_default();

        let (duration_ms, width, height, fps, codec_video, codec_audio, creation_date) =
            meta.unwrap_or((0, 0, 0, 0.0, None, None, None));

        // Check if thumbnail exists
        let thumb_path = self.config.thumbnail_cache_path.join(format!("{}_medium.jpg", video_id));
        let has_thumbnail = thumb_path.exists();

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

        // Expand tilde to home directory
        let expanded_path = expand_tilde(&req.path);

        // Validate path
        let path_obj = std::path::Path::new(&expanded_path);
        if !path_obj.exists() {
            return Ok(Response::new(LocationResponse {
                success: false,
                message: format!("Path does not exist: {}", expanded_path),
            }));
        }
        if !path_obj.is_dir() {
            return Ok(Response::new(LocationResponse {
                success: false,
                message: format!("Path is not a directory: {}", expanded_path),
            }));
        }

        // The DB UPSERTs — if the path already exists, the recursive flag is
        // updated in place. So this is idempotent and lets the user toggle
        // recursive on/off by re-adding.
        self.db
            .add_library_location(&expanded_path, req.recursive)
            .map_err(Status::from)?;

        Ok(Response::new(LocationResponse {
            success: true,
            message: format!("Added library location: {} ({})",
                expanded_path,
                if req.recursive { "recursive" } else { "non-recursive" }),
        }))
    }

    async fn remove_library_location(
        &self,
        request: Request<RemoveLocationRequest>,
    ) -> std::result::Result<Response<LocationResponse>, Status> {
        let req = request.into_inner();

        self.db
            .remove_library_location(&req.path)
            .map_err(Status::from)?;

        Ok(Response::new(LocationResponse {
            success: true,
            message: format!("Removed library location: {}", req.path),
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
                    db.as_ref(),
                    scan_path,
                    recursive,
                    &cache_path,
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
        _request: Request<GenerateProxyRequest>,
    ) -> std::result::Result<Response<Self::GenerateProxyStream>, Status> {
        let (_tx, rx) = tokio::sync::mpsc::channel::<std::result::Result<ProxyGenerationProgress, Status>>(10);
        let stream = ReceiverStream::new(rx);
        Ok(Response::new(Box::pin(stream) as Self::GenerateProxyStream))
    }

    async fn list_proxies(
        &self,
        _request: Request<ListProxiesRequest>,
    ) -> std::result::Result<Response<ListProxiesResponse>, Status> {
        Ok(Response::new(ListProxiesResponse { proxies: vec![] }))
    }

    async fn get_filter_options(
        &self,
        _request: Request<GetFilterOptionsRequest>,
    ) -> std::result::Result<Response<FilterOptions>, Status> {
        let cameras = self.db.list_distinct_cameras().unwrap_or_default();
        let lenses = self.db.list_distinct_lenses().unwrap_or_default();
        let codecs = self.db.list_distinct_codecs().unwrap_or_default();
        let years = self.db.list_distinct_capture_years().unwrap_or_default();
        Ok(Response::new(FilterOptions {
            cameras,
            lenses,
            codecs,
            capture_years: years,
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
        let external_editors = self
            .config
            .external_editors
            .iter()
            .map(|e| ExternalEditor {
                id: e.id.clone(),
                name: e.name.clone(),
                executable_path: e.executable_path.clone(),
                arguments: e.arguments.clone(),
                platforms: e.platforms.clone(),
            })
            .collect();

        Ok(Response::new(ConfigResponse {
            proxy_threshold_scale: self.config.proxy_threshold_scale,
            thumbnail_cache_path: self.config.thumbnail_cache_path.to_string_lossy().to_string(),
            max_concurrent_jobs: self.config.max_concurrent_jobs,
            enable_auto_tagging: self.config.enable_auto_tagging,
            external_editors,
        }))
    }

    async fn update_config(
        &self,
        _request: Request<UpdateConfigRequest>,
    ) -> std::result::Result<Response<videoroom::Response>, Status> {
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
