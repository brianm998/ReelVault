// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

use crate::error::{Result, ReelVaultError};
use crate::db::Database;
use serde::{Deserialize, Serialize, Deserializer};
use std::path::Path;

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
            return Err(ReelVaultError::FfmpegError(
                "ffprobe not found in PATH. Please install FFmpeg.".to_string(),
            ));
        }

        // ffprobe is much lighter than ffmpeg but still counts against the
        // concurrency budget — it does the same kind of network/disk I/O that
        // can saturate a SAN if too many run at once.
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        let output = crate::ffmpeg::ffprobe_command()
            .args([
                "-v",
                "error",
                "-show_format",
                "-show_streams",
                "-of",
                "json",
                video_path.to_str().unwrap_or(""),
            ])
            .output()
            .map_err(|e| ReelVaultError::FfmpegError(format!("Failed to run ffprobe: {}", e)))?;

        if !output.status.success() {
            let error_msg = String::from_utf8_lossy(&output.stderr);
            return Err(ReelVaultError::MetadataExtractionFailed(error_msg.to_string()));
        }

        let json_str = String::from_utf8(output.stdout)
            .map_err(|e| ReelVaultError::FfmpegError(format!("Invalid UTF-8 from ffprobe: {}", e)))?;

        let probe_output: FFProbeOutput = serde_json::from_str(&json_str)
            .map_err(|e| ReelVaultError::MetadataExtractionFailed(format!("Failed to parse ffprobe JSON: {}", e)))?;

        Ok(probe_output)
    }

    pub fn store_metadata(
        db: &Database,
        video_id: &str,
        video_path: &Path,
        probe_output: &FFProbeOutput,
        _file_size: i64,
    ) -> Result<()> {
        let (video_stream, format) = Self::parse_probe_output(probe_output)?;

        // Extract technical metadata
        let duration_ms = (format.duration.unwrap_or(0.0) * 1000.0) as i64;
        let raw_w = video_stream.width.unwrap_or(0);
        let raw_h = video_stream.height.unwrap_or(0);
        // iPhones (and some cameras) record portrait clips as landscape sensor
        // frames and store the display rotation in one of two places:
        //   1. Stream tag "rotate" — older H.264 MOVs (tags["rotate"] = "90")
        //   2. Display-matrix side data — modern HEVC MOVs (iOS 11+, 4K clips).
        //      FFProbe surfaces this as side_data_list[].rotation (integer, e.g. -90).
        //      IMG_4718-style 4K HEVC clips have NO "rotate" tag at all.
        // Check both; prefer the tag to preserve existing behaviour for older clips.
        // Swap width↔height for 90°/270° so every consumer (card aspect-ratio,
        // border drawing, iOS portrait-badge) sees correct display dimensions.
        let rotate_from_tag = video_stream.tags
            .as_ref()
            .and_then(|t| t.get("rotate"))
            .and_then(|v| v.trim().parse::<i32>().ok());

        let rotate_from_matrix = video_stream.side_data_list
            .as_ref()
            .and_then(|sds| {
                sds.iter()
                    .find(|sd| sd.side_data_type.as_deref() == Some("Display Matrix"))
                    .and_then(|sd| sd.rotation)
            });

        let rotate_deg = rotate_from_tag.or(rotate_from_matrix).unwrap_or(0).abs();

        let (width, height) = if rotate_deg == 90 || rotate_deg == 270 {
            (raw_h, raw_w)
        } else {
            (raw_w, raw_h)
        };
        let fps = Self::parse_fps(&video_stream.r_frame_rate);
        let bitrate = format.bit_rate.unwrap_or(0);
        let codec_video = video_stream.codec_name.clone();
        let codec_audio = probe_output.streams
            .iter()
            .find(|s| s.codec_type == Some("audio".to_string()))
            .and_then(|s| s.codec_name.clone());

        // Extract EXIF data from tags - check format tags first (where camera/lens usually live for MOV/MP4)
        // Keep make and model as separate fields so the XMP merge below
        // can fall back per-field (see the camera_model merge note).
        let ff_make = Self::extract_make(&format.tags)
            .or_else(|| Self::extract_make(&video_stream.tags));
        let ff_model = Self::extract_model(&format.tags)
            .or_else(|| Self::extract_model(&video_stream.tags));
        let mut camera_model = combine_make_model(ff_make.as_deref(), ff_model.as_deref());
        let mut lens_model = Self::extract_lens(&format.tags)
            .or_else(|| Self::extract_lens(&video_stream.tags));
        let mut creation_date = Self::extract_creation_date(&format.tags)
            .or_else(|| Self::extract_creation_date(&video_stream.tags));

        // Read an XMP packet from the video file, if one is embedded.
        // ffprobe doesn't see XMP, so this is independent of everything
        // above. XMP wins for fields it provides (it's the richer
        // source — full photo-EXIF, vs. ffprobe's container tags).
        // A read failure is non-fatal: we treat the file as having no
        // XMP rather than failing the whole metadata extraction.
        let xmp = crate::xmp::read_xmp(video_path).ok().flatten();
        if let Some(ref x) = xmp {
            // Merge make and model *per field*, preferring XMP but falling
            // back to the ffprobe tag for whichever half XMP omits. A
            // sidecar that wrote `tiff:Model` but not `tiff:Make` (common
            // when only the body, not the make, was in the source) must
            // not blank out ffprobe's make — otherwise the same camera
            // lands in the catalog twice, e.g. "ILCE-7RM4" alongside
            // "SONY ILCE-7RM4". Combining make-less leaves the model code
            // bare and breaks the marketing-name lookup downstream.
            if let Some(name) = merge_make_model(
                x.make.as_deref(),
                x.model.as_deref(),
                ff_make.as_deref(),
                ff_model.as_deref(),
            ) {
                camera_model = Some(name);
            }
            if let Some(ref l) = x.lens {
                lens_model = Some(l.clone());
            }
            if let Some(ts) = x.date_time_original {
                creation_date = Some(ts);
            }
        }

        // QuickTime *keyed* metadata (moov[/trak]/meta → keys → ilst).
        // ffprobe lifts the movie-level keys into format.tags (make/model/
        // creationdate/location, handled above), but drops the per-track
        // keys where iPhones record the lens — so read them natively here.
        // Empty for `photos://` pseudo-paths and other non-file sources: on
        // iOS the native backend fills format.tags itself, so the extract_*
        // tag lookups above already covered those.
        let qt = crate::quicktime::read_quicktime_metadata(video_path);

        // Lens, last in the priority chain: ffprobe tags → XMP (both above)
        // → the per-track QuickTime key. On the daemon path the first two are
        // empty for iPhone clips, so this is what actually populates it.
        if lens_model.is_none() {
            lens_model = qt.get("com.apple.quicktime.camera.lens_model").cloned();
        }

        // GPS. iPhone clips embed `com.apple.quicktime.location.ISO6709`,
        // which the old extractor ignored entirely — camera-original location
        // never reached the catalog, only a manual UpdateVideoLocation did.
        // Prefer the ffprobe tag; fall back to the natively-parsed key.
        let (gps_latitude, gps_longitude, gps_altitude) =
            Self::extract_location(&format.tags)
                .or_else(|| Self::extract_location(&video_stream.tags))
                .or_else(|| {
                    qt.get("com.apple.quicktime.location.ISO6709")
                        .and_then(|s| parse_iso6709(s))
                })
                .map(|(la, lo, al)| (Some(la), Some(lo), al))
                .unwrap_or((None, None, None));

        // Recover a missing make prefix. Some files carry only the model
        // (a sidecar wrote `tiff:Model` but no make, and ffprobe had none
        // either), leaving a bare code like "ILCE-7SM2". When that code
        // uniquely identifies a known camera, store the canonical
        // make-prefixed form ("SONY ILCE-7SM2") so it groups with — and
        // resolves to the same marketing name as — its make-prefixed
        // siblings instead of forming a duplicate. No-op for anything
        // that already has a make or doesn't match a known body.
        camera_model = camera_model.map(|c| crate::camera_names::recover_make_prefix(&c));

        // Photo-EXIF columns. Two independent sources, in priority order:
        //   1. The XMP packet (richer; written by tools that follow the
        //      XMP-EXIF standard — Premiere, Lightroom, exiftool).
        //   2. QuickTime udta key=value tags (what ffmpeg's
        //      `-metadata iso=100 -movflags use_metadata_tags` produces,
        //      and what older encoders or post-processing tools tend to
        //      emit when they don't bother with XMP).
        //
        // Field-by-field merge: XMP wins where it provides a value;
        // otherwise we fall back to udta. Each field is independent so a
        // video carrying lens-via-XMP and ISO-via-udta gets both. Key
        // names accepted on the udta side are intentionally generous —
        // see the helpers below for the full list — because there's no
        // standard for these tag names in MP4/MOV udta and writers vary.
        let final_iso = xmp.as_ref().and_then(|x| x.iso)
            .or_else(|| Self::udta_int(&format.tags, &video_stream.tags, ISO_KEYS));
        let final_aperture = xmp.as_ref().and_then(|x| x.aperture)
            .or_else(|| Self::udta_rational(&format.tags, &video_stream.tags, FNUMBER_KEYS))
            // iPhone per-track key, formatted "F1.78" — strip the leading F.
            .or_else(|| {
                qt.get("com.apple.quicktime.camera.lens_irisfnumber")
                    .and_then(|s| crate::xmp::parse_rational(s.trim_start_matches(['F', 'f'])))
            });
        let final_exposure_time_s = xmp.as_ref().and_then(|x| x.exposure_time_s)
            .or_else(|| Self::udta_rational(&format.tags, &video_stream.tags, EXPOSURE_TIME_KEYS));
        let final_focal_length_mm = xmp.as_ref().and_then(|x| x.focal_length_mm)
            .or_else(|| Self::udta_rational(&format.tags, &video_stream.tags, FOCAL_LENGTH_KEYS));
        let final_exposure_mode = xmp.as_ref().and_then(|x| x.exposure_mode.clone())
            .or_else(|| Self::udta_enum_label(
                &format.tags, &video_stream.tags, EXPOSURE_MODE_KEYS,
                crate::xmp::exposure_mode_label));
        let final_exposure_program = xmp.as_ref().and_then(|x| x.exposure_program.clone())
            .or_else(|| Self::udta_enum_label(
                &format.tags, &video_stream.tags, EXPOSURE_PROGRAM_KEYS,
                crate::xmp::exposure_program_label));
        let final_white_balance = xmp.as_ref().and_then(|x| x.white_balance.clone())
            .or_else(|| Self::udta_enum_label(
                &format.tags, &video_stream.tags, WHITE_BALANCE_KEYS,
                crate::xmp::white_balance_label));

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
              lens_model, gps_latitude, gps_longitude, gps_altitude,
              iso, aperture, exposure_time_s, focal_length_mm,
              exposure_mode, exposure_program, white_balance, metadata_json)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
             -- Refresh GPS from the file when it carries a location, but keep
             -- an existing value when it doesn't, so a re-index can't wipe a
             -- location the user set manually via UpdateVideoLocation (whose
             -- file write may not have stuck on a read-only source).
             gps_latitude=COALESCE(excluded.gps_latitude, gps_latitude),
             gps_longitude=COALESCE(excluded.gps_longitude, gps_longitude),
             gps_altitude=COALESCE(excluded.gps_altitude, gps_altitude),
             iso=excluded.iso,
             aperture=excluded.aperture,
             exposure_time_s=excluded.exposure_time_s,
             focal_length_mm=excluded.focal_length_mm,
             exposure_mode=excluded.exposure_mode,
             exposure_program=excluded.exposure_program,
             white_balance=excluded.white_balance,
             metadata_json=excluded.metadata_json,
             -- Invalidate the lazily-cached loudness series: the file content may
             -- have changed (an in-place edit re-runs this UPSERT), so force a
             -- recompute on next view (NULL = not computed) rather than serving a
             -- stale curve — or, for a clip that gained audio, a stuck-empty one.
             audio_loudness=NULL",
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
                gps_latitude,
                gps_longitude,
                gps_altitude,
                final_iso,
                final_aperture,
                final_exposure_time_s,
                final_focal_length_mm,
                final_exposure_mode,
                final_exposure_program,
                final_white_balance,
                metadata_json
            ],
        )
        .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;

        tracing::debug!("Stored metadata for video {}: {}x{}@{:.2}fps", video_id, width, height, fps);

        Ok(())
    }

    fn ffprobe_available() -> bool {
        crate::ffmpeg::ffprobe_command()
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
            return Err(ReelVaultError::MetadataExtractionFailed(
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
            .ok_or_else(|| ReelVaultError::MetadataExtractionFailed(
                "video has no parent directory".to_string(),
            ))?;
        let ext = path
            .extension()
            .and_then(|s| s.to_str())
            .unwrap_or("mp4");
        let temp_path = dir.join(format!(
            ".reelvault-loc-{}.{}",
            uuid::Uuid::new_v4(),
            ext
        ));

        // Throttle the ffmpeg invocation like every other ffmpeg call.
        let _permit = crate::concurrency::acquire_ffmpeg_permit();

        let status = crate::ffmpeg::ffmpeg_command()
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
                return Err(ReelVaultError::MetadataExtractionFailed(format!(
                    "ffmpeg failed to spawn: {}", e
                )));
            }
        };

        if !ok {
            let _ = std::fs::remove_file(&temp_path);
            return Err(ReelVaultError::MetadataExtractionFailed(
                "ffmpeg returned a non-zero exit code while writing location".to_string(),
            ));
        }

        // Atomic-ish swap. rename(2) is atomic on the same filesystem,
        // which is the common case (temp is a sibling of the original).
        std::fs::rename(&temp_path, video_path).map_err(|e| {
            // Try to clean the temp file up if the rename failed.
            let _ = std::fs::remove_file(&temp_path);
            ReelVaultError::MetadataExtractionFailed(format!(
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
            return Err(ReelVaultError::MetadataExtractionFailed(
                "ffmpeg not available on PATH; cannot embed creation time in file".to_string(),
            ));
        }

        // Chrono is already a dependency — use it for a robust UTC string.
        let dt = chrono::DateTime::<chrono::Utc>::from_timestamp_millis(timestamp_ms)
            .ok_or_else(|| ReelVaultError::MetadataExtractionFailed(
                format!("timestamp_ms {} is out of range", timestamp_ms)
            ))?;
        let iso = dt.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string();

        let path = std::path::Path::new(video_path);
        let dir = path
            .parent()
            .ok_or_else(|| ReelVaultError::MetadataExtractionFailed(
                "video has no parent directory".to_string(),
            ))?;
        let ext = path
            .extension()
            .and_then(|s| s.to_str())
            .unwrap_or("mp4");
        let temp_path = dir.join(format!(
            ".reelvault-date-{}.{}",
            uuid::Uuid::new_v4(),
            ext
        ));

        let _permit = crate::concurrency::acquire_ffmpeg_permit();

        let status = crate::ffmpeg::ffmpeg_command()
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
                return Err(ReelVaultError::MetadataExtractionFailed(format!(
                    "ffmpeg failed to spawn: {}", e
                )));
            }
        };

        if !ok {
            let _ = std::fs::remove_file(&temp_path);
            return Err(ReelVaultError::MetadataExtractionFailed(
                "ffmpeg returned a non-zero exit code while writing creation_time".to_string(),
            ));
        }

        std::fs::rename(&temp_path, video_path).map_err(|e| {
            let _ = std::fs::remove_file(&temp_path);
            ReelVaultError::MetadataExtractionFailed(format!(
                "Failed to atomically replace {}: {}", video_path, e
            ))
        })?;

        Ok(())
    }

    fn ffmpeg_available() -> bool {
        crate::ffmpeg::ffmpeg_command()
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
            .ok_or_else(|| ReelVaultError::MetadataExtractionFailed("No video stream found".to_string()))?;

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

    /// The camera make tag, accepting the QuickTime-namespaced variant.
    fn extract_make(tags: &Option<FFProbeTagMap>) -> Option<String> {
        Self::extract_tag(tags, "make")
            .or_else(|| Self::extract_tag(tags, "com.apple.quicktime.make"))
    }

    /// The camera model tag, accepting the QuickTime-namespaced variant.
    fn extract_model(tags: &Option<FFProbeTagMap>) -> Option<String> {
        Self::extract_tag(tags, "model")
            .or_else(|| Self::extract_tag(tags, "com.apple.quicktime.model"))
    }

    /// Look for lens info in various tag formats used by different cameras.
    /// `com.apple.quicktime.camera.lens_model` is the real iPhone key (a
    /// *per-track* keyed-metadata entry); ffprobe drops it, so on the daemon
    /// path it arrives via [`crate::quicktime`], but the iOS native backend
    /// synthesizes it straight into `format.tags`, so accept it here too.
    fn extract_lens(tags: &Option<FFProbeTagMap>) -> Option<String> {
        Self::extract_tag(tags, "lens_model")
            .or_else(|| Self::extract_tag(tags, "com.apple.quicktime.camera.lens_model"))
            .or_else(|| Self::extract_tag(tags, "com.apple.quicktime.lens.model"))
            .or_else(|| Self::extract_tag(tags, "lens"))
            .or_else(|| Self::extract_tag(tags, "lensmodel"))
    }

    /// GPS location from a QuickTime ISO 6709 tag. iPhone clips carry
    /// `com.apple.quicktime.location.ISO6709` (ffprobe surfaces it in
    /// `format.tags`); some non-Apple muxers write a bare `location`.
    /// Matched case-insensitively because ffprobe preserves the tag's
    /// mixed-case `ISO6709` spelling. Returns (latitude, longitude, optional
    /// altitude in metres).
    fn extract_location(tags: &Option<FFProbeTagMap>) -> Option<(f64, f64, Option<f64>)> {
        let map = tags.as_ref()?;
        let raw = map
            .iter()
            .find(|(k, _)| {
                let k = k.to_ascii_lowercase();
                k.ends_with("location.iso6709")
                    || k == "location"
                    || k == "com.apple.quicktime.location"
            })
            .map(|(_, v)| v.clone())?;
        parse_iso6709(&raw)
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

    // ----- udta-tag photo-EXIF extraction --------------------------------
    //
    // MP4/MOV `udta` atoms can carry arbitrary key=value tags — this is
    // what ffmpeg writes with `-metadata iso=100 -movflags
    // use_metadata_tags`, and what some camera firmware and post-
    // processing tools use to forward photo-EXIF without going to the
    // trouble of building an XMP packet. There's no standard for the
    // key *names* though, so writers diverge wildly — `iso`, `ISO`,
    // `iso_speed`, `com.apple.quicktime.iso`, etc. The helpers below
    // try a generous list of forms for each field and pick the first
    // one that parses. The lookup is case-insensitive (FFProbeTagMap
    // lowercases its lookups), so casing variants don't need to be
    // listed separately.

    /// Try each key in `keys` against the format tags and the video
    /// stream tags; return the first that parses as an integer.
    fn udta_int(
        format_tags: &Option<FFProbeTagMap>,
        stream_tags: &Option<FFProbeTagMap>,
        keys: &[&str],
    ) -> Option<i64> {
        for key in keys {
            for tags in [format_tags, stream_tags] {
                if let Some(s) = Self::extract_tag(tags, key) {
                    if let Ok(n) = s.trim().parse::<i64>() {
                        return Some(n);
                    }
                }
            }
        }
        None
    }

    /// Same shape as [`udta_int`], but parses EXIF rationals (`1/4000`)
    /// or decimal floats. Shares the rational parser with [`crate::xmp`]
    /// so udta and XMP land on identical numeric values.
    fn udta_rational(
        format_tags: &Option<FFProbeTagMap>,
        stream_tags: &Option<FFProbeTagMap>,
        keys: &[&str],
    ) -> Option<f64> {
        for key in keys {
            for tags in [format_tags, stream_tags] {
                if let Some(s) = Self::extract_tag(tags, key) {
                    if let Some(v) = crate::xmp::parse_rational(&s) {
                        return Some(v);
                    }
                }
            }
        }
        None
    }

    /// Enum-style EXIF field (ExposureMode, ExposureProgram,
    /// WhiteBalance). The udta value can arrive as either:
    ///   - The raw EXIF integer code (`1` for Manual) — typical when
    ///     ffmpeg wrote it from an exiftool source. We translate via
    ///     the matching `*_label` helper in xmp.rs so udta and XMP
    ///     produce identical strings.
    ///   - A human-readable word (`Manual`, `Auto`, …) — common when
    ///     ffmpeg's `-metadata` was given a string directly. We
    ///     pass these through verbatim after trimming.
    fn udta_enum_label(
        format_tags: &Option<FFProbeTagMap>,
        stream_tags: &Option<FFProbeTagMap>,
        keys: &[&str],
        label: fn(&str) -> String,
    ) -> Option<String> {
        for key in keys {
            for tags in [format_tags, stream_tags] {
                if let Some(s) = Self::extract_tag(tags, key) {
                    let v = s.trim();
                    if v.is_empty() {
                        continue;
                    }
                    // Integer code path
                    if v.parse::<i64>().is_ok() {
                        return Some(label(v));
                    }
                    // Plain-word path
                    return Some(v.to_string());
                }
            }
        }
        None
    }
}

// Lists of accepted udta tag names per EXIF field. Lookups are
// case-insensitive, so we only enumerate one casing per spelling.
// Order matters only in that the first matching key wins — we list
// the ffmpeg-canonical name first, then more exotic variants seen in
// the wild.
const ISO_KEYS: &[&str] = &[
    "iso", "iso_speed", "iso_speed_ratings", "isospeed",
    "com.apple.quicktime.iso", "exif_iso",
];
const FNUMBER_KEYS: &[&str] = &[
    "fnumber", "f_number", "aperture", "apertureval",
    "com.apple.quicktime.fnumber", "com.apple.quicktime.aperture",
    "exif_fnumber",
];
const EXPOSURE_TIME_KEYS: &[&str] = &[
    "exposure_time", "exposuretime", "shutter_speed", "shutter_speed_value",
    "shutterspeed", "com.apple.quicktime.exposuretime",
    "com.apple.quicktime.shutterspeed",
];
const FOCAL_LENGTH_KEYS: &[&str] = &[
    "focal_length", "focallength", "com.apple.quicktime.focallength",
];
const EXPOSURE_MODE_KEYS: &[&str] = &[
    "exposure_mode", "exposuremode", "com.apple.quicktime.exposuremode",
];
const EXPOSURE_PROGRAM_KEYS: &[&str] = &[
    "exposure_program", "exposureprogram", "com.apple.quicktime.exposureprogram",
];
const WHITE_BALANCE_KEYS: &[&str] = &[
    "white_balance", "whitebalance", "com.apple.quicktime.whitebalance",
];

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

        // A JSON integer is a valid f64. JSONSerialization (the iOS native
        // backend) emits whole-number durations as integers (5.0 -> `5`), so
        // accept those too rather than only f64/string.
        fn visit_i64<E>(self, value: i64) -> std::result::Result<Option<f64>, E>
        where
            E: de::Error,
        {
            Ok(Some(value as f64))
        }

        fn visit_u64<E>(self, value: u64) -> std::result::Result<Option<f64>, E>
        where
            E: de::Error,
        {
            Ok(Some(value as f64))
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
    /// Modern iPhones (HEVC, iOS 11+) encode rotation in a display matrix
    /// rather than the `rotate` stream tag. FFProbe surfaces this as a list
    /// of side-data entries, one of which has `side_data_type = "Display Matrix"`
    /// and a `rotation` integer (-180..180).
    #[serde(default)]
    pub side_data_list: Option<Vec<FFProbeSideData>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct FFProbeSideData {
    pub side_data_type: Option<String>,
    /// Rotation in degrees derived from the display matrix. Present only for
    /// `side_data_type = "Display Matrix"` entries.
    pub rotation: Option<i32>,
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

/// Combine optional `make` and `model` strings into a single human-readable
/// camera name. Shared between the FFprobe-tag path (which pulls them out
/// of a `FFProbeTagMap`) and the XMP path (which pulls them out of a
/// parsed `XmpMetadata`). If the model already starts with the make
/// (e.g. some Canon bodies report make="Canon" model="Canon EOS R5"), we
/// don't repeat the prefix.
pub(crate) fn combine_make_model(make: Option<&str>, model: Option<&str>) -> Option<String> {
    match (make, model) {
        (Some(make), Some(model)) => {
            if model.to_lowercase().starts_with(&make.to_lowercase()) {
                Some(model.to_string())
            } else {
                Some(format!("{} {}", make, model))
            }
        }
        (None, Some(model)) => Some(model.to_string()),
        (Some(make), None) => Some(make.to_string()),
        (None, None) => None,
    }
}

/// Merge make/model from a primary source (the XMP packet) over a
/// fallback source (ffprobe container tags) **field by field**, then
/// combine into a single camera name.
///
/// The per-field fallback is the important part: an XMP packet that
/// carries `tiff:Model` but no `tiff:Make` (a common shape for
/// sidecar-embedded metadata) must still pick up the make from ffprobe.
/// Overriding wholesale would store the bare model code ("ILCE-7RM4")
/// for those files while files with a full XMP packet store
/// "SONY ILCE-7RM4" — the same body listed as two cameras, and the
/// make-less form misses the marketing-name lookup entirely.
pub(crate) fn merge_make_model(
    primary_make: Option<&str>,
    primary_model: Option<&str>,
    fallback_make: Option<&str>,
    fallback_model: Option<&str>,
) -> Option<String> {
    combine_make_model(
        primary_make.or(fallback_make),
        primary_model.or(fallback_model),
    )
}

/// Parse an ISO 6709 location string into `(latitude, longitude, optional
/// altitude)`. QuickTime/iPhone uses signed decimal degrees with a trailing
/// slash, e.g. `+37.8952-122.0480+069.173/` (altitude in metres) or
/// `+37.8126-122.1204/` (no altitude). Each component is introduced by a
/// mandatory `+`/`-`, which is how we tokenise — the sign is the delimiter.
/// Returns `None` unless at least a lat/lon pair parses.
pub(crate) fn parse_iso6709(s: &str) -> Option<(f64, f64, Option<f64>)> {
    let t = s.trim().trim_end_matches('/');
    let mut tokens: Vec<String> = Vec::new();
    let mut cur = String::new();
    for (i, c) in t.char_indices() {
        if (c == '+' || c == '-') && i != 0 && !cur.is_empty() {
            tokens.push(std::mem::take(&mut cur));
        }
        cur.push(c);
    }
    if !cur.is_empty() {
        tokens.push(cur);
    }
    let lat: f64 = tokens.first()?.parse().ok()?;
    let lon: f64 = tokens.get(1)?.parse().ok()?;
    // A trailing CRS suffix (rare) lands on the altitude token and makes it
    // unparseable — fine, we just drop altitude in that case.
    let alt: Option<f64> = tokens.get(2).and_then(|s| s.parse().ok());
    Some((lat, lon, alt))
}

/// Extract an audio loudness-over-time series for the detail view's volume
/// graph. Runs ffmpeg's EBU R128 meter over the file's audio and reads the
/// momentary-loudness (`M:`) value it logs (~10 per second), then averages
/// those down to at most `MAX_LOUDNESS_SAMPLES` points and normalises each to
/// [0, 1] over a -60..0 LUFS window (silence ≈ 0, full scale ≈ 1).
///
/// Returns an empty vec when the file has no audio or ffmpeg can't be run — the
/// caller then renders no graph. Honours the global ffmpeg permit, since a
/// full-file audio decode over the (slow, networked) library is exactly the
/// kind of I/O that throttle exists to bound.
pub fn extract_audio_loudness(video_path: &Path) -> Vec<f32> {
    const MAX_LOUDNESS_SAMPLES: usize = 480;
    const FLOOR_DB: f32 = -60.0;
    const RANGE_DB: f32 = 60.0; // 0 dB (loud) − FLOOR_DB (silence)

    let _permit = crate::concurrency::acquire_ffmpeg_permit();
    let output = match crate::ffmpeg::ffmpeg_command()
        .args([
            "-hide_banner",
            "-nostats",
            "-i",
            video_path.to_str().unwrap_or(""),
            "-vn", // ignore video — only the audio loudness is wanted
            // Run the EBU R128 meter and have `ametadata` print each frame's
            // momentary-loudness reading to STDOUT as `lavfi.r128.M=<db>`. We read
            // the metadata stream rather than scraping ebur128's human-readable
            // log: newer ffmpeg (8.x) only emits the per-frame `M:` log lines at
            // `-loglevel verbose`, so the old stderr scrape parsed *zero* samples
            // at the default level and the graph silently never appeared.
            "-af",
            "ebur128=metadata=1,ametadata=mode=print:file=-",
            "-f",
            "null",
            "-",
        ])
        .output()
    {
        Ok(o) => o,
        Err(_) => return Vec::new(),
    };

    // `ametadata=print` writes one `lavfi.r128.M=<db>` line per frame (~10/s) to
    // stdout, independent of log level. Pull the momentary-loudness value out.
    let log = String::from_utf8_lossy(&output.stdout);
    let mut db_values: Vec<f32> = Vec::new();
    for line in log.lines() {
        if let Some(rest) = line.trim().strip_prefix("lavfi.r128.M=") {
            if let Ok(db) = rest.trim().parse::<f32>() {
                if db.is_finite() {
                    db_values.push(db);
                }
            }
        }
    }
    if db_values.is_empty() {
        return Vec::new();
    }

    downsample_avg(&db_values, MAX_LOUDNESS_SAMPLES)
        .into_iter()
        .map(|db| ((db.clamp(FLOOR_DB, 0.0) - FLOOR_DB) / RANGE_DB).clamp(0.0, 1.0))
        .collect()
}

/// Average `src` down into at most `max` evenly spaced bins (returns it
/// unchanged when already short enough). Used to keep the loudness series small
/// regardless of clip length.
fn downsample_avg(src: &[f32], max: usize) -> Vec<f32> {
    if src.len() <= max {
        return src.to_vec();
    }
    (0..max)
        .map(|i| {
            let start = i * src.len() / max;
            let end = (((i + 1) * src.len() / max).max(start + 1)).min(src.len());
            let slice = &src[start..end];
            slice.iter().sum::<f32>() / slice.len() as f32
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    /// Build an `Option<FFProbeTagMap>` from a slice of (key, value)
    /// pairs. The map's `get` is case-insensitive so we lowercase keys
    /// here to match what ffprobe actually emits in its JSON.
    fn tags(pairs: &[(&str, &str)]) -> Option<FFProbeTagMap> {
        let mut m = HashMap::new();
        for (k, v) in pairs {
            m.insert(k.to_lowercase(), v.to_string());
        }
        Some(FFProbeTagMap(m))
    }

    #[test]
    fn merge_make_model_recovers_make_from_fallback() {
        // The regression case: XMP carries the model but no make; the
        // make must come from the ffprobe fallback so we don't store a
        // bare "ILCE-7RM4" that splits the camera off from its
        // "SONY ILCE-7RM4" siblings.
        assert_eq!(
            merge_make_model(None, Some("ILCE-7RM4"), Some("SONY"), Some("ILCE-7RM4")),
            Some("SONY ILCE-7RM4".to_string())
        );
    }

    #[test]
    fn merge_make_model_prefers_primary_per_field() {
        // XMP make/model win when present.
        assert_eq!(
            merge_make_model(Some("Nikon"), Some("Z 8"), Some("SONY"), Some("ILCE-9")),
            Some("Nikon Z 8".to_string())
        );
        // Falls back entirely to ffprobe when XMP has neither.
        assert_eq!(
            merge_make_model(None, None, Some("SONY"), Some("ILCE-9")),
            Some("SONY ILCE-9".to_string())
        );
        // Model from XMP, make absent everywhere → bare model (best we can do).
        assert_eq!(
            merge_make_model(None, Some("ILCE-9"), None, None),
            Some("ILCE-9".to_string())
        );
    }

    #[test]
    fn parse_iso6709_decimal_with_and_without_altitude() {
        let (la, lo, al) = parse_iso6709("+37.8952-122.0480+069.173/").unwrap();
        assert!((la - 37.8952).abs() < 1e-9);
        assert!((lo + 122.0480).abs() < 1e-9);
        assert!((al.unwrap() - 69.173).abs() < 1e-6);

        // No altitude component (iPhone X clips, and our own writer when alt=0).
        let (la, lo, al) = parse_iso6709("+37.8126-122.1204/").unwrap();
        assert!((la - 37.8126).abs() < 1e-9);
        assert!((lo + 122.1204).abs() < 1e-9);
        assert!(al.is_none());

        // Negative altitude (below sea level) must keep its sign.
        let (_, _, al) = parse_iso6709("+10.0+20.0-5.5/").unwrap();
        assert!((al.unwrap() + 5.5).abs() < 1e-6);

        assert!(parse_iso6709("garbage").is_none());
        assert!(parse_iso6709("+37.8/").is_none()); // lon missing
    }

    #[test]
    fn extract_location_finds_mixed_case_quicktime_key() {
        // ffprobe preserves the tag's uppercase `ISO6709`; the lookup is
        // case-insensitive so it still resolves.
        let t = tags(&[(
            "com.apple.quicktime.location.ISO6709",
            "+37.8952-122.0480+069.173/",
        )]);
        let (la, lo, al) = MetadataExtractor::extract_location(&t).unwrap();
        assert!((la - 37.8952).abs() < 1e-6);
        assert!((lo + 122.0480).abs() < 1e-6);
        assert!((al.unwrap() - 69.173).abs() < 1e-3);

        // Bare `location` from a non-Apple muxer.
        assert!(MetadataExtractor::extract_location(&tags(&[("location", "+10.0-20.0/")])).is_some());

        // Nothing location-shaped → None.
        assert!(MetadataExtractor::extract_location(&tags(&[("make", "Apple")])).is_none());
    }

    #[test]
    fn extract_lens_accepts_iphone_camera_key() {
        let t = tags(&[(
            "com.apple.quicktime.camera.lens_model",
            "iPhone 16 Pro back camera 6.765mm f/1.78",
        )]);
        assert_eq!(
            MetadataExtractor::extract_lens(&t).as_deref(),
            Some("iPhone 16 Pro back camera 6.765mm f/1.78")
        );
    }

    #[test]
    fn udta_int_finds_iso_under_any_known_key() {
        assert_eq!(
            MetadataExtractor::udta_int(&tags(&[("iso", "100")]), &None, ISO_KEYS),
            Some(100)
        );
        assert_eq!(
            MetadataExtractor::udta_int(
                &tags(&[("com.apple.quicktime.iso", "1600")]), &None, ISO_KEYS),
            Some(1600)
        );
        // Falls through to stream tags when format tags lack the key.
        assert_eq!(
            MetadataExtractor::udta_int(
                &None, &tags(&[("iso_speed", "400")]), ISO_KEYS),
            Some(400)
        );
        // Unknown key: nothing matches → None.
        assert_eq!(
            MetadataExtractor::udta_int(
                &tags(&[("not_iso", "999")]), &None, ISO_KEYS),
            None
        );
    }

    #[test]
    fn udta_rational_accepts_both_decimal_and_fraction() {
        // f-number as decimal
        assert_eq!(
            MetadataExtractor::udta_rational(
                &tags(&[("fnumber", "1.8")]), &None, FNUMBER_KEYS),
            Some(1.8)
        );
        // exposure time as rational
        let v = MetadataExtractor::udta_rational(
            &tags(&[("exposure_time", "1/4000")]), &None, EXPOSURE_TIME_KEYS)
            .unwrap();
        assert!((v - 0.00025).abs() < 1e-9);
        // exposure time as plain seconds
        assert_eq!(
            MetadataExtractor::udta_rational(
                &tags(&[("shutter_speed", "20")]), &None, EXPOSURE_TIME_KEYS),
            Some(20.0)
        );
    }

    #[test]
    fn udta_enum_label_handles_integer_code_and_word() {
        // Integer code path — translated to label via xmp.rs helper.
        assert_eq!(
            MetadataExtractor::udta_enum_label(
                &tags(&[("exposure_mode", "1")]), &None, EXPOSURE_MODE_KEYS,
                crate::xmp::exposure_mode_label),
            Some("Manual".to_string())
        );
        // Plain word: pass-through.
        assert_eq!(
            MetadataExtractor::udta_enum_label(
                &tags(&[("white_balance", "Daylight")]), &None, WHITE_BALANCE_KEYS,
                crate::xmp::white_balance_label),
            Some("Daylight".to_string())
        );
        // Empty string is ignored — no spurious label.
        assert_eq!(
            MetadataExtractor::udta_enum_label(
                &tags(&[("exposure_program", "")]), &None, EXPOSURE_PROGRAM_KEYS,
                crate::xmp::exposure_program_label),
            None
        );
    }

    /// End-to-end check: encode a 1-second clip with udta photo-EXIF
    /// tags via ffmpeg, run MetadataExtractor::extract on it, and
    /// verify the udta helpers find every field. Skipped when ffmpeg
    /// isn't available so CI machines without media tooling still
    /// pass the pure-helper tests above.
    #[test]
    fn round_trip_through_ffmpeg_udta() {
        if !MetadataExtractor::ffmpeg_available() || !MetadataExtractor::ffprobe_available() {
            eprintln!("skipping round_trip_through_ffmpeg_udta: ffmpeg/ffprobe not available");
            return;
        }

        let tmp = tempfile::tempdir().expect("tmpdir");
        let path = tmp.path().join("clip.mov");

        let status = crate::ffmpeg::ffmpeg_command()
            .args([
                "-nostdin", "-loglevel", "error", "-y",
                "-f", "lavfi",
                "-i", "testsrc=duration=1:size=320x240:rate=30",
                "-c:v", "prores_ks",
                // The combination that lets ffmpeg pass arbitrary
                // udta keys through into the output container.
                "-movflags", "use_metadata_tags",
                "-metadata", "iso=100",
                "-metadata", "fnumber=1.8",
                "-metadata", "exposure_time=1/4000",
                "-metadata", "focal_length=14",
                "-metadata", "exposure_mode=Manual",
                "-metadata", "white_balance=Auto",
            ])
            .arg(&path)
            .status()
            .expect("ffmpeg spawn");
        assert!(status.success(), "ffmpeg failed to write fixture");

        let probe = MetadataExtractor::extract(&path).expect("ffprobe ok");
        let format_tags = &probe.format.tags;
        // Video stream is index 0 here but parse_probe_output already
        // takes care of picking the right one — just use the index for
        // the test.
        let stream_tags = &probe.streams[0].tags;

        assert_eq!(
            MetadataExtractor::udta_int(format_tags, stream_tags, ISO_KEYS),
            Some(100)
        );
        let aperture = MetadataExtractor::udta_rational(
            format_tags, stream_tags, FNUMBER_KEYS).unwrap();
        assert!((aperture - 1.8).abs() < 1e-9);
        let exp = MetadataExtractor::udta_rational(
            format_tags, stream_tags, EXPOSURE_TIME_KEYS).unwrap();
        assert!((exp - 1.0 / 4000.0).abs() < 1e-9);
        let focal = MetadataExtractor::udta_rational(
            format_tags, stream_tags, FOCAL_LENGTH_KEYS).unwrap();
        assert!((focal - 14.0).abs() < 1e-9);
        assert_eq!(
            MetadataExtractor::udta_enum_label(
                format_tags, stream_tags, EXPOSURE_MODE_KEYS,
                crate::xmp::exposure_mode_label),
            Some("Manual".to_string())
        );
        assert_eq!(
            MetadataExtractor::udta_enum_label(
                format_tags, stream_tags, WHITE_BALANCE_KEYS,
                crate::xmp::white_balance_label),
            Some("Auto".to_string())
        );
    }
}
