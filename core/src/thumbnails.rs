// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

use crate::ffmpeg::CommandTimeout;
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

        // One frame from the middle of the video, extracted at the largest
        // size (never upscaled). It is published as the large still as-is, and
        // the small/medium sizes are derived from it in-process.
        let thumbnail_frame = Self::extract_frame(source, cache_dir, video_id, duration_secs)?;
        let result = Self::derive_sizes(&thumbnail_frame, video_id, cache_dir);
        let _ = std::fs::remove_file(&thumbnail_frame);
        result
    }

    /// Publish `frame` (already up to `LARGE_WIDTH` wide) as the large still and
    /// derive the small and medium stills from it with the `image` crate — no
    /// further process launches, and the large file is encoded only once.
    fn derive_sizes(frame: &Path, video_id: &str, cache_dir: &Path) -> Result<()> {
        let bytes = std::fs::read(frame)?;
        let img = image::load_from_memory(&bytes)
            .map_err(|e| ReelVaultError::ThumbnailGenerationFailed(e.to_string()))?;
        for (name, width) in [("small", Self::SMALL_WIDTH), ("medium", Self::MEDIUM_WIDTH)] {
            let jpeg = resize_to_jpeg(&img, width as u32)?;
            write_atomic(&cache_dir.join(format!("{}_{}.jpg", video_id, name)), &jpeg)?;
        }
        write_atomic(&cache_dir.join(format!("{}_large.jpg", video_id)), &bytes)?;
        Ok(())
    }

    fn extract_frame(source: &MediaSource, cache_dir: &Path, video_id: &str, duration_secs: f64) -> Result<PathBuf> {
        // Extract frame at 50% through the video
        // Unique per call so concurrent generations never share a scratch file.
        let temp_path = tmp_sibling(&cache_dir.join(format!("{}_temp.jpg", video_id)));

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

        // Frame at up to LARGE_WIDTH, JPEG quality 5 — the source still that
        // `derive_sizes` turns into the small/medium/large variants.
        backend().extract_frame(source, &color_info, seek_pos, Self::LARGE_WIDTH, 5, &temp_path)?;

        Ok(temp_path)
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

    /// Remove cache files whose video id is not in `known_ids` (the catalog's
    /// ids, soft-deleted rows included). A cache file is `{id}_...jpg`; files
    /// younger than `min_age` are left alone so in-flight generation for a row
    /// not yet committed is never raced. At most `max_removals` files go per
    /// call so a huge backlog can't hog the disk; returns how many were removed.
    pub fn sweep_orphans(
        cache_dir: &Path,
        known_ids: &std::collections::HashSet<String>,
        min_age: std::time::Duration,
        max_removals: usize,
    ) -> usize {
        Self::sweep_orphans_since(cache_dir, known_ids, min_age, max_removals, None)
    }

    /// [`sweep_orphans`](Self::sweep_orphans) restricted to files modified at or
    /// after `since` (when given). Used on the shared default cache dir, where
    /// only files written after the catalog registry began are attributable.
    pub fn sweep_orphans_since(
        cache_dir: &Path,
        known_ids: &std::collections::HashSet<String>,
        min_age: std::time::Duration,
        max_removals: usize,
        since: Option<std::time::SystemTime>,
    ) -> usize {
        let Ok(entries) = std::fs::read_dir(cache_dir) else { return 0 };
        let mut removed = 0;
        for entry in entries.flatten() {
            if removed >= max_removals {
                break;
            }
            let name = entry.file_name();
            let Some(name) = name.to_str() else { continue };
            if !name.ends_with(".jpg") || name.starts_with('.') {
                continue;
            }
            let Some((id, _)) = name.split_once('_') else { continue };
            if id.len() < 32 || known_ids.contains(id) {
                continue;
            }
            let modified = entry.metadata().and_then(|m| m.modified()).ok();
            if let (Some(since), Some(m)) = (since, modified) {
                if m < since {
                    continue;
                }
            }
            let old_enough = modified
                .and_then(|t| t.elapsed().ok())
                .map(|age| age >= min_age)
                .unwrap_or(false);
            if old_enough && std::fs::remove_file(entry.path()).is_ok() {
                removed += 1;
            }
        }
        removed
    }

    /// Record `catalog_path` in the shared cache dir's registry so a sweep run
    /// from any catalog knows whose files live there. Starts the registry
    /// (`.catalogs_since`) on first use.
    pub fn register_catalog(cache_dir: &Path, catalog_path: &Path) {
        let since = cache_dir.join(".catalogs_since");
        if !since.exists() {
            let _ = std::fs::write(&since, b"");
        }
        let reg = cache_dir.join(".catalogs");
        let line = catalog_path.to_string_lossy().to_string();
        let existing = std::fs::read_to_string(&reg).unwrap_or_default();
        if existing.lines().any(|l| l == line) {
            return;
        }
        let mut all = existing;
        if !all.is_empty() && !all.ends_with('\n') {
            all.push('\n');
        }
        all.push_str(&line);
        all.push('\n');
        let _ = write_atomic(&reg, all.as_bytes());
    }

    /// Union of video ids across every registered catalog plus the registry
    /// start time, or `None` when any registered catalog can't be read (e.g. its
    /// drive is unmounted) — then nothing in the shared dir may be called an
    /// orphan.
    pub fn shared_known_ids(
        cache_dir: &Path,
    ) -> Option<(std::collections::HashSet<String>, std::time::SystemTime)> {
        let since = std::fs::metadata(cache_dir.join(".catalogs_since"))
            .and_then(|m| m.modified())
            .ok()?;
        let reg = std::fs::read_to_string(cache_dir.join(".catalogs")).ok()?;
        let mut ids = std::collections::HashSet::new();
        for line in reg.lines().filter(|l| !l.is_empty()) {
            let conn = rusqlite::Connection::open_with_flags(
                line,
                rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
            )
            .ok()?;
            let mut stmt = conn.prepare("SELECT id FROM videos").ok()?;
            let rows = stmt.query_map([], |r| r.get::<_, String>(0)).ok()?;
            for r in rows {
                ids.insert(r.ok()?);
            }
        }
        Some((ids, since))
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
                        let _ = publish_via_tmp(&out, |tmp| {
                            std::fs::copy(&poster, tmp)
                                .map(|_| ())
                                .map_err(ReelVaultError::IoError)
                        });
                    }
                }
                let _ = std::fs::remove_file(&poster);
                return Ok(());
            }
            // QuickLook failed too — fall through to the ffmpeg path below.
        }
        } // end: local-file ProRes-RAW fast path

        let count = Self::SCRUB_FRAME_COUNT;
        // Distribute frames across 5%..95% of the video (5%, 15%, ... 95% for
        // 10 frames). Only frames that don't exist yet are requested, and all
        // of them come from a single ffmpeg run.
        let mut jobs: Vec<(f64, PathBuf)> = Vec::new();
        for i in 0..count {
            let output = cache_dir.join(format!("{}_scrub_{}.jpg", video_id, i));
            if output.exists() {
                continue;
            }
            let pct = if count > 1 {
                0.05 + (i as f64 / (count - 1) as f64) * 0.9
            } else {
                0.5
            };
            jobs.push((duration_secs * pct, output));
        }
        if jobs.is_empty() {
            return Ok(());
        }
        if let Err(e) =
            backend().extract_frames(source, &color_info, &jobs, Self::SCRUB_WIDTH, 6)
        {
            tracing::warn!("Scrub frames for {} failed: {}", video_id, e);
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

static TMP_COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// A unique scratch path next to `out` (same directory, so a rename is atomic,
/// and same `{id}_` prefix so per-video cleanup and the orphan sweep cover
/// leftovers). Keeps a `.jpg` extension so ffmpeg infers the muxer.
pub(crate) fn tmp_sibling(out: &Path) -> PathBuf {
    let n = TMP_COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let stem = out.file_stem().and_then(|s| s.to_str()).unwrap_or("thumb");
    out.with_file_name(format!("{}.tmp{}-{}.jpg", stem, std::process::id(), n))
}

/// Write `bytes` to `out` through a unique temp file and rename into place, so a
/// concurrent reader sees either nothing/the old file or the complete new one.
pub(crate) fn write_atomic(out: &Path, bytes: &[u8]) -> Result<()> {
    let tmp = tmp_sibling(out);
    if let Err(e) = std::fs::write(&tmp, bytes).and_then(|_| std::fs::rename(&tmp, out)) {
        let _ = std::fs::remove_file(&tmp);
        return Err(ReelVaultError::IoError(e));
    }
    Ok(())
}

/// Run `produce` against a unique temp path and rename the result onto `out`
/// only on success.
fn publish_via_tmp(out: &Path, produce: impl FnOnce(&Path) -> Result<()>) -> Result<()> {
    let tmp = tmp_sibling(out);
    let r = produce(&tmp).and_then(|_| std::fs::rename(&tmp, out).map_err(ReelVaultError::IoError));
    if r.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    r
}

/// Downscale `img` to `width` (aspect-preserving, never upscaled) and encode JPEG.
fn resize_to_jpeg(img: &image::DynamicImage, width: u32) -> Result<Vec<u8>> {
    use image::GenericImageView;
    let (w, h) = img.dimensions();
    let resized;
    let src = if w > width && w > 0 {
        let nh = (((h as u64) * (width as u64) / (w as u64)).max(1)) as u32;
        resized = img.resize_exact(width, nh, image::imageops::FilterType::Lanczos3);
        &resized
    } else {
        img
    };
    let mut buf = Vec::new();
    src.to_rgb8()
        .write_to(&mut std::io::Cursor::new(&mut buf), image::ImageFormat::Jpeg)
        .map_err(|e| ReelVaultError::ThumbnailGenerationFailed(e.to_string()))?;
    Ok(buf)
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
        .output_timeout(crate::ffmpeg::PROBE_TIMEOUT);
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
    let vf = match tonemap_prefix(ci) {
        Some(prefix) => format!("{},{}", prefix, scale_filter),
        None => scale_filter.to_string(),
    };
    // ffmpeg 8.x MJPEG encoder rejects limited-range YUV with
    // "ff_frame_thread_encoder_init failed". JPEG is inherently full-range, so
    // expand any limited-range signal to yuvj420p before the encoder.
    format!("{},format=yuvj420p", vf)
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
    publish_via_tmp(out, |tmp| {
        ffmpeg_extract_frame_to(video_path, color, seek_secs, max_px, quality, tmp)
    })
}

fn ffmpeg_extract_frame_to(
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
        .output_timeout(crate::ffmpeg::FRAME_TIMEOUT)
        .map_err(|e| ReelVaultError::FfmpegError(format!("Failed to run ffmpeg: {}", e)))?;

    if !output.status.success() {
        return Err(ReelVaultError::ThumbnailGenerationFailed(
            String::from_utf8_lossy(&output.stderr).to_string(),
        ));
    }
    Ok(())
}

/// Extract several frames in ONE ffmpeg launch: the file is opened once per
/// `(seek, output)` job as a separate fast-seeked input and each is mapped to its
/// own output with one frame. Outputs are written to temp names and renamed into
/// place only if ffmpeg succeeded. If the batch fails, each frame is retried
/// individually so one bad position loses only its own frame.
pub(crate) fn ffmpeg_extract_frames(
    video_path: &Path,
    color: &ColorInfo,
    jobs: &[(f64, PathBuf)],
    max_px: i32,
    quality: u8,
) -> Result<()> {
    if jobs.is_empty() {
        return Ok(());
    }
    let vf = build_thumbnail_vf(color, &format!("scale=min({}\\,iw):-1", max_px));
    let tmps: Vec<PathBuf> = jobs.iter().map(|(_, o)| tmp_sibling(o)).collect();

    let mut cmd = crate::ffmpeg::ffmpeg_command();
    cmd.args(["-v", "error"]);
    for (seek, _) in jobs {
        cmd.args(["-ss", &format!("{:.3}", seek), "-i", video_path.to_str().unwrap_or("")]);
    }
    for (i, tmp) in tmps.iter().enumerate() {
        cmd.args(["-map", &format!("{}:v:0", i), "-frames:v", "1", "-vf", &vf]);
        cmd.args(["-q:v", &quality.to_string(), "-y"]);
        cmd.arg(tmp);
    }

    let _permit = acquire_ffmpeg_permit();
    let output = cmd
        .output_timeout(crate::ffmpeg::FRAME_TIMEOUT)
        .map_err(|e| ReelVaultError::FfmpegError(format!("Failed to run ffmpeg: {}", e)));
    let result = match output {
        Ok(o) if o.status.success() => Ok(()),
        Ok(o) => Err(ReelVaultError::ThumbnailGenerationFailed(
            String::from_utf8_lossy(&o.stderr).to_string(),
        )),
        Err(e) => Err(e),
    };
    // A failed run may leave truncated outputs, so publish only after success.
    for (tmp, (_, out)) in tmps.iter().zip(jobs) {
        if result.is_ok() && std::fs::rename(tmp, out).is_ok() {
            continue;
        }
        let _ = std::fs::remove_file(tmp);
    }
    if result.is_err() && jobs.len() > 1 {
        // One undecodable position must not cost every frame: retry each one on
        // its own (atomic publish). Failed positions are simply left missing.
        let mut any = false;
        for (seek, out) in jobs {
            any |= ffmpeg_extract_frames(video_path, color, &[(*seek, out.clone())], max_px, quality)
                .is_ok();
        }
        return if any { Ok(()) } else { result };
    }
    result
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
    publish_via_tmp(out_path, |tmp| frameshot_extract_to(video_path, tmp, time_secs, max_px))
}

fn frameshot_extract_to(
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
        .output_timeout(crate::ffmpeg::FRAME_TIMEOUT);
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
    publish_via_tmp(out_path, |tmp| quicklook_poster_to(video_path, tmp, max_px))
}

fn quicklook_poster_to(video_path: &Path, out_path: &Path, max_px: i32) -> Result<()> {
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
        .output_timeout(crate::ffmpeg::FRAME_TIMEOUT);
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
        .output_timeout(crate::ffmpeg::FRAME_TIMEOUT);
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
            .output_timeout(crate::ffmpeg::PROBE_TIMEOUT)
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
            .output_timeout(crate::ffmpeg::TRANSCODE_TIMEOUT)
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
            .output_timeout(crate::ffmpeg::PROBE_TIMEOUT)
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
        assert_eq!(vf, "scale=400:-1,format=yuvj420p");
    }

    #[test]
    fn sdr_smpte170m_passes_through_unchanged() {
        // The "BT2020F"-named ProRes proxies on disk actually carry
        // smpte170m/bt709 tags — they must NOT be tone-mapped.
        let info = ci("prores", "yuv422p10le", "smpte170m", "bt709", "smpte170m");
        let vf = build_thumbnail_vf(&info, "scale=400:-1");
        assert_eq!(vf, "scale=400:-1,format=yuvj420p");
    }

    #[test]
    fn prores_raw_triggers_tonemap() {
        let info = ci("prores_raw", "gbrpf32le", "", "", "");
        let vf = build_thumbnail_vf(&info, "scale=400:-1");
        assert!(vf.starts_with("format=gbrpf32le,tonemap="));
        assert!(vf.contains("scale=400:-1"));
        assert!(vf.ends_with(",format=yuvj420p"));
    }

    #[test]
    fn bt2020_pq_uses_colorspace_fallback() {
        let info = ci("hevc", "yuv420p10le", "bt2020nc", "smpte2084", "bt2020");
        let vf = build_thumbnail_vf(&info, "scale=400:-1");
        assert!(vf.contains("colorspace=all=bt709:iall=bt2020"));
        assert!(vf.contains("scale=400:-1"));
        assert!(vf.ends_with(",format=yuvj420p"));
    }

    #[test]
    fn bt2020_sdr_uses_colorspace_with_native_trc() {
        let info = ci("hevc", "yuv420p10le", "bt2020nc", "bt2020-10", "bt2020");
        let vf = build_thumbnail_vf(&info, "scale=400:-1");
        assert!(vf.contains("itrc=bt2020-10"));
        assert!(vf.ends_with(",format=yuvj420p"));
    }
}

#[cfg(test)]
mod cache_tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn publish_via_tmp_never_exposes_a_partial_file() {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("p_large.jpg");
        let full_a = vec![b'a'; 256 * 1024];
        let full_b = vec![b'b'; 512 * 1024];
        publish_via_tmp(&out, |t| std::fs::write(t, &full_a).map_err(ReelVaultError::IoError)).unwrap();
        let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let readers: Vec<_> = (0..4)
            .map(|_| {
                let (out, stop, a, b) = (out.clone(), stop.clone(), full_a.clone(), full_b.clone());
                std::thread::spawn(move || {
                    while !stop.load(std::sync::atomic::Ordering::Relaxed) {
                        let got = std::fs::read(&out).unwrap();
                        assert!(got == a || got == b, "observed a partial file ({} bytes)", got.len());
                    }
                })
            })
            .collect();
        for i in 0..50 {
            let data = if i % 2 == 0 { &full_b } else { &full_a };
            publish_via_tmp(&out, |t| std::fs::write(t, data).map_err(ReelVaultError::IoError)).unwrap();
        }
        // A failed producer must leave the published file untouched.
        let _ = publish_via_tmp(&out, |t| {
            std::fs::write(t, b"partial").unwrap();
            Err(ReelVaultError::ThumbnailGenerationFailed("x".into()))
        });
        stop.store(true, std::sync::atomic::Ordering::Relaxed);
        for r in readers {
            r.join().unwrap();
        }
        let got = std::fs::read(&out).unwrap();
        assert!(got == full_a || got == full_b);
    }

    #[test]
    fn concurrent_readers_never_see_a_partial_file() {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("a_large.jpg");
        let full_a = vec![b'a'; 256 * 1024];
        let full_b = vec![b'b'; 512 * 1024];
        write_atomic(&out, &full_a).unwrap();
        let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let readers: Vec<_> = (0..4)
            .map(|_| {
                let (out, stop, a, b) = (out.clone(), stop.clone(), full_a.clone(), full_b.clone());
                std::thread::spawn(move || {
                    let mut reads = 0;
                    while !stop.load(std::sync::atomic::Ordering::Relaxed) {
                        let got = std::fs::read(&out).unwrap();
                        assert!(got == a || got == b, "observed a partial file ({} bytes)", got.len());
                        reads += 1;
                    }
                    reads
                })
            })
            .collect();
        let writers: Vec<_> = (0..2)
            .map(|w| {
                let (out, a, b) = (out.clone(), full_a.clone(), full_b.clone());
                std::thread::spawn(move || {
                    for i in 0..200 {
                        write_atomic(&out, if (i + w) % 2 == 0 { &a } else { &b }).unwrap();
                    }
                })
            })
            .collect();
        for w in writers {
            w.join().unwrap();
        }
        stop.store(true, std::sync::atomic::Ordering::Relaxed);
        for r in readers {
            assert!(r.join().unwrap() > 0);
        }
        // No temp files left behind.
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
    }

    #[test]
    fn derived_sizes_come_from_the_large_source() {
        use image::GenericImageView;
        let dir = tempfile::tempdir().unwrap();
        let frame = dir.path().join("frame.jpg");
        let img = image::DynamicImage::ImageRgb8(image::RgbImage::new(800, 450));
        let jpeg = resize_to_jpeg(&img, 800).unwrap();
        std::fs::write(&frame, &jpeg).unwrap();
        let id = "11111111-1111-1111-1111-111111111111";
        ThumbnailGenerator::derive_sizes(&frame, id, dir.path()).unwrap();
        let dims = |n: &str| {
            image::open(dir.path().join(format!("{id}_{n}.jpg"))).unwrap().dimensions()
        };
        assert_eq!(dims("large"), (800, 450));
        assert_eq!(dims("medium"), (400, 225));
        assert_eq!(dims("small"), (200, 112));
    }

    #[test]
    fn sweep_removes_only_unknown_ids() {
        let dir = tempfile::tempdir().unwrap();
        let keep = "22222222-2222-2222-2222-222222222222";
        let gone = "33333333-3333-3333-3333-333333333333";
        for id in [keep, gone] {
            for n in ["small", "scrub_0"] {
                std::fs::write(dir.path().join(format!("{id}_{n}.jpg")), b"x").unwrap();
            }
        }
        std::fs::write(dir.path().join("notes.txt"), b"x").unwrap();
        let known: HashSet<String> = [keep.to_string()].into();
        // Too young: nothing removed.
        assert_eq!(
            ThumbnailGenerator::sweep_orphans(dir.path(), &known, std::time::Duration::from_secs(3600), 100),
            0
        );
        // Bounded.
        assert_eq!(ThumbnailGenerator::sweep_orphans(dir.path(), &known, std::time::Duration::ZERO, 1), 1);
        assert_eq!(ThumbnailGenerator::sweep_orphans(dir.path(), &known, std::time::Duration::ZERO, 100), 1);
        assert!(dir.path().join(format!("{keep}_small.jpg")).exists());
        assert!(dir.path().join(format!("{keep}_scrub_0.jpg")).exists());
        assert!(!dir.path().join(format!("{gone}_small.jpg")).exists());
        assert!(dir.path().join("notes.txt").exists());
    }

    #[test]
    fn shared_registry_sweep_spares_other_catalogs_and_legacy_files() {
        let dir = tempfile::tempdir().unwrap();
        let cat_dir = tempfile::tempdir().unwrap();
        let mine = "44444444-4444-4444-4444-444444444444";
        let other = "55555555-5555-5555-5555-555555555555";
        let gone = "66666666-6666-6666-6666-666666666666";
        let legacy = "77777777-7777-7777-7777-777777777777";
        // Legacy file predates the registry: unattributable, never swept.
        std::fs::write(dir.path().join(format!("{legacy}_small.jpg")), b"x").unwrap();
        std::thread::sleep(std::time::Duration::from_millis(50));
        let mut paths = Vec::new();
        for (name, id) in [("a.db", mine), ("b.db", other)] {
            let p = cat_dir.path().join(name);
            let c = rusqlite::Connection::open(&p).unwrap();
            c.execute("CREATE TABLE videos (id TEXT)", []).unwrap();
            c.execute("INSERT INTO videos (id) VALUES (?1)", [id]).unwrap();
            ThumbnailGenerator::register_catalog(dir.path(), &p);
            ThumbnailGenerator::register_catalog(dir.path(), &p); // idempotent
            paths.push(p);
        }
        for id in [mine, other, gone] {
            std::fs::write(dir.path().join(format!("{id}_small.jpg")), b"x").unwrap();
        }
        let (ids, since) = ThumbnailGenerator::shared_known_ids(dir.path()).unwrap();
        let n = ThumbnailGenerator::sweep_orphans_since(
            dir.path(), &ids, std::time::Duration::ZERO, 100, Some(since),
        );
        assert_eq!(n, 1);
        assert!(dir.path().join(format!("{mine}_small.jpg")).exists());
        assert!(dir.path().join(format!("{other}_small.jpg")).exists());
        assert!(dir.path().join(format!("{legacy}_small.jpg")).exists());
        assert!(!dir.path().join(format!("{gone}_small.jpg")).exists());
        // An unreadable registered catalog disables the sweep.
        std::fs::remove_file(&paths[1]).unwrap();
        assert!(ThumbnailGenerator::shared_known_ids(dir.path()).is_none());
    }
}
