// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

//! Perceptual image hashing used to identify "same shot, different
//! resolution" pairs — the heart of proxy auto-detection.
//!
//! Algorithm: **dHash** (difference hash). For each image:
//!   1. Decode to luminance (single 8-bit channel).
//!   2. Letterbox-trim and resize to 9×8 nearest-neighbor. The width is
//!      one larger than the height so we can do row-wise pixel-pair
//!      comparisons.
//!   3. For each row, emit 8 bits where bit_i = (px[i] > px[i+1]).
//!   4. Concatenate the 8 rows into a 64-bit hash.
//!
//! Similarity = `1.0 - hamming_distance(a, b) / 64.0`.
//!
//! dHash is intentionally tolerant of overall brightness and saturation
//! shifts (because it only looks at *local* contrast changes), which is
//! exactly what we want for "is this clip the same as that clip, just
//! transcoded?" comparisons. It's *not* great at distinguishing two
//! related clips with different color grades — for that we have the
//! camera-model and frame-count gates layered on top.
//!
//! Letterboxing: a user may export a 16:9 source clip as a 4:3 proxy
//! that's letterboxed top + bottom (or pillarboxed). Before hashing we
//! detect uniform black bars by sampling the outermost rows and crop
//! them away. That makes the dHash see the *content* aspect on both
//! images regardless of container aspect.

use std::path::Path;

/// 64-bit perceptual hash of a single thumbnail.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DHash(pub u64);

impl DHash {
    /// Hamming distance to another hash. Range 0..=64.
    pub fn distance(self, other: DHash) -> u32 {
        (self.0 ^ other.0).count_ones()
    }

    /// Similarity in [0, 1]. 1.0 = identical, 0.0 = totally different.
    /// The proxy-detection threshold is 0.9 (≤ 6-bit Hamming distance).
    pub fn similarity(self, other: DHash) -> f64 {
        1.0 - (self.distance(other) as f64 / 64.0)
    }
}

/// Compute the dHash of an image file. Returns `None` on decode errors —
/// callers treat that as "can't compare" rather than failing the whole
/// proxy-detection pass.
pub fn hash_file(path: &Path) -> Option<DHash> {
    let img = image::open(path).ok()?;
    Some(hash_image(&img))
}

/// Compute the dHash of an in-memory image. Used both by `hash_file` and
/// by the proxy-detection unit tests (synthetic gradients).
pub fn hash_image(img: &image::DynamicImage) -> DHash {
    // Step 1: convert to single-channel luma.
    let luma = img.to_luma8();

    // Step 2: detect + strip letterboxing. We sample a thin band from
    // each edge and trust that uniform near-black pixels (< 16/255) are
    // bars rather than content. The center of a real frame is unlikely
    // to be black uniformly.
    let (left, top, right, bottom) = letterbox_bounds(&luma);
    let cropped = image::imageops::crop_imm(&luma, left, top, right - left, bottom - top);

    // Step 3: resize to 9×8 for the difference hash.
    let small = image::imageops::resize(
        &cropped.to_image(),
        9,
        8,
        image::imageops::FilterType::Triangle,
    );

    // Step 4: pack the 64-bit hash.
    let mut bits: u64 = 0;
    let mut idx = 0;
    for y in 0..8 {
        for x in 0..8 {
            let lhs = small.get_pixel(x, y)[0];
            let rhs = small.get_pixel(x + 1, y)[0];
            if lhs > rhs {
                bits |= 1u64 << idx;
            }
            idx += 1;
        }
    }
    DHash(bits)
}

/// Detect uniform-dark borders. Returns the content rectangle
/// `(left, top, right, bottom)` (in pixels) — passed straight to
/// `crop_imm`.
///
/// Implementation: scan inward from each edge a row at a time; stop when
/// at least 5 % of pixels in that line are above the "dark" threshold.
/// On a 1080p frame that's about 50 px of content tolerance, which is
/// enough to ignore the camera vignette without amputating a real dark
/// scene.
fn letterbox_bounds(luma: &image::GrayImage) -> (u32, u32, u32, u32) {
    let (w, h) = luma.dimensions();
    let dark = 16u8;
    let min_content_frac = 0.05;

    let row_is_dark = |y: u32| -> bool {
        let mut bright = 0u32;
        for x in 0..w {
            if luma.get_pixel(x, y)[0] > dark {
                bright += 1;
            }
        }
        (bright as f32 / w as f32) < min_content_frac
    };
    let col_is_dark = |x: u32| -> bool {
        let mut bright = 0u32;
        for y in 0..h {
            if luma.get_pixel(x, y)[0] > dark {
                bright += 1;
            }
        }
        (bright as f32 / h as f32) < min_content_frac
    };

    let mut top = 0;
    while top < h && row_is_dark(top) {
        top += 1;
    }
    let mut bottom = h;
    while bottom > top && row_is_dark(bottom - 1) {
        bottom -= 1;
    }
    let mut left = 0;
    while left < w && col_is_dark(left) {
        left += 1;
    }
    let mut right = w;
    while right > left && col_is_dark(right - 1) {
        right -= 1;
    }

    // Sanity: if we'd crop more than 90 % of the area, the image is
    // genuinely dark — don't crop at all.
    let crop_w = right.saturating_sub(left);
    let crop_h = bottom.saturating_sub(top);
    if (crop_w as u64 * crop_h as u64) < (w as u64 * h as u64) / 10 {
        return (0, 0, w, h);
    }
    (left, top, right, bottom)
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{DynamicImage, ImageBuffer, Rgb};

    fn gradient(w: u32, h: u32) -> DynamicImage {
        let buf = ImageBuffer::from_fn(w, h, |x, _| {
            let v = ((x * 255) / w.max(1)) as u8;
            Rgb([v, v, v])
        });
        DynamicImage::ImageRgb8(buf)
    }

    #[test]
    fn identical_images_hash_identically() {
        let a = gradient(800, 600);
        let b = gradient(800, 600);
        let ha = hash_image(&a);
        let hb = hash_image(&b);
        assert_eq!(ha, hb);
        assert!((ha.similarity(hb) - 1.0).abs() < 1e-6);
    }

    #[test]
    fn same_image_different_resolution_is_similar() {
        let a = gradient(1920, 1080);
        let b = gradient(640, 360);
        let ha = hash_image(&a);
        let hb = hash_image(&b);
        // Resizing the same gradient at different sources should yield
        // the same dHash bits.
        assert!(
            ha.similarity(hb) > 0.9,
            "similarity {} too low for same-gradient pair",
            ha.similarity(hb)
        );
    }

    #[test]
    fn very_different_images_are_dissimilar() {
        // Two opposite gradients shouldn't match.
        let a = gradient(800, 600);
        let b = {
            let buf = ImageBuffer::from_fn(800, 600, |x, _| {
                let v = (255 - ((x * 255) / 800).min(255)) as u8;
                Rgb([v, v, v])
            });
            DynamicImage::ImageRgb8(buf)
        };
        let ha = hash_image(&a);
        let hb = hash_image(&b);
        assert!(
            ha.similarity(hb) < 0.5,
            "opposite-gradient pair has similarity {}",
            ha.similarity(hb)
        );
    }
}
