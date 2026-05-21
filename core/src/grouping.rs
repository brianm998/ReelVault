// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

use crate::db::{AutoGroupCandidate, Database};
use crate::error::Result;

pub struct AutoGroupOptions {
    /// Only group videos that live in the same parent directory.
    pub same_directory_only: bool,
    /// Require duration to match within ~5% (used as a fallback when frame counts
    /// are unavailable).
    pub match_duration: bool,
    /// Require fps to match (integer-rounded).
    pub match_fps: bool,
}

impl Default for AutoGroupOptions {
    fn default() -> Self {
        AutoGroupOptions {
            same_directory_only: true,
            match_duration: true,
            match_fps: true,
        }
    }
}

/// Strip the file extension and return the bare filename stem.
fn stem(filename: &str) -> &str {
    match filename.rfind('.') {
        Some(idx) if idx > 0 => &filename[..idx],
        _ => filename,
    }
}

/// Extract the "name part" of a filename stem — everything before the codec
/// metadata section. Typical pattern from professional cameras:
///
///     <date>-<camera>-<clip>[-<suffix>...]_<codec>_<rec>_<resolution>_…
///
/// Dates often embed underscores (e.g. `04_18_2026`), so we can't just split
/// on the first `_`. Instead we look for an underscore followed by a letter
/// — that's the canonical boundary between the dash-separated identifier
/// section and the underscore-separated codec/resolution section.
fn name_part(stem: &str) -> &str {
    let bytes = stem.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'_' && i + 1 < bytes.len() {
            let next = bytes[i + 1];
            if next.is_ascii_alphabetic() {
                return &stem[..i];
            }
        }
        i += 1;
    }
    stem
}

/// Reduce a filename to its "canonical base" — the date+camera+clip prefix
/// that all variants of one source clip share, with post-processing
/// suffixes (`-aurora`, `-topaz`, etc.) stripped.
///
/// The heuristic: take the first three dash-separated tokens of the
/// `name_part`. That's tuned for date-camera-clip naming conventions, but
/// degrades gracefully for shorter names (returns the whole `name_part`).
///
/// Examples (with `04_18_2026` as the leading date token):
///   * `04_18_2026-a7sii-1` → `04_18_2026-a7sii-1`
///   * `04_18_2026-a7sii-1-aurora` → `04_18_2026-a7sii-1`
///   * `04_18_2026-a7sii-1-aurora-topaz-star-v-0` → `04_18_2026-a7sii-1`
fn canonical_base(filename: &str) -> String {
    let name = name_part(stem(filename));
    let tokens: Vec<&str> = name.split('-').collect();
    let take = tokens.len().min(3);
    tokens[..take].join("-")
}

/// Decide whether two videos belong in the same group.
///
/// Rules (all must be satisfied):
///   1. Same parent directory (when `same_directory_only` is set).
///   2. Same fps (rounded — different fps usually means different sources).
///   3. Same frame count (with a tiny ±2 tolerance for off-by-one PTS
///      jitter), with a duration-based fallback when frame counts are
///      missing.
///   4. Same canonical base name (date + camera + clip identifier),
///      derived by stripping post-processing suffixes and codec metadata
///      from the filename.
///   5. Same camera model — when both EXIF camera fields are populated.
///      Variants of the same clip should always come from the same
///      camera; this catches naming collisions where two unrelated
///      cameras happened to start with a similar date+number pattern.
fn should_group_together(
    a: &AutoGroupCandidate,
    b: &AutoGroupCandidate,
    opts: &AutoGroupOptions,
) -> bool {
    if opts.same_directory_only && a.parent_dir != b.parent_dir {
        return false;
    }

    if opts.match_fps && (a.fps.round() as i64) != (b.fps.round() as i64) {
        return false;
    }

    // Frame count match (preferred) or duration fallback
    if a.frame_count > 0 && b.frame_count > 0 {
        let diff = (a.frame_count - b.frame_count).abs();
        if diff > 2 {
            return false;
        }
    } else if opts.match_duration {
        if a.duration_ms == 0 || b.duration_ms == 0 {
            return a.duration_ms == b.duration_ms;
        }
        let diff = (a.duration_ms - b.duration_ms).abs();
        let tol = (a.duration_ms.max(b.duration_ms) / 20).max(1000);
        if diff > tol {
            return false;
        }
    }

    // Canonical-base match: variants of the same source clip share
    // <date>-<camera>-<clip>, ignoring post-processing suffixes
    // (`-aurora`, `-topaz`, …) and the codec/resolution section.
    let base_a = canonical_base(&a.filename);
    let base_b = canonical_base(&b.filename);
    if base_a.is_empty() || base_a != base_b {
        return false;
    }

    // Camera-model gate: when both videos carry EXIF camera info, refuse
    // to group across distinct cameras. We don't *require* both to carry
    // it (some pipelines strip EXIF on transcode), but when present it's
    // an authoritative signal.
    if !a.camera_model.is_empty()
        && !b.camera_model.is_empty()
        && a.camera_model != b.camera_model
    {
        return false;
    }

    true
}

/// Simple union-find for grouping items.
struct UnionFind {
    parent: Vec<usize>,
}

impl UnionFind {
    fn new(n: usize) -> Self {
        UnionFind { parent: (0..n).collect() }
    }
    fn find(&mut self, x: usize) -> usize {
        if self.parent[x] != x {
            self.parent[x] = self.find(self.parent[x]);
        }
        self.parent[x]
    }
    fn union(&mut self, a: usize, b: usize) {
        let ra = self.find(a);
        let rb = self.find(b);
        if ra != rb {
            self.parent[ra] = rb;
        }
    }
}

/// Run auto-grouping over all ungrouped videos. Returns (groups_created, videos_grouped).
pub fn auto_group(db: &Database, options: &AutoGroupOptions) -> Result<(i32, i32)> {
    // Only consider videos that aren't already in a group
    let mut candidates: Vec<AutoGroupCandidate> = db
        .list_for_auto_grouping()?
        .into_iter()
        .filter(|c| c.group_id.is_none())
        .collect();

    if candidates.len() < 2 {
        return Ok((0, 0));
    }

    // Pre-compute canonical base + sort by (parent_dir, base) so videos
    // that would group together end up adjacent. Lets the O(n²) pairwise
    // loop short-circuit early when the bases diverge.
    let bases: Vec<String> = candidates.iter().map(|c| canonical_base(&c.filename)).collect();
    let mut idxs: Vec<usize> = (0..candidates.len()).collect();
    idxs.sort_by(|&i, &j| {
        candidates[i]
            .parent_dir
            .cmp(&candidates[j].parent_dir)
            .then_with(|| bases[i].cmp(&bases[j]))
            .then_with(|| candidates[i].filename.cmp(&candidates[j].filename))
    });
    let candidates: Vec<AutoGroupCandidate> = idxs.iter().map(|&i| candidates[i].clone()).collect();
    let bases: Vec<String> = idxs.iter().map(|&i| bases[i].clone()).collect();

    let n = candidates.len();
    let mut uf = UnionFind::new(n);

    for i in 0..n {
        for j in (i + 1)..n {
            // Once the parent dir or canonical base diverges in
            // same-directory-only mode, no further matches are possible
            // (we sorted by both).
            if options.same_directory_only && candidates[i].parent_dir != candidates[j].parent_dir {
                break;
            }
            if bases[i] != bases[j] {
                break;
            }
            if should_group_together(&candidates[i], &candidates[j], options) {
                uf.union(i, j);
            }
        }
    }

    // Collect members per root
    use std::collections::HashMap;
    let mut groups: HashMap<usize, Vec<usize>> = HashMap::new();
    for i in 0..n {
        let root = uf.find(i);
        groups.entry(root).or_default().push(i);
    }

    let mut groups_created = 0;
    let mut videos_grouped = 0;

    for (_, members) in groups {
        if members.len() < 2 {
            continue;
        }
        // Preferred-leader selection. The user gets to open the
        // most-recently-saved highest-resolution version of the source
        // clip by default — typically the master export, not a 720p
        // proxy. Tie-break order:
        //   1. Highest pixel count (width × height).
        //   2. Most recent modified-at timestamp.
        //   3. Largest frame count (tiebreak that helps the rare
        //      duration-fallback case where frame counts differ).
        //   4. Filename — deterministic last-resort tiebreak.
        let preferred_idx = members
            .iter()
            .copied()
            .max_by(|&i, &j| {
                let a = &candidates[i];
                let b = &candidates[j];
                let pixels_a = (a.width as i64) * (a.height as i64);
                let pixels_b = (b.width as i64) * (b.height as i64);
                pixels_a
                    .cmp(&pixels_b)
                    .then(a.modified_at_ms.cmp(&b.modified_at_ms))
                    .then(a.frame_count.cmp(&b.frame_count))
                    .then_with(|| b.filename.cmp(&a.filename))
            })
            .unwrap();

        let preferred_id = candidates[preferred_idx].id.clone();
        let ids: Vec<String> = members.iter().map(|&i| candidates[i].id.clone()).collect();

        // Group name = the canonical base shared by every member.
        // Cleaner than the previous "longest common prefix" output,
        // which would include trailing characters like
        // `_ProRes-422_Rec.709F_` when every member shared codec info.
        let base_trimmed = bases[members[0]]
            .trim_end_matches(|c: char| !c.is_alphanumeric())
            .to_string();
        let group_name = if base_trimmed.is_empty() { None } else { Some(base_trimmed) };

        db.create_group(
            group_name.as_deref(),
            group_name.as_deref(),
            &ids,
            Some(&preferred_id),
        )?;
        groups_created += 1;
        videos_grouped += members.len() as i32;
    }

    Ok((groups_created, videos_grouped))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_base_strips_codec_and_suffixes() {
        // Bare base — already canonical.
        assert_eq!(
            canonical_base("04_18_2026-a7sii-1_ProRes-422_Rec.709F_2160p_30_MQ.mov"),
            "04_18_2026-a7sii-1"
        );
        // Single suffix.
        assert_eq!(
            canonical_base("04_18_2026-a7sii-1-aurora_ProRes-422_Rec.709F_2160p_30_MQ.mov"),
            "04_18_2026-a7sii-1"
        );
        // Multi-suffix chain.
        assert_eq!(
            canonical_base(
                "04_18_2026-a7sii-1-aurora-topaz-star-v-0_10_8_ProRes-444_Rec.709F_OriRes_30_UHQ.mov"
            ),
            "04_18_2026-a7sii-1"
        );
        // Different camera token → different base.
        assert_ne!(
            canonical_base("04_18_2026-a9-1_ProRes-422_Rec.709F_2160p_30_MQ.mov"),
            canonical_base("04_18_2026-a7sii-1_ProRes-422_Rec.709F_2160p_30_MQ.mov")
        );
    }

    #[test]
    fn canonical_base_handles_short_names() {
        // No dashes at all — `name_part` keeps the whole thing because
        // the underscore is followed by digits (not a letter), so
        // there's no codec boundary to cut on. The result is `IMG_1234`
        // verbatim — and since each iPhone-style filename is unique,
        // they auto-group alone, which is correct.
        assert_eq!(canonical_base("IMG_1234.MOV"), "IMG_1234");
        // Two dashes, codec section starts at `_ProRes` (underscore +
        // letter) — return the dash-separated prefix.
        assert_eq!(
            canonical_base("vacation-paris_ProRes-422.mov"),
            "vacation-paris"
        );
    }
}
