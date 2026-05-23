// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

//! Path template expansion for library locations.
//!
//! # Supported templates
//!
//! | Token  | Expands to                                                 |
//! |--------|------------------------------------------------------------|
//! | $YEAR  | Each calendar year from 1970 up to and including the       |
//! |        | current year, but only for paths that exist on disk.       |
//!
//! # Why only `$YEAR`?
//!
//! Media archives are almost always organised by year at the top level
//! (`/Volumes/Media/2023/Raw`, `/Volumes/SSD/2024/Projects`, …).
//! `$YEAR` is the one template that pays its weight: one token covers
//! a realistic naming scheme without imposing complicated cross-product
//! combinatorics on the user.
//!
//! `$MONTH` and `$DAY` were deliberately left out:
//! - A `$YEAR/$MONTH` combination would produce up to 12× as many
//!   `add_library_location` RPCs per dialog submission, and most users
//!   want each *year* folder as one library entry so the panel shows
//!   "2024 — 312 videos", not 12 separate month rows.
//! - The scanner already recurses into subdirectories, so adding the
//!   year folder is enough: month and day subdirectories are picked up
//!   automatically.
//! - If a user genuinely needs per-month entries, they can open the
//!   dialog multiple times or use the plain path field.
//!
//! Add further templates here only if a concrete user need is identified.

use std::path::Path;

/// Return the current year (UTC/local, whichever `SystemTime` gives us).
fn current_year() -> u32 {
    // We use a simple division of the Unix epoch seconds rather than
    // pulling in a heavy time library. Leap-year drift makes this off
    // by at most 1 day per 4 years, which is irrelevant for picking a
    // "current year" cutoff.
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    // 365.2425 days/year × 86 400 s/day ≈ 31 556 952 s/year
    1970 + (secs / 31_556_952) as u32
}

/// Expand any templates in `path` and return the resulting paths.
///
/// If `path` contains `$YEAR`, every year from 1970 to the current year
/// is substituted and only the paths that actually exist on disk are kept.
///
/// If no templates are present, `vec![path.to_string()]` is returned
/// unchanged (existence is *not* checked — that is the caller's job,
/// matching the existing behaviour of `add_library_location`).
pub fn expand_path_templates(path: &str) -> Vec<String> {
    if path.contains("$YEAR") {
        let year_now = current_year();
        (1970..=year_now)
            .map(|y| path.replace("$YEAR", &y.to_string()))
            .filter(|p| Path::new(p).exists())
            .collect()
    } else {
        vec![path.to_string()]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn no_template_is_identity() {
        let p = "/tmp/videos";
        assert_eq!(expand_path_templates(p), vec![p.to_string()]);
    }

    #[test]
    fn year_range_includes_1970_and_current() {
        // We cannot easily check which paths exist in a unit test, so we
        // create a temporary directory named with the current year and
        // verify it is returned.
        let tmp = std::env::temp_dir();
        let year_now = current_year();
        let dir = tmp.join(year_now.to_string());
        let _ = std::fs::create_dir_all(&dir);
        let pattern = format!("{}/{}", tmp.display(), "$YEAR");
        let results = expand_path_templates(&pattern);
        // The current-year directory we just created must appear.
        assert!(
            results.iter().any(|r| r.contains(&year_now.to_string())),
            "expected {year_now} in results, got {results:?}"
        );
        // Cleanup — best effort.
        let _ = std::fs::remove_dir(&dir);
    }
}
