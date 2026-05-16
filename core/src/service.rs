use crate::config::Config;
use crate::db::Database;
use crate::error::{Result, VideoRoomError};
use crate::indexing::{IndexingEngine, ScanProgress};
use crate::metadata::MetadataExtractor;
use crate::search::SearchEngine;
use crate::thumbnails::ThumbnailGenerator;
use std::sync::Arc;
use tonic::{Request, Response, Status};

// Import generated protobuf code
include!(concat!(env!("OUT_DIR"), "/videoroom.rs"));

pub struct VideoRoomService {
    db: Arc<Database>,
    config: Arc<Config>,
}

impl VideoRoomService {
    pub fn new(db: Arc<Database>, config: Arc<Config>) -> Self {
        VideoRoomService { db, config }
    }

    async fn get_video_metadata(&self, video_id: &str) -> Result<VideoMetadata> {
        let db = self.db.as_ref();
        let video = db
            .get_video(video_id)
            .and_then(|v| v.ok_or_else(|| VideoRoomError::VideoNotFound(video_id.to_string())))?;

        let conn = db.get_connection()?;
        let metadata = conn
            .query_row(
                "SELECT
                    m.video_id, m.duration_ms, m.codec_video, m.codec_audio, m.width, m.height,
                    m.fps, m.bitrate, m.color_space, m.hdr, m.audio_channels, m.audio_sample_rate,
                    m.creation_date, m.camera_model, m.lens_model, m.gps_latitude, m.gps_longitude,
                    m.gps_altitude, m.metadata_json
                 FROM metadata WHERE video_id = ?",
                [video_id],
                |row| {
                    Ok((
                        row.get::<_, i64>(1)?,
                        row.get::<_, Option<String>>(2)?,
                        row.get::<_, Option<String>>(3)?,
                        row.get::<_, i32>(4)?,
                        row.get::<_, i32>(5)?,
                        row.get::<_, f64>(6)?,
                        row.get::<_, i64>(7)?,
                        row.get::<_, Option<String>>(8)?,
                        row.get::<_, i32>(9)? != 0,
                        row.get::<_, i32>(10)?,
                        row.get::<_, i32>(11)?,
                        row.get::<_, Option<i64>>(12)?,
                        row.get::<_, Option<String>>(13)?,
                        row.get::<_, Option<String>>(14)?,
                        row.get::<_, Option<f64>>(15)?,
                        row.get::<_, Option<f64>>(16)?,
                        row.get::<_, Option<f64>>(17)?,
                    ))
                },
            )
            .map_err(|_| VideoRoomError::VideoNotFound(video_id.to_string()))?;

        let tags = db.get_video_tags(video_id)?;

        Ok(VideoMetadata {
            id: video_id.to_string(),
            filename: video.filename,
            path: video.path,
            size_bytes: video.file_size_bytes.unwrap_or(0),
            duration_ms: metadata.0,
            width: metadata.3,
            height: metadata.4,
            fps: metadata.5,
            bitrate: metadata.6,
            codec_video: metadata.1.unwrap_or_default(),
            color_space: metadata.8,
            hdr: metadata.9,
            codec_audio: metadata.2.unwrap_or_default(),
            audio_channels: metadata.10,
            audio_sample_rate: metadata.11,
            creation_date: metadata.12.unwrap_or(0),
            modification_date: 0,
            indexed_at: video.indexed_at,
            camera_model: metadata.13.unwrap_or_default(),
            lens_model: metadata.14.unwrap_or_default(),
            gps_latitude: metadata.15.unwrap_or(0.0),
            gps_longitude: metadata.16.unwrap_or(0.0),
            gps_altitude: metadata.17.unwrap_or(0.0),
            tags,
            collections: Vec::new(),
            notes: String::new(),
            volume_id: video.volume_id.unwrap_or_default(),
            is_online: video.is_online != 0,
        })
    }
}

#[tonic::async_trait]
impl video_room_server::VideoRoom for VideoRoomService {
    async fn list_videos(
        &self,
        request: Request<ListVideosRequest>,
    ) -> std::result::Result<Response<ListVideosResponse>, Status> {
        let req = request.into_inner();
        let (videos, total_count) = self
            .db
            .list_videos(req.limit as i64, req.offset as i64)
            .map_err(|e| Status::from(e))?;

        let video_summaries = videos
            .iter()
            .map(|v| VideoSummary {
                id: v.id.clone(),
                filename: v.filename.clone(),
                path: v.path.clone(),
                duration_ms: 0,
                width: 0,
                height: 0,
                codec_video: String::new(),
                codec_audio: String::new(),
                fps: 0.0,
                size_bytes: v.file_size_bytes.unwrap_or(0),
                indexed_at: v.indexed_at,
                creation_date: 0,
                tags: Vec::new(),
                has_thumbnail: false,
            })
            .collect();

        Ok(Response::new(ListVideosResponse {
            videos: video_summaries,
            total_count,
            has_more: (req.offset as i64 + req.limit as i64) < total_count,
        }))
    }

    async fn search_videos(
        &self,
        request: Request<SearchRequest>,
    ) -> std::result::Result<Response<SearchResponse>, Status> {
        let req = request.into_inner();
        let (results, total_count) = SearchEngine::search(
            self.db.as_ref(),
            &req.query,
            req.limit as i64,
            req.offset as i64,
            &req.filter_tags,
        )
        .map_err(|e| Status::from(e))?;

        let video_summaries = results
            .iter()
            .map(|r| VideoSummary {
                id: r.video_id.clone(),
                filename: r.filename.clone(),
                path: r.path.clone(),
                duration_ms: 0,
                width: 0,
                height: 0,
                codec_video: String::new(),
                codec_audio: String::new(),
                fps: 0.0,
                size_bytes: 0,
                indexed_at: 0,
                creation_date: 0,
                tags: Vec::new(),
                has_thumbnail: false,
            })
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
            .get_video_metadata(&req.video_id)
            .await
            .map_err(|e| Status::from(e))?;

        Ok(Response::new(metadata))
    }

    async fn get_thumbnail(
        &self,
        request: Request<GetThumbnailRequest>,
    ) -> std::result::Result<Response<ThumbnailChunk>, Status> {
        let req = request.into_inner();

        let thumbnail_data = ThumbnailGenerator::get_thumbnail(
            &self.config.thumbnail_cache_path,
            &req.video_id,
            &req.size,
        )
        .map_err(|e| Status::from(e))?;

        Ok(Response::new(ThumbnailChunk {
            data: thumbnail_data.unwrap_or_default(),
        }))
    }

    async fn add_library_location(
        &self,
        request: Request<AddLocationRequest>,
    ) -> std::result::Result<Response<LocationResponse>, Status> {
        let req = request.into_inner();

        self.db
            .add_library_location(&req.path, req.recursive)
            .map_err(|e| Status::from(e))?;

        Ok(Response::new(LocationResponse {
            success: true,
            message: format!("Added library location: {}", req.path),
        }))
    }

    async fn remove_library_location(
        &self,
        request: Request<RemoveLocationRequest>,
    ) -> std::result::Result<Response<LocationResponse>, Status> {
        let req = request.into_inner();

        self.db
            .remove_library_location(&req.path)
            .map_err(|e| Status::from(e))?;

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
            .map_err(|e| Status::from(e))?;

        let location_responses = locations
            .iter()
            .map(|l| LibraryLocation {
                path: l.path.clone(),
                recursive: l.recursive,
                enabled: l.enabled,
                video_count: 0,
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
    ) -> std::result::Result<tonic::codec::Streaming<ScanProgress>, Status> {
        let req = request.into_inner();

        let (tx, rx) = tokio::sync::mpsc::channel(100);

        let db = Arc::clone(&self.db);
        let cache_path = self.config.thumbnail_cache_path.clone();
        let location_path = req.location_path.clone();

        tokio::spawn(async move {
            let scan_path = if location_path.is_empty() {
                // Scan all library locations
                match db.list_library_locations() {
                    Ok(locations) => {
                        for loc in locations {
                            if loc.enabled {
                                let _ = IndexingEngine::scan_directory(
                                    db.as_ref(),
                                    std::path::Path::new(&loc.path),
                                    loc.recursive,
                                    &cache_path,
                                    |progress| {
                                        let _ = tx.blocking_send(ScanProgress {
                                            status: progress.status.clone(),
                                            videos_found: progress.videos_found,
                                            videos_indexed: progress.videos_indexed,
                                            current_file: progress.current_file.clone(),
                                            progress_percent: progress.progress_percent,
                                        });
                                    },
                                );
                            }
                        }
                        return;
                    }
                    Err(_) => return,
                }
            } else {
                std::path::PathBuf::from(&location_path)
            };

            let _ = IndexingEngine::scan_directory(
                db.as_ref(),
                &scan_path,
                true,
                &cache_path,
                |progress| {
                    let _ = tx.blocking_send(ScanProgress {
                        status: progress.status.clone(),
                        videos_found: progress.videos_found,
                        videos_indexed: progress.videos_indexed,
                        current_file: progress.current_file.clone(),
                        progress_percent: progress.progress_percent,
                    });
                },
            );
        });

        Ok(Response::new(
            tokio_util::io::ReaderStream::new(
                tokio::io::DuplexStream::new(8192).0,
            ),
        ))
    }

    async fn get_scan_status(
        &self,
        _request: Request<GetScanStatusRequest>,
    ) -> std::result::Result<Response<ScanStatusResponse>, Status> {
        // TODO: Implement real scan status tracking
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

        let tag_id = self
            .db
            .create_tag(&req.name, req.color.as_deref())
            .map_err(|e| Status::from(e))?;

        Ok(Response::new(TagResponse {
            id: tag_id,
            name: req.name,
            color: req.color,
        }))
    }

    async fn delete_tag(
        &self,
        request: Request<DeleteTagRequest>,
    ) -> std::result::Result<Response<Response>, Status> {
        let req = request.into_inner();

        self.db
            .delete_tag(&req.tag_id)
            .map_err(|e| Status::from(e))?;

        Ok(Response::new(Response {
            success: true,
            message: "Tag deleted".to_string(),
            error: String::new(),
        }))
    }

    async fn list_tags(
        &self,
        _request: Request<ListTagsRequest>,
    ) -> std::result::Result<Response<ListTagsResponse>, Status> {
        let tags = self
            .db
            .list_tags()
            .map_err(|e| Status::from(e))?;

        let tag_responses = tags
            .iter()
            .map(|t| TagResponse {
                id: t.id.clone(),
                name: t.name.clone(),
                color: t.color.clone(),
            })
            .collect();

        Ok(Response::new(ListTagsResponse { tags: tag_responses }))
    }

    async fn tag_videos(
        &self,
        request: Request<TagVideosRequest>,
    ) -> std::result::Result<Response<Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db
                .tag_video(video_id, &req.tag_id)
                .map_err(|e| Status::from(e))?;
        }

        Ok(Response::new(Response {
            success: true,
            message: format!("Tagged {} videos", req.video_ids.len()),
            error: String::new(),
        }))
    }

    async fn untag_videos(
        &self,
        request: Request<UntagVideosRequest>,
    ) -> std::result::Result<Response<Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db
                .untag_video(video_id, &req.tag_id)
                .map_err(|e| Status::from(e))?;
        }

        Ok(Response::new(Response {
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
            .create_collection(&req.name, req.is_smart, req.filter_json.as_deref())
            .map_err(|e| Status::from(e))?;

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
    ) -> std::result::Result<Response<Response>, Status> {
        let req = request.into_inner();

        self.db
            .delete_collection(&req.collection_id)
            .map_err(|e| Status::from(e))?;

        Ok(Response::new(Response {
            success: true,
            message: "Collection deleted".to_string(),
            error: String::new(),
        }))
    }

    async fn list_collections(
        &self,
        _request: Request<ListCollectionsRequest>,
    ) -> std::result::Result<Response<ListCollectionsResponse>, Status> {
        let collections = self
            .db
            .list_collections()
            .map_err(|e| Status::from(e))?;

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
    ) -> std::result::Result<Response<Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db
                .add_to_collection(&req.collection_id, video_id)
                .map_err(|e| Status::from(e))?;
        }

        Ok(Response::new(Response {
            success: true,
            message: format!("Added {} videos to collection", req.video_ids.len()),
            error: String::new(),
        }))
    }

    async fn remove_from_collection(
        &self,
        request: Request<RemoveFromCollectionRequest>,
    ) -> std::result::Result<Response<Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db
                .remove_from_collection(&req.collection_id, video_id)
                .map_err(|e| Status::from(e))?;
        }

        Ok(Response::new(Response {
            success: true,
            message: format!("Removed {} videos from collection", req.video_ids.len()),
            error: String::new(),
        }))
    }

    async fn update_video_notes(
        &self,
        request: Request<UpdateNotesRequest>,
    ) -> std::result::Result<Response<Response>, Status> {
        let req = request.into_inner();

        self.db
            .update_notes(&req.video_id, &req.notes)
            .map_err(|e| Status::from(e))?;

        Ok(Response::new(Response {
            success: true,
            message: "Notes updated".to_string(),
            error: String::new(),
        }))
    }

    async fn delete_video(
        &self,
        request: Request<DeleteVideoRequest>,
    ) -> std::result::Result<Response<Response>, Status> {
        let req = request.into_inner();

        if req.delete_file {
            if let Ok(Some(video)) = self.db.get_video(&req.video_id) {
                let _ = std::fs::remove_file(&video.path);
            }
        }

        self.db
            .delete_video(&req.video_id)
            .map_err(|e| Status::from(e))?;

        Ok(Response::new(Response {
            success: true,
            message: "Video deleted".to_string(),
            error: String::new(),
        }))
    }

    async fn generate_proxy(
        &self,
        request: Request<GenerateProxyRequest>,
    ) -> std::result::Result<tonic::codec::Streaming<ProxyGenerationProgress>, Status> {
        let _req = request.into_inner();

        // TODO: Implement proxy generation
        Ok(Response::new(
            tokio_util::io::ReaderStream::new(
                tokio::io::DuplexStream::new(8192).0,
            ),
        ))
    }

    async fn list_proxies(
        &self,
        _request: Request<ListProxiesRequest>,
    ) -> std::result::Result<Response<ListProxiesResponse>, Status> {
        Ok(Response::new(ListProxiesResponse { proxies: vec![] }))
    }

    async fn get_status(
        &self,
        _request: Request<GetStatusRequest>,
    ) -> std::result::Result<Response<StatusResponse>, Status> {
        let (videos, _) = self
            .db
            .list_videos(1, 0)
            .map_err(|e| Status::from(e))?;

        Ok(Response::new(StatusResponse {
            running: true,
            total_videos: 0,
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
        request: Request<UpdateConfigRequest>,
    ) -> std::result::Result<Response<Response>, Status> {
        let _req = request.into_inner();

        // TODO: Implement config update

        Ok(Response::new(Response {
            success: true,
            message: "Config updated".to_string(),
            error: String::new(),
        }))
    }
}
