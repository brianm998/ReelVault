// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

use crate::db::Database;
use crate::error::{Result, ReelVaultError};
use crate::media_backend::{backend, MediaSource};
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

/// Inputs for a full directory scan, grouped into one struct so
/// [`IndexingEngine::scan_directory`] stays under clippy's argument limit as
/// the set of per-scan knobs grows. Mirrors the option-bundle pattern already
/// used by [`post_index::Options`].
pub struct ScanConfig<'a> {
    /// Root directory to scan.
    pub path: &'a Path,
    /// Recurse into subdirectories when true; otherwise only the top level.
    pub recursive: bool,
    /// Directory where generated thumbnails are cached.
    pub thumbnail_cache: &'a Path,
    /// Optional rule for inferring capture dates from filenames.
    pub filename_date: Option<FilenameDateRule>,
    /// Post-index pipeline toggles (proxy detection, auto-grouping, …).
    pub post_index_options: post_index::Options,
    /// Broadcast bus for post-index progress events. `None` disables
    /// progress reporting (CLI / tests); the gRPC service passes its
    /// catalog-events sender so a long proxy-detection pass shows up in
    /// the clients' background-activity panel.
    pub events: Option<tokio::sync::broadcast::Sender<crate::watcher::CatalogChange>>,
}

pub struct IndexingEngine;

impl IndexingEngine {
    pub fn scan_directory(
        db: Arc<Database>,
        config: ScanConfig,
        on_progress: impl Fn(&ScanProgress) + Sync,
    ) -> Result<()> {
        let ScanConfig {
            path,
            recursive,
            thumbnail_cache,
            filename_date,
            post_index_options,
            events,
        } = config;

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
            events.clone(),
            videos_found.max(0) as u64,
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

        // Retire catalog entries whose files have vanished since the last index
        // (moved, renamed, or an unmounted drive). The found set is the paths we
        // just walked; any online video under this directory that isn't in it
        // gets soft-deleted (is_online = 0) so clients can flag it offline.
        // Found files were re-asserted online by index_video's size refresh.
        let present: std::collections::HashSet<std::path::PathBuf> =
            video_paths.iter().cloned().collect();
        match db.mark_missing_locations_offline(path, recursive, &present) {
            Ok(results) => {
                let fully_offline = results.iter().filter(|(_, f)| *f).count();
                let location_only = results.iter().filter(|(_, f)| !*f).count();
                if fully_offline > 0 || location_only > 0 {
                    tracing::info!(
                        "Scan: {} video(s) offline, {} lost a location under {}",
                        fully_offline, location_only, path.display()
                    );
                }
            }
            Err(e) => tracing::warn!("Scan: offline sweep failed: {}", e),
        }

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
            .ok_or_else(|| ReelVaultError::InvalidPath("Invalid filename".to_string()))?;

        // Get file size
        let file_size = std::fs::metadata(video_path)
            .map(|m| m.len() as i64)
            .ok();

        // Extract metadata (through the media backend: ffprobe on desktop,
        // AVFoundation on iOS).
        let source = MediaSource::Path(video_path.to_path_buf());
        let probe_output = backend().probe(&source)?;

        // Check if already indexed — if so, reuse the existing location row and
        // just refresh metadata. We now key on video_locations, not videos.path.
        let video_id = if let Some(loc) = db.get_location_by_path(video_path.to_str().unwrap_or(""))? {
            // Already a known location — sync file size on both the location and
            // the master videos row.
            if let Some(sz) = file_size {
                db.update_location_size(&loc.id, sz)?;
                db.update_video_file_size(&loc.video_id, sz)?;
            }
            loc.video_id
        } else {
            // New path. Check if this is a copy/move of an existing video:
            // same filename + same file size → same content.
            let copy_id = if let Some(sz) = file_size {
                db.find_location_by_filename_size(filename, sz, video_path.to_str().unwrap_or(""))
                    .ok()
                    .flatten()
            } else {
                None
            };

            if let Some(existing_id) = copy_id {
                tracing::info!(
                    "location added: {} is a copy of video {}",
                    video_path.display(),
                    existing_id
                );
                db.add_video_location(&existing_id, video_path.to_str().unwrap_or(""), filename, file_size)?;
                // Ensure videos.is_online reflects the new active location.
                db.update_video_file_size(&existing_id, file_size.unwrap_or(0))?;
                existing_id
            } else {
                // Genuinely new video — create the master row and its first location.
                let new_id = db.add_video(
                    video_path.to_str().unwrap_or(""),
                    filename,
                    None,
                    None,
                    file_size,
                )?;
                db.add_video_location(&new_id, video_path.to_str().unwrap_or(""), filename, file_size)?;
                new_id
            }
        };

        // (Re-)store metadata (UPSERT)
        MetadataExtractor::store_metadata(db, &video_id, video_path, &probe_output, file_size.unwrap_or(0))?;

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
            match ThumbnailGenerator::generate(db, &source, &video_id, thumbnail_cache, duration_secs) {
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
                &source,
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

    /// Index (or re-index) a non-filesystem source — the iOS on-device path,
    /// where videos are Photos assets / bookmarks rather than walkable files
    /// (docs/IOS_CORE_PORT.md §6.9). Mirrors [`Self::index_video`] but probes
    /// and thumbnails through the given [`MediaSource`] (the native backend) and
    /// keys the row on a synthetic `display_path` (e.g. `photos://<localId>`),
    /// recording `source_kind`/`source_id` so later lookups resolve the right
    /// `MediaSource`. Returns the video_id.
    #[allow(clippy::too_many_arguments)]
    pub fn index_media_source(
        db: &Database,
        source: &MediaSource,
        display_path: &str,
        filename: &str,
        source_kind: &str,
        source_id: &str,
        thumbnail_cache: &Path,
    ) -> Result<String> {
        let probe_output = backend().probe(source)?;

        let video_id = if let Ok(Some(existing)) = db.get_video_by_path(display_path) {
            existing.id
        } else {
            db.add_video(display_path, filename, None, None, None)?
        };
        db.set_video_source(&video_id, source_kind, source_id)?;

        MetadataExtractor::store_metadata(
            db,
            &video_id,
            Path::new(display_path),
            &probe_output,
            0,
        )?;

        let duration_secs = probe_output.format.duration.unwrap_or(0.0);
        let thumb_path = thumbnail_cache.join(format!("{}_medium.jpg", video_id));
        if !thumb_path.exists() {
            if let Err(e) =
                ThumbnailGenerator::generate(db, source, &video_id, thumbnail_cache, duration_secs)
            {
                tracing::warn!("index_media_source: thumbnail failed for {video_id}: {e}");
            }
        }
        let first_scrub = thumbnail_cache.join(format!("{}_scrub_0.jpg", video_id));
        if !first_scrub.exists() {
            if let Err(e) = ThumbnailGenerator::generate_scrub_thumbnails(
                source,
                &video_id,
                thumbnail_cache,
                duration_secs,
            ) {
                tracing::warn!("index_media_source: scrub frames failed for {video_id}: {e}");
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
            return Err(ReelVaultError::InvalidPath(format!(
                "unsupported extension: {}",
                video_path.display()
            )));
        }

        // Detect new-vs-update *before* we touch the DB. After
        // `index_video` runs, the row exists no matter what — and we want
        // the caller to know which gRPC event to publish.
        let location_existed = db
            .get_location_by_path(video_path.to_str().unwrap_or(""))
            .ok()
            .flatten()
            .is_some();

        let video_id = Self::index_video(db, video_path, thumbnail_cache, filename_date)?;

        // Copy detection: if this path was new but the video already had
        // other locations, it's a copy — the logical video isn't new.
        // Emit Modified so clients don't add a duplicate grid entry.
        let outcome = if location_existed {
            ScanFileOutcome::Modified
        } else {
            let is_copy = db
                .get_connection()
                .and_then(|conn| {
                    conn.query_row(
                        "SELECT COUNT(*) FROM video_locations WHERE video_id = ?",
                        rusqlite::params![&video_id],
                        |r| r.get::<_, i64>(0),
                    )
                    .map_err(|e| crate::error::ReelVaultError::DatabaseError(e.to_string()))
                })
                .map(|n| n > 1)
                .unwrap_or(false);
            if is_copy {
                ScanFileOutcome::Modified
            } else {
                ScanFileOutcome::Added
            }
        };
        Ok((video_id, outcome))
    }

    /// Mark a path as removed from disk. Delegates to the location-aware
    /// `db.mark_location_offline` which handles multi-location videos:
    /// if another location is still online the video stays alive and only
    /// the canonical path is updated; if all locations are gone the
    /// `videos.is_online` flag is cleared too.
    ///
    /// Returns `Some((video_id, fully_offline))` if the path was a known
    /// location, or `None` if it wasn't in the catalog (silently ignored).
    pub fn mark_offline(db: &Database, video_path: &Path) -> Result<Option<(String, bool)>> {
        db.mark_location_offline(video_path.to_str().unwrap_or(""))
    }

    #[allow(dead_code)]
    pub fn update_online_status(db: &Database, path: &Path, is_online: bool) -> Result<()> {
        if let Ok(Some(loc)) = db.get_location_by_path(path.to_str().unwrap_or("")) {
            let conn = db.get_connection()?;
            conn.execute(
                "UPDATE video_locations SET is_online = ? WHERE id = ?",
                rusqlite::params![is_online as i32, loc.id],
            )
            .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;
            if !is_online {
                db.mark_location_offline(path.to_str().unwrap_or(""))?;
            } else {
                db.update_video_file_size(&loc.video_id, loc.file_size_bytes.unwrap_or(0))?;
            }
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
