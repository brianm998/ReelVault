// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

use crate::concurrency::acquire_ffmpeg_permit;
use crate::db::Database;
use crate::error::{Result, ReelVaultError};
use crate::media_backend::{backend, MediaSource};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

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
        source: &MediaSource,
        video_id: &str,
        cache_dir: &Path,
        duration_secs: f64,
    ) -> Result<()> {
        Self::generate_default_sizes(source, video_id, cache_dir, duration_secs)
    }

    /// Generate the standard still thumbnails (small/medium/large) for a video.
    /// Split out from [`generate`] (which only forwarded here — its `_db` arg is
    /// unused) so the service can regenerate a missing still on demand without a
    /// `Database` handle, mirroring the on-demand scrub-frame path.
    pub fn generate_default_sizes(
        source: &MediaSource,
        video_id: &str,
        cache_dir: &Path,
        duration_secs: f64,
    ) -> Result<()> {
        if !backend().is_available() {
            return Err(ReelVaultError::FfmpegError(
                "media backend unavailable (ffmpeg not found in PATH?)".to_string(),
            ));
        }

        // Extract one frame from middle of video
        let thumbnail_frame = Self::extract_frame(source, cache_dir, video_id, duration_secs)?;

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

    fn extract_frame(source: &MediaSource, cache_dir: &Path, video_id: &str, duration_secs: f64) -> Result<PathBuf> {
        // Extract frame at 50% through the video
        let temp_path = cache_dir.join(format!("{}_temp.jpg", video_id));

        // Calculate seek position at 50% of duration
        let seek_pos = if duration_secs > 0.0 {
            duration_secs * 0.5
        } else {
            0.0
        };

        let color_info = backend().probe_color(source);

        // ProRes RAW on macOS: ffmpeg can't develop it, so source the frame from
        // QuickLook (the OS decoder) at a size big enough for the largest cached
        // thumbnail. Only applies to local-file sources (Photos/Bookmark sources
        // are iOS, where the native backend handles ProRes itself).
        if let Some(p) = source.as_path() {
            if use_quicklook(&color_info)
                && quicklook_poster(p, &temp_path, Self::LARGE_WIDTH).is_ok()
            {
                return Ok(temp_path);
            }
        }

        // Frame at 400px wide, JPEG quality 5 — the source still that
        // `generate_size` then downscales into the small/medium/large variants.
        backend().extract_frame(source, &color_info, seek_pos, 400, 5, &temp_path)?;

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

        // Resizing a JPEG is pure image work, not video decode — on iOS (no
        // ffmpeg subprocess) do it with the `image` crate. Desktop keeps the
        // ffmpeg path so its thumbnails are byte-for-byte unchanged.
        #[cfg(any(target_os = "ios", target_os = "android"))]
        {
            use image::GenericImageView;
            let img = image::open(frame_path)
                .map_err(|e| ReelVaultError::ThumbnailGenerationFailed(e.to_string()))?;
            let (w, h) = img.dimensions();
            let nw = width.max(1) as u32;
            // Width-driven, aspect-preserving (mirrors ffmpeg `scale=W:-1`).
            let nh = if w > 0 {
                (((h as u64) * (nw as u64) / (w as u64)).max(1)) as u32
            } else {
                nw
            };
            img.resize_exact(nw, nh, image::imageops::FilterType::Lanczos3)
                .save_with_format(&output_path, image::ImageFormat::Jpeg)
                .map_err(|e| ReelVaultError::ThumbnailGenerationFailed(e.to_string()))?;
            Ok(())
        }

        #[cfg(not(any(target_os = "ios", target_os = "android")))]
        {
            let output = crate::ffmpeg::ffmpeg_command()
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
    }

    pub fn get_thumbnail(
        cache_dir: &Path,
        video_id: &str,
        size: &str,
    ) -> Result<Option<Vec<u8>>> {
        let path = cache_dir.join(format!("{}_{}.jpg", video_id, size));
        read_cache_file(&path)
    }

    pub fn cleanup_thumbnails(cache_dir: &Path, video_id: &str) -> Result<()> {
        // Sweep every cache file for this video: the fixed sizes, the scrub
        // frames, and any higher-resolution `{id}_{size}_w{width}.jpg` detail
        // variants (whose widths we don't track here). The `{id}_` prefix is
        // collision-free since ids are UUIDs.
        if let Ok(entries) = std::fs::read_dir(cache_dir) {
            let prefix = format!("{}_", video_id);
            for entry in entries.flatten() {
                if let Some(name) = entry.file_name().to_str() {
                    if name.starts_with(&prefix) {
                        let _ = std::fs::remove_file(entry.path());
                    }
                }
            }
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
        source: &MediaSource,
        video_id: &str,
        cache_dir: &Path,
        duration_secs: f64,
    ) -> Result<()> {
        if !backend().is_available() {
            return Err(ReelVaultError::FfmpegError(
                "media backend unavailable (ffmpeg not found in PATH?)".to_string(),
            ));
        }
        if duration_secs <= 0.5 {
            // Too short to meaningfully scrub; skip.
            return Ok(());
        }

        // Probe color info once — applied to every scrub frame from this video.
        let color_info = backend().probe_color(source);

        // ProRes RAW on macOS: ffmpeg can't develop it. Prefer real per-position
        // frames via the embedded AVFoundation helper (the OS decoder seeks to a
        // timestamp); only if that's unavailable do we fall back to a single
        // QuickLook poster reused for every position (`qlmanage` is poster-only,
        // so every frame would be identical — the bug this avoids). A correct
        // still still beats ffmpeg's dark/flat ProRes RAW frames. Local-file
        // sources only (Photos/Bookmark are iOS, handled by the native backend).
        if let Some(video_path) = source.as_path() {
        if use_quicklook(&color_info) {
            let count = Self::SCRUB_FRAME_COUNT;
            // Try real frames first (only when the helper is actually embedded).
            let mut all_ok = frameshot_path().is_some();
            if all_ok {
                for i in 0..count {
                    let out = cache_dir.join(format!("{}_scrub_{}.jpg", video_id, i));
                    if out.exists() {
                        continue;
                    }
                    let pct = if count > 1 {
                        0.05 + (i as f64 / (count - 1) as f64) * 0.9
                    } else {
                        0.5
                    };
                    if frameshot_extract(video_path, &out, duration_secs * pct, Self::SCRUB_WIDTH)
                        .is_err()
                    {
                        all_ok = false;
                        break;
                    }
                }
            }
            if all_ok {
                return Ok(());
            }
            // Helper missing or failed — reuse one poster for the remaining
            // positions (won't overwrite any real frames already written).
            let poster = cache_dir.join(format!("{}_qlscrub.jpg", video_id));
            if quicklook_poster(video_path, &poster, Self::SCRUB_WIDTH).is_ok() {
                for i in 0..count {
                    let out = cache_dir.join(format!("{}_scrub_{}.jpg", video_id, i));
                    if !out.exists() {
                        let _ = std::fs::copy(&poster, &out);
                    }
                }
                let _ = std::fs::remove_file(&poster);
                return Ok(());
            }
            // QuickLook failed too — fall through to the ffmpeg path below.
        }
        } // end: local-file ProRes-RAW fast path

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

            // One frame per position at SCRUB_WIDTH, JPEG quality 6. The backend
            // acquires/releases an ffmpeg permit per call, so a slot frees for
            // another concurrent run between iterations.
            if let Err(e) =
                backend().extract_frame(source, &color_info, seek_pos, Self::SCRUB_WIDTH, 6, &output)
            {
                tracing::warn!(
                    "Scrub frame {} for {} (t={:.3}s) failed: {}",
                    i,
                    video_id,
                    seek_pos,
                    e
                );
            }
        }
        Ok(())
    }

    /// Cache filename for a `(size, max_width)` thumbnail request. `max_width
    /// <= 0` is the default `{id}_{size}.jpg`; otherwise a higher-resolution
    /// variant keyed by the requested width, `{id}_{size}_w{max_width}.jpg`.
    pub fn thumbnail_filename(video_id: &str, size: &str, max_width: i32) -> String {
        if max_width > 0 {
            format!("{}_{}_w{}.jpg", video_id, size, max_width)
        } else {
            format!("{}_{}.jpg", video_id, size)
        }
    }

    /// Read a higher-resolution thumbnail variant from cache, if present.
    pub fn get_thumbnail_at_width(
        cache_dir: &Path,
        video_id: &str,
        size: &str,
        max_width: i32,
    ) -> Result<Option<Vec<u8>>> {
        let path = cache_dir.join(Self::thumbnail_filename(video_id, size, max_width));
        read_cache_file(&path)
    }

    /// Seek position (seconds) for the frame a `size` token names: `scrub_N`
    /// resolves to the Nth of [`SCRUB_FRAME_COUNT`](Self::SCRUB_FRAME_COUNT)
    /// positions evenly spread across 5%..95% (matching
    /// [`generate_scrub_thumbnails`](Self::generate_scrub_thumbnails));
    /// anything else resolves to the 50% poster frame.
    fn frame_seek_pos(size: &str, duration_secs: f64) -> f64 {
        if let Some(n) = size.strip_prefix("scrub_").and_then(|s| s.parse::<usize>().ok()) {
            let count = Self::SCRUB_FRAME_COUNT;
            let pct = if count > 1 {
                0.05 + (n as f64 / (count - 1) as f64) * 0.9
            } else {
                0.5
            };
            duration_secs * pct
        } else {
            duration_secs * 0.5
        }
    }

    /// Extract a single frame (the one `size` names) at `max_width` pixels wide,
    /// never upscaled past the source (`scale=min(W,iw)`), and cache it under
    /// the width-specific name. Idempotent: a no-op if the file already exists.
    /// Used by the detail view to fetch display-resolution scrub frames on
    /// demand without baking full-resolution stills for high-resolution videos.
    pub fn generate_frame_at_width(
        source: &MediaSource,
        video_id: &str,
        cache_dir: &Path,
        duration_secs: f64,
        size: &str,
        max_width: i32,
    ) -> Result<()> {
        if max_width <= 0 || duration_secs <= 0.0 {
            return Ok(());
        }
        if !backend().is_available() {
            return Err(ReelVaultError::FfmpegError(
                "media backend unavailable (ffmpeg not found in PATH?)".to_string(),
            ));
        }
        let output = cache_dir.join(Self::thumbnail_filename(video_id, size, max_width));
        if output.exists() {
            return Ok(());
        }
        let seek_pos = Self::frame_seek_pos(size, duration_secs);
        let color_info = backend().probe_color(source);

        // ProRes RAW on macOS: a real frame at the requested position via the
        // AVFoundation helper (so hi-res scrub_N frames differ), falling back to
        // the QuickLook poster when the helper isn't available. Local-file
        // sources only (Photos/Bookmark are iOS, handled by the native backend).
        if let Some(video_path) = source.as_path() {
            if use_quicklook(&color_info) {
                if frameshot_extract(video_path, &output, seek_pos, max_width).is_ok() {
                    return Ok(());
                }
                if quicklook_poster(video_path, &output, max_width).is_ok() {
                    return Ok(());
                }
            }
        }

        // One frame at the requested width, JPEG quality 3 (the detail view
        // wants a crisper still than the grid).
        backend().extract_frame(source, &color_info, seek_pos, max_width, 3, &output)
    }
}

/// Read a cache file: `Ok(None)` only when the file genuinely doesn't exist;
/// every other IO failure (fd exhaustion, permissions, a transient mount
/// error…) is an `Err`. The previous `exists()`-then-read pattern collapsed
/// those failures into "no thumbnail" — `Path::exists()` returns `false` on
/// *any* stat error — leaving clients unable to tell a real miss from a
/// transient one.
fn read_cache_file(path: &Path) -> Result<Option<Vec<u8>>> {
    match std::fs::read(path) {
        Ok(data) => Ok(Some(data)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(ReelVaultError::IoError(e)),
    }
}

/// Color metadata probed from a single video stream — used to decide whether
/// thumbnail extraction needs a tonemap/colorspace conversion step. Public so
/// the [`crate::media_backend::MediaBackend`] trait can pass it to
/// `extract_frame` (probed once per video, reused across scrub frames).
#[derive(Debug, Default, Clone)]
pub struct ColorInfo {
    pub codec_name: String,
    pub pix_fmt: String,
    pub color_space: String,
    pub color_transfer: String,
    pub color_primaries: String,
}

/// Run ffprobe once to fetch the fields needed by [`build_thumbnail_vf`].
/// Cheap (~30ms) and tolerant of failure — on any error the returned
/// `ColorInfo` is all-empty, which makes [`build_thumbnail_vf`] fall back
/// to the plain scale filter (preserving prior behavior).
pub(crate) fn probe_color_info(video_path: &Path) -> ColorInfo {
    let mut info = ColorInfo::default();
    let output = crate::ffmpeg::ffprobe_command()
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

/// The shared ffmpeg frame-extraction leaf behind every thumbnail call site
/// (still / scrub / on-demand) and the CLI [`crate::media_backend::MediaBackend`]
/// `extract_frame`. Decodes one frame at `seek_secs`, scales so the longest side
/// is `max_px` (never upscaled — `scale=min(max_px,iw):-1`), tonemaps per
/// `color`, and writes a JPEG at ffmpeg `-q:v quality`. Acquires one ffmpeg
/// permit for the call. The args reproduce the previous per-site invocations
/// verbatim (only the per-site `-q:v` and scale width differed), so cached
/// thumbnails are bit-identical.
pub(crate) fn ffmpeg_extract_frame(
    video_path: &Path,
    color: &ColorInfo,
    seek_secs: f64,
    max_px: i32,
    quality: u8,
    out: &Path,
) -> Result<()> {
    let scale = format!("scale=min({}\\,iw):-1", max_px);
    let vf = build_thumbnail_vf(color, &scale);

    let _permit = acquire_ffmpeg_permit();
    let output = crate::ffmpeg::ffmpeg_command()
        .args([
            "-v",
            "error",
            "-ss",
            &format!("{:.3}", seek_secs),
            "-i",
            video_path.to_str().unwrap_or(""),
            "-frames:v",
            "1",
            "-vf",
            &vf,
            "-q:v",
            &quality.to_string(),
            "-y",
            out.to_str().unwrap_or(""),
        ])
        .output()
        .map_err(|e| ReelVaultError::FfmpegError(format!("Failed to run ffmpeg: {}", e)))?;

    if !output.status.success() {
        return Err(ReelVaultError::ThumbnailGenerationFailed(
            String::from_utf8_lossy(&output.stderr).to_string(),
        ));
    }
    Ok(())
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

/// True when we should bypass ffmpeg and use the macOS QuickLook decoder for
/// this clip's thumbnails. ffmpeg's experimental ProRes RAW decoder doesn't
/// develop S-Log3 / S-Gamut3.Cine footage — it comes out dark, flat and in the
/// wrong gamut, and no `-vf` curve fixes the gamut. QuickLook uses the same
/// system decoder AVPlayer does, so the thumbnail matches playback. macOS only;
/// every other platform keeps the ffmpeg path.
fn use_quicklook(ci: &ColorInfo) -> bool {
    cfg!(target_os = "macos") && ci.codec_name == "prores_raw"
}

// The embedded `rv-frameshot` helper bytes (Some on macOS when swiftc was
// available at build time, else None) — see build.rs::emit_frameshot.
mod frameshot {
    include!(concat!(env!("OUT_DIR"), "/frameshot.rs"));
}

/// Path to the extracted `rv-frameshot` helper, or None when it wasn't embedded
/// (non-macOS, or `swiftc` absent at build time). The embedded bytes are written
/// once to a temp path (reused across calls) and marked executable.
fn frameshot_path() -> Option<&'static Path> {
    static PATH: OnceLock<Option<PathBuf>> = OnceLock::new();
    PATH.get_or_init(|| {
        let bytes = frameshot::FRAMESHOT_BIN?;
        let dest = std::env::temp_dir().join("reelvault-rv-frameshot");
        // Rewrite unless an identical-length copy is already present (cheap
        // staleness check across daemon restarts / version bumps).
        let needs_write = std::fs::metadata(&dest)
            .map(|m| m.len() != bytes.len() as u64)
            .unwrap_or(true);
        if needs_write && std::fs::write(&dest, bytes).is_err() {
            return None;
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&dest, std::fs::Permissions::from_mode(0o755));
        }
        Some(dest)
    })
    .as_deref()
}

/// Extract the frame at `time_secs` from `video_path` into `out_path` (JPEG),
/// longest side ≤ `max_px`, via the embedded AVFoundation helper. The OS decoder
/// is the only thing that both reads ProRes RAW correctly *and* seeks to a
/// timestamp (`qlmanage` is poster-only; ffmpeg can't develop it). Errors when
/// the helper is unavailable or extraction fails, so callers fall back to the
/// single QuickLook poster.
fn frameshot_extract(
    video_path: &Path,
    out_path: &Path,
    time_secs: f64,
    max_px: i32,
) -> Result<()> {
    let helper = frameshot_path().ok_or_else(|| {
        ReelVaultError::ThumbnailGenerationFailed("rv-frameshot helper unavailable".into())
    })?;
    let _permit = acquire_ffmpeg_permit();
    let out = std::process::Command::new(helper)
        .arg(video_path)
        .arg(out_path)
        .arg(format!("{:.3}", time_secs.max(0.0)))
        .arg(max_px.to_string())
        .output();
    match out {
        Ok(o) if o.status.success() && out_path.exists() => Ok(()),
        Ok(o) => Err(ReelVaultError::ThumbnailGenerationFailed(format!(
            "rv-frameshot failed: {}",
            String::from_utf8_lossy(&o.stderr)
        ))),
        Err(e) => Err(ReelVaultError::ThumbnailGenerationFailed(e.to_string())),
    }
}

/// Render a QuickLook poster for `video_path` into `out_path` (JPEG), scaled so
/// its longest side is at most `max_px`. Runs `qlmanage -t` (the OS QuickLook
/// generators) into a unique scratch dir, then transcodes the resulting PNG to
/// `out_path` via ffmpeg. QuickLook only yields a single poster frame, so for a
/// ProRes RAW clip every thumbnail/scrub size is this one frame — correct
/// colour beats true scrubbing, which ffmpeg can't develop here anyway.
fn quicklook_poster(video_path: &Path, out_path: &Path, max_px: i32) -> Result<()> {
    let parent = out_path.parent().ok_or_else(|| {
        ReelVaultError::ThumbnailGenerationFailed("thumbnail output path has no parent".into())
    })?;
    // Unique scratch dir keyed by the output stem, so concurrent sizes of the
    // same clip don't race on qlmanage's `<filename>.png` output name.
    let stem = out_path.file_stem().and_then(|s| s.to_str()).unwrap_or("ql");
    let tmp_dir = parent.join(format!(".qltmp_{}", stem));
    let _ = std::fs::create_dir_all(&tmp_dir);
    let in_name = video_path.file_name().and_then(|s| s.to_str()).unwrap_or("input");
    let produced = tmp_dir.join(format!("{}.png", in_name));

    let _permit = acquire_ffmpeg_permit();
    let ql = std::process::Command::new("qlmanage")
        .args(["-t", "-s", &max_px.to_string(), "-o"])
        .arg(&tmp_dir)
        .arg(video_path)
        .output();
    let ql_failure = match ql {
        Ok(_) if produced.exists() => None,
        Ok(o) => Some(String::from_utf8_lossy(&o.stderr).to_string()),
        Err(e) => Some(e.to_string()),
    };
    if let Some(err) = ql_failure {
        let _ = std::fs::remove_dir_all(&tmp_dir);
        return Err(ReelVaultError::ThumbnailGenerationFailed(format!(
            "qlmanage produced no thumbnail: {err}"
        )));
    }

    // Transcode the QuickLook PNG to the requested JPEG output.
    let conv = crate::ffmpeg::ffmpeg_command()
        .args(["-v", "error", "-i"])
        .arg(&produced)
        .args(["-q:v", "4", "-y"])
        .arg(out_path)
        .output();
    let _ = std::fs::remove_dir_all(&tmp_dir);
    match conv {
        Ok(o) if o.status.success() => Ok(()),
        Ok(o) => Err(ReelVaultError::ThumbnailGenerationFailed(
            String::from_utf8_lossy(&o.stderr).to_string(),
        )),
        Err(e) => Err(ReelVaultError::FfmpegError(e.to_string())),
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
        let output = crate::ffmpeg::ffprobe_command()
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

        let output = crate::ffmpeg::ffmpeg_command()
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
        crate::ffmpeg::ffmpeg_command()
            .arg("-version")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }
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
