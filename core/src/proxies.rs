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
//!    Why those gates? Same-fps/same-frame-count is a strong "this is
//!    literally the same recording" signal — even a re-encode preserves
//!    both. Pixel-level thumbnail comparison then disambiguates between
//!    two clips that happened to share those properties (e.g. two
//!    similar-duration takes from the same camera at the same fps).
//!
//! 2. **On-demand creation** ([`create_proxy`]). When the client asks
//!    for a proxy at a specific height, we invoke ffmpeg with
//!    `scale=-2:H` (preserves aspect, ensures even dimensions),
//!    H.264 + AAC, single-pass CRF, output beside the original with
//!    a `_proxy_<H>p` suffix. The freshly-created file is then
//!    indexed and linked as a proxy of the source.
//!
//! Aspect-ratio note: a user might export a 16:9 source as a 4:3
//! proxy with letterboxing. The thumbnail comparison scales both images
//! to the same dimensions before subtracting, so the comparison works
//! even when aspect ratios differ. (The frame_count gate is what really
//! catches it — letterboxed transcodes have the same frame count as the
//! source.)

use crate::db::{Database, ProxyDetectCandidate};
use crate::error::Result;
use crate::imagehash;
use std::path::{Path, PathBuf};

/// Threshold below which we refuse to call two videos "the same shot".
///
/// With pixel-MAD similarity, a true proxy pair (same clip, different
/// resolution) typically scores 0.97+. 0.9 gives comfortable headroom
/// for codec artefacts, slight colour-grade differences, and aspect-ratio
/// squish from 3:2→16:9 exports while still rejecting visually-similar
/// but distinct shots.
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

    // Two bucketing strategies, chosen per-video:
    //
    // • **Grouped** (`group_id IS NOT NULL`): use the group ID as the sole
    //   bucket key. Auto-grouping already validated that members share the
    //   same clip identity (date + camera + frame count + name prefix), so
    //   every member is a genuine proxy candidate for every other member.
    //   The frame-count gate is intentionally kept loose for ungrouped videos
    //   but would fire incorrectly on grouped videos when the directory also
    //   contains other clips of different duration — the A7R III 1858-frame
    //   anchor blocked all 11_30_2024-a9-1 2103-frame candidates even though
    //   those files form a perfect proxy triplet among themselves.
    //
    // • **Ungrouped** (`group_id IS NULL`): fall back to `(parent_dir,
    //   fps_rounded)`. The frame-count gate inside the loop then filters out
    //   clips of genuinely different durations that share the directory.
    //
    // camera_model and frame_count are intentionally excluded from both
    // bucket keys for the same reasons as before (EXIF stripping, codec
    // container rounding differences).
    use std::collections::HashMap;
    let mut buckets: HashMap<(String, i64), Vec<ProxyDetectCandidate>> = HashMap::new();
    for c in candidates {
        if c.width == 0 || c.height == 0 {
            tracing::debug!(
                skip_reason = "no resolution metadata",
                filename = %c.filename,
                "proxy detection: skipping candidate",
            );
            continue; // no resolution metadata yet; skip
        }
        let key = match &c.group_id {
            // Group ID used as bucket — the leading "g:" prefix avoids any
            // collision with a directory path that starts with a UUID.
            Some(gid) => (format!("g:{}", gid), 0i64),
            // Ungrouped: same directory + same fps bucket as before.
            None => (c.parent_dir.clone(), c.fps.round() as i64),
        };
        buckets.entry(key).or_default().push(c);
    }

    tracing::debug!(
        bucket_count = buckets.len(),
        "proxy detection: formed buckets",
    );
    for ((dir, fps), members) in &buckets {
        tracing::debug!(
            dir = %dir,
            fps = fps,
            count = members.len(),
            members = %members.iter().map(|m| m.filename.as_str()).collect::<Vec<_>>().join(", "),
            "proxy detection: bucket",
        );
    }

    let mut summary = DetectSummary::default();

    for ((bucket_dir, bucket_fps), mut members) in buckets {
        if members.len() < 2 {
            continue;
        }
        // Sort by (descending pixel count, descending file size).
        // When two files have the same pixel dimensions (e.g. ProRes-444 UHQ
        // and ProRes-422 MQ both at 3840×2160), the larger file — the heavier
        // codec — becomes the anchor / master, and the smaller one is the proxy.
        members.sort_by_key(|m| {
            let pixels = (m.width as i64) * (m.height as i64);
            (-(pixels), -(m.file_size_bytes))
        });

        let mut img_cache: HashMap<String, ThumbImages> = HashMap::with_capacity(members.len());

        let anchor = members[0].clone();
        tracing::debug!(
            dir = %bucket_dir,
            fps = bucket_fps,
            anchor = %anchor.filename,
            anchor_res = %format!("{}×{}", anchor.width, anchor.height),
            anchor_fc = anchor.frame_count,
            candidates = members.len() - 1,
            "proxy detection: processing bucket",
        );
        let anchor_imgs = thumb_images_for(thumbnail_cache, &anchor.id);
        img_cache.insert(anchor.id.clone(), anchor_imgs.clone());

        for candidate in members.iter().skip(1) {
            // Skip already-tagged rows in case detect_proxies is called
            // mid-scan and we picked them up before the upsert landed.
            if db.get_proxy_target(&candidate.id).ok().flatten().is_some() {
                tracing::debug!(
                    skip_reason = "already tagged",
                    filename = %candidate.filename,
                    anchor = %anchor.filename,
                    "proxy detection: skipping candidate",
                );
                continue;
            }

            // ── Frame-count gate (with tolerance) ──────────────────────────
            // Allow ±5% OR ±10 frames (same tolerance the auto-grouper uses).
            // Videos differing more than this are genuinely different shots.
            if anchor.frame_count > 0 && candidate.frame_count > 0 {
                let max_fc = anchor.frame_count.max(candidate.frame_count);
                let fc_tol = (max_fc / 20).max(10); // 5% or 10 frames
                let fc_diff = (anchor.frame_count - candidate.frame_count).abs();
                if fc_diff > fc_tol {
                    tracing::debug!(
                        skip_reason = "frame count mismatch",
                        filename = %candidate.filename,
                        anchor = %anchor.filename,
                        anchor_fc = anchor.frame_count,
                        candidate_fc = candidate.frame_count,
                        diff = fc_diff,
                        tolerance = fc_tol,
                        "proxy detection: skipping candidate",
                    );
                    continue;
                }
            }

            // ── Same-resolution gate (reworked) ────────────────────────────
            // Pure duplicates at the same resolution belong in a stack, not a
            // proxy relationship. HOWEVER: a same-resolution file with a much
            // smaller file size is a *codec proxy* — e.g. ProRes-444 UHQ master
            // (heavy) vs ProRes-422 MQ at the same dimensions (~4× smaller).
            // The lighter file is still useful as a playback proxy when the
            // machine can't decode the master in real time.
            // Gate: require ≥ 2.5× file-size ratio; below that it's close
            // enough to a re-encode that stacking is the right relationship.
            let same_res = candidate.width == anchor.width
                && candidate.height == anchor.height;
            if same_res {
                let size_ratio = if candidate.file_size_bytes > 0 && anchor.file_size_bytes > 0 {
                    anchor.file_size_bytes as f64 / candidate.file_size_bytes as f64
                } else {
                    1.0
                };
                if size_ratio < 2.5 {
                    tracing::debug!(
                        skip_reason = "same resolution, size ratio too low for codec-proxy",
                        filename = %candidate.filename,
                        anchor = %anchor.filename,
                        res = %format!("{}×{}", candidate.width, candidate.height),
                        size_ratio = %format!("{:.2}×", size_ratio),
                        "proxy detection: skipping candidate",
                    );
                    continue; // Similar size → stack member, not proxy
                }
                // Large size difference at same resolution → codec proxy candidate.
            }

            // ── Camera-model gate ──────────────────────────────────────────
            // Reject only when *both* sides carry non-empty, non-matching EXIF.
            if !anchor.camera_model.is_empty()
                && !candidate.camera_model.is_empty()
                && anchor.camera_model != candidate.camera_model
            {
                tracing::debug!(
                    skip_reason = "camera model mismatch",
                    filename = %candidate.filename,
                    anchor = %anchor.filename,
                    anchor_camera = %anchor.camera_model,
                    candidate_camera = %candidate.camera_model,
                    "proxy detection: skipping candidate",
                );
                continue;
            }

            let cand_imgs = img_cache
                .entry(candidate.id.clone())
                .or_insert_with(|| thumb_images_for(thumbnail_cache, &candidate.id))
                .clone();

            summary.pairs_compared += 1;
            let conf = compare_thumb_sets(&anchor_imgs, &cand_imgs);

            // Always log at DEBUG so a verbose run shows every comparison.
            tracing::debug!(
                proxy_a = %anchor.filename,
                proxy_b = %candidate.filename,
                res_a = %format!("{}×{}", anchor.width, anchor.height),
                res_b = %format!("{}×{}", candidate.width, candidate.height),
                size_a_mb = anchor.file_size_bytes / 1_048_576,
                size_b_mb = candidate.file_size_bytes / 1_048_576,
                fc_a = anchor.frame_count,
                fc_b = candidate.frame_count,
                confidence = %format!("{:.3}", conf),
                "proxy pair compared",
            );
            // Promote near-misses (≥ 0.5 but below threshold) to INFO so
            // operators can see borderline pairs without enabling debug mode.
            // Pairs below 0.5 are almost certainly unrelated clips.
            if conf >= 0.5 && conf < PROXY_SIMILARITY_THRESHOLD {
                tracing::info!(
                    "Near-miss proxy pair (conf {:.3} < {:.2} threshold): {} [{}×{}] vs {} [{}×{}]",
                    conf,
                    PROXY_SIMILARITY_THRESHOLD,
                    anchor.filename,
                    anchor.width, anchor.height,
                    candidate.filename,
                    candidate.width, candidate.height,
                );
            }

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
                    candidate.filename, anchor.filename, conf,
                );
                summary.proxies_marked += 1;
            }
        }
    }

    Ok(summary)
}

// ---- Public thumbnail comparison API -----------------------------------

/// Compare the thumbnail sets for two already-indexed videos and return a
/// confidence score in `[0.0, 1.0]`.
///
/// This is the same algorithm used internally by [`detect_proxies`] but
/// exposed so the gRPC service (and tests) can call it directly.
///
/// * If both videos have ≥ [`MIN_SCRUB_FRAMES_FOR_AVG`] scrub frames, the
///   result is the average dHash similarity across all matched-index pairs
///   (same temporal offsets), with the medium thumbnail appended.
/// * Otherwise the medium thumbnail alone is used as a fallback.
/// * Returns 0.0 when neither side has any thumbnail to compare.
///
/// The proxy threshold is [`PROXY_SIMILARITY_THRESHOLD`] (0.9).
/// A plausible stacking-similarity lower bound is ~0.5 — dHash is
/// brightness/saturation-agnostic so colour-grade variants of the same
/// shot tend to score well above that.
pub fn compare_video_thumbnails(thumbnail_cache: &Path, id_a: &str, id_b: &str) -> f64 {
    let a = thumb_images_for(thumbnail_cache, id_a);
    let b = thumb_images_for(thumbnail_cache, id_b);
    compare_thumb_sets(&a, &b)
}

// ---- Internal types ----------------------------------------------------

/// Loaded thumbnail images for one video — medium thumbnail plus any
/// scrub frames that exist on disk.  Stored so [`compare_thumb_sets`]
/// can average across them without re-loading from disk.
#[derive(Clone, Default)]
pub(crate) struct ThumbImages {
    pub(crate) medium: Option<image::DynamicImage>,
    pub(crate) scrubs: Vec<image::DynamicImage>, // one per existing scrub_N.jpg
}

pub(crate) fn thumb_images_for(thumbnail_cache: &Path, video_id: &str) -> ThumbImages {
    let medium_path = thumbnail_cache.join(format!("{}_medium.jpg", video_id));
    let medium = image::open(&medium_path).ok();

    let mut scrubs = Vec::new();
    for i in 0..10 {
        let p = thumbnail_cache.join(format!("{}_scrub_{}.jpg", video_id, i));
        if p.exists() {
            if let Ok(img) = image::open(&p) {
                scrubs.push(img);
            }
        }
    }

    ThumbImages { medium, scrubs }
}

/// Average pixel-MAD similarity across two videos' thumbnail sets.
/// Returns 0.0 when neither side has any thumbnail to compare.
///
/// When both sides have ≥ [`MIN_SCRUB_FRAMES_FOR_AVG`] scrubs we average
/// across the matched-index scrub pairs (same temporal offset, so they
/// depict the same moment).  Otherwise we fall back to the medium
/// thumbnail alone.
pub(crate) fn compare_thumb_sets(a: &ThumbImages, b: &ThumbImages) -> f64 {
    let mut scores: Vec<f64> = Vec::new();

    // Pair up scrub frames by index.
    let scrub_pairs = a.scrubs.len().min(b.scrubs.len());
    if scrub_pairs >= MIN_SCRUB_FRAMES_FOR_AVG {
        for i in 0..scrub_pairs {
            scores.push(imagehash::similarity(&a.scrubs[i], &b.scrubs[i]));
        }
    }

    if let (Some(ma), Some(mb)) = (&a.medium, &b.medium) {
        scores.push(imagehash::similarity(ma, mb));
    }

    if scores.is_empty() {
        0.0
    } else {
        scores.iter().sum::<f64>() / scores.len() as f64
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
