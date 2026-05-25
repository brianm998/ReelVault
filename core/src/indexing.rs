// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

use crate::db::Database;
use crate::error::{Result, VideoRoomError};
use crate::metadata::MetadataExtractor;
use crate::post_index;
use crate::thumbnails::ThumbnailGenerator;
use rayon::prelude::*;
use std::path::Path;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;
use walkdir::WalkDir;

pub const SUPPORTED_EXTENSIONS: &[&str] = &[
    "mp4", "mov", "mkv", "avi", "webm", "mxf", "mpeg", "ts",
    "m2ts", "mts", "flv", "wmv", "asf", "rm", "rmvb", "3gp", "3g2",
];

/// Whether a single-file scan resulted in a brand-new row or a refresh of
/// an existing one. Used by the file watcher to emit the right
/// `CatalogEvent` variant. Comparison is by-path: if a videos row already
/// matched the path on disk, we treat it as a modification regardless of
/// whether ffprobe extracted different bytes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScanFileOutcome {
    /// The file wasn't in the catalog. A new row was inserted.
    Added,
    /// A row existed for this path; metadata was re-extracted in place.
    Modified,
}

/// Lowercased file extension check, used by the watcher to filter notify
/// events before queueing them.
pub fn is_supported_video_extension(path: &Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .map(|s| SUPPORTED_EXTENSIONS.contains(&s.to_lowercase().as_str()))
        .unwrap_or(false)
}

/// How a date should be located inside a filename. Used as a hint when
/// inferring capture_date from the filename for clips that lack EXIF dates.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FilenameDatePosition {
    Anywhere,
    Beginning,
    End,
}

/// Component ordering of the date as it appears in the filename. Separators
/// are not part of the format — they're inferred as any non-digit character.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FilenameDateFormat {
    MonthDayYear,
    DayMonthYear,
    YearMonthDay,
}

/// Optional rule for parsing a capture date out of a video's filename when
/// ffprobe doesn't supply one.
#[derive(Debug, Clone, Copy)]
pub struct FilenameDateRule {
    pub format: FilenameDateFormat,
    pub position: FilenameDatePosition,
}

impl FilenameDateRule {
    /// Parse from the strings sent over gRPC. Returns `None` if either field
    /// is empty/unrecognized — feature disabled.
    pub fn from_proto(format: &str, position: &str) -> Option<Self> {
        let fmt = match format {
            "MM-DD-YYYY" => FilenameDateFormat::MonthDayYear,
            "DD-MM-YYYY" => FilenameDateFormat::DayMonthYear,
            "YYYY-MM-DD" => FilenameDateFormat::YearMonthDay,
            _ => return None,
        };
        let pos = match position {
            "" | "anywhere" => FilenameDatePosition::Anywhere,
            "beginning" => FilenameDatePosition::Beginning,
            "end" => FilenameDatePosition::End,
            _ => FilenameDatePosition::Anywhere,
        };
        Some(Self { format: fmt, position: pos })
    }

    /// Attempt to extract a date from a filename (without extension) and
    /// convert it to a Unix timestamp in milliseconds at midnight UTC.
    pub fn parse_filename(&self, filename_stem: &str) -> Option<i64> {
        let (a, b, c) = self.find_components(filename_stem)?;
        let (year, month, day) = match self.format {
            FilenameDateFormat::MonthDayYear => (c, a, b),
            FilenameDateFormat::DayMonthYear => (c, b, a),
            FilenameDateFormat::YearMonthDay => (a, b, c),
        };
        if !(1..=12).contains(&month) || !(1..=31).contains(&day) || !(1900..=2999).contains(&year) {
            return None;
        }
        let date = chrono::NaiveDate::from_ymd_opt(year, month as u32, day as u32)?;
        let dt = date.and_hms_opt(0, 0, 0)?.and_utc();
        Some(dt.timestamp_millis())
    }

    /// Locate three integer components in the order dictated by self.format.
    /// Year is always 4 digits; month and day are 1 or 2 digits.
    fn find_components(&self, stem: &str) -> Option<(i32, i32, i32)> {
        // Build a regex matching (group1)(sep)(group2)(sep)(group3) where sep is
        // any single non-digit character.
        let (g1, g2, g3) = match self.format {
            FilenameDateFormat::MonthDayYear => (r"(\d{1,2})", r"(\d{1,2})", r"(\d{4})"),
            FilenameDateFormat::DayMonthYear => (r"(\d{1,2})", r"(\d{1,2})", r"(\d{4})"),
            FilenameDateFormat::YearMonthDay => (r"(\d{4})", r"(\d{1,2})", r"(\d{1,2})"),
        };
        let body = format!("{}\\D{}\\D{}", g1, g2, g3);
        let pattern = match self.position {
            FilenameDatePosition::Anywhere => body,
            FilenameDatePosition::Beginning => format!("^{}", body),
            FilenameDatePosition::End => format!("{}$", body),
        };
        let re = regex::Regex::new(&pattern).ok()?;
        let caps = re.captures(stem)?;
        let a = caps.get(1)?.as_str().parse::<i32>().ok()?;
        let b = caps.get(2)?.as_str().parse::<i32>().ok()?;
        let c = caps.get(3)?.as_str().parse::<i32>().ok()?;
        Some((a, b, c))
    }
}

pub struct IndexingEngine;

impl IndexingEngine {
    pub fn scan_directory(
        db: Arc<Database>,
        path: &Path,
        recursive: bool,
        thumbnail_cache: &Path,
        filename_date: Option<FilenameDateRule>,
        post_index_options: post_index::Options,
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

        // Spin up the incremental post-index worker pool *before* the
        // par_iter so each indexed video can be handed off the moment
        // its row lands in the DB.  The pool exists for the lifetime of
        // this scan; we close it (drop sender + join) after the par_iter
        // returns, so any items still in flight finish processing before
        // we report scan completion.
        let post_index = post_index::spawn(
            Arc::clone(&db),
            thumbnail_cache.to_path_buf(),
            post_index_options,
        );

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

            match Self::index_video(db.as_ref(), video_path, thumbnail_cache, filename_date) {
                Ok(video_id) => {
                    let done = videos_indexed.fetch_add(1, Ordering::Relaxed) + 1;
                    tracing::debug!("Indexed: {}", filename);
                    // Hand the freshly-indexed video off to the
                    // post-index pool. The submit call applies
                    // backpressure if the pool is saturated, which
                    // throttles the rayon loop and keeps memory bounded.
                    post_index.submit(video_id);
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

        // Drain the worker pool before reporting "complete". Without
        // this, the caller could observe the scan as finished while
        // proxy/stack decisions for the last few videos are still
        // pending — and an interrupt at that point would lose them.
        on_progress(&ScanProgress {
            status: "finalizing".to_string(),
            videos_found,
            videos_indexed: videos_indexed.load(Ordering::Relaxed),
            current_file: "Finishing proxy & group detection".to_string(),
            progress_percent: 99.0,
        });
        post_index.finish();

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

    /// The outcome of a single-file index/refresh, used by the file watcher
    /// to publish the right `CatalogEvent` to subscribed clients.
    ///
    /// `Added` means we just inserted a new video row. `Modified` means we
    /// re-extracted metadata on an existing row (because the file changed on
    /// disk). The watcher decides which to emit based on whether the catalog
    /// already had a row for the path.
    ///
    /// Returned by `scan_single_file` alongside the video ID so the caller
    /// can include both in the event payload.
    pub fn index_video(
        db: &Database,
        video_path: &Path,
        thumbnail_cache: &Path,
        filename_date: Option<FilenameDateRule>,
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

        // If ffprobe didn't supply a creation_date but the caller asked us to
        // infer one from the filename, do that now. We only fill the date when
        // the column is currently NULL so an explicit EXIF date always wins.
        if let Some(rule) = filename_date {
            if let Some(stem) = video_path.file_stem().and_then(|s| s.to_str()) {
                if let Some(ts_ms) = rule.parse_filename(stem) {
                    if let Ok(conn) = db.get_connection() {
                        let _ = conn.execute(
                            "UPDATE metadata SET creation_date = ?
                             WHERE video_id = ? AND creation_date IS NULL",
                            rusqlite::params![ts_ms, video_id],
                        );
                    }
                }
            }
        }

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

    /// Index (or re-index) one specific file. Used by the file watcher when
    /// a single path settles. Returns the video_id and whether the row was
    /// freshly inserted vs. updated in place.
    ///
    /// Behavior matches a single iteration of `scan_directory`'s inner loop:
    /// the file must have a supported extension, ffprobe must succeed, and
    /// the path is UPSERTed. Thumbnails are generated if missing.
    pub fn scan_single_file(
        db: &Database,
        video_path: &Path,
        thumbnail_cache: &Path,
        filename_date: Option<FilenameDateRule>,
    ) -> Result<(String, ScanFileOutcome)> {
        // Extension gate. The watcher already filters by extension before
        // calling us, but cheap to repeat — we'd rather reject a stray
        // `.txt` here than blow up inside ffprobe.
        let ext_ok = video_path
            .extension()
            .and_then(|e| e.to_str())
            .map(|s| SUPPORTED_EXTENSIONS.contains(&s.to_lowercase().as_str()))
            .unwrap_or(false);
        if !ext_ok {
            return Err(VideoRoomError::InvalidPath(format!(
                "unsupported extension: {}",
                video_path.display()
            )));
        }

        // Detect new-vs-update *before* we touch the DB. After
        // `index_video` runs, the row exists no matter what — and we want
        // the caller to know which gRPC event to publish.
        let existed = db
            .get_video_by_path(video_path.to_str().unwrap_or(""))
            .ok()
            .flatten()
            .is_some();

        let video_id = Self::index_video(db, video_path, thumbnail_cache, filename_date)?;
        let outcome = if existed {
            ScanFileOutcome::Modified
        } else {
            ScanFileOutcome::Added
        };
        Ok((video_id, outcome))
    }

    /// Mark a path as removed from disk. Soft-delete: we set
    /// `is_online = false` rather than deleting the video row, so tags,
    /// notes, collection memberships, and the user's catalog history
    /// survive a temporarily-unplugged drive.
    ///
    /// Returns the video_id if a matching row existed (so the watcher can
    /// publish a `VideoRemoved` event), or None if the path wasn't in the
    /// catalog (a delete for a file we never indexed — silently ignored).
    pub fn mark_offline(db: &Database, video_path: &Path) -> Result<Option<String>> {
        if let Ok(Some(video)) = db.get_video_by_path(video_path.to_str().unwrap_or("")) {
            let conn = db.get_connection()?;
            conn.execute(
                "UPDATE videos SET is_online = 0 WHERE id = ?",
                rusqlite::params![&video.id],
            )
            .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;
            Ok(Some(video.id))
        } else {
            Ok(None)
        }
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
