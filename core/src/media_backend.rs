// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Abstraction over everything the core used to do by shelling out to a CLI
//! tool (ffmpeg / ffprobe), so the media pipeline can run on platforms that
//! forbid subprocess `exec` — namely iOS (docs/IOS_CORE_PORT.md §6.1).
//!
//! Two implementations:
//! - [`CliMediaBackend`] — the desktop default. A thin dispatcher: each method
//!   delegates to the existing ffmpeg/ffprobe code (now factored into leaf
//!   helpers in `metadata.rs` / `thumbnails.rs` / `proxies.rs`). Desktop
//!   behavior is unchanged — same binaries, same args, same output bytes.
//! - `NativeMediaBackend` (iOS, a later phase) — implements the same trait with
//!   AVFoundation / VideoToolbox in-process.
//!
//! ## Why a process-global, not a threaded field
//!
//! docs/IOS_CORE_PORT.md §6.1 suggested storing an `Arc<dyn MediaBackend>` on
//! `ReelVaultService` and threading it to every call site. In practice the media
//! call sites sit at the bottom of long chains (RPC handler → `scan_directory` →
//! rayon `index_video`; the `post_index` worker pool; on-demand thumbnail/proxy
//! RPCs), so threading a parameter through all of them is a large, error-prone
//! change that works against Phase 1's "desktop byte-for-byte unchanged" bar.
//!
//! Instead the backend is a process-global set once at startup — exactly the
//! pattern `concurrency.rs` already uses for the ffmpeg permit semaphore, and a
//! sound model because there is only ever one backend per process (CLI on
//! desktop, native on iOS). [`backend()`] reads it; [`set_backend`] installs it
//! (desktop relies on the `CliMediaBackend` default; the iOS embed calls
//! `set_backend` before first use). Trivially convertible to a threaded field
//! later if a per-service backend is ever needed.

use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};

use crate::error::{ReelVaultError, Result};
use crate::metadata::{extract_audio_loudness, FFProbeOutput, MetadataExtractor};
use crate::thumbnails::{ffmpeg_extract_frame, probe_color_info, ColorInfo};

/// What a media operation runs against. Identity is backend-specific: the CLI
/// backend needs a filesystem [`MediaSource::Path`]; the iOS backend will also
/// accept a Photos `localIdentifier` or a security-scoped bookmark (Phase 3).
#[derive(Debug, Clone)]
pub enum MediaSource {
    /// A filesystem path (desktop, and Files-app bookmarks once resolved).
    Path(PathBuf),
    /// A Photos `PHAsset.localIdentifier` (iOS).
    PhotoAsset(String),
    /// Security-scoped bookmark data for a Files-app item (iOS).
    Bookmark(Vec<u8>),
}

impl MediaSource {
    /// Convenience constructor from any path-like value.
    pub fn path(p: impl Into<PathBuf>) -> Self {
        MediaSource::Path(p.into())
    }

    /// The filesystem path, if this is a `Path` source.
    pub fn as_path(&self) -> Option<&Path> {
        match self {
            MediaSource::Path(p) => Some(p),
            _ => None,
        }
    }

    fn require_path(&self) -> Result<&Path> {
        self.as_path().ok_or_else(|| {
            ReelVaultError::InvalidPath(
                "CliMediaBackend only supports filesystem (Path) sources".to_string(),
            )
        })
    }
}

/// Everything the core used to shell out to ffmpeg/ffprobe for. All methods are
/// blocking; the implementations bound themselves with `acquire_ffmpeg_permit()`
/// the same way the CLI calls always have.
pub trait MediaBackend: Send + Sync {
    /// Technical metadata for the source — replaces `ffprobe … -of json`.
    /// Returns the same [`FFProbeOutput`] the rest of the pipeline already
    /// consumes, so a native backend synthesizes one from `AVAsset` and nothing
    /// downstream changes.
    fn probe(&self, src: &MediaSource) -> Result<FFProbeOutput>;

    /// Color/transfer info used to decide thumbnail tonemapping — replaces the
    /// color-info ffprobe. Infallible: an all-empty `ColorInfo` means "treat as
    /// plain SDR", which is the safe default.
    fn probe_color(&self, src: &MediaSource) -> ColorInfo;

    /// Per-window audio loudness samples (dB-derived, 0..1) for the waveform —
    /// replaces the `ebur128` ffmpeg pass. Empty on failure / no audio.
    fn extract_loudness(&self, src: &MediaSource) -> Vec<f32>;

    /// Decode one frame at `time_secs`, scaled so its longest side is `max_px`,
    /// and write a JPEG to `out`. The single primitive behind every thumbnail
    /// call site (still, scrub, on-demand). `color` is probed once per video and
    /// passed in (so the scrub loop doesn't re-probe); `quality` is ffmpeg's
    /// `-q:v` (kept per-site so output stays bit-identical: 5 still / 6 scrub /
    /// 3 detail). A native backend may use `color` for HDR handling and ignore
    /// `quality`.
    fn extract_frame(
        &self,
        src: &MediaSource,
        color: &ColorInfo,
        time_secs: f64,
        max_px: i32,
        quality: u8,
        out: &Path,
    ) -> Result<()>;

    /// Transcode a downscaled H.264/AAC proxy at `target_height` to `out` —
    /// replaces the libx264 ffmpeg pass. `total_frames` (0 = unknown) lets the
    /// implementation report `progress` as a 0.0–85.0 percentage on the band the
    /// `GenerateProxy` stream expects.
    fn transcode_proxy(
        &self,
        src: &MediaSource,
        out: &Path,
        target_height: i32,
        total_frames: i64,
        progress: &mut dyn FnMut(f64),
    ) -> Result<()>;

    /// Embed an ISO-8601 `creation_time` into the source — replaces the
    /// `ffmpeg -metadata` rewrite. On iOS this is a no-op for Photos sources
    /// (catalog-only edits, docs/IOS_CORE_PORT.md D4).
    fn write_creation_time(&self, src: &MediaSource, timestamp_ms: i64) -> Result<()>;

    /// Embed an ISO-6709 `location` into the source — replaces the
    /// `ffmpeg -metadata` rewrite. No-op for Photos sources on iOS (D4).
    fn write_location(
        &self,
        src: &MediaSource,
        latitude: f64,
        longitude: f64,
        altitude: f64,
    ) -> Result<()>;

    /// Whether the backend can do media work at all. On desktop this probes for
    /// ffmpeg on PATH (the thumbnail generators gate on it for a clear error);
    /// the native backend is always available. Defaults to true.
    fn is_available(&self) -> bool {
        true
    }
}

/// The desktop backend: every method delegates to the existing CLI code, so
/// behavior is unchanged.
#[derive(Debug, Default, Clone, Copy)]
pub struct CliMediaBackend;

impl MediaBackend for CliMediaBackend {
    fn probe(&self, src: &MediaSource) -> Result<FFProbeOutput> {
        MetadataExtractor::extract(src.require_path()?)
    }

    fn probe_color(&self, src: &MediaSource) -> ColorInfo {
        match src.as_path() {
            Some(p) => probe_color_info(p),
            None => ColorInfo::default(),
        }
    }

    fn extract_loudness(&self, src: &MediaSource) -> Vec<f32> {
        match src.as_path() {
            Some(p) => extract_audio_loudness(p),
            None => Vec::new(),
        }
    }

    fn extract_frame(
        &self,
        src: &MediaSource,
        color: &ColorInfo,
        time_secs: f64,
        max_px: i32,
        quality: u8,
        out: &Path,
    ) -> Result<()> {
        ffmpeg_extract_frame(src.require_path()?, color, time_secs, max_px, quality, out)
    }

    fn transcode_proxy(
        &self,
        src: &MediaSource,
        out: &Path,
        target_height: i32,
        total_frames: i64,
        progress: &mut dyn FnMut(f64),
    ) -> Result<()> {
        crate::proxies::ffmpeg_transcode_proxy(
            src.require_path()?,
            out,
            target_height,
            total_frames,
            progress,
        )
    }

    fn write_creation_time(&self, src: &MediaSource, timestamp_ms: i64) -> Result<()> {
        let path = src.require_path()?.to_str().ok_or_else(|| {
            ReelVaultError::InvalidPath("source path is not valid UTF-8".to_string())
        })?;
        MetadataExtractor::write_creation_time_tag(path, timestamp_ms)
    }

    fn write_location(
        &self,
        src: &MediaSource,
        latitude: f64,
        longitude: f64,
        altitude: f64,
    ) -> Result<()> {
        let path = src.require_path()?.to_str().ok_or_else(|| {
            ReelVaultError::InvalidPath("source path is not valid UTF-8".to_string())
        })?;
        MetadataExtractor::write_location_tag(path, latitude, longitude, altitude)
    }

    fn is_available(&self) -> bool {
        crate::ffmpeg::ffmpeg_command()
            .arg("-version")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }
}

/// The process-wide backend. Set once at startup; defaults to [`CliMediaBackend`].
static BACKEND: OnceLock<Arc<dyn MediaBackend>> = OnceLock::new();

/// Install the process media backend. Best-effort: the first writer wins (a
/// later call is a no-op), so install before anything calls [`backend()`].
/// Desktop can skip this entirely — the default is already [`CliMediaBackend`].
pub fn set_backend(b: Arc<dyn MediaBackend>) {
    let _ = BACKEND.set(b);
}

/// The process media backend, defaulting to [`CliMediaBackend`] on first use.
pub fn backend() -> Arc<dyn MediaBackend> {
    BACKEND
        .get_or_init(|| Arc::new(CliMediaBackend))
        .clone()
}
