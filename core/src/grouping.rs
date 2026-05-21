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

/// Length of the longest common prefix (in characters, not bytes).
fn longest_common_prefix_len(a: &str, b: &str) -> usize {
    a.chars().zip(b.chars()).take_while(|(x, y)| x == y).count()
}

/// Levenshtein edit distance between two strings (character-based).
fn levenshtein(a: &str, b: &str) -> usize {
    let a_chars: Vec<char> = a.chars().collect();
    let b_chars: Vec<char> = b.chars().collect();
    if a_chars.is_empty() {
        return b_chars.len();
    }
    if b_chars.is_empty() {
        return a_chars.len();
    }

    let mut prev: Vec<usize> = (0..=b_chars.len()).collect();
    let mut curr: Vec<usize> = vec![0; b_chars.len() + 1];

    for i in 1..=a_chars.len() {
        curr[0] = i;
        for j in 1..=b_chars.len() {
            let cost = if a_chars[i - 1] == b_chars[j - 1] { 0 } else { 1 };
            curr[j] = std::cmp::min(
                std::cmp::min(curr[j - 1] + 1, prev[j] + 1),
                prev[j - 1] + cost,
            );
        }
        std::mem::swap(&mut prev, &mut curr);
    }
    prev[b_chars.len()]
}

/// Similarity score in [0, 1]. 1.0 means identical, 0.0 means totally different.
fn similarity(a: &str, b: &str) -> f64 {
    let max_len = a.chars().count().max(b.chars().count());
    if max_len == 0 {
        return 1.0;
    }
    let dist = levenshtein(a, b);
    1.0 - (dist as f64 / max_len as f64)
}

/// Decide whether two videos belong in the same group.
///
/// Rules (all must be satisfied):
///   1. Same parent directory (if option enabled).
///   2. Same fps (rounded) — different fps usually means different sources.
///   3. Same frame count (with a tiny ±1 tolerance for off-by-one rounding).
///      Falls back to duration similarity if frame_count is 0 for either video.
///   4. Filenames share a long common prefix (at least `min_prefix` characters,
///      where `min_prefix` is the larger of 8 chars or 25% of the shorter name).
///   5. Filenames are sufficiently similar overall (Levenshtein similarity >= 0.5).
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
        // Allow up to 2 frames difference for rounding/PTS jitter
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

    // Fuzzy filename match
    let stem_a = stem(&a.filename);
    let stem_b = stem(&b.filename);
    let shorter = stem_a.chars().count().min(stem_b.chars().count());

    // Require a long common prefix. Use a generous threshold so we catch
    // variants of the same source even if they diverge later in the name.
    let lcp = longest_common_prefix_len(stem_a, stem_b);
    let min_prefix = std::cmp::max(8, shorter / 4);
    if lcp < min_prefix {
        return false;
    }

    // Overall similarity check - cheap insurance against
    // very-different files that happen to share a prefix
    if similarity(stem_a, stem_b) < 0.5 {
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

    // Sort by filename so videos with shared prefixes end up adjacent. This both
    // makes the result deterministic and lets us short-circuit comparisons.
    candidates.sort_by(|a, b| {
        a.parent_dir
            .cmp(&b.parent_dir)
            .then_with(|| a.filename.cmp(&b.filename))
    });

    let n = candidates.len();
    let mut uf = UnionFind::new(n);

    // For each candidate, compare against subsequent ones until the prefix
    // diverges so much we know no further matches are possible.
    for i in 0..n {
        for j in (i + 1)..n {
            // Quick reject: if the parent directories differ in same_directory_only mode,
            // and since the list is sorted by parent_dir, we can break out early.
            if options.same_directory_only && candidates[i].parent_dir != candidates[j].parent_dir {
                break;
            }
            // Bound the comparison: once filenames stop sharing a prefix entirely,
            // there's no point continuing because the list is sorted.
            if longest_common_prefix_len(&candidates[i].filename, &candidates[j].filename) == 0 {
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
        // Pick the highest-resolution member as preferred. If tied, pick the largest
        // frame count, then the first by filename.
        let preferred_idx = members
            .iter()
            .copied()
            .max_by_key(|&i| {
                let c = &candidates[i];
                ((c.width as i64) * (c.height as i64), c.frame_count)
            })
            .unwrap();

        let preferred_id = candidates[preferred_idx].id.clone();
        let ids: Vec<String> = members.iter().map(|&i| candidates[i].id.clone()).collect();

        // Use the longest common prefix of all members' filenames as the group name
        let names: Vec<&str> = members.iter().map(|&i| candidates[i].filename.as_str()).collect();
        let base = lcp_all(&names);
        let base_trimmed = base.trim_end_matches(|c: char| !c.is_alphanumeric()).to_string();
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

/// Longest common prefix of an arbitrary number of strings.
fn lcp_all(strs: &[&str]) -> String {
    if strs.is_empty() {
        return String::new();
    }
    let first = strs[0];
    let mut end = first.chars().count();
    for s in &strs[1..] {
        end = end.min(longest_common_prefix_len(first, s));
        if end == 0 {
            return String::new();
        }
    }
    first.chars().take(end).collect()
}
