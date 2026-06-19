// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

use crate::config::Config;
use crate::db::{Database, FilterSpec};
use crate::error::{Result, ReelVaultError};
use crate::indexing::IndexingEngine;
use crate::metadata_keys;
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
pub mod reelvault {
    #![allow(clippy::doc_lazy_continuation)]
    tonic::include_proto!("reelvault");
}

use reelvault::reel_vault_server::{ReelVault as ReelVaultTrait, ReelVaultServer};
use reelvault::*;

pub use reelvault::reel_vault_server;

/// Cloning is cheap — every field is an `Arc` or a handle — and lets RPC
/// handlers move a copy of the service into `spawn_blocking` closures.
#[derive(Clone)]
pub struct ReelVaultService {
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
    /// Pending one-time pairing code, shared with the media server so a code
    /// minted by `StartPairing` here is redeemable at `POST /pair` there.
    pairing: crate::pairing::PairingState,
    /// Per-OS data dir, used to write `pairing.txt` when a code is issued.
    data_dir: std::path::PathBuf,
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

impl ReelVaultService {
    pub fn new(
        db: Arc<Database>,
        config: Arc<Config>,
        pairing: crate::pairing::PairingState,
        data_dir: std::path::PathBuf,
    ) -> Self {
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

        let service = ReelVaultService {
            db,
            config: Arc::clone(&config),
            opened_at_ms: Arc::new(std::sync::RwLock::new(initial_opened)),
            scrub_locks: Arc::new(tokio::sync::Mutex::new(std::collections::HashMap::new())),
            catalog_events: catalog_tx,
            watcher: Arc::new(tokio::sync::Mutex::new(None)),
            watch_settings: Arc::new(tokio::sync::RwLock::new(watch_settings)),
            pairing,
            data_dir,
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

    /// A sender on the catalog-change broadcast bus. The media server holds one
    /// so an HLS transcode it promotes to a durable proxy can publish a
    /// `VideoAdded` event, just as `GenerateProxy` does.
    pub fn catalog_events(&self) -> broadcast::Sender<CatalogChange> {
        self.catalog_events.clone()
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

    pub fn into_server(self) -> ReelVaultServer<Self> {
        ReelVaultServer::new(self)
    }

    /// Run blocking DB/filesystem work on tokio's blocking pool with a cheap
    /// clone of the service, keeping the async workers free to serve other
    /// RPCs. Handlers whose cost scales with the catalog or page size (list,
    /// search, facets, …) go through this; one-row indexed lookups stay
    /// inline.
    // result_large_err: the Err type is tonic's Status (large by design);
    // the closures exist to carry RPC results, so the lint is moot here.
    #[allow(clippy::result_large_err)]
    async fn run_blocking<T, F>(&self, f: F) -> std::result::Result<T, Status>
    where
        T: Send + 'static,
        F: FnOnce(ReelVaultService) -> std::result::Result<T, Status> + Send + 'static,
    {
        let svc = self.clone();
        tokio::task::spawn_blocking(move || f(svc))
            .await
            .map_err(|e| Status::internal(format!("blocking task panicked: {e}")))?
    }

    /// Compute the audio loudness-over-time blob for `video_id` (see
    /// [`crate::metadata::extract_audio_loudness`]): little-endian f32 samples
    /// in [0, 1], one per time slice. Returns empty bytes when the video is
    /// unknown or has no audio track — the caller streams that back and the
    /// client simply shows no graph. The ffmpeg decode runs off the async
    /// runtime via `run_blocking`.
    #[allow(clippy::result_large_err)]
    async fn audio_loudness_blob(&self, video_id: &str) -> std::result::Result<Vec<u8>, Status> {
        let video = match self.db.get_video(video_id) {
            Ok(Some(v)) => v,
            _ => return Ok(Vec::new()),
        };
        // Read the cached blob (if computed before) and the audio codec in one go.
        // A non-NULL blob — even empty — means we already decoded this video, so
        // return it and skip the (slow, networked) ffmpeg pass. NULL means we've
        // never tried; empty codec_audio means there's no audio to analyse.
        let (cached, has_audio): (Option<Vec<u8>>, bool) = self
            .db
            .get_connection()
            .ok()
            .and_then(|c| {
                c.query_row(
                    "SELECT audio_loudness, codec_audio FROM metadata WHERE video_id = ?",
                    [video_id],
                    |row| {
                        let blob: Option<Vec<u8>> = row.get(0)?;
                        let codec: Option<String> = row.get(1)?;
                        Ok((blob, codec.map(|s| !s.is_empty()).unwrap_or(false)))
                    },
                )
                .ok()
            })
            .unwrap_or((None, false));
        if let Some(blob) = cached {
            // A non-empty cached series is authoritative. An *empty* cached blob is
            // only trusted when the video has no audio track; for a video that does
            // have audio, an empty cache means an earlier decode produced nothing —
            // e.g. the ffmpeg log-level bug that suppressed every per-frame reading
            // (see extract_audio_loudness). Recompute rather than serve a
            // permanently-blank graph, so existing catalogs self-heal.
            if !blob.is_empty() || !has_audio {
                return Ok(blob);
            }
        }
        if !has_audio {
            return Ok(Vec::new());
        }

        let path = video.path.clone();
        let bytes = self
            .run_blocking(move |_svc| {
                let samples = crate::media_backend::backend()
                    .extract_loudness(&crate::media_backend::MediaSource::Path(path.clone().into()));
                let mut bytes = Vec::with_capacity(samples.len() * 4);
                for s in samples {
                    bytes.extend_from_slice(&s.to_le_bytes());
                }
                Ok(bytes)
            })
            .await?;
        // Cache the result (even empty — a decode that found no loudness shouldn't
        // be retried over the network on every detail open). Best-effort.
        if let Ok(conn) = self.db.get_connection() {
            let _ = conn.execute(
                "UPDATE metadata SET audio_loudness = ? WHERE video_id = ?",
                rusqlite::params![bytes, video_id],
            );
        }
        Ok(bytes)
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
            ReelVaultError::DatabaseError(format!("custom_camera_names serialize failed: {e}"))
        })?;
        let conn = self.db.get_connection()?;
        conn.execute(
            "INSERT INTO config (key, value) VALUES (?, ?)
             ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = CURRENT_TIMESTAMP",
            rusqlite::params!["custom_camera_names", json, json],
        )
        .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;
        Ok(())
    }

    /// Lens display-name overrides, normalised into the `raw → alias`
    /// map used when resolving facet display names. Mirrors
    /// [`Self::load_custom_camera_names`]; stored under the
    /// `custom_lens_names` config key as `[{"raw": …, "alias": …}, …]`.
    /// Any failure yields an empty map so a malformed override never
    /// breaks the metadata pipeline. Reuses the generic normalise /
    /// build_custom_overrides helpers from `camera_names`.
    fn load_custom_lens_names(&self) -> std::collections::HashMap<String, String> {
        let parsed = self.read_custom_lens_names();
        if parsed.is_empty() {
            return std::collections::HashMap::new();
        }
        crate::camera_names::build_custom_overrides(
            parsed.into_iter().map(|e| (e.raw, e.alias)),
        )
    }

    /// Raw `Vec<CustomLensName>` from the config table (unfiltered,
    /// unsorted) — used by the editor RPCs so we can mutate and write
    /// the list back while preserving the user's chosen `raw` casing.
    fn read_custom_lens_names(&self) -> Vec<crate::config::CustomLensName> {
        let raw: Option<String> = self
            .db
            .get_connection()
            .ok()
            .and_then(|c| {
                c.query_row(
                    "SELECT value FROM config WHERE key = ?",
                    ["custom_lens_names"],
                    |row| row.get::<_, String>(0),
                )
                .ok()
            });
        match raw {
            Some(s) if !s.trim().is_empty() => serde_json::from_str(&s).unwrap_or_default(),
            _ => Vec::new(),
        }
    }

    /// Serialise + persist a custom-lens-name list to the config table.
    fn write_custom_lens_names(&self, entries: &[crate::config::CustomLensName]) -> Result<()> {
        let json = serde_json::to_string(entries).map_err(|e| {
            ReelVaultError::DatabaseError(format!("custom_lens_names serialize failed: {e}"))
        })?;
        let conn = self.db.get_connection()?;
        conn.execute(
            "INSERT INTO config (key, value) VALUES (?, ?)
             ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = CURRENT_TIMESTAMP",
            rusqlite::params!["custom_lens_names", json, json],
        )
        .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;
        Ok(())
    }

    fn get_video_metadata_sync(&self, video_id: &str) -> Result<VideoMetadata> {
        let db = self.db.as_ref();
        let video = db
            .get_video(video_id)
            .and_then(|v| v.ok_or_else(|| ReelVaultError::VideoNotFound(video_id.to_string())))?;

        let conn = db.get_connection()?;
        let metadata_row = conn
            .query_row(
                "SELECT
                    duration_ms, codec_video, codec_audio, width, height,
                    fps, bitrate, color_space, hdr, audio_channels, audio_sample_rate,
                    creation_date, camera_model, lens_model, gps_latitude, gps_longitude,
                    gps_altitude,
                    iso, aperture, exposure_time_s, focal_length_mm,
                    exposure_mode, exposure_program, white_balance,
                    color_transfer, color_primaries, dynamic_range, timecode_start, capture_fps,
                    bit_depth, audio_bit_depth, audio_language, audio_track_count, spatial
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
                        row.get::<_, Option<String>>(24)?,
                        row.get::<_, Option<String>>(25)?,
                        row.get::<_, Option<String>>(26)?,
                        row.get::<_, Option<String>>(27)?,
                        row.get::<_, Option<f64>>(28)?,
                        row.get::<_, Option<i32>>(29)?,
                        row.get::<_, Option<i32>>(30)?,
                        row.get::<_, Option<String>>(31)?,
                        row.get::<_, Option<i32>>(32)?,
                        row.get::<_, i32>(33)? != 0,
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
             exposure_mode, exposure_program, white_balance,
             color_transfer, color_primaries, dynamic_range, timecode_start, capture_fps,
             bit_depth, audio_bit_depth, audio_language, audio_track_count, spatial) =
            row.unwrap_or((0, None, None, 0, 0, 0.0, 0, None, false, 0, 0, None, None, None, None, None, None,
                           None, None, None, None, None, None, None,
                           None, None, None, None, None,
                           None, None, None, None, false));

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

        let full_resolution = classify_full_resolution(
            &self.db,
            &camera_model_str,
            width,
            height,
        );

        // ffprobe's nb_frames, stored at index time (0 when the container
        // didn't report one; clients estimate from duration × fps in that case).
        let frame_count = db.get_video_frame_count(video_id);

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
            color_transfer: color_transfer.unwrap_or_default(),
            color_primaries: color_primaries.unwrap_or_default(),
            dynamic_range: dynamic_range.unwrap_or_default(),
            timecode: timecode_start.unwrap_or_default(),
            capture_fps: capture_fps.unwrap_or(0.0),
            bit_depth: bit_depth.unwrap_or(0),
            audio_bit_depth: audio_bit_depth.unwrap_or(0),
            audio_language: audio_language.unwrap_or_default(),
            audio_track_count: audio_track_count.unwrap_or(0),
            spatial,
            tags,
            collections: db.get_video_collections(video_id).unwrap_or_default(),
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
            full_resolution,
            frame_count,
        })
    }

    /// Video duration in seconds, read from the cached metadata row (0.0 when
    /// unknown). Used to seek the right frame for on-demand thumbnail work.
    fn video_duration_secs(&self, video_id: &str) -> f64 {
        self.db
            .get_connection()
            .ok()
            .and_then(|c| {
                c.query_row(
                    "SELECT duration_ms FROM metadata WHERE video_id = ?",
                    [video_id],
                    |row| row.get::<_, i64>(0),
                )
                .ok()
            })
            .map(|ms| ms as f64 / 1000.0)
            .unwrap_or(0.0)
    }

    /// Serve a higher-resolution thumbnail variant (`max_width` px wide),
    /// generating just that one frame on demand if it isn't cached yet. Used by
    /// the detail view to upgrade scrub frames to the display resolution. The
    /// per-frame `generate_frame_at_width` is idempotent (skips if the file
    /// exists) and parallel-safe, so concurrent requests for sibling frames run
    /// up to the global ffmpeg concurrency limit without a per-video lock.
    async fn load_or_generate_hires(
        &self,
        video_id: &str,
        size: &str,
        max_width: i32,
    ) -> std::result::Result<Option<Vec<u8>>, Status> {
        let cache = self.config.thumbnail_cache_path.clone();
        if let Some(data) = read_cached_thumbnail(
            cache.clone(),
            video_id.to_string(),
            size.to_string(),
            max_width,
        )
        .await?
        {
            return Ok(Some(data));
        }
        if let Ok(Some(_video)) = self.db.get_video(video_id) {
            let duration = self.video_duration_secs(video_id);
            if duration > 0.0 {
                let cache2 = cache.clone();
                let vid = video_id.to_string();
                let size_s = size.to_string();
                let db = std::sync::Arc::clone(&self.db);
                let _ = tokio::task::spawn_blocking(move || {
                    let source = match db.media_source_for(&vid) {
                        Ok(s) => s,
                        Err(_) => return,
                    };
                    let _ = ThumbnailGenerator::generate_frame_at_width(
                        &source,
                        &vid,
                        &cache2,
                        duration,
                        &size_s,
                        max_width,
                    );
                })
                .await;
            }
        }
        read_cached_thumbnail(cache, video_id.to_string(), size.to_string(), max_width).await
    }

    /// Build the `VideoSummary` rows for a whole page of videos.
    ///
    /// The per-row predecessor of this method issued ~10 individual lookups
    /// per video (metadata, tags, marks, proxy/group linkage, camera-name
    /// overrides, …), which multiplied out to hundreds of queries per
    /// `ListVideos` page. This version fetches each aspect for the entire
    /// batch in one query (chunked to stay clear of SQLite's bind-variable
    /// limit), shares a single connection, loads the camera-name overrides
    /// once, and memoizes full-resolution classification per distinct
    /// `(camera, width, height)`.
    ///
    /// Missing rows keep the per-row defaults: no metadata → zeros, no marks
    /// → (0, ""), unknown id → online, no group → singleton.
    fn build_video_summaries(&self, seeds: Vec<SummarySeed>) -> Vec<VideoSummary> {
        use std::collections::HashMap;

        if seeds.is_empty() {
            return Vec::new();
        }
        let conn = self.db.get_connection().ok();
        let ids: Vec<&str> = seeds.iter().map(|s| s.id.as_str()).collect();

        // (is_online, group_id, proxy_of) straight off the videos row.
        let mut flags: HashMap<String, (bool, Option<String>, Option<String>)> = HashMap::new();
        let mut meta: HashMap<String, MetaFields> = HashMap::new();
        let mut tags: HashMap<String, Vec<String>> = HashMap::new();
        let mut marks: HashMap<String, (i32, String)> = HashMap::new();
        let mut proxy_counts: HashMap<String, i32> = HashMap::new();

        if let Some(conn) = conn.as_ref() {
            for chunk in ids.chunks(SQL_IN_CHUNK) {
                let ph = sql_placeholders(chunk.len());

                if let Ok(mut stmt) = conn.prepare(&format!(
                    "SELECT id, is_online, group_id, proxy_of FROM videos WHERE id IN ({ph})"
                )) {
                    if let Ok(rows) = stmt.query_map(
                        rusqlite::params_from_iter(chunk.iter()),
                        |row| {
                            Ok((
                                row.get::<_, String>(0)?,
                                row.get::<_, i32>(1)?,
                                row.get::<_, Option<String>>(2)?,
                                row.get::<_, Option<String>>(3)?,
                            ))
                        },
                    ) {
                        for (id, online, group_id, proxy_of) in rows.flatten() {
                            flags.insert(id, (online != 0, group_id, proxy_of));
                        }
                    }
                }

                // A row whose extraction fails (e.g. NULL duration on a
                // half-indexed video) is skipped entirely so the summary
                // falls back to all-default metadata — same behavior as the
                // old per-row `.ok()`.
                if let Ok(mut stmt) = conn.prepare(&format!(
                    "SELECT video_id, duration_ms, width, height, fps, codec_video, codec_audio,
                            creation_date, camera_model, gps_latitude, gps_longitude,
                            lens_model, iso, aperture, exposure_time_s, focal_length_mm,
                            bitrate, COALESCE(frame_count, 0),
                            dynamic_range, timecode_start, capture_fps,
                            bit_depth, audio_bit_depth, audio_language, audio_track_count, spatial
                     FROM metadata WHERE video_id IN ({ph})"
                )) {
                    if let Ok(rows) = stmt.query_map(
                        rusqlite::params_from_iter(chunk.iter()),
                        |row| {
                            Ok((
                                row.get::<_, String>(0)?,
                                MetaFields {
                                    duration_ms: row.get::<_, i64>(1)?,
                                    width: row.get::<_, i32>(2)?,
                                    height: row.get::<_, i32>(3)?,
                                    fps: row.get::<_, f64>(4)?,
                                    codec_video: row.get::<_, Option<String>>(5)?,
                                    codec_audio: row.get::<_, Option<String>>(6)?,
                                    creation_date: row.get::<_, Option<i64>>(7)?,
                                    camera_model: row.get::<_, Option<String>>(8)?,
                                    gps_lat: row.get::<_, Option<f64>>(9)?,
                                    gps_lon: row.get::<_, Option<f64>>(10)?,
                                    lens_model: row.get::<_, Option<String>>(11)?,
                                    iso: row.get::<_, Option<i64>>(12)?,
                                    aperture: row.get::<_, Option<f64>>(13)?,
                                    exposure_time_s: row.get::<_, Option<f64>>(14)?,
                                    focal_length_mm: row.get::<_, Option<f64>>(15)?,
                                    bitrate: row.get::<_, i64>(16)?,
                                    frame_count: row.get::<_, i64>(17)?,
                                    dynamic_range: row.get::<_, Option<String>>(18)?,
                                    timecode: row.get::<_, Option<String>>(19)?,
                                    capture_fps: row.get::<_, Option<f64>>(20)?,
                                    bit_depth: row.get::<_, Option<i32>>(21)?,
                                    audio_bit_depth: row.get::<_, Option<i32>>(22)?,
                                    audio_language: row.get::<_, Option<String>>(23)?,
                                    audio_track_count: row.get::<_, Option<i32>>(24)?,
                                    spatial: row.get::<_, Option<i32>>(25)?.unwrap_or(0) != 0,
                                },
                            ))
                        },
                    ) {
                        for (id, fields) in rows.flatten() {
                            meta.insert(id, fields);
                        }
                    }
                }

                // ORDER BY keeps each video's tag list name-sorted, matching
                // the old get_video_tags.
                if let Ok(mut stmt) = conn.prepare(&format!(
                    "SELECT vt.video_id, t.name FROM video_tags vt
                     JOIN tags t ON t.id = vt.tag_id
                     WHERE vt.video_id IN ({ph})
                     ORDER BY t.name"
                )) {
                    if let Ok(rows) = stmt.query_map(
                        rusqlite::params_from_iter(chunk.iter()),
                        |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)),
                    ) {
                        for (id, name) in rows.flatten() {
                            tags.entry(id).or_default().push(name);
                        }
                    }
                }

                if let Ok(mut stmt) = conn.prepare(&format!(
                    "SELECT video_id, rating, color_label FROM video_user_marks
                     WHERE video_id IN ({ph})"
                )) {
                    if let Ok(rows) = stmt.query_map(
                        rusqlite::params_from_iter(chunk.iter()),
                        |row| {
                            Ok((
                                row.get::<_, String>(0)?,
                                row.get::<_, i32>(1)?,
                                row.get::<_, String>(2)?,
                            ))
                        },
                    ) {
                        for (id, rating, label) in rows.flatten() {
                            marks.insert(id, (rating, label));
                        }
                    }
                }

                // Join through videos so dangling proxy_links rows (possible
                // in catalogs written before foreign keys were enforced)
                // don't inflate the badge — same set list_proxies counted.
                if let Ok(mut stmt) = conn.prepare(&format!(
                    "SELECT pl.master_id, COUNT(*) FROM proxy_links pl
                     JOIN videos v ON pl.proxy_id = v.id
                     WHERE pl.master_id IN ({ph})
                     GROUP BY pl.master_id"
                )) {
                    if let Ok(rows) = stmt.query_map(
                        rusqlite::params_from_iter(chunk.iter()),
                        |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
                    ) {
                        for (id, count) in rows.flatten() {
                            proxy_counts.insert(id, count as i32);
                        }
                    }
                }
            }
        }

        // Stack info for every distinct group on the page: member count,
        // preferred id, and the preferred video's path.
        let group_ids: Vec<String> = {
            let mut set = std::collections::BTreeSet::new();
            for (_, gid, _) in flags.values() {
                if let Some(g) = gid {
                    set.insert(g.clone());
                }
            }
            set.into_iter().collect()
        };
        let mut group_sizes: HashMap<String, i32> = HashMap::new();
        let mut group_preferred: HashMap<String, Option<String>> = HashMap::new();
        let mut preferred_paths: HashMap<String, String> = HashMap::new();
        if let Some(conn) = conn.as_ref() {
            for chunk in group_ids.chunks(SQL_IN_CHUNK) {
                let ph = sql_placeholders(chunk.len());

                if let Ok(mut stmt) = conn.prepare(&format!(
                    "SELECT group_id, COUNT(*) FROM videos WHERE group_id IN ({ph}) GROUP BY group_id"
                )) {
                    if let Ok(rows) = stmt.query_map(
                        rusqlite::params_from_iter(chunk.iter()),
                        |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
                    ) {
                        for (gid, count) in rows.flatten() {
                            group_sizes.insert(gid, count as i32);
                        }
                    }
                }

                if let Ok(mut stmt) = conn.prepare(&format!(
                    "SELECT id, preferred_video_id FROM video_groups WHERE id IN ({ph})"
                )) {
                    if let Ok(rows) = stmt.query_map(
                        rusqlite::params_from_iter(chunk.iter()),
                        |row| Ok((row.get::<_, String>(0)?, row.get::<_, Option<String>>(1)?)),
                    ) {
                        for (gid, preferred) in rows.flatten() {
                            group_preferred.insert(gid, preferred);
                        }
                    }
                }
            }

            let preferred_ids: Vec<&str> = group_preferred
                .values()
                .filter_map(|p| p.as_deref())
                .collect();
            for chunk in preferred_ids.chunks(SQL_IN_CHUNK) {
                let ph = sql_placeholders(chunk.len());
                if let Ok(mut stmt) = conn.prepare(&format!(
                    "SELECT id, path FROM videos WHERE id IN ({ph})"
                )) {
                    if let Ok(rows) = stmt.query_map(
                        rusqlite::params_from_iter(chunk.iter()),
                        |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)),
                    ) {
                        for (id, path) in rows.flatten() {
                            preferred_paths.insert(id, path);
                        }
                    }
                }
            }
        }

        // Loaded once per page (the old path re-read and re-parsed the
        // overrides JSON for every row that carried a camera model).
        let custom_overrides = self.load_custom_camera_names();
        let mut fullres_memo: HashMap<(String, i32, i32), i32> = HashMap::new();

        seeds
            .into_iter()
            .map(|seed| {
                let m = meta.remove(&seed.id).unwrap_or_default();
                let (is_online, group_id_opt, proxy_of_opt) =
                    flags.remove(&seed.id).unwrap_or((true, None, None));
                let video_tags = tags.remove(&seed.id).unwrap_or_default();
                let (rating, color_label) =
                    marks.remove(&seed.id).unwrap_or((0, String::new()));
                let proxy_count = proxy_counts.get(seed.id.as_str()).copied().unwrap_or(0);

                // Resolve the marketing-friendly camera name the same way
                // build_video_metadata does — user overrides on top of the
                // built-in mapping table, falling back to the raw EXIF string
                // when no mapping is known. Clients detect "no mapping" by
                // comparing the two and may hide the affordance that flips
                // between them.
                let camera_model_str = m.camera_model.unwrap_or_default();
                let camera_display_name = if camera_model_str.is_empty() {
                    String::new()
                } else {
                    crate::camera_names::marketing_name_for_with_custom(
                        &camera_model_str,
                        &custom_overrides,
                    )
                    .unwrap_or_else(|| camera_model_str.clone())
                };

                // `has_thumbnail` gates whether the client even requests a
                // still. The daemon now regenerates a missing still on demand
                // (see get_thumbnail), so for an *online* video the client
                // should always ask — otherwise a cleared/!-yet-generated still
                // (common for ProRes RAW, whose stills can only be made via the
                // QuickLook/AVFoundation path) leaves the card on the placeholder
                // forever, since the regen only fires on a request. Offline
                // videos can't be regenerated, so there we still require a
                // cached file.
                let thumb_path = self
                    .config
                    .thumbnail_cache_path
                    .join(format!("{}_medium.jpg", seed.id));
                let has_thumbnail = is_online || thumb_path.exists();

                let (group_id, group_size, group_preferred_id, group_preferred_path) =
                    match group_id_opt {
                        Some(gid) => {
                            let size = group_sizes.get(&gid).copied().unwrap_or(1);
                            let preferred_id = group_preferred
                                .get(&gid)
                                .cloned()
                                .flatten()
                                .unwrap_or_else(|| seed.id.clone());
                            let preferred_path = if preferred_id == seed.id {
                                seed.path.clone()
                            } else {
                                preferred_paths
                                    .get(&preferred_id)
                                    .cloned()
                                    .unwrap_or_default()
                            };
                            (gid, size, preferred_id, preferred_path)
                        }
                        None => (String::new(), 1, String::new(), String::new()),
                    };

                let full_resolution = *fullres_memo
                    .entry((camera_model_str.clone(), m.width, m.height))
                    .or_insert_with(|| {
                        classification_code(
                            conn.as_deref(),
                            &camera_model_str,
                            m.width,
                            m.height,
                        )
                    });

                VideoSummary {
                    id: seed.id,
                    filename: seed.filename,
                    path: seed.path,
                    duration_ms: m.duration_ms,
                    width: m.width,
                    height: m.height,
                    codec_video: m.codec_video.unwrap_or_default(),
                    codec_audio: m.codec_audio.unwrap_or_default(),
                    fps: m.fps,
                    size_bytes: seed.size_bytes,
                    indexed_at: seed.indexed_at,
                    creation_date: m.creation_date.unwrap_or(0),
                    tags: video_tags,
                    has_thumbnail,
                    group_id,
                    group_size,
                    group_preferred_id,
                    group_preferred_path,
                    proxy_count,
                    proxy_of: proxy_of_opt.unwrap_or_default(),
                    playable_natively: self.config.max_native_playback_height == 0
                        || m.height <= self.config.max_native_playback_height,
                    rating,
                    color_label,
                    camera_model: camera_model_str,
                    camera_display_name,
                    gps_latitude: m.gps_lat.unwrap_or(0.0),
                    gps_longitude: m.gps_lon.unwrap_or(0.0),
                    lens_model: m.lens_model.unwrap_or_default(),
                    iso: m.iso.unwrap_or(0) as i32,
                    aperture: m.aperture.unwrap_or(0.0),
                    exposure_time_s: m.exposure_time_s.unwrap_or(0.0),
                    focal_length_mm: m.focal_length_mm.unwrap_or(0.0),
                    full_resolution,
                    is_online,
                    bitrate: m.bitrate,
                    frame_count: m.frame_count,
                    dynamic_range: m.dynamic_range.unwrap_or_default(),
                    timecode: m.timecode.unwrap_or_default(),
                    capture_fps: m.capture_fps.unwrap_or(0.0),
                    bit_depth: m.bit_depth.unwrap_or(0),
                    audio_bit_depth: m.audio_bit_depth.unwrap_or(0),
                    audio_language: m.audio_language.unwrap_or_default(),
                    audio_track_count: m.audio_track_count.unwrap_or(0),
                    spatial: m.spatial,
                }
            })
            .collect()
    }
}

/// The identifying columns a summary caller already has in hand for each row;
/// everything else is batch-fetched by
/// [`ReelVaultService::build_video_summaries`].
struct SummarySeed {
    id: String,
    filename: String,
    path: String,
    size_bytes: i64,
    indexed_at: i64,
}

/// One video's `metadata` row, shaped for `VideoSummary`. `Default` mirrors
/// the old per-row fallback tuple: zeros and `None`s.
#[derive(Default)]
struct MetaFields {
    duration_ms: i64,
    width: i32,
    height: i32,
    fps: f64,
    codec_video: Option<String>,
    codec_audio: Option<String>,
    creation_date: Option<i64>,
    camera_model: Option<String>,
    gps_lat: Option<f64>,
    gps_lon: Option<f64>,
    lens_model: Option<String>,
    iso: Option<i64>,
    aperture: Option<f64>,
    exposure_time_s: Option<f64>,
    focal_length_mm: Option<f64>,
    bitrate: i64,
    frame_count: i64,
    dynamic_range: Option<String>,
    timecode: Option<String>,
    capture_fps: Option<f64>,
    bit_depth: Option<i32>,
    audio_bit_depth: Option<i32>,
    audio_language: Option<String>,
    audio_track_count: Option<i32>,
    spatial: bool,
}

/// Chunk size for `IN (?,…)` lists — comfortably below SQLite's bind-variable
/// limit while keeping the query count at one per ~page.
const SQL_IN_CHUNK: usize = 500;

/// `n` comma-separated `?` placeholders for an `IN` list.
fn sql_placeholders(n: usize) -> String {
    std::iter::repeat_n("?", n).collect::<Vec<_>>().join(", ")
}

/// Read a cached thumbnail file on the blocking pool — file IO can stall on a
/// cold or busy disk, and GetThumbnail arrives in bursts of dozens when a
/// grid page mounts. `max_width > 0` selects the width-specific variant.
async fn read_cached_thumbnail(
    cache: std::path::PathBuf,
    video_id: String,
    size: String,
    max_width: i32,
) -> std::result::Result<Option<Vec<u8>>, Status> {
    tokio::task::spawn_blocking(move || {
        if max_width > 0 {
            ThumbnailGenerator::get_thumbnail_at_width(&cache, &video_id, &size, max_width)
        } else {
            ThumbnailGenerator::get_thumbnail(&cache, &video_id, &size)
        }
    })
    .await
    .map_err(|e| Status::internal(format!("blocking task panicked: {e}")))?
    .map_err(Status::from)
}

/// Classify a video's full-resolution status to its `i32` proto code.
///
/// Reads the catalog's `camera_sensor_cache` table when the camera
/// isn't covered by the built-in `core/data/sensor_resolutions.json`
/// table; falls back to the pure built-in classifier when the catalog
/// isn't reachable (degraded mode rather than an error).
///
/// Negative widths/heights from the DB are clamped to 0 — the
/// classifier treats 0×0 as Unknown.
fn classify_full_resolution(
    db: &crate::db::Database,
    camera_model: &str,
    width: i32,
    height: i32,
) -> i32 {
    classification_code(db.get_connection().ok().as_deref(), camera_model, width, height)
}

/// Same classification on an already-open connection, so batch callers can
/// reuse one connection (and memoize) instead of opening one per video.
/// `None` falls back to the pure built-in classifier — degraded mode rather
/// than an error, matching [`classify_full_resolution`].
fn classification_code(
    conn: Option<&rusqlite::Connection>,
    camera_model: &str,
    width: i32,
    height: i32,
) -> i32 {
    use crate::full_resolution::Classification;
    let w = width.max(0) as u32;
    let h = height.max(0) as u32;
    let classification = match conn {
        Some(c) => crate::sensor_cache::classify_with_cache(c, camera_model, w, h),
        None => crate::full_resolution::classify(camera_model, w, h),
    };
    let proto_enum = match classification {
        Classification::Unknown => FullResolutionStatus::Unspecified,
        Classification::Full => FullResolutionStatus::Full,
        Classification::NotFull => FullResolutionStatus::NotFull,
    };
    proto_enum as i32
}

/// Map a proto `AttributeFilter` to the DB layer's tri-state `Option<bool>`:
/// ANY → no constraint, YES → must have, NO → must not have.
fn attribute_filter_opt(v: AttributeFilter) -> Option<bool> {
    match v {
        AttributeFilter::Yes => Some(true),
        AttributeFilter::No => Some(false),
        AttributeFilter::Any => None,
    }
}

/// Resolve the request's `filter_full_resolution` toggle into the DB layer's
/// [`FullResolutionFilter`], precomputing the catalog's FULL `(camera, w, h)`
/// tuples (see [`full_resolution_full_tuples`]) only when the filter is active.
fn full_resolution_filter(
    db: &crate::db::Database,
    toggle: AttributeFilter,
) -> Option<crate::db::FullResolutionFilter> {
    attribute_filter_opt(toggle).map(|want_full| crate::db::FullResolutionFilter {
        want_full,
        full_tuples: full_resolution_full_tuples(db),
    })
}

/// The catalog's distinct `(camera_model, width, height)` combinations that
/// classify as full resolution. Translates the Rust-side classifier into a
/// SQL membership test for the "is full resolution" attribute filter (see
/// [`crate::db::FullResolutionFilter`]). Returns an empty vec when the catalog
/// is unreachable — the caller treats that as "nothing is full".
fn full_resolution_full_tuples(db: &crate::db::Database) -> Vec<(String, i64, i64)> {
    use crate::full_resolution::Classification;
    let conn = match db.get_connection() {
        Ok(c) => c,
        Err(_) => return Vec::new(),
    };
    // Distinct camera+resolution combos that could be FULL (a known camera
    // with real dimensions); classify each through the sensor cache.
    let combos: Vec<(String, i64, i64)> = {
        let mut stmt = match conn.prepare(
            "SELECT DISTINCT camera_model, width, height FROM metadata \
             WHERE camera_model IS NOT NULL AND camera_model != '' \
               AND width > 0 AND height > 0",
        ) {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, i64>(2)?,
            ))
        });
        match rows {
            Ok(r) => r.filter_map(std::result::Result::ok).collect(),
            Err(_) => return Vec::new(),
        }
    };
    combos
        .into_iter()
        .filter(|(cam, w, h)| {
            matches!(
                crate::sensor_cache::classify_with_cache(&conn, cam, *w as u32, *h as u32),
                Classification::Full
            )
        })
        .collect()
}

// result_large_err: every handler (and every run_blocking closure inside
// one) returns tonic's Status by value — that's the shape tonic's API
// dictates, so the "boxing the Err" suggestion doesn't apply.
#[allow(clippy::result_large_err)]
#[tonic::async_trait]
impl ReelVaultTrait for ReelVaultService {
    type ScanLibraryStream = Pin<Box<dyn Stream<Item = std::result::Result<ScanProgress, Status>> + Send>>;
    type GenerateProxyStream = Pin<Box<dyn Stream<Item = std::result::Result<ProxyGenerationProgress, Status>> + Send>>;
    type GetThumbnailStream = Pin<Box<dyn Stream<Item = std::result::Result<ThumbnailChunk, Status>> + Send>>;

    async fn list_videos(
        &self,
        request: Request<ListVideosRequest>,
    ) -> std::result::Result<Response<ListVideosResponse>, Status> {
        let req = request.into_inner();
        // Page-scale DB work — off the async runtime.
        self.run_blocking(move |svc| {
            let limit = if req.limit <= 0 { 50 } else { req.limit as i64 };
            let offset = req.offset.max(0) as i64;

            // Expand tilde in the location filter if provided. The library panel
            // supports multi-select, so `location_path` may carry several
            // directories joined by '\n'; expand each and re-join. The DB layer
            // splits on '\n' and matches a video under ANY of them. A single
            // directory contains no '\n' and behaves exactly as before.
            let location_filter = if req.location_path.is_empty() {
                String::new()
            } else {
                req.location_path
                    .split('\n')
                    .filter(|p| !p.is_empty())
                    .map(expand_tilde)
                    .collect::<Vec<_>>()
                    .join("\n")
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
                    if let Ok(Some(tag)) = svc.db.get_tag_by_name(s) {
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

            // Fold the legacy scalar dropdown filters into the generic metadata
            // filters (back-compat: old clients still send camera/lens/codec/year
            // as scalars; new clients send `metadata_filters`). Then build the
            // shared FilterSpec.
            let mut metadata_filters: Vec<(String, String)> = req
                .metadata_filters
                .iter()
                .map(|mf| (mf.key.clone(), mf.value.clone()))
                .collect();
            if !req.filter_camera.is_empty() {
                metadata_filters.push(("camera".to_string(), req.filter_camera.clone()));
            }
            if !req.filter_lens.is_empty() {
                metadata_filters.push(("lens".to_string(), req.filter_lens.clone()));
            }
            if !req.filter_codec.is_empty() {
                metadata_filters.push(("codec".to_string(), req.filter_codec.clone()));
            }
            if req.filter_capture_year > 0 {
                metadata_filters.push(("year".to_string(), req.filter_capture_year.to_string()));
            }

            let spec = FilterSpec {
                location_filter,
                tag_ids,
                geo: geo_filter,
                min_rating: req.filter_min_rating,
                color_label: req.filter_color_label.clone(),
                collection_id: if req.collection_id.is_empty() {
                    None
                } else {
                    Some(req.collection_id.clone())
                },
                search_query: req.search_query.clone(),
                metadata_filters,
                has_location: attribute_filter_opt(req.filter_has_location()),
                has_keywords: attribute_filter_opt(req.filter_has_keywords()),
                has_proxies: attribute_filter_opt(req.filter_has_proxies()),
                full_resolution: full_resolution_filter(&svc.db, req.filter_full_resolution()),
            };

            // Use grouped listing — returns one representative per group + ungrouped videos
            let (videos, total_count) = svc
                .db
                .list_videos_grouped(limit, offset, &req.sort_by, req.sort_ascending, &spec)
                .map_err(Status::from)?;

            let seeds: Vec<SummarySeed> = videos
                .iter()
                .map(|v| SummarySeed {
                    id: v.id.clone(),
                    filename: v.filename.clone(),
                    path: v.path.clone(),
                    size_bytes: v.file_size_bytes.unwrap_or(0),
                    indexed_at: v.indexed_at,
                })
                .collect();
            let video_summaries = svc.build_video_summaries(seeds);

            Ok(Response::new(ListVideosResponse {
                videos: video_summaries,
                total_count,
                has_more: (offset + limit) < total_count,
            }))
        })
        .await
    }

    async fn search_videos(
        &self,
        request: Request<SearchRequest>,
    ) -> std::result::Result<Response<SearchResponse>, Status> {
        let req = request.into_inner();
        self.run_blocking(move |svc| {
            let limit = if req.limit <= 0 { 50 } else { req.limit as i64 };
            let offset = req.offset.max(0) as i64;

            let (results, total_count) = SearchEngine::search(
                svc.db.as_ref(),
                &req.query,
                limit,
                offset,
                &req.filter_tags,
            )
            .map_err(Status::from)?;

            let seeds: Vec<SummarySeed> = results
                .iter()
                .map(|r| SummarySeed {
                    id: r.video_id.clone(),
                    filename: r.filename.clone(),
                    path: r.path.clone(),
                    size_bytes: 0,
                    indexed_at: 0,
                })
                .collect();
            let video_summaries = svc.build_video_summaries(seeds);

            Ok(Response::new(SearchResponse {
                videos: video_summaries,
                total_count,
            }))
        })
        .await
    }

    async fn get_metadata(
        &self,
        request: Request<GetMetadataRequest>,
    ) -> std::result::Result<Response<VideoMetadata>, Status> {
        let req = request.into_inner();
        self.run_blocking(move |svc| {
            let metadata = svc
                .get_video_metadata_sync(&req.video_id)
                .map_err(Status::from)?;
            Ok(Response::new(metadata))
        })
        .await
    }

    async fn get_thumbnail(
        &self,
        request: Request<GetThumbnailRequest>,
    ) -> std::result::Result<Response<Self::GetThumbnailStream>, Status> {
        let req = request.into_inner();

        // Audio loudness-over-time series for the detail view's volume graph,
        // delivered through this (already polymorphic) thumbnail RPC under a
        // special size token — the same idea as the "scrub_N" frames — so it
        // needs no dedicated RPC/proto. The payload is the loudness blob (see
        // audio_loudness_blob): little-endian f32 samples, each in [0, 1].
        if req.size == "audio_loudness" {
            let data = self.audio_loudness_blob(&req.video_id).await?;
            let (tx, rx) = tokio::sync::mpsc::channel(1);
            tokio::spawn(async move {
                let _ = tx.send(Ok(ThumbnailChunk { data })).await;
            });
            return Ok(Response::new(
                Box::pin(ReceiverStream::new(rx)) as Self::GetThumbnailStream,
            ));
        }

        // Higher-resolution request (detail view): serve a width-specific
        // variant, generating just that one frame on demand. Bypasses the
        // default-size path below entirely.
        let mut thumbnail_data = if req.max_width > 0 {
            self.load_or_generate_hires(&req.video_id, &req.size, req.max_width).await?
        } else {
            read_cached_thumbnail(
                self.config.thumbnail_cache_path.clone(),
                req.video_id.clone(),
                req.size.clone(),
                0,
            )
            .await?
        };

        // On-demand regeneration of a missing STILL thumbnail (small/medium/
        // large). Mirrors the scrub path below: when the file was removed (e.g.
        // the cache was cleared to force a rebuild) but the catalog still
        // references the video, regenerate the standard sizes now and return
        // the requested one. Without this the card shows the film-icon
        // placeholder forever — GetThumbnail returns NOT_FOUND, which tells the
        // client to stop asking — even though only scrub frames self-healed.
        if req.max_width == 0 && thumbnail_data.is_none() && !req.size.starts_with("scrub_") {
            let lock = self.scrub_lock_for(&req.video_id).await;
            let _gen_guard = lock.lock().await;

            // Double-check: another waiter may have generated it meanwhile.
            thumbnail_data = read_cached_thumbnail(
                self.config.thumbnail_cache_path.clone(),
                req.video_id.clone(),
                req.size.clone(),
                0,
            )
            .await?;

            if thumbnail_data.is_none() {
                if let Ok(Some(_video)) = self.db.get_video(&req.video_id) {
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
                        let video_id = req.video_id.clone();
                        let db = Arc::clone(&self.db);
                        let _ = tokio::task::spawn_blocking(move || {
                            let source = match db.media_source_for(&video_id) {
                                Ok(s) => s,
                                Err(_) => return,
                            };
                            let _ = ThumbnailGenerator::generate_default_sizes(
                                &source,
                                &video_id,
                                &cache,
                                duration_secs,
                            );
                        })
                        .await;

                        thumbnail_data = read_cached_thumbnail(
                            self.config.thumbnail_cache_path.clone(),
                            req.video_id.clone(),
                            req.size.clone(),
                            0,
                        )
                        .await?;
                    }
                }
            }

            drop(_gen_guard);
            if Arc::strong_count(&lock) == 2 {
                let mut map = self.scrub_locks.lock().await;
                if let Some(existing) = map.get(&req.video_id) {
                    if Arc::strong_count(existing) <= 2 {
                        map.remove(&req.video_id);
                    }
                }
            }
        }

        // On-demand scrub frame generation. If a "scrub_N" frame is requested
        // but doesn't exist yet (e.g. for libraries scanned before this feature
        // existed), generate it now and return it. The work is deduplicated via
        // a per-video Mutex so 10 simultaneous "scrub_0..9" requests for the
        // same video share a single generation pass instead of starting 10.
        // Concurrent ffmpeg invocations across all generation tasks are
        // additionally bounded by the global ffmpeg semaphore.
        if req.max_width == 0 && thumbnail_data.is_none() && req.size.starts_with("scrub_") {
            let lock = self.scrub_lock_for(&req.video_id).await;
            let _gen_guard = lock.lock().await;

            // Double-check: another waiter may have finished generation while
            // we were queued on the mutex.
            thumbnail_data = read_cached_thumbnail(
                self.config.thumbnail_cache_path.clone(),
                req.video_id.clone(),
                req.size.clone(),
                0,
            )
            .await?;

            if thumbnail_data.is_none() {
                if let Ok(Some(_video)) = self.db.get_video(&req.video_id) {
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
                        let video_id = req.video_id.clone();
                        let db = Arc::clone(&self.db);
                        // Generate ALL scrub frames in one shot (next requests
                        // for sibling indices hit the cache).
                        let _ = tokio::task::spawn_blocking(move || {
                            let source = match db.media_source_for(&video_id) {
                                Ok(s) => s,
                                Err(_) => return,
                            };
                            let _ = ThumbnailGenerator::generate_scrub_thumbnails(
                                &source,
                                &video_id,
                                &cache,
                                duration_secs,
                            );
                        })
                        .await;

                        thumbnail_data = read_cached_thumbnail(
                            self.config.thumbnail_cache_path.clone(),
                            req.video_id.clone(),
                            req.size.clone(),
                            0,
                        )
                        .await?;
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

        // A definitive miss is NOT_FOUND, not an empty stream. Clients can
        // stop asking for a thumbnail that will never appear, while transient
        // failures (fd exhaustion, a slow disk) surface from the cache reads
        // above as errors worth a quick retry. The old empty-stream answer
        // made the two indistinguishable.
        let data = thumbnail_data.ok_or_else(|| {
            Status::not_found(format!(
                "no '{}' thumbnail cached for video {}",
                req.size, req.video_id
            ))
        })?;

        let (tx, rx) = tokio::sync::mpsc::channel(1);

        tokio::spawn(async move {
            let _ = tx.send(Ok(ThumbnailChunk { data })).await;
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
                // Only recursive locations can show a disclosure chevron;
                // gate here so non-recursive ones never advertise children.
                has_subdirectories: l.recursive
                    && self.db.location_has_subdirectories(&l.path).unwrap_or(false),
            })
            .collect();

        Ok(Response::new(ListLocationsResponse {
            locations: location_responses,
        }))
    }

    async fn list_subdirectories(
        &self,
        request: Request<ListSubdirectoriesRequest>,
    ) -> std::result::Result<Response<ListSubdirectoriesResponse>, Status> {
        let req = request.into_inner();
        // Expand tilde like the location filter does (see `list_videos`), so a
        // client may pass either an absolute path or a "~/..." one.
        let path = expand_tilde(&req.path);

        self.run_blocking(move |svc| {
            let subdirectories = svc
                .db
                .list_subdirectories(&path)
                .map_err(Status::from)?
                .into_iter()
                .map(|s| Subdirectory {
                    path: s.path,
                    video_count: s.video_count,
                    has_subdirectories: s.has_subdirectories,
                })
                .collect();

            Ok(Response::new(ListSubdirectoriesResponse { subdirectories }))
        })
        .await
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
        // Copy the persistent config flag out of `self.config` before the
        // 'static-bound spawn — the scan_one closure can't borrow self.
        let auto_tag_timelapses = self.config.auto_tag_timelapses;
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
                    crate::indexing::ScanConfig {
                        path: scan_path,
                        recursive,
                        thumbnail_cache: &cache_path,
                        filename_date: filename_date_rule,
                        post_index_options: crate::post_index::Options {
                            // Mirror the existing gate: auto-grouping
                            // is opt-in per scan request; proxy
                            // detection runs unconditionally; sensor
                            // fetch piggybacks on the scan to fill the
                            // runtime cache for unknown camera models;
                            // timelapse auto-tag follows the persistent
                            // config flag (off by default).
                            auto_group,
                            detect_proxies: true,
                            sensor_fetch: true,
                            auto_tag_timelapses,
                        },
                        // Publish post-index progress on the catalog-events bus
                        // so a long proxy-detection drain isn't invisible.
                        events: Some(scan_events.clone()),
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
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();

        self.db.delete_tag(&req.tag_id).map_err(Status::from)?;

        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Tag deleted".to_string(),
            error: String::new(),
            error_code: 0,
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
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db.tag_video(video_id, &req.tag_id).map_err(Status::from)?;
        }

        Ok(Response::new(reelvault::Response {
            success: true,
            message: format!("Tagged {} videos", req.video_ids.len()),
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn untag_videos(
        &self,
        request: Request<UntagVideosRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db.untag_video(video_id, &req.tag_id).map_err(Status::from)?;
        }

        Ok(Response::new(reelvault::Response {
            success: true,
            message: format!("Untagged {} videos", req.video_ids.len()),
            error: String::new(),
            error_code: 0,
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
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();
        self.db.delete_collection(&req.collection_id).map_err(Status::from)?;

        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Collection deleted".to_string(),
            error: String::new(),
            error_code: 0,
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
                // Manual collections report their real member count (was always
                // 0). Smart collections have no members; their count is derived
                // from the saved filter by the client.
                video_count: c.video_count,
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
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db.add_to_collection(&req.collection_id, video_id).map_err(Status::from)?;
        }

        Ok(Response::new(reelvault::Response {
            success: true,
            message: format!("Added {} videos to collection", req.video_ids.len()),
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn remove_from_collection(
        &self,
        request: Request<RemoveFromCollectionRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();

        for video_id in &req.video_ids {
            self.db.remove_from_collection(&req.collection_id, video_id).map_err(Status::from)?;
        }

        Ok(Response::new(reelvault::Response {
            success: true,
            message: format!("Removed {} videos from collection", req.video_ids.len()),
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn update_video_notes(
        &self,
        request: Request<UpdateNotesRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();

        self.db.update_notes(&req.video_id, &req.notes).map_err(Status::from)?;

        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Notes updated".to_string(),
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn update_video_rating(
        &self,
        request: Request<reelvault::UpdateVideoRatingRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
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
        Ok(Response::new(reelvault::Response {
            success: true,
            message: format!("Rating set to {} on {} video(s)", req.rating, count),
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn update_video_color_label(
        &self,
        request: Request<reelvault::UpdateVideoColorLabelRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
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
        Ok(Response::new(reelvault::Response {
            success: true,
            message: format!("Color label '{}' applied to {} video(s)", displayed_label, count),
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn get_grid_settings(
        &self,
        _request: Request<reelvault::GetGridSettingsRequest>,
    ) -> std::result::Result<Response<reelvault::GridSettings>, Status> {
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
        Ok(Response::new(reelvault::GridSettings { top_slots: slots }))
    }

    async fn update_grid_settings(
        &self,
        request: Request<reelvault::GridSettings>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
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
        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Grid settings saved".to_string(),
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn delete_video(
        &self,
        request: Request<DeleteVideoRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();

        if req.delete_file {
            if let Ok(Some(video)) = self.db.get_video(&req.video_id) {
                let _ = std::fs::remove_file(&video.path);
            }
        }

        self.db.delete_video(&req.video_id).map_err(Status::from)?;

        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Video deleted".to_string(),
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn list_group_members(
        &self,
        request: Request<ListGroupMembersRequest>,
    ) -> std::result::Result<Response<ListGroupMembersResponse>, Status> {
        let req = request.into_inner();
        let member_ids = self.db.list_group_member_ids(&req.group_id).map_err(Status::from)?;

        let mut seeds = Vec::with_capacity(member_ids.len());
        for vid in &member_ids {
            if let Ok(Some(video)) = self.db.get_video(vid) {
                seeds.push(SummarySeed {
                    id: video.id,
                    filename: video.filename,
                    path: video.path,
                    size_bytes: video.file_size_bytes.unwrap_or(0),
                    indexed_at: video.indexed_at,
                });
            }
        }
        let members = self.build_video_summaries(seeds);

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
        tracing::info!(
            video_ids = ?req.video_ids,
            preferred = %req.preferred_video_id,
            name = %req.name,
            "combine/CreateGroup: RPC received"
        );
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
        tracing::info!(group_id = %group_id, size, "combine/CreateGroup: RPC complete");
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
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();
        self.db.ungroup_video(&req.video_id).map_err(Status::from)?;
        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Video ungrouped".to_string(),
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn set_group_preferred(
        &self,
        request: Request<SetGroupPreferredRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();
        self.db
            .set_group_preferred(&req.group_id, &req.video_id)
            .map_err(Status::from)?;
        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Preferred video set".to_string(),
            error: String::new(),
            error_code: 0,
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

        // Resolve the backend source the same way the thumbnail paths do, so
        // proxy generation works for Photos-backed rows (whose `path` is a
        // synthetic `photos://…`, not a real file) — not just filesystem rows.
        let source = self.db.media_source_for(&req.video_id).map_err(Status::from)?;
        let output_path = if !req.output_path.is_empty() {
            std::path::PathBuf::from(req.output_path)
        } else if let crate::media_backend::MediaSource::Path(p) = &source {
            crate::proxies::proxy_path_beside(p, target_height)
        } else {
            // Non-filesystem source (Photos/bookmark): there's no source dir to
            // write beside, so put the proxy in the thumbnail cache, keyed by id.
            self.config
                .thumbnail_cache_path
                .join(format!("{}_proxy_{}p.mp4", video.id, target_height))
        };
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
                playable_natively: self.config.max_native_playback_height == 0
                    || r.height <= self.config.max_native_playback_height,
            })
            .collect();
        Ok(Response::new(ListProxiesResponse { proxies }))
    }

    async fn set_proxy_of(
        &self,
        request: Request<SetProxyOfRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();
        if req.proxy_id.is_empty() {
            return Err(Status::invalid_argument("proxy_id is required"));
        }
        if req.original_id.is_empty() {
            self.db.clear_proxy_of(&req.proxy_id).map_err(Status::from)?;
            return Ok(Response::new(reelvault::Response {
                success: true,
                message: "Proxy link cleared".into(),
                error: String::new(),
                error_code: 0,
            }));
        }
        // Manual mark — confidence = 1.0, auto_detected = false.
        self.db
            .set_proxy_of(&req.proxy_id, &req.original_id, 1.0, false)
            .map_err(Status::from)?;
        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Proxy link saved".into(),
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn remove_proxy_link(
        &self,
        request: Request<RemoveProxyLinkRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();
        if req.master_id.is_empty() || req.proxy_id.is_empty() {
            return Err(Status::invalid_argument(
                "master_id and proxy_id are both required",
            ));
        }
        self.db
            .remove_proxy_link(&req.master_id, &req.proxy_id)
            .map_err(Status::from)?;
        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Proxy link removed".into(),
            error: String::new(),
            error_code: 0,
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

    async fn attach_proxies(
        &self,
        request: Request<AttachProxiesRequest>,
    ) -> std::result::Result<Response<AttachProxiesResponse>, Status> {
        let req = request.into_inner();
        tracing::info!(video_ids = ?req.video_ids, "attach/AttachProxies: RPC received");
        if req.video_ids.len() < 2 {
            return Err(Status::invalid_argument(
                "Select at least 2 videos to attach proxies",
            ));
        }
        let (master_id, attached) = self
            .db
            .attach_proxies(&req.video_ids)
            .map_err(Status::from)?;
        tracing::info!(master = %master_id, attached, "attach/AttachProxies: RPC complete");
        Ok(Response::new(AttachProxiesResponse {
            master_video_id: master_id,
            proxies_attached: attached as i32,
            message: format!(
                "Attached {} {} to the highest-resolution selection",
                attached,
                if attached == 1 { "proxy" } else { "proxies" },
            ),
        }))
    }

    async fn get_filter_options(
        &self,
        _request: Request<GetFilterOptionsRequest>,
    ) -> std::result::Result<Response<FilterOptions>, Status> {
        self.run_blocking(move |svc| {
            let cameras = svc.db.list_distinct_cameras().unwrap_or_default();
            let lenses = svc.db.list_distinct_lenses().unwrap_or_default();
            let codecs = svc.db.list_distinct_codecs().unwrap_or_default();
            let years = svc.db.list_distinct_capture_years().unwrap_or_default();
            // Parallel list of marketing-friendly camera names — same length
            // and order as `cameras`. Falls back to the internal name when
            // no mapping (built-in or custom) exists so the two lists stay
            // in lockstep.
            let custom_overrides = svc.load_custom_camera_names();
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
        })
        .await
    }

    async fn get_metadata_facets(
        &self,
        request: Request<MetadataFacetsRequest>,
    ) -> std::result::Result<Response<MetadataFacetsResponse>, Status> {
        let req = request.into_inner();

        // The facet cascade runs one query per visible column plus the
        // available-keys scan — page-scale work, so off the async runtime.
        self.run_blocking(move |svc| {
            let location_filter = if req.location_path.is_empty() {
                String::new()
            } else {
                expand_tilde(&req.location_path)
            };
            // Resolve tags by name or id, same as list_videos.
            let tag_ids: Vec<String> = req
                .filter_tags
                .iter()
                .filter_map(|s| {
                    if s.is_empty() {
                        return None;
                    }
                    if let Ok(Some(tag)) = svc.db.get_tag_by_name(s) {
                        Some(tag.id)
                    } else {
                        Some(s.clone())
                    }
                })
                .collect();
            let geo = if req.filter_by_location {
                Some((
                    req.filter_latitude,
                    req.filter_longitude,
                    req.filter_radius_km.max(0.01),
                ))
            } else {
                None
            };
            let collection_id = if req.collection_id.is_empty() {
                None
            } else {
                Some(req.collection_id.clone())
            };

            // The attribute presence filters scope the facet set just like the
            // grid. Resolved once (the full-resolution tuple scan is not free) and
            // shared across every per-column spec below.
            let has_location = attribute_filter_opt(req.filter_has_location());
            let has_keywords = attribute_filter_opt(req.filter_has_keywords());
            let has_proxies = attribute_filter_opt(req.filter_has_proxies());
            let full_resolution = full_resolution_filter(&svc.db, req.filter_full_resolution());

            // Base FilterSpec for the upstream filters; `extra` carries the
            // metadata columns to the left of whichever column we're computing.
            let base_spec = |extra: Vec<(String, String)>| FilterSpec {
                location_filter: location_filter.clone(),
                tag_ids: tag_ids.clone(),
                geo,
                min_rating: req.filter_min_rating,
                color_label: req.filter_color_label.clone(),
                collection_id: collection_id.clone(),
                search_query: req.search_query.clone(),
                metadata_filters: extra,
                has_location,
                has_keywords,
                has_proxies,
                full_resolution: full_resolution.clone(),
            };

            // Available keys = registry keys with data under the upstream filters
            // (no metadata columns applied), for each column's "change key" picker.
            let upstream = base_spec(Vec::new());
            let available_keys: Vec<MetadataKeyInfo> = svc
                .db
                .metadata_keys_with_data(&upstream)
                .unwrap_or_default()
                .iter()
                .filter_map(|tok| metadata_keys::lookup(tok))
                .map(|mk| MetadataKeyInfo {
                    key: mk.token.to_string(),
                    display_name: mk.display_name.to_string(),
                    is_numeric: mk.is_numeric,
                })
                .collect();

            let custom_overrides = svc.load_custom_camera_names();
            let lens_overrides = svc.load_custom_lens_names();
            let mut columns_out = Vec::with_capacity(req.columns.len());
            for (i, col) in req.columns.iter().enumerate() {
                // Placeholder column (no key chosen yet) — keep its position so the
                // response stays index-aligned, but return no values.
                if col.key.is_empty() {
                    columns_out.push(MetadataFacetColumn {
                        key: String::new(),
                        display_name: String::new(),
                        is_numeric: false,
                        values: Vec::new(),
                    });
                    continue;
                }

                // Cascade: column i is constrained by every column to its LEFT that
                // has a value selected.
                let left: Vec<(String, String)> = req.columns[..i]
                    .iter()
                    .filter(|c| !c.key.is_empty() && !c.value.is_empty())
                    .map(|c| (c.key.clone(), c.value.clone()))
                    .collect();
                let spec = base_spec(left);
                let counts = svc
                    .db
                    .distinct_facet_values(&col.key, &spec)
                    .unwrap_or_default();

                let mk = metadata_keys::lookup(&col.key);
                let is_numeric = mk.map(|m| m.is_numeric).unwrap_or(false);
                let display_name = mk
                    .map(|m| m.display_name.to_string())
                    .unwrap_or_else(|| col.key.clone());

                let values = counts
                    .into_iter()
                    .map(|fc| {
                        // Keyword carries its own display (tag name); camera resolves
                        // to a marketing name; lens resolves to a custom alias if the
                        // user set one; the rest format from the token.
                        let display = fc.display.unwrap_or_else(|| {
                            if col.key == "camera" {
                                crate::camera_names::marketing_name_for_with_custom(
                                    &fc.token,
                                    &custom_overrides,
                                )
                                .unwrap_or_else(|| fc.token.clone())
                            } else if col.key == "lens" {
                                lens_overrides
                                    .get(&crate::camera_names::normalise(&fc.token))
                                    .cloned()
                                    .unwrap_or_else(|| fc.token.clone())
                            } else {
                                mk.map(|m| m.format_value(&fc.token))
                                    .unwrap_or_else(|| fc.token.clone())
                            }
                        });
                        FacetValue {
                            token: fc.token,
                            display,
                            count: fc.count,
                        }
                    })
                    .collect();

                columns_out.push(MetadataFacetColumn {
                    key: col.key.clone(),
                    display_name,
                    is_numeric,
                    values,
                });
            }

            Ok(Response::new(MetadataFacetsResponse {
                columns: columns_out,
                available_keys,
            }))
        })
        .await
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

    async fn start_pairing(
        &self,
        _request: Request<StartPairingRequest>,
    ) -> std::result::Result<Response<StartPairingResponse>, Status> {
        // Mint a one-time code and surface it (log + pairing.txt). The same
        // pending cell backs the media server's POST /pair, so the new device
        // redeems this code there for a bearer token. Loopback callers only in
        // practice — the LAN gRPC bind requires a token a new device lacks.
        let (code, expires_at_ms) =
            crate::pairing::issue_code(&self.pairing, &self.data_dir).await;
        Ok(Response::new(StartPairingResponse {
            code,
            expires_at_ms,
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
            auto_tag_timelapses: self.config.auto_tag_timelapses,
        }))
    }

    async fn update_config(
        &self,
        request: Request<UpdateConfigRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
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
                ("auto_tag_timelapses", req.auto_tag_timelapses.to_string()),
            ];
            for (key, value) in pairs {
                let _ = conn.execute(
                    "INSERT INTO config (key, value) VALUES (?, ?)
                     ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = CURRENT_TIMESTAMP",
                    rusqlite::params![key, value, value],
                );
            }
        }
        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Config updated".to_string(),
            error: String::new(),
            error_code: 0,
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
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
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
        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Catalog closed".to_string(),
            error: String::new(),
            error_code: 0,
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
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
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
                match crate::media_backend::backend().write_location(
                    &crate::media_backend::MediaSource::Path(v.path.clone().into()),
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

        Ok(Response::new(reelvault::Response {
            success: true,
            message: format!(
                "Location set to {:.6}, {:.6}{}",
                req.latitude, req.longitude, file_write_message
            ),
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn list_videos_with_locations(
        &self,
        _request: Request<ListVideosWithLocationsRequest>,
    ) -> std::result::Result<Response<VideoLocationsResponse>, Status> {
        self.run_blocking(move |svc| {
            let rows = svc.db.list_videos_with_locations().map_err(Status::from)?;
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
        })
        .await
    }

    async fn update_video_capture_date(
        &self,
        request: Request<UpdateVideoCaptureDateRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
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
                match crate::media_backend::backend().write_creation_time(
                    &crate::media_backend::MediaSource::Path(v.path.clone().into()),
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

        Ok(Response::new(reelvault::Response {
            success: true,
            message: format!("Capture date set{}", file_write_message),
            error: String::new(),
            error_code: 0,
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
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();
        if req.id.is_empty() {
            return Err(Status::invalid_argument("id is required"));
        }
        self.db.delete_named_location(&req.id).map_err(Status::from)?;
        Ok(Response::new(reelvault::Response {
            success: true,
            message: "Named location deleted".into(),
            error: String::new(),
            error_code: 0,
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
            reelvault::catalog_event::Kind::WatcherStarted as i32
        } else {
            reelvault::catalog_event::Kind::WatcherDisabled as i32
        };
        let _ = tx
            .send(Ok(CatalogEvent {
                kind: greeting_kind,
                video_id: String::new(),
                path: String::new(),
                at_ms: now_ms(),
                message: String::new(),
                post_index: None,
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
                                kind: reelvault::catalog_event::Kind::ScanCompleted as i32,
                                video_id: String::new(),
                                path: String::new(),
                                at_ms: now_ms(),
                                message: format!(
                                    "watcher event stream lagged {} events — please refresh",
                                    n
                                ),
                                post_index: None,
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
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
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

        Ok(Response::new(reelvault::Response {
            success: true,
            message: format!(
                "Watch settings updated (enabled={}, settle={}ms, poll={}ms)",
                req.enabled, settle, poll
            ),
            error: String::new(),
            error_code: 0,
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
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();
        let internal_raw = req.internal.trim().to_string();
        let marketing = req.marketing.trim().to_string();

        if internal_raw.is_empty() {
            return Ok(Response::new(reelvault::Response {
                success: false,
                message: String::new(),
                error: "internal name must not be blank".to_string(),
                error_code: 13, // ERROR_INVALID_REQUEST
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
        Ok(Response::new(reelvault::Response {
            success: true,
            message,
            error: String::new(),
            error_code: 0,
        }))
    }

    async fn list_lens_name_mappings(
        &self,
        _request: Request<ListLensNameMappingsRequest>,
    ) -> std::result::Result<Response<ListLensNameMappingsResponse>, Status> {
        // Custom overrides keyed by normalised raw lens string. Keep the
        // user-typed raw + alias so the editor renders them verbatim.
        let mut custom_by_normalised: std::collections::HashMap<String, (String, String)> =
            std::collections::HashMap::new();
        for c in self.read_custom_lens_names() {
            let key = crate::camera_names::normalise(&c.raw);
            let alias = c.alias.trim();
            if key.is_empty() || alias.is_empty() {
                continue;
            }
            custom_by_normalised.insert(key, (c.raw.clone(), alias.to_string()));
        }

        // One row per distinct lens currently in the catalog, alphabetised.
        // A matching override (popped out) supplies the alias + custom flag.
        let mut catalog_lenses = self.db.list_distinct_lenses().unwrap_or_default();
        catalog_lenses.sort_by_key(|s| s.to_ascii_uppercase());
        let mut mappings = Vec::new();
        for raw in catalog_lenses {
            let key = crate::camera_names::normalise(&raw);
            let overridden = custom_by_normalised.remove(&key);
            let (alias, is_custom) = match overridden {
                Some((_, alias)) => (alias, true),
                None => (raw.clone(), false),
            };
            mappings.push(LensNameMapping {
                raw,
                alias,
                is_custom,
                in_catalog: true,
            });
        }

        // Custom-only overrides whose lens no longer appears in the catalog,
        // alphabetised after the live entries so the table is stable.
        let mut custom_only: Vec<(String, String)> = custom_by_normalised.into_values().collect();
        custom_only.sort_by_key(|a| a.0.to_ascii_uppercase());
        for (raw, alias) in custom_only {
            mappings.push(LensNameMapping {
                raw,
                alias,
                is_custom: true,
                in_catalog: false,
            });
        }

        Ok(Response::new(ListLensNameMappingsResponse { mappings }))
    }

    async fn set_lens_name_mapping(
        &self,
        request: Request<SetLensNameMappingRequest>,
    ) -> std::result::Result<Response<reelvault::Response>, Status> {
        let req = request.into_inner();
        let raw = req.raw.trim().to_string();
        let alias = req.alias.trim().to_string();

        if raw.is_empty() {
            return Ok(Response::new(reelvault::Response {
                success: false,
                message: String::new(),
                error: "lens name must not be blank".to_string(),
                error_code: 13, // ERROR_INVALID_REQUEST
            }));
        }

        let normalised_key = crate::camera_names::normalise(&raw);
        let mut entries = self.read_custom_lens_names();
        // Drop any existing override for the same normalised lens, then
        // (re-)add it. An empty alias just removes the override.
        entries.retain(|e| crate::camera_names::normalise(&e.raw) != normalised_key);
        if !alias.is_empty() {
            entries.push(crate::config::CustomLensName {
                raw: raw.clone(),
                alias: alias.clone(),
            });
        }

        self.write_custom_lens_names(&entries)
            .map_err(|e| Status::internal(format!("Failed to save lens mappings: {e}")))?;

        let message = if alias.is_empty() {
            format!("Removed lens alias for \"{raw}\"")
        } else {
            format!("Saved lens alias: \"{raw}\" → \"{alias}\"")
        };
        Ok(Response::new(reelvault::Response {
            success: true,
            message,
            error: String::new(),
            error_code: 0,
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
fn change_to_event(change: &CatalogChange) -> reelvault::CatalogEvent {
    use reelvault::catalog_event::Kind;
    let at_ms = now_ms();
    match change {
        CatalogChange::VideoAdded { video_id, path } => reelvault::CatalogEvent {
            kind: Kind::VideoAdded as i32,
            video_id: video_id.clone(),
            path: path.to_string_lossy().into_owned(),
            at_ms,
            message: String::new(),
            post_index: None,
        },
        CatalogChange::VideoModified { video_id, path } => reelvault::CatalogEvent {
            kind: Kind::VideoModified as i32,
            video_id: video_id.clone(),
            path: path.to_string_lossy().into_owned(),
            at_ms,
            message: String::new(),
            post_index: None,
        },
        CatalogChange::VideoRemoved { video_id, path } => reelvault::CatalogEvent {
            kind: Kind::VideoRemoved as i32,
            video_id: video_id.clone().unwrap_or_default(),
            path: path.to_string_lossy().into_owned(),
            at_ms,
            message: path
                .file_name()
                .and_then(|n| n.to_str())
                .map(str::to_string)
                .unwrap_or_default(),
            post_index: None,
        },
        CatalogChange::ScanStarted { path } => reelvault::CatalogEvent {
            kind: Kind::ScanStarted as i32,
            video_id: String::new(),
            path: path.clone(),
            at_ms,
            message: String::new(),
            post_index: None,
        },
        CatalogChange::ScanCompleted { path } => reelvault::CatalogEvent {
            kind: Kind::ScanCompleted as i32,
            video_id: String::new(),
            path: path.clone(),
            at_ms,
            message: String::new(),
            post_index: None,
        },
        CatalogChange::PostIndexStarted { total } => reelvault::CatalogEvent {
            kind: Kind::PostIndexStarted as i32,
            video_id: String::new(),
            path: String::new(),
            at_ms,
            message: String::new(),
            post_index: Some(reelvault::PostIndexProgress {
                processed: 0,
                total: *total as i64,
                percent: 0.0,
                eta_seconds: 0,
                phase: String::new(),
                detail: String::new(),
            }),
        },
        CatalogChange::PostIndexProgress {
            processed,
            total,
            percent,
            eta_secs,
            phase,
            detail,
        } => reelvault::CatalogEvent {
            kind: Kind::PostIndexProgress as i32,
            video_id: String::new(),
            path: String::new(),
            at_ms,
            message: String::new(),
            post_index: Some(reelvault::PostIndexProgress {
                processed: *processed as i64,
                total: *total as i64,
                percent: *percent,
                eta_seconds: *eta_secs as i64,
                phase: phase.clone(),
                detail: detail.clone(),
            }),
        },
        CatalogChange::PostIndexCompleted { processed } => reelvault::CatalogEvent {
            kind: Kind::PostIndexCompleted as i32,
            video_id: String::new(),
            path: String::new(),
            at_ms,
            message: String::new(),
            post_index: Some(reelvault::PostIndexProgress {
                processed: *processed as i64,
                total: 0,
                percent: 100.0,
                eta_seconds: 0,
                phase: String::new(),
                detail: String::new(),
            }),
        },
        CatalogChange::PairingRequested { device_name } => reelvault::CatalogEvent {
            kind: Kind::PairingRequested as i32,
            video_id: String::new(),
            path: String::new(),
            at_ms,
            // The device name rides in `message` (no new proto field needed).
            message: device_name.clone(),
            post_index: None,
        },
    }
}
