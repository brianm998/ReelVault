// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

use crate::concurrency::acquire_ffmpeg_permit;
use crate::db::Database;
use crate::error::{Result, ReelVaultError};
use std::path::{Path, PathBuf};
use std::process::Command;

pub struct ThumbnailGenerator;

impl ThumbnailGenerator {
    // Standard thumbnail sizes
    pub const SMALL_WIDTH: i32 = 200;
    pub const MEDIUM_WIDTH: i32 = 400;
    pub const LARGE_WIDTH: i32 = 800;

    /// Number of evenly-distributed frames generated per video for Lightroom-style
    /// hover-scrubbing in the grid.
    pub const SCRUB_FRAME_COUNT: usize = 10;
    pub const SCRUB_WIDTH: i32 = 320;

    pub fn generate(
        _db: &Database,
        video_path: &Path,
        video_id: &str,
        cache_dir: &Path,
        duration_secs: f64,
    ) -> Result<()> {
        if !Self::ffmpeg_available() {
            return Err(ReelVaultError::FfmpegError(
                "ffmpeg not found in PATH. Please install FFmpeg.".to_string(),
            ));
        }

        // Extract one frame from middle of video
        let thumbnail_frame = Self::extract_frame(video_path, cache_dir, video_id, duration_secs)?;

        // Generate different sizes
        Self::generate_size(
            &thumbnail_frame,
            video_id,
            cache_dir,
            "small",
            Self::SMALL_WIDTH,
        )?;
        Self::generate_size(
            &thumbnail_frame,
            video_id,
            cache_dir,
            "medium",
            Self::MEDIUM_WIDTH,
        )?;
        Self::generate_size(
            &thumbnail_frame,
            video_id,
            cache_dir,
            "large",
            Self::LARGE_WIDTH,
        )?;

        // Clean up temp frame
        let _ = std::fs::remove_file(&thumbnail_frame);

        Ok(())
    }

    fn extract_frame(video_path: &Path, cache_dir: &Path, video_id: &str, duration_secs: f64) -> Result<PathBuf> {
        // Extract frame at 50% through the video
        let temp_path = cache_dir.join(format!("{}_temp.jpg", video_id));

        // Calculate seek position at 50% of duration
        let seek_pos = if duration_secs > 0.0 {
            (duration_secs * 0.5).to_string()
        } else {
            "0".to_string()
        };

        let color_info = probe_color_info(video_path);
        let vf = build_thumbnail_vf(&color_info, "scale=min(400\\,iw):-1");

        let _permit = acquire_ffmpeg_permit();
        let output = Command::new("ffmpeg")
            .args([
                "-v",
                "error",
                "-ss",
                &seek_pos,
                "-i",
                video_path.to_str().unwrap_or(""),
                "-vframes",
                "1",
                "-vf",
                &vf,
                "-q:v",
                "5",
                temp_path.to_str().unwrap_or(""),
            ])
            .output()
            .map_err(|e| ReelVaultError::FfmpegError(format!("Failed to run ffmpeg: {}", e)))?;

        if !output.status.success() {
            let error_msg = String::from_utf8_lossy(&output.stderr);
            return Err(ReelVaultError::ThumbnailGenerationFailed(error_msg.to_string()));
        }

        Ok(temp_path)
    }

    fn generate_size(
        frame_path: &Path,
        video_id: &str,
        cache_dir: &Path,
        size_name: &str,
        width: i32,
    ) -> Result<()> {
        let output_path = cache_dir.join(format!("{}_{}.jpg", video_id, size_name));

        let output = Command::new("ffmpeg")
            .args([
                "-v",
                "error",
                "-i",
                frame_path.to_str().unwrap_or(""),
                "-vf",
                &format!("scale={}:-1", width),
                "-q:v",
                "5",
                output_path.to_str().unwrap_or(""),
            ])
            .output()
            .map_err(|e| ReelVaultError::FfmpegError(format!("Failed to resize thumbnail: {}", e)))?;

        if !output.status.success() {
            let error_msg = String::from_utf8_lossy(&output.stderr);
            return Err(ReelVaultError::ThumbnailGenerationFailed(error_msg.to_string()));
        }

        Ok(())
    }

    fn ffmpeg_available() -> bool {
        Command::new("ffmpeg")
            .arg("-version")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }

    pub fn get_thumbnail(
        cache_dir: &Path,
        video_id: &str,
        size: &str,
    ) -> Result<Option<Vec<u8>>> {
        let path = cache_dir.join(format!("{}_{}.jpg", video_id, size));

        if path.exists() {
            let data = std::fs::read(&path)
                .map_err(ReelVaultError::IoError)?;
            Ok(Some(data))
        } else {
            Ok(None)
        }
    }

    pub fn cleanup_thumbnails(cache_dir: &Path, video_id: &str) -> Result<()> {
        for size in &["small", "medium", "large"] {
            let path = cache_dir.join(format!("{}_{}.jpg", video_id, size));
            let _ = std::fs::remove_file(path);
        }
        for i in 0..Self::SCRUB_FRAME_COUNT {
            let path = cache_dir.join(format!("{}_scrub_{}.jpg", video_id, i));
            let _ = std::fs::remove_file(path);
        }
        Ok(())
    }

    /// Generate evenly-spaced scrub frames (for the Lightroom-style hover
    /// preview). Frames are sampled between 5% and 95% of the video duration
    /// so the first and last samples don't land on black/leader frames.
    ///
    /// Frames are stored as `{video_id}_scrub_{0..N-1}.jpg` and served via the
    /// same `get_thumbnail` endpoint using size = "scrub_N".
    pub fn generate_scrub_thumbnails(
        video_path: &Path,
        video_id: &str,
        cache_dir: &Path,
        duration_secs: f64,
    ) -> Result<()> {
        if !Self::ffmpeg_available() {
            return Err(ReelVaultError::FfmpegError(
                "ffmpeg not found in PATH".to_string(),
            ));
        }
        if duration_secs <= 0.5 {
            // Too short to meaningfully scrub; skip.
            return Ok(());
        }

        // Probe color info once — applied to every scrub frame from this video.
        let color_info = probe_color_info(video_path);
        let scrub_scale = format!("scale=min({}\\,iw):-1", Self::SCRUB_WIDTH);
        let vf = build_thumbnail_vf(&color_info, &scrub_scale);

        let count = Self::SCRUB_FRAME_COUNT;
        for i in 0..count {
            let output = cache_dir.join(format!("{}_scrub_{}.jpg", video_id, i));
            if output.exists() {
                continue;
            }

            // Distribute frames across 5%..95% of the video. With 10 frames,
            // that gives positions at 5%, 15%, 25%, ..., 95%.
            let pct = if count > 1 {
                0.05 + (i as f64 / (count - 1) as f64) * 0.9
            } else {
                0.5
            };
            let seek_pos = duration_secs * pct;

            // Single ffmpeg call: seek (fast input-side seek), extract one
            // frame, scale, write JPEG. ~50–500ms per call typically. The
            // permit is dropped at the end of the loop iteration, freeing
            // a slot for another concurrent ffmpeg run.
            let _permit = acquire_ffmpeg_permit();
            let result = Command::new("ffmpeg")
                .args([
                    "-v",
                    "error",
                    "-ss",
                    &format!("{:.3}", seek_pos),
                    "-i",
                    video_path.to_str().unwrap_or(""),
                    "-frames:v",
                    "1",
                    "-vf",
                    &vf,
                    "-q:v",
                    "6",
                    "-y",
                    output.to_str().unwrap_or(""),
                ])
                .output();

            match result {
                Ok(o) if o.status.success() => {}
                Ok(o) => {
                    let msg = String::from_utf8_lossy(&o.stderr);
                    tracing::warn!(
                        "Scrub frame {} for {} (t={:.3}s) failed: {}",
                        i,
                        video_id,
                        seek_pos,
                        msg
                    );
                }
                Err(e) => {
                    tracing::warn!("ffmpeg invocation failed: {}", e);
                }
            }
        }
        Ok(())
    }
}

/// Color metadata probed from a single video stream — used to decide whether
/// thumbnail extraction needs a tonemap/colorspace conversion step.
#[derive(Debug, Default, Clone)]
struct ColorInfo {
    codec_name: String,
    pix_fmt: String,
    color_space: String,
    color_transfer: String,
    color_primaries: String,
}

/// Run ffprobe once to fetch the fields needed by [`build_thumbnail_vf`].
/// Cheap (~30ms) and tolerant of failure — on any error the returned
/// `ColorInfo` is all-empty, which makes [`build_thumbnail_vf`] fall back
/// to the plain scale filter (preserving prior behavior).
fn probe_color_info(video_path: &Path) -> ColorInfo {
    let mut info = ColorInfo::default();
    let output = Command::new("ffprobe")
        .args([
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=codec_name,pix_fmt,color_space,color_transfer,color_primaries",
            "-of",
            "default=nw=1",
            video_path.to_str().unwrap_or(""),
        ])
        .output();
    let Ok(out) = output else { return info };
    if !out.status.success() {
        return info;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    for line in text.lines() {
        if let Some((k, v)) = line.split_once('=') {
            let v = v.trim().to_string();
            match k.trim() {
                "codec_name" => info.codec_name = v,
                "pix_fmt" => info.pix_fmt = v,
                "color_space" => info.color_space = v,
                "color_transfer" => info.color_transfer = v,
                "color_primaries" => info.color_primaries = v,
                _ => {}
            }
        }
    }
    info
}

/// Build the `-vf` chain for thumbnail extraction.
///
/// For ordinary Rec.709 / sRGB SDR sources we keep the prior behavior:
/// just the caller-supplied scale filter. For log-encoded or wide-gamut
/// sources (ProRes RAW, BT.2020, HLG/PQ HDR, V-Log) we prepend a
/// `colorspace`/`tonemap` step so the resulting JPEG matches what
/// playback shows instead of baking the log curve into 8-bit sRGB.
///
/// This ffmpeg build (8.x on macOS Homebrew) ships without `zscale`, so
/// proper PQ/HLG → SDR tonemapping isn't available. We approximate by
/// running PQ/HLG through `colorspace` as if it were `bt2020-10` — not a
/// real tonemap, but better than leaving the EOTF baked into the JPEG.
/// True `tonemap` is used for ProRes RAW since its decoder emits linear
/// scene-referred floats that the filter can consume directly.
fn build_thumbnail_vf(ci: &ColorInfo, scale_filter: &str) -> String {
    match tonemap_prefix(ci) {
        Some(prefix) => format!("{},{}", prefix, scale_filter),
        None => scale_filter.to_string(),
    }
}

fn tonemap_prefix(ci: &ColorInfo) -> Option<String> {
    let trc = ci.color_transfer.as_str();
    let prim = ci.color_primaries.as_str();
    let space = ci.color_space.as_str();
    let codec = ci.codec_name.as_str();
    let pix = ci.pix_fmt.as_str();

    // ProRes RAW: decoder emits scene-referred linear data. `tonemap` is
    // the right tool; `format=gbrpf32le` ensures the filter sees linear
    // float RGB regardless of the decoder's native output layout.
    if codec == "prores_raw" || pix.starts_with("gbrpf32") {
        return Some(
            "format=gbrpf32le,tonemap=tonemap=hable:desat=0,format=yuv420p".to_string(),
        );
    }

    // HDR transfer functions the `colorspace` filter can't natively
    // invert (PQ / HLG / DCI). Without zscale we can't do a real
    // tonemap; treat as bt2020-10 so the inverse gamma at least lands
    // in a reasonable display range. Still better than baking the
    // EOTF into the JPEG.
    let unsupported_hdr_trc = matches!(trc, "smpte2084" | "arib-std-b67" | "smpte428");
    if unsupported_hdr_trc {
        return Some(
            "colorspace=all=bt709:iall=bt2020:itrc=bt2020-10,format=yuv420p".to_string(),
        );
    }

    // BT.2020 wide-gamut SDR or explicit log/linear transfer.
    let wide_gamut = prim == "bt2020"
        || space == "bt2020nc"
        || space == "bt2020c"
        || matches!(trc, "bt2020-10" | "bt2020-12" | "linear" | "vlog");
    if wide_gamut {
        let itrc = match trc {
            "linear" | "bt2020-10" | "bt2020-12" | "vlog" => trc,
            _ => "bt2020-10",
        };
        return Some(format!(
            "colorspace=all=bt709:iall=bt2020:itrc={},format=yuv420p",
            itrc
        ));
    }

    None
}

#[cfg(test)]
mod color_tests {
    use super::*;

    fn ci(codec: &str, pix: &str, space: &str, trc: &str, prim: &str) -> ColorInfo {
        ColorInfo {
            codec_name: codec.into(),
            pix_fmt: pix.into(),
            color_space: space.into(),
            color_transfer: trc.into(),
            color_primaries: prim.into(),
        }
    }

    #[test]
    fn sdr_rec709_passes_through_unchanged() {
        let info = ci("h264", "yuv420p", "bt709", "bt709", "bt709");
        let vf = build_thumbnail_vf(&info, "scale=400:-1");
        assert_eq!(vf, "scale=400:-1");
    }

    #[test]
    fn sdr_smpte170m_passes_through_unchanged() {
        // The "BT2020F"-named ProRes proxies on disk actually carry
        // smpte170m/bt709 tags — they must NOT be tone-mapped.
        let info = ci("prores", "yuv422p10le", "smpte170m", "bt709", "smpte170m");
        let vf = build_thumbnail_vf(&info, "scale=400:-1");
        assert_eq!(vf, "scale=400:-1");
    }

    #[test]
    fn prores_raw_triggers_tonemap() {
        let info = ci("prores_raw", "gbrpf32le", "", "", "");
        let vf = build_thumbnail_vf(&info, "scale=400:-1");
        assert!(vf.starts_with("format=gbrpf32le,tonemap="));
        assert!(vf.ends_with("scale=400:-1"));
    }

    #[test]
    fn bt2020_pq_uses_colorspace_fallback() {
        let info = ci("hevc", "yuv420p10le", "bt2020nc", "smpte2084", "bt2020");
        let vf = build_thumbnail_vf(&info, "scale=400:-1");
        assert!(vf.contains("colorspace=all=bt709:iall=bt2020"));
        assert!(vf.ends_with("scale=400:-1"));
    }

    #[test]
    fn bt2020_sdr_uses_colorspace_with_native_trc() {
        let info = ci("hevc", "yuv420p10le", "bt2020nc", "bt2020-10", "bt2020");
        let vf = build_thumbnail_vf(&info, "scale=400:-1");
        assert!(vf.contains("itrc=bt2020-10"));
    }
}

pub struct ProxyGenerator;

impl ProxyGenerator {
    pub fn needs_proxy(width: i32, height: i32, threshold: i32) -> bool {
        // Check if video is larger than threshold (e.g., 4K = 3840x2160)
        width > 1920 * threshold / 4 || height > 1080 * threshold / 4
    }

    pub fn generate_proxy(
        video_path: &Path,
        scale: f64,
        output_path: &Path,
    ) -> Result<()> {
        if !Self::ffmpeg_available() {
            return Err(ReelVaultError::FfmpegError(
                "ffmpeg not found in PATH. Please install FFmpeg.".to_string(),
            ));
        }

        // Calculate new dimensions
        let output = Command::new("ffprobe")
            .args([
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=width,height",
                "-of",
                "csv=p=0",
                video_path.to_str().unwrap_or(""),
            ])
            .output()
            .map_err(|e| ReelVaultError::FfmpegError(format!("Failed to get video dimensions: {}", e)))?;

        if !output.status.success() {
            return Err(ReelVaultError::FfmpegError("Failed to probe video".to_string()));
        }

        let dims = String::from_utf8(output.stdout)
            .map_err(|e| ReelVaultError::FfmpegError(format!("Invalid UTF-8: {}", e)))?;

        let parts: Vec<&str> = dims.trim().split(',').collect();
        if parts.len() < 2 {
            return Err(ReelVaultError::FfmpegError("Could not parse video dimensions".to_string()));
        }

        let width: f64 = parts[0].parse()
            .map_err(|_| ReelVaultError::FfmpegError("Invalid width".to_string()))?;
        let height: f64 = parts[1].parse()
            .map_err(|_| ReelVaultError::FfmpegError("Invalid height".to_string()))?;

        let new_width = ((width * scale) as i32 / 2) * 2; // Round to even
        let new_height = ((height * scale) as i32 / 2) * 2;

        let filter = format!("scale={}:{}", new_width, new_height);

        let output = Command::new("ffmpeg")
            .args([
                "-v",
                "error",
                "-i",
                video_path.to_str().unwrap_or(""),
                "-vf",
                &filter,
                "-c:v",
                "libx264",
                "-preset",
                "medium",
                "-b:v",
                "2500k",
                "-c:a",
                "aac",
                "-b:a",
                "128k",
                output_path.to_str().unwrap_or(""),
            ])
            .output()
            .map_err(|e| ReelVaultError::FfmpegError(format!("Failed to generate proxy: {}", e)))?;

        if !output.status.success() {
            let error_msg = String::from_utf8_lossy(&output.stderr);
            return Err(ReelVaultError::FfmpegError(error_msg.to_string()));
        }

        Ok(())
    }

    fn ffmpeg_available() -> bool {
        Command::new("ffmpeg")
            .arg("-version")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }
}
