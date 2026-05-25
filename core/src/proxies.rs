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
//!    frames score > 0.9 average similarity AND the two filenames share
//!    the same `name_part` (the stem chunk before the codec section),
//!    the lower-resolution member is marked as a proxy of the
//!    higher-resolution one (`videos.proxy_of` set).
//!
//!    Why those gates? Same-fps/same-frame-count is a strong "this is
//!    literally the same recording" signal — even a re-encode preserves
//!    both. Pixel-level thumbnail comparison then disambiguates between
//!    two clips that happened to share those properties (e.g. two
//!    similar-duration takes from the same camera at the same fps).
//!    The name_part equality gate is what stops cross-lineage links
//!    inside a stack — auto-grouping correctly collapses a "plain" take
//!    and its `-aurora`/`-topaz`/… post-processed siblings into one
//!    stack, but each lineage has its own independent proxy chain.
//!    Without that gate, the 720p of the plain take would erroneously
//!    score ≥ 0.96 against the UHQ of the `-aurora` master and get
//!    cross-linked.
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
/// resolution) typically scores 0.97+. 0.96 sits just below the cluster
/// of real-world proxy pairs and well above the false-positive cluster
/// observed in field tests — for example, two clips that share a
/// canonical filename prefix and bucket but differ in post-processing
/// (e.g. raw take vs. aurora+topaz pass) consistently scored ≤ 0.95,
/// while genuine same-shot pairs from the same machine scored ≥ 0.98.
/// The previous 0.9 ceiling let a band of post-processing siblings slip
/// through.
pub const PROXY_SIMILARITY_THRESHOLD: f64 = 0.96;

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
    run_detection(db, thumbnail_cache, candidates)
}

/// Like [`detect_proxies`] but restricted to videos under `base_path` (plus
/// their grouped siblings elsewhere on disk). Called after a per-directory
/// refresh so we don't re-do O(n) thumbnail loads for every video in the
/// catalog when only one location changed.
pub fn detect_proxies_under_path(
    db: &Database,
    thumbnail_cache: &Path,
    base_path: &Path,
) -> Result<DetectSummary> {
    let base = base_path.to_string_lossy();
    let candidates = db.list_for_proxy_detection_under_path(&base)?;
    run_detection(db, thumbnail_cache, candidates)
}

fn run_detection(
    db: &Database,
    thumbnail_cache: &Path,
    candidates: Vec<ProxyDetectCandidate>,
) -> Result<DetectSummary> {
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
        // codec — comes first; smaller same-res files are codec-proxy
        // candidates downstream.
        members.sort_by_key(|m| {
            let pixels = (m.width as i64) * (m.height as i64);
            (-(pixels), -(m.file_size_bytes))
        });

        tracing::debug!(
            dir = %bucket_dir,
            fps = bucket_fps,
            members = members.len(),
            "proxy detection: processing bucket",
        );

        // Cache thumbnail loads per video so the inner O(b²) loop
        // doesn't re-decode the same JPEG for every pairwise pass.
        let mut img_cache: HashMap<String, ThumbImages> = HashMap::with_capacity(members.len());

        // For each (master, candidate) pair where the master is
        // earlier in the sorted bucket and qualifies as a master of
        // the candidate (strictly more pixels OR same-pixels + ≥ 2.5×
        // larger file), run the gates + thumbnail comparison. Every
        // pair that passes adds a junction-table link, so a single
        // proxy can attach to multiple visually-equivalent masters
        // (e.g. three slightly-different aurora/topaz/star_v variants
        // that all share the same low-res preview).
        for j in 1..members.len() {
            let candidate = members[j].clone();
            // Gather this candidate's thumbnails once.
            let cand_imgs = img_cache
                .entry(candidate.id.clone())
                .or_insert_with(|| thumb_images_for(thumbnail_cache, &candidate.id))
                .clone();

            for i in 0..j {
                let master = members[i].clone();
                if !is_master_of(&master, &candidate) {
                    continue;
                }
                if !proxy_pair_gates_pass(&master, &candidate) {
                    continue;
                }
                let master_imgs = img_cache
                    .entry(master.id.clone())
                    .or_insert_with(|| thumb_images_for(thumbnail_cache, &master.id))
                    .clone();

                summary.pairs_compared += 1;
                let conf = compare_thumb_sets(&master_imgs, &cand_imgs);
                tracing::debug!(
                    master = %master.filename,
                    candidate = %candidate.filename,
                    res_master = %format!("{}×{}", master.width, master.height),
                    res_cand = %format!("{}×{}", candidate.width, candidate.height),
                    size_master_mb = master.file_size_bytes / 1_048_576,
                    size_cand_mb = candidate.file_size_bytes / 1_048_576,
                    fc_master = master.frame_count,
                    fc_cand = candidate.frame_count,
                    confidence = %format!("{:.3}", conf),
                    "proxy pair compared",
                );
                if conf >= 0.5 && conf < PROXY_SIMILARITY_THRESHOLD {
                    tracing::info!(
                        "Near-miss proxy pair (conf {:.3} < {:.2} threshold): {} [{}×{}] vs {} [{}×{}]",
                        conf,
                        PROXY_SIMILARITY_THRESHOLD,
                        master.filename,
                        master.width, master.height,
                        candidate.filename,
                        candidate.width, candidate.height,
                    );
                }
                if conf >= PROXY_SIMILARITY_THRESHOLD {
                    if let Err(e) = db.set_proxy_of(&candidate.id, &master.id, conf, true) {
                        tracing::warn!(
                            "Failed to link {} as proxy of {}: {}",
                            candidate.filename, master.filename, e,
                        );
                        continue;
                    }
                    tracing::info!(
                        "Auto-detected proxy: {} → {} (confidence {:.3})",
                        candidate.filename, master.filename, conf,
                    );
                    summary.proxies_marked += 1;
                }
            }
        }
    }

    Ok(summary)
}

/// Does `master` qualify as a potential master for `candidate`?
///
/// A master either has strictly more pixels than the candidate, or
/// matches on pixel count but is ≥ 2.5× larger on disk (a "codec
/// proxy" relationship — e.g. ProRes-444 UHQ master vs ProRes-422 MQ
/// at the same dimensions but ~4× lighter). Equal-pixel-and-similar-
/// size pairs belong in a stack instead.
pub(crate) fn is_master_of(master: &ProxyDetectCandidate, candidate: &ProxyDetectCandidate) -> bool {
    let m_px = (master.width as i64) * (master.height as i64);
    let c_px = (candidate.width as i64) * (candidate.height as i64);
    if m_px > c_px {
        return true;
    }
    if m_px == c_px && master.file_size_bytes > 0 && candidate.file_size_bytes > 0 {
        let ratio = master.file_size_bytes as f64 / candidate.file_size_bytes as f64;
        return ratio >= 2.5;
    }
    false
}

/// Name-prefix / frame-count / camera-model gates shared between batch
/// detection and the incremental post-index worker. The pixel-count and
/// same-resolution-size gates are handled by [`is_master_of`].
///
/// The name-prefix gate is what keeps proxy links honest inside a stack.
/// Auto-grouping collapses post-processing siblings (a "plain" take and a
/// `-aurora`/`-topaz`/… variant of the same source) into one stack via
/// `canonical_base`, which is correct — they belong together. But each
/// lineage in that stack has its own proxy chain: the 720p of the plain
/// take is *not* a proxy of the `-aurora` UHQ master, even though the
/// dHash similarity is ≥ 0.99 (post-processing rarely changes thumbnails
/// enough to drop below the proxy threshold). Requiring matching
/// `name_part`s — the stem chunk before the codec/resolution boundary —
/// is the disambiguating signal: it survives "plain → 720p" but separates
/// "plain → -aurora". This deliberately follows the user-stated rule
/// "similar names + matching frames + different sizes ⇒ proxy" by treating
/// `name_part` equality as the operational definition of "similar names".
pub(crate) fn proxy_pair_gates_pass(
    master: &ProxyDetectCandidate,
    candidate: &ProxyDetectCandidate,
) -> bool {
    // Name-prefix gate: refuse to cross-link proxies between
    // sibling lineages inside a stack. See module comment above.
    if crate::grouping::name_part_of_filename(&master.filename)
        != crate::grouping::name_part_of_filename(&candidate.filename)
    {
        return false;
    }
    // Frame-count gate (with tolerance): allow ±5% or ±10 frames.
    if master.frame_count > 0 && candidate.frame_count > 0 {
        let max_fc = master.frame_count.max(candidate.frame_count);
        let fc_tol = (max_fc / 20).max(10);
        let fc_diff = (master.frame_count - candidate.frame_count).abs();
        if fc_diff > fc_tol {
            return false;
        }
    }
    // Camera-model gate: reject only when both sides carry non-empty,
    // non-matching EXIF.
    if !master.camera_model.is_empty()
        && !candidate.camera_model.is_empty()
        && master.camera_model != candidate.camera_model
    {
        return false;
    }
    true
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::ProxyDetectCandidate;

    fn mk(filename: &str, w: i32, h: i32, fc: i64, size_mb: i64) -> ProxyDetectCandidate {
        ProxyDetectCandidate {
            id: filename.to_string(),
            filename: filename.to_string(),
            path: format!("/tmp/{}", filename),
            parent_dir: "/tmp".into(),
            group_id: None,
            width: w,
            height: h,
            fps: 30.0,
            frame_count: fc,
            camera_model: String::new(),
            file_size_bytes: size_mb * 1_048_576,
        }
    }

    // Within one stack ("plain" lineage + "-aurora" lineage of the same shot),
    // the master of one lineage must not adopt a proxy from another. Without
    // the name_part gate, frame matching alone would link them because
    // -aurora is just a noise-reduction pass over the plain take.
    #[test]
    fn proxy_gate_rejects_cross_lineage_pair() {
        let master_aurora = mk(
            "05_16_2026-a7sii-1-aurora_ProRes-444_Rec.709F_OriRes_30_UHQ.mov",
            4240, 2832, 900, 4000,
        );
        let proxy_plain = mk(
            "05_16_2026-a7sii-1_ProRes-422_Rec.709F_720p_30_MQ.mov",
            1080, 720, 900, 80,
        );
        // Frame counts match, camera_model empty on both — every gate but
        // name_part would let this through.
        assert!(!proxy_pair_gates_pass(&master_aurora, &proxy_plain));
    }

    // Same-lineage pair (one master, one of its proxies) must still pass.
    #[test]
    fn proxy_gate_accepts_same_lineage_pair() {
        let master = mk(
            "05_16_2026-a7sii-1_ProRes-444_Rec.709F_OriRes_30_UHQ.mov",
            4240, 2832, 900, 4000,
        );
        let proxy = mk(
            "05_16_2026-a7sii-1_ProRes-422_Rec.709F_720p_30_MQ.mov",
            1080, 720, 900, 80,
        );
        assert!(proxy_pair_gates_pass(&master, &proxy));
    }

    // VideoRoom's own generated-proxy naming convention
    // (`<orig_stem>_proxy_<H>p.<ext>`) must still pass the name_part gate
    // — `_proxy` is itself the `_<letter>` codec-section boundary, so both
    // sides reduce to the same name_part.
    #[test]
    fn proxy_gate_accepts_generated_proxy_naming() {
        let master = mk("IMG_1234.mov", 3840, 2160, 600, 800);
        let generated = mk("IMG_1234_proxy_720p.mp4", 1280, 720, 600, 40);
        assert!(proxy_pair_gates_pass(&master, &generated));
    }

    // Sibling lineages further down the post-processing chain
    // (`-aurora` vs `-aurora-star-v-0_10_8`) must also be separated.
    #[test]
    fn proxy_gate_rejects_deeper_lineage_split() {
        let aurora = mk(
            "04_18_2026-a9-2-aurora_ProRes-444_Rec.709F_OriRes_30_UHQ.mov",
            6000, 4000, 900, 4000,
        );
        let star_v = mk(
            "04_18_2026-a9-2-aurora-star-v-0_10_8_ProRes-422_Rec.709F_720p_30_MQ.mov",
            1080, 720, 900, 80,
        );
        assert!(!proxy_pair_gates_pass(&aurora, &star_v));
    }
}
