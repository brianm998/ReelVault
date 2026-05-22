// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

//! Proxy discovery and creation.
//!
//! Two paths into this module:
//!
//! 1. **Auto-detection during scan** ([`detect_proxies`]). After
//!    [`crate::indexing::IndexingEngine::scan_directory`] completes,
//!    we group candidate videos by `(parent_dir, camera_model, fps,
//!    frame_count)` and within each group compare every pair's
//!    thumbnails. If the medium thumbnail and all available scrub
//!    frames score > 0.9 average similarity, the lower-resolution
//!    member is marked as a proxy of the higher-resolution one
//!    (`videos.proxy_of` set).
//!
//!    Why those gates? Same-camera/same-fps/same-frame-count is a
//!    strong "this is literally the same recording" signal — even a
//!    re-encode preserves all three. Thumbnails then disambiguate
//!    between two different clips that happened to share those
//!    properties (e.g. two takes back-to-back from the same camera
//!    where the user happened to stop at the same frame).
//!
//! 2. **On-demand creation** ([`create_proxy`]). When the client asks
//!    for a proxy at a specific height, we invoke ffmpeg with
//!    `scale=-2:H` (preserves aspect, ensures even dimensions),
//!    H.264 + AAC, single-pass CRF, output beside the original with
//!    a `_proxy_<H>p` suffix. The freshly-created file is then
//!    indexed and linked as a proxy of the source.
//!
//! Aspect-ratio note: a user might export a 16:9 source as a 4:3
//! proxy with letterboxing. The image hash already strips uniform
//! dark borders before computing, so the comparison works even when
//! aspect ratios differ. (The frame_count gate is what really catches
//! it — letterboxed transcodes have the same frame count as the
//! source.)

use crate::db::{Database, ProxyDetectCandidate};
use crate::error::Result;
use crate::imagehash::{self, DHash};
use std::path::{Path, PathBuf};

/// Threshold below which we refuse to call two videos "the same shot".
/// 0.9 = at most 6 differing bits out of 64 in the dHash. Empirically
/// the sweet spot for "same clip, different resolution" pairs while
/// rejecting "similar clip" pairs.
pub const PROXY_SIMILARITY_THRESHOLD: f64 = 0.9;

/// How many of the 10 scrub frames must exist on disk for us to trust
/// the average. If fewer than this exist, we fall back to comparing
/// just the medium thumbnail. (5 is generous — scrubs are generated
/// lazily; recently-scanned videos often have only the medium one.)
const MIN_SCRUB_FRAMES_FOR_AVG: usize = 5;

/// Summary of one auto-detection pass — for the scan progress / logs.
#[derive(Debug, Default, Clone, Copy)]
pub struct DetectSummary {
    pub pairs_compared: usize,
    pub proxies_marked: usize,
}

/// Scan the catalog for proxy relationships and mark them in the DB.
///
/// Runs once after a scan completes. Idempotent: re-running on an
/// already-tagged catalog yields a [`DetectSummary`] of zero
/// `proxies_marked` because the candidates query excludes rows whose
/// `proxy_of` is already set.
pub fn detect_proxies(db: &Database, thumbnail_cache: &Path) -> Result<DetectSummary> {
    let candidates = db.list_for_proxy_detection()?;
    if candidates.len() < 2 {
        return Ok(DetectSummary::default());
    }

    // Bucket by (parent_dir, camera, fps_rounded, frame_count). Only
    // pairs in the same bucket can possibly be proxies of each other.
    use std::collections::HashMap;
    let mut buckets: HashMap<(String, String, i64, i64), Vec<ProxyDetectCandidate>> = HashMap::new();
    for c in candidates {
        if c.frame_count <= 0 || c.width == 0 || c.height == 0 {
            // Without a frame count we can't be confident enough; skip.
            continue;
        }
        let key = (
            c.parent_dir.clone(),
            c.camera_model.clone(),
            c.fps.round() as i64,
            c.frame_count,
        );
        buckets.entry(key).or_default().push(c);
    }

    let mut summary = DetectSummary::default();

    for (_, mut members) in buckets {
        if members.len() < 2 {
            continue;
        }
        // Sort by descending pixel count so the highest-res candidate is
        // the "anchor"; lower-res members get checked against it.
        members.sort_by_key(|m| -((m.width as i64) * (m.height as i64)));

        // Cache hashes so we don't rehash the anchor for every comparison.
        let mut hash_cache: HashMap<String, ThumbHashes> =
            HashMap::with_capacity(members.len());

        let anchor = members[0].clone();
        let anchor_hashes = thumb_hashes_for(thumbnail_cache, &anchor.id);
        hash_cache.insert(anchor.id.clone(), anchor_hashes.clone());

        for candidate in members.iter().skip(1) {
            // Skip already-tagged rows in case detect_proxies is called
            // mid-scan and we picked them up before the upsert landed.
            if db.get_proxy_target(&candidate.id).ok().flatten().is_some() {
                continue;
            }
            // Same-resolution variants aren't "proxies" — they're more
            // likely auto-grouped duplicates. Bail.
            if candidate.width == anchor.width && candidate.height == anchor.height {
                continue;
            }
            let cand_hashes = hash_cache
                .entry(candidate.id.clone())
                .or_insert_with(|| thumb_hashes_for(thumbnail_cache, &candidate.id))
                .clone();

            summary.pairs_compared += 1;
            let conf = compare_thumb_sets(&anchor_hashes, &cand_hashes);
            if conf >= PROXY_SIMILARITY_THRESHOLD {
                if let Err(e) = db.set_proxy_of(&candidate.id, &anchor.id, conf, true) {
                    tracing::warn!(
                        "Failed to mark {} as proxy of {}: {}",
                        candidate.filename, anchor.filename, e,
                    );
                    continue;
                }
                tracing::info!(
                    "Auto-detected proxy: {} → {} (confidence {:.3})",
                    candidate.filename,
                    anchor.filename,
                    conf,
                );
                summary.proxies_marked += 1;
            }
        }
    }

    Ok(summary)
}

/// Pre-computed hashes for one video's medium thumbnail + scrub frames.
/// Stored grouped so [`compare_thumb_sets`] can average across them
/// without re-reading from disk.
#[derive(Debug, Clone, Default)]
struct ThumbHashes {
    medium: Option<DHash>,
    scrubs: Vec<DHash>, // one entry per existing scrub_N.jpg
}

fn thumb_hashes_for(thumbnail_cache: &Path, video_id: &str) -> ThumbHashes {
    let medium_path = thumbnail_cache.join(format!("{}_medium.jpg", video_id));
    let medium = imagehash::hash_file(&medium_path);

    let mut scrubs = Vec::new();
    for i in 0..10 {
        let p = thumbnail_cache.join(format!("{}_scrub_{}.jpg", video_id, i));
        if p.exists() {
            if let Some(h) = imagehash::hash_file(&p) {
                scrubs.push(h);
            }
        }
    }

    ThumbHashes { medium, scrubs }
}

/// Average similarity across two videos' thumbnails. Returns 0.0 if
/// neither side has anything to compare.
///
/// When both sides have ≥ `MIN_SCRUB_FRAMES_FOR_AVG` scrubs, we average
/// the scrub similarities (one per matched index). When only the medium
/// thumbnail exists on either side, we use that.
fn compare_thumb_sets(a: &ThumbHashes, b: &ThumbHashes) -> f64 {
    let mut comparisons: Vec<f64> = Vec::new();

    // Pair up scrub frames by index — both sides took 10 frames at the
    // same relative offsets in the source video, so scrub_0(a) and
    // scrub_0(b) should depict the same moment.
    let scrub_pairs = a.scrubs.len().min(b.scrubs.len());
    if scrub_pairs >= MIN_SCRUB_FRAMES_FOR_AVG {
        for i in 0..scrub_pairs {
            comparisons.push(a.scrubs[i].similarity(b.scrubs[i]));
        }
    }

    if let (Some(ma), Some(mb)) = (a.medium, b.medium) {
        comparisons.push(ma.similarity(mb));
    }

    if comparisons.is_empty() {
        0.0
    } else {
        comparisons.iter().sum::<f64>() / comparisons.len() as f64
    }
}

// --- Proxy creation via ffmpeg ---

/// Suggested file path for a generated proxy beside the source video.
/// Pattern: `<original_stem>_proxy_<height>p.<ext>`. Same extension as
/// the source so file managers / editors recognize it.
pub fn proxy_path_beside(source: &Path, height: u32) -> PathBuf {
    let parent = source.parent().map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    let stem = source.file_stem().and_then(|s| s.to_str()).unwrap_or("proxy");
    let ext = source.extension().and_then(|s| s.to_str()).unwrap_or("mp4");
    parent.join(format!("{}_proxy_{}p.{}", stem, height, ext))
}

/// Progress emitted by [`create_proxy`] for streaming back to the client.
#[derive(Debug, Clone)]
pub struct CreateProxyProgress {
    pub status: String,
    pub progress_percent: f64,
    pub message: String,
}

/// Generate a proxy file at `target_height` from `source`, write it to
/// `output_path`, and (on success) link it into the catalog as a proxy
/// of `source_id`. Streams progress through `on_progress`.
///
/// The ffmpeg invocation is single-pass H.264 + AAC, CRF 23, fast preset.
/// Aspect ratio is preserved (`scale=-2:H` keeps width even and divisible
/// by 2; `-2` also rounds down to the nearest even pixel which avoids
/// libx264's "width not divisible by 2" warning).
///
/// `re_index_after`: when true, the new file is also indexed normally so
/// the videos row exists before we try to link it. Set to false only if
/// you're going to call `scan_single_file` immediately afterwards.
pub fn create_proxy(
    db: &Database,
    source_id: &str,
    source: &Path,
    output_path: &Path,
    target_height: u32,
    thumbnail_cache: &Path,
    re_index_after: bool,
    on_progress: impl Fn(&CreateProxyProgress),
) -> Result<String> {
    on_progress(&CreateProxyProgress {
        status: "started".into(),
        progress_percent: 0.0,
        message: format!("Generating {}p proxy", target_height),
    });

    // Bound concurrent ffmpeg invocations the same way scan does.
    let _permit = crate::concurrency::acquire_ffmpeg_permit();

    // Build the ffmpeg command. `-y` to overwrite if a partial file is
    // left over from a prior interrupted run.
    let scale_filter = format!("scale=-2:{}", target_height);
    let status = std::process::Command::new("ffmpeg")
        .args([
            "-y",
            "-i",
            source.to_string_lossy().as_ref(),
            "-vf",
            &scale_filter,
            "-c:v",
            "libx264",
            "-preset",
            "fast",
            "-crf",
            "23",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            output_path.to_string_lossy().as_ref(),
        ])
        .status()
        .map_err(|e| crate::error::VideoRoomError::FfmpegError(format!("spawn ffmpeg: {}", e)))?;

    if !status.success() {
        return Err(crate::error::VideoRoomError::FfmpegError(format!(
            "ffmpeg exited with status {}",
            status,
        )));
    }

    on_progress(&CreateProxyProgress {
        status: "indexing".into(),
        progress_percent: 90.0,
        message: "Indexing proxy".into(),
    });

    // Index the new file so it has a videos row, then link it.
    let (proxy_id, _) = if re_index_after {
        crate::indexing::IndexingEngine::scan_single_file(db, output_path, thumbnail_cache, None)?
    } else {
        // Caller will index. We need *some* id; fall back to lookup.
        let v = db
            .get_video_by_path(output_path.to_str().unwrap_or(""))?
            .ok_or_else(|| {
                crate::error::VideoRoomError::InternalError(
                    "proxy not indexed yet; pass re_index_after=true".into(),
                )
            })?;
        (v.id, crate::indexing::ScanFileOutcome::Added)
    };

    // Confidence 1.0 because we *generated* this — we know it's a proxy.
    db.set_proxy_of(&proxy_id, source_id, 1.0, false)?;

    on_progress(&CreateProxyProgress {
        status: "complete".into(),
        progress_percent: 100.0,
        message: format!("Created {}p proxy", target_height),
    });

    Ok(proxy_id)
}
