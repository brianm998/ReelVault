// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

use crate::concurrency::acquire_ffmpeg_permit;
use crate::db::Database;
use crate::error::{Result, VideoRoomError};
use std::path::{Path, PathBuf};
use std::process::Command;
use uuid::Uuid;

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
            return Err(VideoRoomError::FfmpegError(
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

        let _permit = acquire_ffmpeg_permit();
        let output = Command::new("ffmpeg")
            .args(&[
                "-v",
                "error",
                "-ss",
                &seek_pos,
                "-i",
                video_path.to_str().unwrap_or(""),
                "-vframes",
                "1",
                "-vf",
                "scale=min(400\\,iw):-1",
                "-q:v",
                "5",
                temp_path.to_str().unwrap_or(""),
            ])
            .output()
            .map_err(|e| VideoRoomError::FfmpegError(format!("Failed to run ffmpeg: {}", e)))?;

        if !output.status.success() {
            let error_msg = String::from_utf8_lossy(&output.stderr);
            return Err(VideoRoomError::ThumbnailGenerationFailed(error_msg.to_string()));
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
            .args(&[
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
            .map_err(|e| VideoRoomError::FfmpegError(format!("Failed to resize thumbnail: {}", e)))?;

        if !output.status.success() {
            let error_msg = String::from_utf8_lossy(&output.stderr);
            return Err(VideoRoomError::ThumbnailGenerationFailed(error_msg.to_string()));
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
                .map_err(|e| VideoRoomError::IoError(e))?;
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
            return Err(VideoRoomError::FfmpegError(
                "ffmpeg not found in PATH".to_string(),
            ));
        }
        if duration_secs <= 0.5 {
            // Too short to meaningfully scrub; skip.
            return Ok(());
        }

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
                .args(&[
                    "-v",
                    "error",
                    "-ss",
                    &format!("{:.3}", seek_pos),
                    "-i",
                    video_path.to_str().unwrap_or(""),
                    "-frames:v",
                    "1",
                    "-vf",
                    &format!("scale=min({}\\,iw):-1", Self::SCRUB_WIDTH),
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
            return Err(VideoRoomError::FfmpegError(
                "ffmpeg not found in PATH. Please install FFmpeg.".to_string(),
            ));
        }

        // Calculate new dimensions
        let output = Command::new("ffprobe")
            .args(&[
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
            .map_err(|e| VideoRoomError::FfmpegError(format!("Failed to get video dimensions: {}", e)))?;

        if !output.status.success() {
            return Err(VideoRoomError::FfmpegError("Failed to probe video".to_string()));
        }

        let dims = String::from_utf8(output.stdout)
            .map_err(|e| VideoRoomError::FfmpegError(format!("Invalid UTF-8: {}", e)))?;

        let parts: Vec<&str> = dims.trim().split(',').collect();
        if parts.len() < 2 {
            return Err(VideoRoomError::FfmpegError("Could not parse video dimensions".to_string()));
        }

        let width: f64 = parts[0].parse()
            .map_err(|_| VideoRoomError::FfmpegError("Invalid width".to_string()))?;
        let height: f64 = parts[1].parse()
            .map_err(|_| VideoRoomError::FfmpegError("Invalid height".to_string()))?;

        let new_width = ((width * scale) as i32 / 2) * 2; // Round to even
        let new_height = ((height * scale) as i32 / 2) * 2;

        let filter = format!("scale={}:{}", new_width, new_height);

        let output = Command::new("ffmpeg")
            .args(&[
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
            .map_err(|e| VideoRoomError::FfmpegError(format!("Failed to generate proxy: {}", e)))?;

        if !output.status.success() {
            let error_msg = String::from_utf8_lossy(&output.stderr);
            return Err(VideoRoomError::FfmpegError(error_msg.to_string()));
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
