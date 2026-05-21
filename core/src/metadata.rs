// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

use crate::error::{Result, VideoRoomError};
use crate::db::Database;
use serde::{Deserialize, Serialize, Deserializer};
use serde_with::serde_as;
use std::path::Path;
use std::process::Command;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VideoMetadata {
    pub id: String,
    pub video_id: String,
    pub duration_ms: i64,
    pub codec_video: Option<String>,
    pub codec_audio: Option<String>,
    pub width: i32,
    pub height: i32,
    pub fps: f64,
    pub bitrate: i64,
    pub color_space: Option<String>,
    pub hdr: bool,
    pub audio_channels: i32,
    pub audio_sample_rate: i32,
    pub creation_date: Option<i64>,
    pub camera_model: Option<String>,
    pub lens_model: Option<String>,
    pub gps_latitude: Option<f64>,
    pub gps_longitude: Option<f64>,
    pub gps_altitude: Option<f64>,
    pub metadata_json: String,
}

pub struct MetadataExtractor;

impl MetadataExtractor {
    pub fn extract(video_path: &Path) -> Result<FFProbeOutput> {
        // Check if ffprobe is available
        if !Self::ffprobe_available() {
            return Err(VideoRoomError::FfmpegError(
                "ffprobe not found in PATH. Please install FFmpeg.".to_string(),
            ));
        }

        // ffprobe is much lighter than ffmpeg but still counts against the
        // concurrency budget — it does the same kind of network/disk I/O that
        // can saturate a SAN if too many run at once.
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        let output = Command::new("ffprobe")
            .args(&[
                "-v",
                "error",
                "-show_format",
                "-show_streams",
                "-of",
                "json",
                video_path.to_str().unwrap_or(""),
            ])
            .output()
            .map_err(|e| VideoRoomError::FfmpegError(format!("Failed to run ffprobe: {}", e)))?;

        if !output.status.success() {
            let error_msg = String::from_utf8_lossy(&output.stderr);
            return Err(VideoRoomError::MetadataExtractionFailed(error_msg.to_string()));
        }

        let json_str = String::from_utf8(output.stdout)
            .map_err(|e| VideoRoomError::FfmpegError(format!("Invalid UTF-8 from ffprobe: {}", e)))?;

        let probe_output: FFProbeOutput = serde_json::from_str(&json_str)
            .map_err(|e| VideoRoomError::MetadataExtractionFailed(format!("Failed to parse ffprobe JSON: {}", e)))?;

        Ok(probe_output)
    }

    pub fn store_metadata(
        db: &Database,
        video_id: &str,
        probe_output: &FFProbeOutput,
        _file_size: i64,
    ) -> Result<()> {
        let (video_stream, format) = Self::parse_probe_output(probe_output)?;

        // Extract technical metadata
        let duration_ms = (format.duration.unwrap_or(0.0) * 1000.0) as i64;
        let width = video_stream.width.unwrap_or(0);
        let height = video_stream.height.unwrap_or(0);
        let fps = Self::parse_fps(&video_stream.r_frame_rate);
        let bitrate = format.bit_rate.unwrap_or(0);
        let codec_video = video_stream.codec_name.clone();
        let codec_audio = probe_output.streams
            .iter()
            .find(|s| s.codec_type == Some("audio".to_string()))
            .and_then(|s| s.codec_name.clone());

        // Extract EXIF data from tags - check format tags first (where camera/lens usually live for MOV/MP4)
        let camera_model = Self::build_camera_name(&format.tags)
            .or_else(|| Self::build_camera_name(&video_stream.tags));
        let lens_model = Self::extract_lens(&format.tags)
            .or_else(|| Self::extract_lens(&video_stream.tags));
        let creation_date = Self::extract_creation_date(&format.tags)
            .or_else(|| Self::extract_creation_date(&video_stream.tags));

        // Audio info
        let audio_stream = probe_output.streams
            .iter()
            .find(|s| s.codec_type == Some("audio".to_string()));
        let audio_channels = audio_stream.and_then(|s| s.channels).unwrap_or(0);
        let audio_sample_rate = audio_stream
            .and_then(|s| s.sample_rate.as_ref().map(|r| r.parse::<i32>().unwrap_or(0)))
            .unwrap_or(0);

        // Color space
        let color_space = video_stream.color_space.clone();

        // Frame count from video stream (fall back to duration*fps if missing)
        let frame_count: i64 = video_stream
            .nb_frames
            .unwrap_or_else(|| {
                if fps > 0.0 {
                    ((duration_ms as f64 / 1000.0) * fps).round() as i64
                } else {
                    0
                }
            });

        // Metadata JSON for future expansion
        let metadata_json = serde_json::to_string(probe_output)
            .unwrap_or_else(|_| "{}".to_string());

        let conn = db.get_connection()?;

        conn.execute(
            "INSERT INTO metadata
             (video_id, duration_ms, frame_count, codec_video, codec_audio, width, height, fps, bitrate,
              color_space, hdr, audio_channels, audio_sample_rate, creation_date, camera_model,
              lens_model, metadata_json)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT(video_id) DO UPDATE SET
             duration_ms=excluded.duration_ms,
             frame_count=excluded.frame_count,
             codec_video=excluded.codec_video,
             codec_audio=excluded.codec_audio,
             width=excluded.width,
             height=excluded.height,
             fps=excluded.fps,
             bitrate=excluded.bitrate,
             color_space=excluded.color_space,
             audio_channels=excluded.audio_channels,
             audio_sample_rate=excluded.audio_sample_rate,
             creation_date=excluded.creation_date,
             camera_model=excluded.camera_model,
             lens_model=excluded.lens_model,
             metadata_json=excluded.metadata_json",
            rusqlite::params![
                video_id,
                duration_ms,
                frame_count,
                codec_video,
                codec_audio,
                width,
                height,
                fps,
                bitrate,
                color_space,
                0, // HDR - TODO: detect HDR
                audio_channels,
                audio_sample_rate,
                creation_date,
                camera_model,
                lens_model,
                metadata_json
            ],
        )
        .map_err(|e| VideoRoomError::DatabaseError(e.to_string()))?;

        tracing::debug!("Stored metadata for video {}: {}x{}@{:.2}fps", video_id, width, height, fps);

        Ok(())
    }

    fn ffprobe_available() -> bool {
        Command::new("ffprobe")
            .arg("-version")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }

    /// Embed an ISO 6709 `location` tag into a video file via ffmpeg. Uses
    /// `-c copy` so streams are passed through without re-encoding — a few
    /// hundred kilobytes get rewritten (the moov atom on MP4/MOV), nothing
    /// expensive. We write to a sibling temp file and atomically rename
    /// over the original so an interrupted run can't corrupt the source.
    ///
    /// `altitude` of 0 is encoded as "/" with no third component, which the
    /// QuickTime/MP4 spec treats as "altitude unknown". Other readers
    /// (Lightroom, Photos.app, etc.) all accept this form.
    pub fn write_location_tag(
        video_path: &str,
        latitude: f64,
        longitude: f64,
        altitude: f64,
    ) -> Result<()> {
        if !Self::ffmpeg_available() {
            return Err(VideoRoomError::MetadataExtractionFailed(
                "ffmpeg not available on PATH; cannot embed location in file".to_string(),
            ));
        }

        // ISO 6709 string: "±DD.DDDD±DDD.DDDD[±AAAA]/". The leading sign on
        // each component is mandatory, hence the explicit `+`/`-` formatting.
        let iso6709 = if altitude.abs() < f64::EPSILON {
            format!("{:+.6}{:+.6}/", latitude, longitude)
        } else {
            format!("{:+.6}{:+.6}{:+.3}/", latitude, longitude, altitude)
        };

        let path = std::path::Path::new(video_path);
        let dir = path
            .parent()
            .ok_or_else(|| VideoRoomError::MetadataExtractionFailed(
                "video has no parent directory".to_string(),
            ))?;
        let ext = path
            .extension()
            .and_then(|s| s.to_str())
            .unwrap_or("mp4");
        let temp_path = dir.join(format!(
            ".videoroom-loc-{}.{}",
            uuid::Uuid::new_v4(),
            ext
        ));

        // Throttle the ffmpeg invocation like every other ffmpeg call.
        let _permit = crate::concurrency::acquire_ffmpeg_permit();

        let status = Command::new("ffmpeg")
            .arg("-nostdin")
            .arg("-loglevel").arg("error")
            .arg("-y")
            .arg("-i").arg(video_path)
            .arg("-c").arg("copy")
            .arg("-map_metadata").arg("0")
            // QuickTime/MP4 location atom. ffmpeg also accepts
            // `-metadata:s:v location=...` but the global form covers both
            // container-level and stream-level placements correctly.
            .arg("-metadata").arg(format!("location={}", iso6709))
            .arg("-metadata").arg(format!("location-eng={}", iso6709))
            .arg(&temp_path)
            .status();

        let ok = match status {
            Ok(s) => s.success(),
            Err(e) => {
                let _ = std::fs::remove_file(&temp_path);
                return Err(VideoRoomError::MetadataExtractionFailed(format!(
                    "ffmpeg failed to spawn: {}", e
                )));
            }
        };

        if !ok {
            let _ = std::fs::remove_file(&temp_path);
            return Err(VideoRoomError::MetadataExtractionFailed(
                "ffmpeg returned a non-zero exit code while writing location".to_string(),
            ));
        }

        // Atomic-ish swap. rename(2) is atomic on the same filesystem,
        // which is the common case (temp is a sibling of the original).
        std::fs::rename(&temp_path, video_path).map_err(|e| {
            // Try to clean the temp file up if the rename failed.
            let _ = std::fs::remove_file(&temp_path);
            VideoRoomError::MetadataExtractionFailed(format!(
                "Failed to atomically replace {}: {}", video_path, e
            ))
        })?;

        Ok(())
    }

    /// Embed an ISO 8601 `creation_time` tag into a video file via ffmpeg.
    /// Same atomic temp-file dance as [`write_location_tag`] — streams are
    /// stream-copied so the rewrite is fast and lossless. Accepts a Unix
    /// millisecond timestamp; formatted as `YYYY-MM-DDTHH:MM:SS.sssZ`,
    /// which is what QuickTime/MP4 readers expect (and ffprobe writes when
    /// reading back).
    pub fn write_creation_time_tag(video_path: &str, timestamp_ms: i64) -> Result<()> {
        if !Self::ffmpeg_available() {
            return Err(VideoRoomError::MetadataExtractionFailed(
                "ffmpeg not available on PATH; cannot embed creation time in file".to_string(),
            ));
        }

        // Chrono is already a dependency — use it for a robust UTC string.
        let dt = chrono::DateTime::<chrono::Utc>::from_timestamp_millis(timestamp_ms)
            .ok_or_else(|| VideoRoomError::MetadataExtractionFailed(
                format!("timestamp_ms {} is out of range", timestamp_ms)
            ))?;
        let iso = dt.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string();

        let path = std::path::Path::new(video_path);
        let dir = path
            .parent()
            .ok_or_else(|| VideoRoomError::MetadataExtractionFailed(
                "video has no parent directory".to_string(),
            ))?;
        let ext = path
            .extension()
            .and_then(|s| s.to_str())
            .unwrap_or("mp4");
        let temp_path = dir.join(format!(
            ".videoroom-date-{}.{}",
            uuid::Uuid::new_v4(),
            ext
        ));

        let _permit = crate::concurrency::acquire_ffmpeg_permit();

        let status = Command::new("ffmpeg")
            .arg("-nostdin")
            .arg("-loglevel").arg("error")
            .arg("-y")
            .arg("-i").arg(video_path)
            .arg("-c").arg("copy")
            .arg("-map_metadata").arg("0")
            .arg("-metadata").arg(format!("creation_time={}", iso))
            .arg(&temp_path)
            .status();

        let ok = match status {
            Ok(s) => s.success(),
            Err(e) => {
                let _ = std::fs::remove_file(&temp_path);
                return Err(VideoRoomError::MetadataExtractionFailed(format!(
                    "ffmpeg failed to spawn: {}", e
                )));
            }
        };

        if !ok {
            let _ = std::fs::remove_file(&temp_path);
            return Err(VideoRoomError::MetadataExtractionFailed(
                "ffmpeg returned a non-zero exit code while writing creation_time".to_string(),
            ));
        }

        std::fs::rename(&temp_path, video_path).map_err(|e| {
            let _ = std::fs::remove_file(&temp_path);
            VideoRoomError::MetadataExtractionFailed(format!(
                "Failed to atomically replace {}: {}", video_path, e
            ))
        })?;

        Ok(())
    }

    fn ffmpeg_available() -> bool {
        Command::new("ffmpeg")
            .arg("-version")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }

    fn parse_probe_output(probe: &FFProbeOutput) -> Result<(&FFProbeStream, &FFProbeFormat)> {
        let format = &probe.format;
        let video_stream = probe
            .streams
            .iter()
            .find(|s| s.codec_type == Some("video".to_string()))
            .ok_or_else(|| VideoRoomError::MetadataExtractionFailed("No video stream found".to_string()))?;

        Ok((video_stream, format))
    }

    fn parse_fps(r_frame_rate: &Option<String>) -> f64 {
        match r_frame_rate {
            Some(fps_str) => {
                // Handle formats like "30000/1001" (29.97 fps)
                let parts: Vec<&str> = fps_str.split('/').collect();
                if parts.len() == 2 {
                    let num: f64 = parts[0].parse().unwrap_or(0.0);
                    let den: f64 = parts[1].parse().unwrap_or(1.0);
                    num / den
                } else {
                    fps_str.parse().unwrap_or(0.0)
                }
            }
            None => 0.0,
        }
    }

    fn extract_tag(tags: &Option<FFProbeTagMap>, key: &str) -> Option<String> {
        tags.as_ref().and_then(|t| t.get(key).cloned())
    }

    /// Combine `make` and `model` tags into a readable camera name.
    /// Example: "SONY ILCE-7RM3" -> "Sony A7R III" friendly is hard, so just use raw values.
    fn build_camera_name(tags: &Option<FFProbeTagMap>) -> Option<String> {
        let make = Self::extract_tag(tags, "make")
            .or_else(|| Self::extract_tag(tags, "com.apple.quicktime.make"));
        let model = Self::extract_tag(tags, "model")
            .or_else(|| Self::extract_tag(tags, "com.apple.quicktime.model"));

        match (make, model) {
            (Some(make), Some(model)) => {
                // Avoid duplication if model already starts with make
                if model.to_lowercase().starts_with(&make.to_lowercase()) {
                    Some(model)
                } else {
                    Some(format!("{} {}", make, model))
                }
            }
            (None, Some(model)) => Some(model),
            (Some(make), None) => Some(make),
            (None, None) => None,
        }
    }

    /// Look for lens info in various tag formats used by different cameras.
    fn extract_lens(tags: &Option<FFProbeTagMap>) -> Option<String> {
        Self::extract_tag(tags, "lens_model")
            .or_else(|| Self::extract_tag(tags, "com.apple.quicktime.lens.model"))
            .or_else(|| Self::extract_tag(tags, "lens"))
            .or_else(|| Self::extract_tag(tags, "lensmodel"))
    }

    fn extract_creation_date(tags: &Option<FFProbeTagMap>) -> Option<i64> {
        tags.as_ref().and_then(|t| {
            let date_str = t.get("creation_time")
                .or_else(|| t.get("com.apple.quicktime.creationdate"))?;
            // Parse ISO 8601: "2024-01-15T10:30:00.000000Z"
            chrono::DateTime::parse_from_rfc3339(date_str)
                .ok()
                .map(|dt| dt.timestamp_millis())
        })
    }
}

// FFprobe output structures
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FFProbeOutput {
    pub streams: Vec<FFProbeStream>,
    pub format: FFProbeFormat,
}

fn deserialize_f64_from_str<'de, D>(deserializer: D) -> std::result::Result<Option<f64>, D::Error>
where
    D: Deserializer<'de>,
{
    use serde::de::{self, Visitor};
    use std::fmt;

    struct F64Visitor;

    impl<'de> Visitor<'de> for F64Visitor {
        type Value = Option<f64>;

        fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
            formatter.write_str("a f64 or string representation of f64")
        }

        fn visit_f64<E>(self, value: f64) -> std::result::Result<Option<f64>, E>
        where
            E: de::Error,
        {
            Ok(Some(value))
        }

        fn visit_str<E>(self, value: &str) -> std::result::Result<Option<f64>, E>
        where
            E: de::Error,
        {
            value.parse::<f64>().map(Some).map_err(de::Error::custom)
        }

        fn visit_none<E>(self) -> std::result::Result<Option<f64>, E>
        where
            E: de::Error,
        {
            Ok(None)
        }

        fn visit_unit<E>(self) -> std::result::Result<Option<f64>, E>
        where
            E: de::Error,
        {
            Ok(None)
        }
    }

    deserializer.deserialize_any(F64Visitor)
}

fn deserialize_i64_from_str<'de, D>(deserializer: D) -> std::result::Result<Option<i64>, D::Error>
where
    D: Deserializer<'de>,
{
    use serde::de::{self, Visitor};
    use std::fmt;

    struct I64Visitor;

    impl<'de> Visitor<'de> for I64Visitor {
        type Value = Option<i64>;

        fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
            formatter.write_str("an i64 or string representation of i64")
        }

        fn visit_i64<E>(self, value: i64) -> std::result::Result<Option<i64>, E>
        where
            E: de::Error,
        {
            Ok(Some(value))
        }

        fn visit_str<E>(self, value: &str) -> std::result::Result<Option<i64>, E>
        where
            E: de::Error,
        {
            value.parse::<i64>().map(Some).map_err(de::Error::custom)
        }

        fn visit_none<E>(self) -> std::result::Result<Option<i64>, E>
        where
            E: de::Error,
        {
            Ok(None)
        }

        fn visit_unit<E>(self) -> std::result::Result<Option<i64>, E>
        where
            E: de::Error,
        {
            Ok(None)
        }
    }

    deserializer.deserialize_any(I64Visitor)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FFProbeStream {
    pub index: Option<i32>,
    pub codec_type: Option<String>,
    pub codec_name: Option<String>,
    pub width: Option<i32>,
    pub height: Option<i32>,
    pub r_frame_rate: Option<String>,
    pub color_space: Option<String>,
    pub channels: Option<i32>,
    pub sample_rate: Option<String>,
    pub tags: Option<FFProbeTagMap>,
    #[serde(default, deserialize_with = "deserialize_i64_from_str")]
    pub nb_frames: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FFProbeFormat {
    #[serde(deserialize_with = "deserialize_f64_from_str")]
    pub duration: Option<f64>,
    pub size: Option<String>,
    #[serde(deserialize_with = "deserialize_i64_from_str")]
    pub bit_rate: Option<i64>,
    pub tags: Option<FFProbeTagMap>,
}

#[derive(Debug, Clone)]
pub struct FFProbeTagMap(pub std::collections::HashMap<String, String>);

impl<'de> serde::Deserialize<'de> for FFProbeTagMap {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        Ok(FFProbeTagMap(
            std::collections::HashMap::deserialize(deserializer)?,
        ))
    }
}

impl serde::Serialize for FFProbeTagMap {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        self.0.serialize(serializer)
    }
}

impl std::ops::Deref for FFProbeTagMap {
    type Target = std::collections::HashMap<String, String>;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

impl FFProbeTagMap {
    pub fn get(&self, key: &str) -> Option<&String> {
        self.0.get(&key.to_lowercase())
            .or_else(|| self.0.get(key))
    }
}
