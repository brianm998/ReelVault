// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Thumbnail similarity for proxy auto-detection.
//!
//! ## Algorithm
//!
//! Given two thumbnails A and B:
//!
//! 1. Load both images from disk.
//! 2. Scale the *larger* image down to the *smaller* image's exact
//!    dimensions using a high-quality Lanczos3 filter.
//! 3. Convert both to grayscale (luma).
//! 4. Compute the **mean absolute difference** (MAD) per pixel.
//! 5. Normalise: `similarity = 1.0 − MAD / 255`.
//!
//! A true proxy pair (same clip, different resolution/codec) produces a
//! near-zero residual — the content is identical and only compression
//! artefacts remain — so similarity is typically 0.97+.  Two different
//! shots of visually-similar content (e.g. two different night-sky clips
//! both processed through Aurora + Topaz) have distinct luminance
//! structure at thumbnail resolution and score well below 0.90.
//!
//! ## Why not dHash?
//!
//! The previous dHash approach folded each thumbnail into 64 bits before
//! comparison. At that extreme resolution two unrelated astrophotography
//! clips processed through the same pipeline looked identical (0.82
//! similarity) because both were dark-sky + subtle gradient at 9×8.
//! Direct pixel comparison catches that: the exact star positions,
//! horizon lines, and noise patterns differ.
//!
//! ## Aspect-ratio handling
//!
//! If the two thumbnails have different aspect ratios (e.g. the source
//! clip is 3:2 and the proxy export is 16:9), `resize_exact` squishes
//! whichever is larger to match the smaller's dimensions.  For a 16:9
//! crop-and-export of a 3:2 original this works well in practice: the
//! 16:9 thumbnail shows the centre strip, and squishing the 3:2 thumbnail
//! to 16:9 also emphasises the centre.  Pairs that were cropped or
//! pillarboxed differently score lower — but the frame-count gate already
//! requires very similar durations, so radically-different crop operations
//! are rare in practice.

use image::{DynamicImage, GenericImageView, GrayImage};
use std::path::Path;

/// Compute the similarity between two thumbnail files.
///
/// Returns `None` if either file cannot be opened/decoded.
/// Returns a value in `[0.0, 1.0]`:
/// - `1.0` — pixel-identical after scaling
/// - `≥ 0.9` — proxy threshold (same clip, different resolution)
/// - `< 0.5` — clearly unrelated content
pub fn similarity_files(path_a: &Path, path_b: &Path) -> Option<f64> {
    let a = image::open(path_a).ok()?;
    let b = image::open(path_b).ok()?;
    Some(similarity(&a, &b))
}

/// Compute similarity between two in-memory images.
///
/// The larger image (by pixel count) is scaled down to the smaller's
/// exact dimensions before comparison.
pub fn similarity(a: &DynamicImage, b: &DynamicImage) -> f64 {
    let (wa, ha) = a.dimensions();
    let (wb, hb) = b.dimensions();

    // Pick the smaller image's dimensions as the comparison target.
    let (target_w, target_h) = if (wa as u64 * ha as u64) <= (wb as u64 * hb as u64) {
        (wa, ha)
    } else {
        (wb, hb)
    };

    if target_w == 0 || target_h == 0 {
        return 0.0;
    }

    let a_gray = to_gray_at(a, target_w, target_h);
    let b_gray = to_gray_at(b, target_w, target_h);

    mad_similarity(&a_gray, &b_gray)
}

// ---- Internals -------------------------------------------------------

/// Resize `img` to `(w, h)` and convert to luma8.
fn to_gray_at(img: &DynamicImage, w: u32, h: u32) -> GrayImage {
    let (iw, ih) = img.dimensions();
    if iw == w && ih == h {
        // Already the right size — skip the resize, just convert.
        img.to_luma8()
    } else {
        img.resize_exact(w, h, image::imageops::FilterType::Lanczos3)
            .to_luma8()
    }
}

/// Mean-absolute-difference similarity for two same-sized GrayImages.
/// Returns `1.0 - mean(|A_i - B_i|) / 255`.
fn mad_similarity(a: &GrayImage, b: &GrayImage) -> f64 {
    debug_assert_eq!(a.dimensions(), b.dimensions());

    let total: u64 = a
        .pixels()
        .zip(b.pixels())
        .map(|(pa, pb)| (pa[0] as i32 - pb[0] as i32).unsigned_abs() as u64)
        .sum();

    let pixel_count = (a.width() as u64) * (a.height() as u64);
    if pixel_count == 0 {
        return 0.0;
    }

    let mean_diff = total as f64 / (pixel_count as f64 * 255.0);
    1.0 - mean_diff
}

// ---- Tests -----------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use image::{DynamicImage, ImageBuffer, Rgb};

    fn solid(w: u32, h: u32, value: u8) -> DynamicImage {
        DynamicImage::ImageRgb8(ImageBuffer::from_pixel(w, h, Rgb([value, value, value])))
    }

    fn gradient(w: u32, h: u32) -> DynamicImage {
        let buf = ImageBuffer::from_fn(w, h, |x, _| {
            let v = ((x * 255) / w.max(1)) as u8;
            Rgb([v, v, v])
        });
        DynamicImage::ImageRgb8(buf)
    }

    #[test]
    fn identical_images_score_one() {
        let a = gradient(480, 270);
        let b = gradient(480, 270);
        let s = similarity(&a, &b);
        assert!((s - 1.0).abs() < 1e-9, "identical → {}", s);
    }

    #[test]
    fn same_content_different_resolution_scores_high() {
        // A gradient at two resolutions — same content, different pixel count.
        // After scaling down to the smaller, the residual should be tiny.
        let large = gradient(1920, 1080);
        let small = gradient(480, 270);
        let s = similarity(&large, &small);
        assert!(s > 0.95, "proxy pair scored only {:.4}", s);
    }

    #[test]
    fn different_content_scores_low() {
        // Solid black vs solid white — maximum possible difference.
        let black = solid(480, 270, 0);
        let white = solid(480, 270, 255);
        let s = similarity(&black, &white);
        assert!((s - 0.0).abs() < 1e-9, "black/white → {}", s);
    }

    #[test]
    fn slight_brightness_difference_scores_high() {
        // Brightness-shifted version of the same gradient — simulates a
        // color-grade variant or codec exposure difference.
        let base = gradient(480, 270);
        let shifted = DynamicImage::ImageRgb8(ImageBuffer::from_fn(480, 270, |x, _| {
            let v = (((x * 255) / 480) as u8).saturating_add(10);
            Rgb([v, v, v])
        }));
        let s = similarity(&base, &shifted);
        // A uniform +10/255 ≈ 3.9 % shift → similarity ≈ 0.96
        assert!(s > 0.93, "brightness-shifted pair scored {:.4}", s);
    }

    #[test]
    fn unrelated_gradients_score_below_threshold() {
        // Forward vs reverse gradient — unrelated content.
        let fwd = gradient(480, 270);
        let rev = DynamicImage::ImageRgb8(ImageBuffer::from_fn(480, 270, |x, _| {
            let v = (255 - ((x * 255) / 480).min(255)) as u8;
            Rgb([v, v, v])
        }));
        let s = similarity(&fwd, &rev);
        assert!(s < 0.90, "opposite gradients scored {:.4}", s);
    }
}
