// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Reader for DJI drone telemetry carried in an `.SRT` subtitle track.
//!
//! DJI drones record per-frame flight + camera telemetry as subtitles — either
//! a sidecar `<clip>.SRT` next to the video or an embedded subtitle stream. The
//! data (GPS, ISO, aperture, shutter, capture time) maps straight onto columns
//! the catalog already has, so this recovers location and shot settings for
//! drone footage that otherwise carries none in its container tags.
//!
//! Two format generations, both handled (verified against real DJI samples):
//!   - **Modern** (Air 2S, Mini 3, Mavic 3, …):
//!     `[iso : 100] [shutter : 1/80.0] [fnum : 280] … [latitude: 41.42] [longitude: 2.23] [altitude: 117.0]`
//!     plus a standalone `2022-08-07 13:40:40,774,808` timestamp line.
//!   - **Older** (Mavic Pro, Phantom): `GPS(149.02,-20.25,16) … ISO:100 Shutter:60 Fnum:2.2`
//!     (note: `GPS(longitude, latitude, altitude)` order) / `TV:60 IR:F2.8`.
//!
//! We extract one representative record (the first with a non-zero GPS fix);
//! the per-frame flight path is a later follow-up, like the GPMF track.

use std::path::Path;

use regex::Regex;

/// Representative DJI telemetry recovered from the SRT. All optional — older
/// formats omit some fields, and a clip may carry GPS but no camera EXIF.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct DjiTelemetry {
    /// (latitude, longitude, optional altitude in metres).
    pub gps: Option<(f64, f64, Option<f64>)>,
    pub iso: Option<i64>,
    /// F-number (e.g. 2.8).
    pub aperture: Option<f64>,
    /// Exposure time in seconds (1/80 → 0.0125).
    pub exposure_time_s: Option<f64>,
    /// Capture timestamp as Unix milliseconds (UTC; SRT carries no offset).
    pub creation_date_ms: Option<i64>,
    /// Full movement path: every GPS fix in the SRT, downsampled to ≤512
    /// points. Empty when the clip carries no GPS lock.
    pub gps_track: Vec<(f64, f64)>,
    /// Total ground distance along `gps_track` in metres (great-circle sum).
    pub track_distance_m: f64,
}

impl DjiTelemetry {
    pub fn is_empty(&self) -> bool {
        self.gps.is_none()
            && self.iso.is_none()
            && self.aperture.is_none()
            && self.exposure_time_s.is_none()
            && self.creation_date_ms.is_none()
    }
}

/// Look for a sidecar `<clip>.srt` / `.SRT` next to `video_path` and parse it.
/// Returns empty when there's no sidecar (the common non-DJI case) or it can't
/// be read. The full file is read so the GPS track covers the entire flight.
pub fn read_sidecar(video_path: &Path) -> DjiTelemetry {
    for ext in ["srt", "SRT"] {
        let sidecar = video_path.with_extension(ext);
        if sidecar == *video_path {
            continue;
        }
        if let Ok(text) = read_full(&sidecar) {
            let t = parse_srt(&text);
            if !t.is_empty() {
                return t;
            }
        }
    }
    DjiTelemetry::default()
}

/// Read an entire file as lossy UTF-8.
fn read_full(path: &Path) -> std::io::Result<String> {
    let bytes = std::fs::read(path)?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

/// Parse DJI telemetry from SRT text. Scalar fields (ISO, aperture, shutter,
/// timestamp) are taken from the first informative record; GPS is collected
/// from every frame to build the full flight path.
pub fn parse_srt(text: &str) -> DjiTelemetry {
    let all_fixes = collect_gps_track(text);
    DjiTelemetry {
        gps: parse_gps_first(text),
        iso: first_capture(text, r"(?i)\biso\s*[:=]\s*(\d+)").and_then(|s| s.parse().ok()),
        aperture: parse_aperture(text),
        exposure_time_s: parse_shutter(text),
        creation_date_ms: parse_datetime(text),
        track_distance_m: crate::gpmf::track_distance(&all_fixes),
        gps_track: crate::gpmf::downsample(&all_fixes, MAX_TRACK_POINTS),
    }
}

/// Maximum GPS points stored after downsampling — matches the GPMF limit.
const MAX_TRACK_POINTS: usize = 512;

/// Collect every GPS fix from the SRT text as (latitude, longitude), skipping
/// (0, 0) sentinels. Handles both modern and older format in one pass.
fn collect_gps_track(text: &str) -> Vec<(f64, f64)> {
    let mut pts: Vec<(f64, f64)> = Vec::new();

    // Modern format: explicit [latitude: …] [longitude: …] labels, one pair per subtitle.
    if let Ok(re) = Regex::new(
        r"(?i)\[latitude\s*[:=]\s*(-?\d+\.?\d*)\].*?\[longitude\s*[:=]\s*(-?\d+\.?\d*)\]",
    ) {
        for caps in re.captures_iter(text) {
            let la = caps.get(1).and_then(|m| m.as_str().parse::<f64>().ok());
            let lo = caps.get(2).and_then(|m| m.as_str().parse::<f64>().ok());
            if let (Some(la), Some(lo)) = (la, lo) {
                if la != 0.0 || lo != 0.0 {
                    pts.push((la, lo));
                }
            }
        }
    }

    // Older format: GPS(lon, lat, alt) tuples (note lon-first ordering).
    if pts.is_empty() {
        if let Ok(re) = Regex::new(
            r"GPS\(\s*(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)\s*\)",
        ) {
            for caps in re.captures_iter(text) {
                let lo = caps.get(1).and_then(|m| m.as_str().parse::<f64>().ok());
                let la = caps.get(2).and_then(|m| m.as_str().parse::<f64>().ok());
                if let (Some(la), Some(lo)) = (la, lo) {
                    if la != 0.0 || lo != 0.0 {
                        pts.push((la, lo));
                    }
                }
            }
        }
    }

    pts
}

/// Compile `pat` and return the first capture group, if any.
fn first_capture(text: &str, pat: &str) -> Option<String> {
    let re = Regex::new(pat).ok()?;
    re.captures(text)?.get(1).map(|m| m.as_str().to_string())
}

/// GPS, trying the modern labelled form first, then the older `GPS(lon,lat,alt)`
/// tuple (note the lon-first ordering). Returns the first non-(0,0) fix.
fn parse_gps_first(text: &str) -> Option<(f64, f64, Option<f64>)> {
    // Modern: explicit latitude / longitude / altitude labels.
    let lat = first_capture(text, r"(?i)\blatitude\s*[:=]\s*(-?\d+\.?\d*)").and_then(|s| s.parse::<f64>().ok());
    let lon = first_capture(text, r"(?i)\blongitude\s*[:=]\s*(-?\d+\.?\d*)").and_then(|s| s.parse::<f64>().ok());
    if let (Some(la), Some(lo)) = (lat, lon) {
        if la != 0.0 || lo != 0.0 {
            // Altitude: `altitude`, `abs_alt`, or `rel_alt` (first present).
            let alt = first_capture(text, r"(?i)\b(?:altitude|abs_alt|rel_alt)\s*[:=]\s*(-?\d+\.?\d*)")
                .and_then(|s| s.parse::<f64>().ok());
            return Some((la, lo, alt));
        }
    }

    // Older: GPS(longitude, latitude, altitude) — take the first non-zero tuple.
    if let Ok(re) = Regex::new(r"GPS\(\s*(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)\s*\)") {
        for caps in re.captures_iter(text) {
            let lon = caps.get(1).and_then(|m| m.as_str().parse::<f64>().ok());
            let lat = caps.get(2).and_then(|m| m.as_str().parse::<f64>().ok());
            let alt = caps.get(3).and_then(|m| m.as_str().parse::<f64>().ok());
            if let (Some(la), Some(lo)) = (lat, lon) {
                if la != 0.0 || lo != 0.0 {
                    return Some((la, lo, alt));
                }
            }
        }
    }
    None
}

/// F-number. Modern writes it ×100 (`fnum : 280` → 2.8); older writes it
/// directly (`Fnum:2.2`, `IR:F2.8`). Disambiguate by magnitude — drone
/// f-numbers are well under 30, so a value above that is the ×100 form.
fn parse_aperture(text: &str) -> Option<f64> {
    let raw = first_capture(text, r"(?i)\bf?num\s*[:=]\s*(\d+\.?\d*)")
        .or_else(|| first_capture(text, r"(?i)\bIR\s*[:=]\s*F?(\d+\.?\d*)"))?;
    let v: f64 = raw.parse().ok()?;
    Some(if v > 30.0 { v / 100.0 } else { v })
}

/// Shutter / exposure time in seconds. `1/80.0` → 0.0125; a bare `60` (or
/// `TV:60`) means 1/60 s.
fn parse_shutter(text: &str) -> Option<f64> {
    let raw = first_capture(text, r"(?i)\bshutter\s*[:=]\s*(\d+/?\d*\.?\d*)")
        .or_else(|| first_capture(text, r"(?i)\bTV\s*[:=]\s*(\d+/?\d*\.?\d*)"))?;
    if let Some((n, d)) = raw.split_once('/') {
        let n: f64 = n.trim().parse().ok()?;
        let d: f64 = d.trim().parse().ok()?;
        if d.abs() < f64::EPSILON {
            return None;
        }
        Some(n / d)
    } else {
        let v: f64 = raw.trim().parse().ok()?;
        if v.abs() < f64::EPSILON {
            None
        } else {
            Some(1.0 / v)
        }
    }
}

/// Capture timestamp → Unix ms (UTC). Modern `2022-08-07 13:40:40`; older
/// `2017.08.05 14:11:51` (also single-digit `2017.8.5`).
fn parse_datetime(text: &str) -> Option<i64> {
    if let Some(s) = first_capture(text, r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})") {
        if let Ok(dt) = chrono::NaiveDateTime::parse_from_str(&s, "%Y-%m-%d %H:%M:%S") {
            return Some(dt.and_utc().timestamp_millis());
        }
    }
    if let Ok(re) = Regex::new(r"(\d{4})\.(\d{1,2})\.(\d{1,2}) (\d{1,2}):(\d{2}):(\d{2})") {
        if let Some(c) = re.captures(text) {
            let g = |i: usize| c.get(i).and_then(|m| m.as_str().parse::<u32>().ok());
            if let (Some(y), Some(mo), Some(d), Some(h), Some(mi), Some(s)) =
                (g(1), g(2), g(3), g(4), g(5), g(6))
            {
                if let Some(date) = chrono::NaiveDate::from_ymd_opt(y as i32, mo, d) {
                    if let Some(t) = date.and_hms_opt(h, mi, s) {
                        return Some(t.and_utc().timestamp_millis());
                    }
                }
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_modern_format() {
        // Air 2S / Mini-style record.
        let srt = "1\n00:00:00,000 --> 00:00:00,033\n<font size=\"36\">SrtCnt : 1, DiffTime : 33ms\n\
                   2022-08-07 13:40:40,774,808\n\
                   [iso : 100] [shutter : 1/80.0] [fnum : 280] [ev : 0] [ct : 5744] \
                   [color_md : default] [focal_len : 240] [latitude: 41.424724] \
                   [longitude: 2.234156] [altitude: 117.000000] </font>\n";
        let t = parse_srt(srt);
        let (la, lo, al) = t.gps.expect("gps");
        assert!((la - 41.424724).abs() < 1e-6);
        assert!((lo - 2.234156).abs() < 1e-6);
        assert!((al.unwrap() - 117.0).abs() < 1e-3);
        assert_eq!(t.iso, Some(100));
        assert!((t.aperture.unwrap() - 2.8).abs() < 1e-9); // 280 → 2.8
        assert!((t.exposure_time_s.unwrap() - 1.0 / 80.0).abs() < 1e-9);
        assert!(t.creation_date_ms.is_some());
    }

    #[test]
    fn parses_older_mavic_format() {
        let srt = "1\n00:00:01,000 --> 00:00:02,000\n\
                   HOME(149.0251,-20.2532) 2017.08.05 14:11:51\n\
                   GPS(149.0251,-20.2533,16) BAROMETER:1.9\n\
                   ISO:100 Shutter:60 EV: Fnum:2.2\n";
        let t = parse_srt(srt);
        let (la, lo, al) = t.gps.expect("gps");
        // GPS(lon, lat, alt) → lat is the second value.
        assert!((la + 20.2533).abs() < 1e-4, "lat={la}");
        assert!((lo - 149.0251).abs() < 1e-4, "lon={lo}");
        assert!((al.unwrap() - 16.0).abs() < 1e-6);
        assert_eq!(t.iso, Some(100));
        assert!((t.aperture.unwrap() - 2.2).abs() < 1e-9); // direct
        assert!((t.exposure_time_s.unwrap() - 1.0 / 60.0).abs() < 1e-9); // bare 60 → 1/60
    }

    #[test]
    fn parses_old_format_ir_aperture() {
        let srt = "GPS(149.0251,-20.2533,16) Hb:1.9 Hs:1.9\nISO:100 TV:60 EV: 0 IR:F2.8\n";
        let t = parse_srt(srt);
        assert_eq!(t.iso, Some(100));
        assert!((t.aperture.unwrap() - 2.8).abs() < 1e-9); // IR:F2.8
        assert!((t.exposure_time_s.unwrap() - 1.0 / 60.0).abs() < 1e-9); // TV:60
    }

    #[test]
    fn collects_full_gps_track() {
        // Three subtitle blocks, each with a distinct GPS fix.
        let srt = "1\n00:00:00,000 --> 00:00:00,033\n\
                   [latitude: 41.424724] [longitude: 2.234156] [iso : 100]\n\n\
                   2\n00:00:00,033 --> 00:00:00,066\n\
                   [latitude: 41.424800] [longitude: 2.234200] [iso : 100]\n\n\
                   3\n00:00:00,066 --> 00:00:00,099\n\
                   [latitude: 41.424900] [longitude: 2.234300] [iso : 100]\n";
        let t = parse_srt(srt);
        assert_eq!(t.gps_track.len(), 3, "expected 3 track points");
        assert!(t.track_distance_m > 0.0, "expected non-zero distance");
    }

    /// Real-file check against the downloaded DJI SRT samples (skips if absent).
    #[test]
    fn reads_real_dji_samples_if_present() {
        let dir = std::path::Path::new("/mammoth/video_archive/imports/dji_srt_samples");
        if !dir.exists() {
            eprintln!("skip: no DJI samples");
            return;
        }
        for name in ["air2s.srt", "mavic_pro.SRT", "mavic_air2.srt", "old_format.SRT"] {
            let p = dir.join(name);
            if !p.exists() {
                continue;
            }
            let text = read_full(&p).unwrap_or_default();
            let t = parse_srt(&text);
            eprintln!(
                "DJI {name}: gps={:?} iso={:?} aperture={:?} shutter={:?} track_pts={} dist_m={:.1}",
                t.gps, t.iso, t.aperture, t.exposure_time_s, t.gps_track.len(), t.track_distance_m
            );
            assert!(t.gps.is_some(), "{name}: expected a GPS fix");
        }
    }
}
