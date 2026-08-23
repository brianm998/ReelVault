// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

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
//!    cross-linked. The shared-dimension gate handles a different
//!    false-positive: alternate-resolution-tier renders of the same
//!    source — e.g. `_2160p_` (3840×2160, 16:9) and `_2160w_`
//!    (2160×1440, 3:2) — share the source's "2160" identifier across
//!    axes. They're sibling renders, not a master/proxy pair. Rule:
//!    if any dimension of the master appears as a dimension of the
//!    candidate (either axis), reject the link — *unless* both
//!    dimensions match exactly, which is the codec-proxy signature
//!    (UHQ vs MQ at the same crop and resolution) and gets through.
//!
//!    Gate order is load-bearing for performance, not just correctness.
//!    The gates above are arithmetic and byte comparisons on rows already
//!    in memory; a thumbnail comparison first has to decode up to eleven
//!    JPEGs per side (~90 ms against a large cache directory). Buckets are
//!    whole directories, and this library has a flat one with 1030
//!    same-fps clips, so gating first is the difference between ~5,800
//!    comparisons and ~1.6 million wasted decodes. Every path here —
//!    batch and incremental — must reject on metadata before it touches
//!    the thumbnail cache.
//!
//!    A separate relaxation handles **editor-generated proxies** (Adobe
//!    Premiere, DaVinci Resolve) that land in a dedicated `Proxies/`
//!    subfolder and are re-rated to a different fps — either of which
//!    otherwise keeps them out of the original's `(parent_dir, fps)`
//!    bucket. See [`detect_proxies_folder_pairs`] for the strict gates
//!    (no audio, "proxy" in the name, exact frame count, …) that make
//!    ignoring fps safe in that case.
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
use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

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
    /// Thumbnail sets actually decoded off disk. This is the pass's real
    /// cost driver — one load is up to eleven JPEG opens — so it belongs in
    /// the completion log next to `pairs_compared`. It should stay in the
    /// same ballpark as `pairs_compared`; a number orders of magnitude
    /// larger means something is decoding before the metadata gates again.
    pub thumbnails_loaded: usize,
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
    // One bounded thumbnail cache for the whole pass. Both passes below
    // revisit the same masters repeatedly, and the cache is what keeps that
    // from meaning a fresh eleven-JPEG decode every time.
    run_detection_with_cache(db, &ThumbCache::new(thumbnail_cache), candidates)
}

fn run_detection_with_cache(
    db: &Database,
    img_cache: &ThumbCache,
    candidates: Vec<ProxyDetectCandidate>,
) -> Result<DetectSummary> {
    if candidates.len() < 2 {
        return Ok(DetectSummary::default());
    }

    let mut summary = DetectSummary::default();

    // Pass 1 — Proxies-folder pairs. Editor-generated proxies (Adobe
    // Premiere, DaVinci Resolve, …) land in a dedicated `Proxies/`
    // subdirectory and are frequently re-encoded at a different frame rate
    // than the source. Either difference alone keeps them out of the
    // `(parent_dir, fps_rounded)` buckets below, so the main loop never
    // compares them to the original one directory up. This pass links them
    // under a strict set of gates that substitute for the relaxed fps
    // requirement — see [`proxies_folder_pair_gates_pass`]. Run before
    // bucketing so it can borrow `candidates` by reference.
    detect_proxies_folder_pairs(db, img_cache, &candidates, &mut summary);

    // Pass 2 — directory/fps- and group-bucketed detection.
    //
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

        // For each (master, candidate) pair where the master is
        // earlier in the sorted bucket and qualifies as a master of
        // the candidate (strictly more pixels OR same-pixels + ≥ 2.5×
        // larger file), run the gates + thumbnail comparison. Every
        // pair that passes adds a junction-table link, so a single
        // proxy can attach to multiple visually-equivalent masters
        // (e.g. three slightly-different aurora/topaz/star_v variants
        // that all share the same low-res preview).
        //
        // The candidate's own thumbnails are loaded lazily, on the first
        // pair that survives the metadata gates: in a directory bucket of
        // any size the overwhelming majority of members never clear
        // `name_part` equality, and decoding eleven JPEGs for them is
        // ~90 ms thrown away each time.
        for j in 1..members.len() {
            let candidate = &members[j];
            let mut cand_imgs: Option<Arc<ThumbImages>> = None;

            for master in members.iter().take(j) {
                if !is_master_of(master, candidate) {
                    continue;
                }
                if !proxy_pair_gates_pass(master, candidate) {
                    continue;
                }
                let cand_imgs = cand_imgs.get_or_insert_with(|| img_cache.get(&candidate.id));
                let master_imgs = img_cache.get(&master.id);

                summary.pairs_compared += 1;
                let conf = compare_thumb_sets(&master_imgs, cand_imgs);
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
                if (0.5..PROXY_SIMILARITY_THRESHOLD).contains(&conf) {
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

    summary.thumbnails_loaded = img_cache.loads();
    Ok(summary)
}

/// Detection pass for **Proxies-folder pairs** — proxies an editor (Adobe
/// Premiere, DaVinci Resolve, …) generated into a dedicated `Proxies/`
/// subdirectory beside the source clip.
///
/// These defeat the main detector's `(parent_dir, fps_rounded)` bucketing
/// twice over: the proxy lives one directory deeper than the original, and
/// editors frequently conform the proxy to a different frame rate than the
/// source. Either difference alone keeps the pair out of the same bucket,
/// so [`run_detection`]'s main loop never compares them.
///
/// For each no-audio, `proxy`-named file inside a `Proxies`-like folder we
/// look one directory up for an original it out-resolves, apply
/// [`proxies_folder_pair_gates_pass`], and link when the thumbnails clear
/// [`PROXY_SIMILARITY_THRESHOLD`]. Like the main loop this is many-to-many:
/// a proxy can link to every same-lineage master in the parent directory
/// (e.g. an OriRes master plus a same-name codec proxy of it).
fn detect_proxies_folder_pairs(
    db: &Database,
    img_cache: &ThumbCache,
    candidates: &[ProxyDetectCandidate],
    summary: &mut DetectSummary,
) {
    // Index every candidate by its parent directory so each proxy can find
    // the originals one level up in O(1).
    let mut by_dir: HashMap<&str, Vec<&ProxyDetectCandidate>> = HashMap::new();
    for c in candidates {
        by_dir.entry(c.parent_dir.as_str()).or_default().push(c);
    }

    for proxy in candidates {
        // Cheap pre-filter: only no-audio files in a `Proxies`-like folder
        // whose name mentions "proxy" can ever qualify.
        if !is_proxies_folder_candidate(proxy) {
            continue;
        }
        // Originals live in the directory that contains the Proxies folder.
        let Some(parent_dir) = Path::new(&proxy.parent_dir).parent().and_then(|p| p.to_str()) else {
            continue;
        };
        let Some(originals) = by_dir.get(parent_dir) else {
            continue;
        };

        for original in originals {
            if original.id == proxy.id {
                continue;
            }
            // Direction + relationship gates. is_master_of confirms the
            // original out-resolves the proxy; the folder gate enforces the
            // strict no-audio / name / frame-count / directory criteria.
            if !is_master_of(original, proxy) {
                continue;
            }
            if !proxies_folder_pair_gates_pass(original, proxy) {
                continue;
            }

            let master_imgs = img_cache.get(&original.id);
            let proxy_imgs = img_cache.get(&proxy.id);

            summary.pairs_compared += 1;
            let conf = compare_thumb_sets(&master_imgs, &proxy_imgs);
            tracing::debug!(
                master = %original.filename,
                proxy = %proxy.filename,
                res_master = %format!("{}×{}", original.width, original.height),
                res_proxy = %format!("{}×{}", proxy.width, proxy.height),
                fps_master = original.fps,
                fps_proxy = proxy.fps,
                frame_count = original.frame_count,
                confidence = %format!("{:.3}", conf),
                "Proxies-folder pair compared",
            );
            if conf >= PROXY_SIMILARITY_THRESHOLD {
                if let Err(e) = db.set_proxy_of(&proxy.id, &original.id, conf, true) {
                    tracing::warn!(
                        "Failed to link {} as Proxies-folder proxy of {}: {}",
                        proxy.filename, original.filename, e,
                    );
                    continue;
                }
                tracing::info!(
                    "Auto-detected Proxies-folder proxy: {} → {} (confidence {:.3}, fps {} vs {})",
                    proxy.filename, original.filename, conf, proxy.fps, original.fps,
                );
                summary.proxies_marked += 1;
            }
        }
    }
}

/// Pair gate for the Proxies-folder path ([`detect_proxies_folder_pairs`]).
/// Returns true when `proxy` is an editor-generated proxy of `original`
/// that should link despite living in a separate folder and (possibly)
/// carrying a different frame rate. The combination is deliberately strict
/// — each clause rules out a different false positive, and together they
/// substitute for the same-fps signal the main detector relies on:
///
///   * **No audio on the proxy.** Editor proxies of timelapse footage are
///     silent; this avoids treating an unrelated audio-bearing clip that
///     happens to sit in a `Proxies` folder as a proxy.
///   * **Filename mentions "proxy" and shares the original's name_part.**
///     `name_part` equality is the detector's standing definition of "very
///     close filename match" (enforced by [`proxy_pair_gates_pass`]); the
///     literal "proxy" token confirms intent.
///   * **`proxy` sits in a `Proxies`-like folder directly under
///     `original`'s directory** — the on-disk convention editors follow.
///   * **Frame counts match exactly.** A re-rate changes fps and duration
///     but not the number of source frames, so this is the strongest
///     "same recording" signal once fps is off the table. Stricter than
///     the ±tolerance in [`proxy_pair_gates_pass`], which still also runs.
///
/// [`is_master_of`] (original out-resolves the proxy) and the thumbnail
/// threshold are checked separately by the caller.
pub(crate) fn proxies_folder_pair_gates_pass(
    original: &ProxyDetectCandidate,
    proxy: &ProxyDetectCandidate,
) -> bool {
    // proxy must be a no-audio, "proxy"-named file in a Proxies-like folder.
    if !is_proxies_folder_candidate(proxy) {
        return false;
    }
    // …directly beneath the original's directory.
    match Path::new(&proxy.parent_dir).parent().and_then(|p| p.to_str()) {
        Some(grandparent) if grandparent == original.parent_dir => {}
        _ => return false,
    }
    // Exact frame-count match: same number of source frames even though the
    // frame rate (and therefore duration) may differ.
    if original.frame_count <= 0
        || proxy.frame_count <= 0
        || original.frame_count != proxy.frame_count
    {
        return false;
    }
    // Reuse the standard gates: name_part equality (the "very close filename
    // match"), shared-dimension, frame-count tolerance, and camera-model.
    proxy_pair_gates_pass(original, proxy)
}

/// Cheap per-file pre-filter for the Proxies-folder pass: a no-audio file,
/// inside a `Proxies`-like directory, whose filename mentions "proxy".
fn is_proxies_folder_candidate(c: &ProxyDetectCandidate) -> bool {
    c.audio_channels == 0
        && filename_mentions_proxy(&c.filename)
        && Path::new(&c.parent_dir)
            .file_name()
            .and_then(|n| n.to_str())
            .map(looks_like_proxies_dir)
            .unwrap_or(false)
}

/// True when a directory name follows the editor convention for a proxy
/// folder — "Proxies", "Proxy", or a close variant — matched
/// case-insensitively.
fn looks_like_proxies_dir(name: &str) -> bool {
    let n = name.to_ascii_lowercase();
    n.contains("proxies") || n.contains("proxy")
}

/// True when a filename contains the literal token "proxy" (any case).
fn filename_mentions_proxy(filename: &str) -> bool {
    filename.to_ascii_lowercase().contains("proxy")
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
    // Shared-dimension gate: a legitimate resolution proxy strictly
    // shrinks the source — fewer pixels in BOTH axes. Sibling renders
    // that share a dimension value across axes (e.g. a `_2160p_`
    // 3840×2160 cut and a `_2160w_` 2160×1440 cut of the same
    // timelapse, where the "2160" appears in master.height and
    // candidate.width) are alternate crops at the same nominal
    // resolution tier, not a master/proxy pair. The thumbnails of
    // such pairs still score above the 0.96 similarity threshold
    // because the underlying source frames are identical — only the
    // crop window differs — so without this gate the auto-detector
    // happily links them. Rule: reject when any value in
    // {master.width, master.height} also appears in
    // {candidate.width, candidate.height}.
    if shares_dimension_value(master, candidate) {
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

/// True when a numeric dimension is shared across axes between two
/// non-dimensionally-identical videos.
///
/// The intent is to catch alternate-crop sibling renders that sit at
/// the same nominal resolution tier — e.g. `_2160p_` (3840×2160) and
/// `_2160w_` (2160×1440), where the value 2160 appears in
/// master.height and candidate.width. Those videos share a name_part
/// and a frame count and visually-near-identical thumbnails, so the
/// other gates would let them link, but they aren't actually a
/// master/proxy pair — they're sibling renders of the same source at
/// different crops.
///
/// Codec-quality proxies — same crop, same resolution, smaller file
/// (e.g. ProRes-444 UHQ vs ProRes-422 MQ both at 3840×2160) — must
/// still pass. Hence the "non-dimensionally-identical" qualifier:
/// when both width and height match exactly, we're not looking at
/// sibling crops, we're looking at a codec proxy.
///
/// Missing dimensions on either side return `false` so other gates
/// can make the call.
fn shares_dimension_value(a: &ProxyDetectCandidate, b: &ProxyDetectCandidate) -> bool {
    if a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0 {
        return false;
    }
    // Codec proxy at the same exact resolution — let it through.
    if a.width == b.width && a.height == b.height {
        return false;
    }
    a.width == b.width
        || a.width == b.height
        || a.height == b.width
        || a.height == b.height
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

/// Approximate resident size of one decoded thumbnail set, used to keep
/// [`ThumbCache`] inside a byte budget. A typical video contributes a
/// 400×N medium plus ten 320×N scrubs ≈ 2.4 MB of RGB8.
fn thumb_images_bytes(t: &ThumbImages) -> usize {
    use image::GenericImageView;
    let one = |img: &image::DynamicImage| {
        let (w, h) = img.dimensions();
        (w as usize) * (h as usize) * (img.color().bytes_per_pixel() as usize)
    };
    t.medium.as_ref().map_or(0, one) + t.scrubs.iter().map(one).sum::<usize>()
}

/// Byte budget for [`ThumbCache`]. At ~2.4 MB per video this holds ~40
/// decoded sets — plenty for the locality the detectors actually have
/// (a handful of same-`name_part` siblings inside one directory), while
/// staying small enough that a 1000-file directory can't balloon the
/// daemon's RSS. The pre-cache code kept one unbounded `HashMap` per
/// bucket, which on this library's 1030-file directory would have held
/// ~2.4 GB of decoded JPEG.
const THUMB_CACHE_BUDGET_BYTES: usize = 96 * 1024 * 1024;

/// Bounded, shareable cache of decoded thumbnail sets keyed by video id.
///
/// Decoding one video's thumbnails means opening and decoding up to
/// eleven JPEGs — on a large cache directory that measures ~90 ms, which
/// dwarfs every other step of proxy detection. Both detection paths
/// revisit the same videos repeatedly (a master is compared against each
/// of its proxies; the incremental worker re-examines a directory once
/// per newly-indexed file), so a cache turns most of those loads into a
/// pointer copy.
///
/// Eviction is FIFO on insertion order rather than true LRU — the access
/// pattern is a sliding window over one directory, so recency and
/// insertion order agree closely enough and FIFO costs nothing to
/// maintain.
///
/// Thread-safe and cheap to share: `Arc<ThumbCache>` is handed to every
/// post-index worker. Decoding happens *outside* the lock, so two workers
/// racing on the same cold id may both decode it — harmless (the loads
/// are pure) and much cheaper than serialising every decode.
///
/// Entries are never invalidated, so an instance is scoped to one pass (a
/// scan's worker pool, or one batch detection run). That is safe because
/// `index_video` finishes writing a video's medium and scrub frames before
/// it hands the id to the post-index queue — thumbnails don't change under
/// a running pass. The one exception is a client `GetThumbnail` lazily
/// generating scrub frames mid-scan for a video that had none, where a
/// cached set could miss them; the comparison then falls back to the
/// medium thumbnail, exactly as it would have without the cache had the
/// read landed a moment earlier.
pub(crate) struct ThumbCache {
    cache_dir: PathBuf,
    budget_bytes: usize,
    loads: AtomicUsize,
    inner: Mutex<ThumbCacheInner>,
}

#[derive(Default)]
struct ThumbCacheInner {
    /// video id → (decoded set, its `thumb_images_bytes`).
    map: HashMap<String, (Arc<ThumbImages>, usize)>,
    /// Insertion order, for FIFO eviction.
    order: VecDeque<String>,
    bytes: usize,
}

impl ThumbCache {
    pub(crate) fn new(cache_dir: impl Into<PathBuf>) -> Self {
        Self::with_budget(cache_dir, THUMB_CACHE_BUDGET_BYTES)
    }

    pub(crate) fn with_budget(cache_dir: impl Into<PathBuf>, budget_bytes: usize) -> Self {
        ThumbCache {
            cache_dir: cache_dir.into(),
            budget_bytes,
            loads: AtomicUsize::new(0),
            inner: Mutex::new(ThumbCacheInner::default()),
        }
    }

    /// How many sets this cache has decoded off disk (misses, not calls).
    pub(crate) fn loads(&self) -> usize {
        self.loads.load(Ordering::Relaxed)
    }

    /// Decoded thumbnails for `video_id`, loading them on a miss.
    ///
    /// A video with no thumbnails on disk yields an empty set, which is
    /// cached too — re-statting eleven absent files for every pair is the
    /// exact cost this type exists to avoid.
    pub(crate) fn get(&self, video_id: &str) -> Arc<ThumbImages> {
        {
            let inner = self.lock();
            if let Some((hit, _)) = inner.map.get(video_id) {
                return Arc::clone(hit);
            }
        }

        // Decode with the lock released: the loads are pure, so at worst two
        // workers racing on the same cold id duplicate one decode — far
        // cheaper than serialising every decode behind the cache.
        self.loads.fetch_add(1, Ordering::Relaxed);
        let loaded = Arc::new(thumb_images_for(&self.cache_dir, video_id));
        let size = thumb_images_bytes(&loaded);

        let mut inner = self.lock();
        // Another worker may have won the race while we decoded; prefer the
        // entry that is already published so callers share one allocation.
        if let Some((hit, _)) = inner.map.get(video_id) {
            return Arc::clone(hit);
        }
        inner
            .map
            .insert(video_id.to_string(), (Arc::clone(&loaded), size));
        inner.order.push_back(video_id.to_string());
        inner.bytes += size;

        // Evict oldest-first until we are back inside the budget, never the
        // entry just added (`order.len() > 1`).
        while inner.bytes > self.budget_bytes && inner.order.len() > 1 {
            let Some(oldest) = inner.order.pop_front() else {
                break;
            };
            if let Some((_, dropped_size)) = inner.map.remove(&oldest) {
                inner.bytes = inner.bytes.saturating_sub(dropped_size);
            }
        }

        loaded
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, ThumbCacheInner> {
        // A panic inside a cache operation can't leave the map inconsistent
        // (every mutation is a complete insert or remove), so recovering from
        // poisoning is safe and keeps a stray panic from wedging the pool.
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }
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
/// The ffmpeg proxy-transcode leaf behind the CLI
/// [`crate::media_backend::MediaBackend`] `transcode_proxy`. libx264 / AAC with
/// `-movflags +faststart` at `target_height`, reporting encode progress on the
/// 0–85% band via `progress` (parsed from ffmpeg's `-progress pipe:1` `frame=`
/// lines; `total_frames == 0` means unknown → pulse 5–50%). Acquires one ffmpeg
/// permit for the whole encode. The args reproduce the previous inline
/// invocation verbatim, so the proxy file is bit-identical.
pub(crate) fn ffmpeg_transcode_proxy(
    source: &Path,
    output_path: &Path,
    target_height: i32,
    total_frames: i64,
    progress: &mut dyn FnMut(f64),
) -> Result<()> {
    // Bound concurrent ffmpeg invocations the same way scan does.
    let _permit = crate::concurrency::acquire_ffmpeg_permit();

    // `-y` to overwrite a partial file left by an interrupted run. `-progress
    // pipe:1` writes machine-readable key=value progress to stdout; `-nostats`
    // suppresses the interleaved per-frame stat lines.
    let scale_filter = format!("scale=-2:{}", target_height);
    let mut child = crate::ffmpeg::ffmpeg_command()
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
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            "-progress",
            "pipe:1",
            "-nostats",
            output_path.to_string_lossy().as_ref(),
        ])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::inherit())
        .spawn()
        .map_err(|e| crate::error::ReelVaultError::FfmpegError(format!("spawn ffmpeg: {}", e)))?;

    // Parse stdout for `frame=N` progress lines and map them onto the 0-85%
    // band; "indexing"/"complete" cover 90-100% after ffmpeg exits.
    let stdout = child.stdout.take().expect("stdout was piped");
    use std::io::BufRead;
    for line in std::io::BufReader::new(stdout).lines().map_while(|l| l.ok()) {
        if let Some(frame_str) = line.strip_prefix("frame=") {
            if let Ok(frame) = frame_str.trim().parse::<i64>() {
                let percent = if total_frames > 0 {
                    ((frame as f64 / total_frames as f64) * 85.0).min(85.0)
                } else {
                    // Unknown total — pulse between 5% and 50% so the
                    // bar moves without making up a completion claim.
                    ((frame % 10) as f64 * 4.5 + 5.0).min(50.0)
                };
                progress(percent);
            }
        }
    }

    let exit_status = child
        .wait()
        .map_err(|e| crate::error::ReelVaultError::FfmpegError(format!("wait ffmpeg: {}", e)))?;
    if !exit_status.success() {
        return Err(crate::error::ReelVaultError::FfmpegError(format!(
            "ffmpeg exited with status {}",
            exit_status,
        )));
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
pub fn create_proxy(
    db: &Database,
    source_id: &str,
    source: &crate::media_backend::MediaSource,
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

    // Total frame count drives the encoding progress percentage.
    // Falls back gracefully to 0 when metadata isn't available yet.
    let total_frames = db.get_video_frame_count(source_id);

    // Transcode through the media backend (ffmpeg on desktop; native on iOS).
    // Progress arrives on the 0–85% band; "indexing"/"complete" cover 90–100%
    // afterward. The backend acquires its own ffmpeg permit for the encode.
    crate::media_backend::backend().transcode_proxy(
        source,
        output_path,
        target_height as i32,
        total_frames,
        &mut |percent| {
            on_progress(&CreateProxyProgress {
                status: "encoding".into(),
                progress_percent: percent,
                message: format!("Encoding… {:.0}%", percent),
            });
        },
    )?;

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
                crate::error::ReelVaultError::InternalError(
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

    /// Write a tiny valid JPEG so `thumb_images_for` finds something to
    /// decode. Content is irrelevant here — these tests are about how often
    /// we hit the disk, not what the pixels say.
    fn write_thumb(dir: &std::path::Path, video_id: &str, suffix: &str) {
        let img = image::DynamicImage::ImageRgb8(image::ImageBuffer::from_pixel(
            32,
            18,
            image::Rgb([128u8, 128, 128]),
        ));
        img.save(dir.join(format!("{}_{}.jpg", video_id, suffix)))
            .expect("write test thumbnail");
    }

    // A cache miss decodes; every later hit for the same id must not.
    #[test]
    fn thumb_cache_decodes_each_video_once() {
        let dir = tempfile::tempdir().unwrap();
        write_thumb(dir.path(), "vid-a", "medium");
        write_thumb(dir.path(), "vid-b", "medium");

        let cache = ThumbCache::new(dir.path());
        let first = cache.get("vid-a");
        let second = cache.get("vid-a");
        cache.get("vid-b");

        assert_eq!(cache.loads(), 2, "one decode per distinct video");
        assert!(Arc::ptr_eq(&first, &second), "hits share one allocation");
        assert!(first.medium.is_some());
    }

    // A missing video caches its empty result rather than re-statting the
    // eleven absent files on every pair.
    #[test]
    fn thumb_cache_caches_absent_thumbnails() {
        let dir = tempfile::tempdir().unwrap();
        let cache = ThumbCache::new(dir.path());
        assert!(cache.get("nope").medium.is_none());
        cache.get("nope");
        assert_eq!(cache.loads(), 1);
    }

    // The budget bounds resident memory: a bucket far bigger than the cache
    // must evict rather than hold every decoded set at once. (Before the
    // cache existed, one unbounded map per bucket meant a 1030-file
    // directory held ~2.4 GB of decoded JPEG.)
    #[test]
    fn thumb_cache_evicts_to_stay_within_budget() {
        let dir = tempfile::tempdir().unwrap();
        for i in 0..8 {
            write_thumb(dir.path(), &format!("vid-{}", i), "medium");
        }
        // One 32×18 RGB8 medium is 1728 bytes; a 4 KB budget holds two.
        let cache = ThumbCache::with_budget(dir.path(), 4096);
        for i in 0..8 {
            cache.get(&format!("vid-{}", i));
        }
        let inner = cache.lock();
        assert!(inner.bytes <= 4096, "over budget: {} bytes", inner.bytes);
        assert!(inner.map.len() <= 2, "held {} sets", inner.map.len());
        assert_eq!(inner.map.len(), inner.order.len(), "map/order out of sync");
    }

    // The load-bearing performance invariant: the metadata gates decide
    // whether a pair is worth comparing, and they must decide it *before*
    // anyone touches the disk. Decoding a thumbnail set is ~90 ms against a
    // real cache directory, and a flat directory of same-fps clips puts
    // every file in one bucket — this library has a 1030-file one, where
    // loading first meant ~1.6 M decodes and many hours per re-index.
    #[test]
    fn detection_loads_no_thumbnails_when_gates_reject_every_pair() {
        let dir = tempfile::tempdir().unwrap();
        // Ten distinct lineages at descending resolutions: `is_master_of`
        // says yes to most orderings, but no two share a `name_part`, so no
        // pair should ever reach a comparison.
        let candidates: Vec<_> = (0..10)
            .map(|i| {
                let c = mk(
                    &format!("05_16_2026-a7sii-{}_ProRes-444_OriRes_30_UHQ.mov", i),
                    4240 - i * 100,
                    2832 - i * 100,
                    900,
                    4000,
                );
                write_thumb(dir.path(), &c.id, "medium");
                c
            })
            .collect();

        let cache = ThumbCache::new(dir.path());
        let summary = run_detection_with_cache(&Database::new_empty(), &cache, candidates).unwrap();

        assert_eq!(summary.pairs_compared, 0);
        assert_eq!(
            cache.loads(),
            0,
            "gates must reject before any thumbnail is decoded",
        );
        assert_eq!(summary.thumbnails_loaded, 0);
    }

    // The converse: a pair that clears the gates does get compared, and each
    // side is decoded exactly once even though it appears in several pairs.
    #[test]
    fn detection_loads_each_side_once_for_gated_pairs() {
        let dir = tempfile::tempdir().unwrap();
        // One lineage, three resolutions — three ordered master/proxy pairs
        // over three distinct videos.
        let candidates = vec![
            mk(
                "05_16_2026-a7sii-1_ProRes-444_OriRes_30_UHQ.mov",
                4240, 2832, 900, 4000,
            ),
            mk(
                "05_16_2026-a7sii-1_ProRes-422_1080p_30_HQ.mov",
                1920, 1080, 900, 400,
            ),
            mk(
                "05_16_2026-a7sii-1_ProRes-422_720p_30_MQ.mov",
                1280, 720, 900, 80,
            ),
        ];
        for c in &candidates {
            write_thumb(dir.path(), &c.id, "medium");
        }

        let cache = ThumbCache::new(dir.path());
        let summary = run_detection_with_cache(&Database::new_empty(), &cache, candidates).unwrap();

        assert_eq!(summary.pairs_compared, 3, "every ordered pair compared");
        assert_eq!(cache.loads(), 3, "three videos, three decodes");
        assert_eq!(summary.thumbnails_loaded, 3);
    }

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
            audio_channels: 2,
        }
    }

    /// Build a candidate from an explicit path (filename + parent_dir
    /// derived from it) with explicit fps and audio-channel count — for the
    /// Proxies-folder tests, which depend on directory layout, frame rate,
    /// and audio presence.
    #[allow(clippy::too_many_arguments)]
    fn mk_at(
        path: &str,
        w: i32,
        h: i32,
        fc: i64,
        size_mb: i64,
        fps: f64,
        audio_channels: i32,
    ) -> ProxyDetectCandidate {
        let p = std::path::Path::new(path);
        ProxyDetectCandidate {
            id: path.to_string(),
            filename: p.file_name().unwrap().to_str().unwrap().to_string(),
            path: path.to_string(),
            parent_dir: p.parent().unwrap().to_str().unwrap().to_string(),
            group_id: None,
            width: w,
            height: h,
            fps,
            frame_count: fc,
            camera_model: String::new(),
            file_size_bytes: size_mb * 1_048_576,
            audio_channels,
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

    // ReelVault's own generated-proxy naming convention
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

    /// `_2160p_` (3840×2160) and `_2160w_` (2160×1440) are alternate-
    /// crop renders of the same timelapse — same name_part, same frame
    /// count, same camera. Their dimensions don't match on the same
    /// axis (3840≠2160, 2160≠1440), but the value "2160" appears in
    /// master.height AND candidate.width — they share a dimension
    /// across axes, which the shared-dimension gate catches.
    #[test]
    fn proxy_gate_rejects_cross_axis_shared_dimension() {
        let p_variant = mk(
            "07_27_2024-a9-1-aurora-topaz_ProRes-422_Rec.709F_2160p_30_MQ.mov",
            3840, 2160, 900, 800,
        );
        let w_variant = mk(
            "07_27_2024-a9-1-aurora-topaz_ProRes-422_Rec.709F_2160w_30_MQ.mov",
            2160, 1440, 900, 600,
        );
        assert!(!proxy_pair_gates_pass(&p_variant, &w_variant));
        // Symmetric — order shouldn't matter.
        assert!(!proxy_pair_gates_pass(&w_variant, &p_variant));
    }

    /// The same cross-axis collision at a smaller tier: `_720p_`
    /// (1080×720) vs `_720w_` (720×480) share the value "720".
    /// They're alternate-resolution crops at the "720-tier", not a
    /// proxy pair.
    #[test]
    fn proxy_gate_rejects_720_tier_cross_axis_collision() {
        let p720 = mk(
            "07_27_2024-a9-1-aurora-topaz_ProRes-422_Rec.709F_720p_30_MQ.mov",
            1080, 720, 900, 80,
        );
        let w720 = mk(
            "07_27_2024-a9-1-aurora-topaz_ProRes-422_Rec.709F_720w_30_MQ.mov",
            720, 480, 900, 50,
        );
        assert!(!proxy_pair_gates_pass(&p720, &w720));
    }

    /// A real proxy pair — strict shrink in BOTH axes, no shared
    /// dimension value — must still pass. This is the canonical
    /// "master vs lower-res proxy of the same crop" case.
    #[test]
    fn proxy_gate_allows_strict_resolution_shrink() {
        // 6000×4000 (OriRes) → 2160×1440 (2160w) — same 3:2 aspect,
        // smaller in both axes, no shared dimension value.
        let master = mk(
            "foo_ProRes-444_Rec.709F_OriRes_30_UHQ.mov",
            6000, 4000, 600, 4000,
        );
        let proxy = mk(
            "foo_ProRes-422_Rec.709F_2160w_30_MQ.mov",
            2160, 1440, 600, 600,
        );
        assert!(proxy_pair_gates_pass(&master, &proxy));
    }

    /// Codec proxy: same crop, same resolution, smaller file (e.g.
    /// ProRes-444 UHQ vs ProRes-422 MQ both at 3840×2160). Both
    /// dimensions match — but on the SAME axis (width=width AND
    /// height=height), which is the codec-proxy signature, not the
    /// sibling-crop signature. The gate must let these through;
    /// is_master_of's "same pixels, ≥ 2.5× larger file" rule is what
    /// confirms the codec-proxy relationship is genuine.
    #[test]
    fn proxy_gate_allows_codec_proxy_same_dimensions() {
        let uhq = mk(
            "foo_ProRes-444_Rec.709F_OriRes_30_UHQ.mov",
            3840, 2160, 900, 4000,
        );
        let mq = mk(
            "foo_ProRes-422_Rec.709F_OriRes_30_MQ.mov",
            3840, 2160, 900, 800,
        );
        assert!(proxy_pair_gates_pass(&uhq, &mq));
    }

    // ---- Proxies-folder (editor proxy) detection ------------------------

    /// The concrete field case: an Adobe Premiere proxy in a `Proxies/`
    /// subfolder, re-rated to 25 fps, no audio, identical frame count, with
    /// `_Proxy` appended to an otherwise-identical filename. It must link
    /// even though the fps differs and it lives one directory deeper than
    /// the original.
    #[test]
    fn proxies_folder_gate_accepts_premiere_reencode() {
        let original = mk_at(
            "/lib/2024-video/06_05_2024-a7iv-2-aurora-topaz-star-v-0_6_7-exp_ProRes-444_Rec.709F_OriRes_30_UHQ.mov",
            3840, 2160, 9000, 4000, 30.0, 0,
        );
        let proxy = mk_at(
            "/lib/2024-video/Proxies/06_05_2024-a7iv-2-aurora-topaz-star-v-0_6_7-exp_ProRes-444_Rec.709F_OriRes_30_UHQ_Proxy.mov",
            1280, 720, 9000, 120, 25.0, 0,
        );
        assert!(is_master_of(&original, &proxy));
        assert!(proxies_folder_pair_gates_pass(&original, &proxy));
    }

    /// Audio on the proxy disqualifies it — an audio-bearing clip that
    /// merely sits in a Proxies folder isn't an editor proxy of silent
    /// timelapse footage.
    #[test]
    fn proxies_folder_gate_rejects_proxy_with_audio() {
        let original = mk_at(
            "/lib/v/clip_ProRes-444_Rec.709F_OriRes_30_UHQ.mov",
            3840, 2160, 9000, 4000, 30.0, 0,
        );
        let proxy = mk_at(
            "/lib/v/Proxies/clip_ProRes-444_Rec.709F_OriRes_30_UHQ_Proxy.mov",
            1280, 720, 9000, 120, 25.0, 2,
        );
        assert!(!proxies_folder_pair_gates_pass(&original, &proxy));
    }

    /// A filename without the "proxy" token doesn't qualify, even with
    /// everything else (no audio, Proxies folder, matching frames) lined up.
    #[test]
    fn proxies_folder_gate_requires_proxy_in_filename() {
        let original = mk_at(
            "/lib/v/clip_ProRes-444_Rec.709F_OriRes_30_UHQ.mov",
            3840, 2160, 9000, 4000, 30.0, 0,
        );
        let proxy = mk_at(
            "/lib/v/Proxies/clip_ProRes-422_Rec.709F_720p_25_MQ.mov",
            1280, 720, 9000, 120, 25.0, 0,
        );
        assert!(!proxies_folder_pair_gates_pass(&original, &proxy));
    }

    /// A different-fps re-encode sitting *next to* the original (not in a
    /// Proxies folder) must NOT take this relaxed path — same-directory
    /// pairs are governed by the main detector's same-fps bucketing.
    #[test]
    fn proxies_folder_gate_requires_proxies_dir() {
        let original = mk_at(
            "/lib/v/clip_ProRes-444_Rec.709F_OriRes_30_UHQ.mov",
            3840, 2160, 9000, 4000, 30.0, 0,
        );
        let proxy = mk_at(
            "/lib/v/clip_ProRes-444_Rec.709F_OriRes_30_UHQ_Proxy.mov",
            1280, 720, 9000, 120, 25.0, 0,
        );
        assert!(!proxies_folder_pair_gates_pass(&original, &proxy));
    }

    /// The original must live directly above the Proxies folder. A
    /// same-named proxy under some *other* directory's Proxies folder must
    /// not link to it.
    #[test]
    fn proxies_folder_gate_requires_original_one_level_up() {
        let original = mk_at(
            "/lib/other/clip_ProRes-444_Rec.709F_OriRes_30_UHQ.mov",
            3840, 2160, 9000, 4000, 30.0, 0,
        );
        let proxy = mk_at(
            "/lib/v/Proxies/clip_ProRes-444_Rec.709F_OriRes_30_UHQ_Proxy.mov",
            1280, 720, 9000, 120, 25.0, 0,
        );
        assert!(!proxies_folder_pair_gates_pass(&original, &proxy));
    }

    /// Differing source-frame counts mean it isn't the same recording —
    /// reject even when everything else matches. (The relaxed fps path
    /// leans entirely on exact frame-count equality.)
    #[test]
    fn proxies_folder_gate_requires_exact_frame_count() {
        let original = mk_at(
            "/lib/v/clip_ProRes-444_Rec.709F_OriRes_30_UHQ.mov",
            3840, 2160, 9000, 4000, 30.0, 0,
        );
        let proxy = mk_at(
            "/lib/v/Proxies/clip_ProRes-444_Rec.709F_OriRes_30_UHQ_Proxy.mov",
            1280, 720, 8999, 120, 25.0, 0,
        );
        assert!(!proxies_folder_pair_gates_pass(&original, &proxy));
    }

    /// Wrong direction: the file in the Proxies folder out-resolves the
    /// "original", so it isn't a proxy of it — is_master_of gates the
    /// caller before the pair gate even runs.
    #[test]
    fn proxies_folder_wrong_direction_is_not_master() {
        let smaller = mk_at(
            "/lib/v/clip_ProRes-444_Rec.709F_720p_30_MQ.mov",
            1280, 720, 9000, 120, 30.0, 0,
        );
        let bigger = mk_at(
            "/lib/v/Proxies/clip_ProRes-444_Rec.709F_OriRes_30_UHQ_Proxy.mov",
            3840, 2160, 9000, 4000, 25.0, 0,
        );
        assert!(!is_master_of(&smaller, &bigger));
    }

    #[test]
    fn looks_like_proxies_dir_matches_conventions() {
        assert!(looks_like_proxies_dir("Proxies"));
        assert!(looks_like_proxies_dir("proxies"));
        assert!(looks_like_proxies_dir("Proxy"));
        assert!(looks_like_proxies_dir("PROXY"));
        assert!(!looks_like_proxies_dir("2024-video"));
        assert!(!looks_like_proxies_dir("renders"));
    }
}
