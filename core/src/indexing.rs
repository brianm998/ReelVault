// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

use crate::db::Database;
use crate::error::{Result, VideoRoomError};
use crate::metadata::MetadataExtractor;
use crate::thumbnails::ThumbnailGenerator;
use rayon::prelude::*;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicI64, Ordering};
use walkdir::WalkDir;

const SUPPORTED_EXTENSIONS: &[&str] = &[
    "mp4", "mov", "mkv", "avi", "webm", "mxf", "mpeg", "ts",
    "m2ts", "mts", "flv", "wmv", "asf", "rm", "rmvb", "3gp", "3g2",
];

pub struct IndexingEngine;

impl IndexingEngine {
    pub fn scan_directory(
        db: &Database,
        path: &Path,
        recursive: bool,
        thumbnail_cache: &Path,
        on_progress: impl Fn(&ScanProgress) + Sync,
    ) -> Result<()> {
        tracing::info!("Starting scan of: {}", path.display());

        on_progress(&ScanProgress {
            status: "scanning".to_string(),
            videos_found: 0,
            videos_indexed: 0,
            current_file: String::new(),
            progress_percent: 0.0,
        });

        // First pass: find all videos
        let mut video_paths = Vec::new();

        for entry in WalkDir::new(path)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| recursive || e.depth() <= 1)
        {
            let file_path = entry.path();
            if file_path.is_file() {
                if let Some(ext) = file_path.extension() {
                    if let Some(ext_str) = ext.to_str() {
                        if SUPPORTED_EXTENSIONS.contains(&ext_str.to_lowercase().as_str()) {
                            video_paths.push(file_path.to_path_buf());
                        }
                    }
                }
            }
        }

        let videos_found = video_paths.len() as i64;
        tracing::info!("Found {} videos to index", videos_found);

        // Second pass: extract metadata + generate thumbnails in parallel.
        //
        // SAN-backed scans are usually IO-bound, so a single in-flight
        // FFprobe leaves the link mostly idle waiting on file-open and
        // moov-atom seeks. Letting rayon dispatch many `index_video`
        // calls at once keeps several IO requests outstanding,
        // dramatically improving throughput on network storage. The
        // FFmpeg semaphore in `concurrency.rs` still bounds external
        // process count, so a 64-core box doesn't fork 64 ffprobes at
        // once on a local SSD where that would just thrash.
        //
        // Database access from worker threads is safe because every
        // call goes through `db.get_connection()` (which opens a new
        // SQLite handle each time) and SQLite in WAL mode supports
        // concurrent readers + single writer with row-level
        // serialization.
        let videos_indexed = AtomicI64::new(0);
        let videos_started = AtomicI64::new(0);

        video_paths.par_iter().for_each(|video_path| {
            let filename = video_path
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("unknown")
                .to_string();

            // Emit "starting this file" progress before we touch the
            // file. We use a separate `videos_started` counter for the
            // percent so the bar climbs as work is dispatched rather
            // than waiting for each ffprobe to return — closer to user
            // intuition on a parallel scan.
            let started_idx = videos_started.fetch_add(1, Ordering::Relaxed);
            on_progress(&ScanProgress {
                status: "indexing_metadata".to_string(),
                videos_found,
                videos_indexed: videos_indexed.load(Ordering::Relaxed),
                current_file: filename.clone(),
                progress_percent: (started_idx as f64 / videos_found.max(1) as f64) * 100.0,
            });

            match Self::index_video(db, video_path, thumbnail_cache) {
                Ok(_) => {
                    let done = videos_indexed.fetch_add(1, Ordering::Relaxed) + 1;
                    tracing::debug!("Indexed: {}", filename);
                    // Also emit a "finished this file" tick so the
                    // client sees the indexed count climb steadily.
                    on_progress(&ScanProgress {
                        status: "indexing_metadata".to_string(),
                        videos_found,
                        videos_indexed: done,
                        current_file: filename.clone(),
                        progress_percent: (done as f64 / videos_found.max(1) as f64) * 100.0,
                    });
                }
                Err(e) => {
                    tracing::warn!("Failed to index {}: {}", filename, e);
                }
            }
        });

        let videos_indexed_final = videos_indexed.load(Ordering::Relaxed);

        on_progress(&ScanProgress {
            status: "complete".to_string(),
            videos_found,
            videos_indexed: videos_indexed_final,
            current_file: String::new(),
            progress_percent: 100.0,
        });

        tracing::info!(
            "Scan complete: {} videos found, {} indexed",
            videos_found,
            videos_indexed_final
        );

        Ok(())
    }

    fn index_video(
        db: &Database,
        video_path: &Path,
        thumbnail_cache: &Path,
    ) -> Result<String> {
        // Extract filename
        let filename = video_path
            .file_name()
            .and_then(|n| n.to_str())
            .ok_or_else(|| VideoRoomError::InvalidPath("Invalid filename".to_string()))?;

        // Get file size
        let file_size = std::fs::metadata(video_path)
            .map(|m| m.len() as i64)
            .ok();

        // Extract metadata
        let probe_output = MetadataExtractor::extract(video_path)?;

        // Check if already indexed — if so, reuse the existing ID and just refresh metadata.
        let video_id = if let Ok(Some(existing)) = db.get_video_by_path(video_path.to_str().unwrap_or("")) {
            existing.id
        } else {
            // Create video record
            db.add_video(
                video_path.to_str().unwrap_or(""),
                filename,
                None,
                None,
                file_size,
            )?
        };

        // (Re-)store metadata (UPSERT)
        MetadataExtractor::store_metadata(db, &video_id, &probe_output, file_size.unwrap_or(0))?;

        // Get duration from probe output for thumbnail generation
        let duration_secs = probe_output.format.duration.unwrap_or(0.0);

        // Generate thumbnail (skip if already exists, ffmpeg overwrite would be redundant)
        let thumb_path = thumbnail_cache.join(format!("{}_medium.jpg", video_id));
        if !thumb_path.exists() {
            match ThumbnailGenerator::generate(db, video_path, &video_id, thumbnail_cache, duration_secs) {
                Ok(_) => {
                    tracing::debug!("Generated thumbnail for {}", video_id);
                }
                Err(e) => {
                    tracing::warn!("Failed to generate thumbnail for {}: {}", video_id, e);
                }
            }
        }

        // Generate scrub-frame previews (idempotent — skips already-existing).
        // Done after the regular thumbnail so users see *something* in the grid
        // even if scrub generation is mid-flight.
        let first_scrub = thumbnail_cache.join(format!("{}_scrub_0.jpg", video_id));
        if !first_scrub.exists() {
            match ThumbnailGenerator::generate_scrub_thumbnails(
                video_path,
                &video_id,
                thumbnail_cache,
                duration_secs,
            ) {
                Ok(_) => tracing::debug!("Generated scrub frames for {}", video_id),
                Err(e) => tracing::warn!("Failed to generate scrub frames for {}: {}", video_id, e),
            }
        }

        Ok(video_id)
    }

    pub fn update_online_status(db: &Database, path: &Path, is_online: bool) -> Result<()> {
        if let Ok(Some(video)) = db.get_video_by_path(path.to_str().unwrap_or("")) {
            let conn = db.get_connection()?;
            conn.execute(
                "UPDATE videos SET is_online = ? WHERE id = ?",
                rusqlite::params![is_online as i32, video.id],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
        }
        Ok(())
    }
}

#[derive(Debug, Clone)]
pub struct ScanProgress {
    pub status: String,
    pub videos_found: i64,
    pub videos_indexed: i64,
    pub current_file: String,
    pub progress_percent: f64,
}
