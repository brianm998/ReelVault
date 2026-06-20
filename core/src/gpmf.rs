// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Native reader for GoPro's GPMF telemetry track (`gpmd`).
//!
//! GoPro cameras (HERO5+, Fusion, MAX, and the Karma drone) record a timed
//! metadata track in **GPMF** — a nested key-length-value binary format — that
//! ffprobe surfaces only as an opaque `bin_data` stream. Inside it sits the
//! data ReelVault otherwise can't see for GoPro clips: the **GPS track**, the
//! **camera/device name**, accelerometer/gyroscope series, and per-clip
//! settings. Crucially, GoPro stores GPS *only* here — not in the
//! `com.apple.quicktime.location` container tag — so without this a GoPro clip
//! shows no location and no camera in the catalog even though the data is in
//! the file. (Same shape as the iPhone-lens gap [`crate::quicktime`] closes.)
//!
//! ## Scope (high-value slice)
//!
//! We extract a single representative **GPS fix** and the **device name**, both
//! of which feed columns the catalog already has (`gps_*`, `camera_model`). The
//! full per-frame telemetry (GPS path, accel, gyro) is a larger follow-up — a
//! motion/track visualization, like the loudness series — and isn't read here.
//!
//! ## Two layers
//!
//! 1. **MP4 sample table** — the GPMF payloads are timed samples interleaved in
//!    `mdat`, located via the `gpmd` track's `stbl` (`stsd`/`stsz`/`stco`/`co64`/
//!    `stsc`). We read just the **first chunk** (≈ the first second of
//!    telemetry), which carries a complete device record + GPS, so the read
//!    stays bounded regardless of clip length.
//! 2. **GPMF KLV** — each element is `[FourCC:4][type:1][struct_size:1]
//!    [repeat:2 BE]` followed by `struct_size × repeat` bytes, padded to a
//!    4-byte boundary. `type == 0` marks a nested container (DEVC, STRM). All
//!    multi-byte numbers are big-endian.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

/// Cap on the GPMF bytes we read from `mdat` — the first chunk is normally a
/// few KB to tens of KB; this only guards against a pathological sample table.
const MAX_GPMF_BYTES: u64 = 2 * 1024 * 1024;
/// Cap on a table payload (`stsz`/`stco`/`stsc`) we'll buffer — generous for
/// even hour-long clips (a few samples per second).
const MAX_TABLE_BYTES: u64 = 8 * 1024 * 1024;
/// Atom-walk depth ceiling (defensive).
const MAX_DEPTH: u8 = 8;

/// What we recover from a GoPro GPMF track. Empty for non-GoPro files (and any
/// file without a readable `gpmd` track).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct GpmfMetadata {
    /// Recording device / camera name, from `DVNM` (e.g. "Hero6 Black",
    /// "GoPro Karma v1.0"). The per-clip "Video Global Settings" / "Highlights"
    /// pseudo-devices are skipped.
    pub device_name: Option<String>,
    /// First valid GPS fix: (latitude, longitude, optional altitude in metres),
    /// from the `GPS5` stream scaled by its `SCAL` divisors. `None` when the
    /// clip carries no GPS lock.
    pub gps: Option<(f64, f64, Option<f64>)>,
    /// The full movement path: every valid GPS sample over the clip, as
    /// (latitude, longitude), downsampled to a sane point count. Empty when
    /// there's no GPS. Drives a flight/track polyline on the map.
    pub gps_track: Vec<(f64, f64)>,
    /// Total ground distance along `gps_track` in metres (great-circle sum).
    pub track_distance_m: f64,
}

impl GpmfMetadata {
    pub fn is_empty(&self) -> bool {
        self.device_name.is_none() && self.gps.is_none()
    }
}

/// Largest point count we keep for a stored track — enough for a smooth map
/// polyline without bloating the row (GoPro logs GPS at ~18 Hz).
const MAX_TRACK_POINTS: usize = 512;

/// Read the GoPro GPMF track from `path`, returning what we recovered. Never
/// errors: a missing/unreadable/`gpmd`-less file yields an empty result, like
/// [`crate::xmp::read_xmp`]. Callers gate the call on the presence of a `gpmd`
/// stream so non-GoPro files are never opened twice.
pub fn read_gpmf(path: &Path) -> GpmfMetadata {
    read_inner(path).ok().flatten().unwrap_or_default()
}

fn read_inner(path: &Path) -> std::io::Result<Option<GpmfMetadata>> {
    let mut f = File::open(path)?;
    let end = f.seek(SeekFrom::End(0))?;

    // moov → (the gpmd) trak → mdia → minf → stbl
    let children = collect_children(&mut f, 0, end, 0)?;
    let moov = match children.iter().find(|c| &c.0 == b"moov") {
        Some(c) => (c.1, c.2),
        None => return Ok(None),
    };

    let stbl = match find_gpmd_stbl(&mut f, moov.0, moov.1)? {
        Some(r) => r,
        None => return Ok(None), // no GPMF track
    };

    // Read every GPMF sample (all chunks) so we can build the full GPS track,
    // not just a single fix. Bounded by MAX_GPMF_BYTES.
    let payload = match read_all_chunks(&mut f, stbl)? {
        Some(p) => p,
        None => return Ok(None),
    };

    let mut acc = Acc::default();
    walk_gpmf(&payload, 0, &mut acc);
    let out = acc.finish();
    Ok((!out.is_empty()).then_some(out))
}

// ----- MP4 box navigation -------------------------------------------------

/// One child box: (FourCC, payload_start, payload_end).
type Boxes = Vec<([u8; 4], u64, u64)>;

/// Read the immediate child boxes in `[start, end)` (headers only — cheap).
fn collect_children<R: Read + Seek>(
    reader: &mut R,
    start: u64,
    end: u64,
    depth: u8,
) -> std::io::Result<Boxes> {
    let mut out = Boxes::new();
    if depth > MAX_DEPTH {
        return Ok(out);
    }
    let mut pos = start;
    while pos + 8 <= end {
        reader.seek(SeekFrom::Start(pos))?;
        let mut hdr = [0u8; 8];
        if reader.read_exact(&mut hdr).is_err() {
            break;
        }
        let size32 = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]);
        let atom_type = [hdr[4], hdr[5], hdr[6], hdr[7]];
        let (atom_size, payload_off) = match size32 {
            0 => (end - pos, 8u64),
            1 => {
                let mut big = [0u8; 8];
                if reader.read_exact(&mut big).is_err() {
                    break;
                }
                (u64::from_be_bytes(big), 16u64)
            }
            n => (n as u64, 8u64),
        };
        if atom_size < payload_off || pos + atom_size > end {
            break;
        }
        out.push((atom_type, pos + payload_off, pos + atom_size));
        pos += atom_size;
    }
    Ok(out)
}

/// Single immediate child of a given type.
fn find_child<R: Read + Seek>(
    reader: &mut R,
    start: u64,
    end: u64,
    want: &[u8; 4],
) -> std::io::Result<Option<(u64, u64)>> {
    Ok(collect_children(reader, start, end, 0)?
        .into_iter()
        .find(|c| &c.0 == want)
        .map(|c| (c.1, c.2)))
}

/// Locate the `stbl` of the track whose sample description is `gpmd`. Walks each
/// `trak` → `mdia/minf/stbl`, checking the first `stsd` entry's format FourCC.
fn find_gpmd_stbl<R: Read + Seek>(
    reader: &mut R,
    moov_start: u64,
    moov_end: u64,
) -> std::io::Result<Option<(u64, u64)>> {
    for (ty, ts, te) in collect_children(reader, moov_start, moov_end, 0)? {
        if &ty != b"trak" {
            continue;
        }
        let Some((mds, mde)) = find_child(reader, ts, te, b"mdia")? else { continue };
        let Some((mis, mie)) = find_child(reader, mds, mde, b"minf")? else { continue };
        let Some((sbs, sbe)) = find_child(reader, mis, mie, b"stbl")? else { continue };
        let Some((sds, sde)) = find_child(reader, sbs, sbe, b"stsd")? else { continue };
        // stsd payload: version/flags(4) + entry_count(4) + [entry: size(4) + format(4) …]
        if sde >= sds + 16 {
            reader.seek(SeekFrom::Start(sds + 8))?;
            let mut entry = [0u8; 8];
            if reader.read_exact(&mut entry).is_ok() && &entry[4..8] == b"gpmd" {
                return Ok(Some((sbs, sbe)));
            }
        }
    }
    Ok(None)
}

/// Read a box's payload (capped) into memory.
fn read_payload<R: Read + Seek>(reader: &mut R, start: u64, end: u64, cap: u64) -> std::io::Result<Vec<u8>> {
    let len = (end - start).min(cap);
    let mut buf = vec![0u8; len as usize];
    reader.seek(SeekFrom::Start(start))?;
    reader.read_exact(&mut buf)?;
    Ok(buf)
}

fn be_u32(b: &[u8], off: usize) -> Option<u32> {
    b.get(off..off + 4).map(|s| u32::from_be_bytes([s[0], s[1], s[2], s[3]]))
}
fn be_u64(b: &[u8], off: usize) -> Option<u64> {
    b.get(off..off + 8)
        .map(|s| u64::from_be_bytes([s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]]))
}

/// Read **all** GPMF samples (every chunk) from `mdat` and concatenate them,
/// using the `stbl` sub-tables: chunk offsets (`stco`/`co64`), the
/// sample-to-chunk map (`stsc`), and sample sizes (`stsz`). Bounded by
/// `MAX_GPMF_BYTES` — a long clip's telemetry is at most a few MB.
fn read_all_chunks<R: Read + Seek>(reader: &mut R, stbl: (u64, u64)) -> std::io::Result<Option<Vec<u8>>> {
    let (sbs, sbe) = stbl;

    // Chunk offsets (stco = 32-bit, co64 = 64-bit).
    let offsets: Vec<u64> = if let Some((s, e)) = find_child(reader, sbs, sbe, b"stco")? {
        let p = read_payload(reader, s, e, MAX_TABLE_BYTES)?;
        let n = be_u32(&p, 4).unwrap_or(0) as usize;
        (0..n).filter_map(|i| be_u32(&p, 8 + i * 4).map(|v| v as u64)).collect()
    } else if let Some((s, e)) = find_child(reader, sbs, sbe, b"co64")? {
        let p = read_payload(reader, s, e, MAX_TABLE_BYTES)?;
        let n = be_u32(&p, 4).unwrap_or(0) as usize;
        (0..n).filter_map(|i| be_u64(&p, 8 + i * 8)).collect()
    } else {
        return Ok(None);
    };
    if offsets.is_empty() {
        return Ok(None);
    }

    // stsc entries: (first_chunk, samples_per_chunk). Sorted by first_chunk.
    let stsc: Vec<(u32, u32)> = match find_child(reader, sbs, sbe, b"stsc")? {
        Some((s, e)) => {
            let p = read_payload(reader, s, e, MAX_TABLE_BYTES)?;
            let n = be_u32(&p, 4).unwrap_or(0) as usize;
            (0..n)
                .filter_map(|i| {
                    let fc = be_u32(&p, 8 + i * 12)?;
                    let spc = be_u32(&p, 12 + i * 12)?;
                    Some((fc, spc))
                })
                .collect()
        }
        None => vec![(1, 1)],
    };
    if stsc.is_empty() {
        return Ok(None);
    }

    // stsz: uniform size, or a per-sample table.
    let (s2, e2) = match find_child(reader, sbs, sbe, b"stsz")? {
        Some(r) => r,
        None => return Ok(None),
    };
    let stsz = read_payload(reader, s2, e2, MAX_TABLE_BYTES)?;
    let uniform = be_u32(&stsz, 4).unwrap_or(0);
    let sample_size = |idx: usize| -> u64 {
        if uniform != 0 {
            uniform as u64
        } else {
            be_u32(&stsz, 12 + idx * 4).unwrap_or(0) as u64
        }
    };
    // samples_per_chunk for 1-based chunk `c`: the last stsc entry whose
    // first_chunk <= c.
    let spc_for = |c: u32| -> u32 {
        stsc.iter().rev().find(|(fc, _)| *fc <= c).map(|(_, spc)| *spc).unwrap_or(1)
    };

    let mut out: Vec<u8> = Vec::new();
    let mut sample_idx = 0usize;
    for (ci, &off) in offsets.iter().enumerate() {
        let spc = spc_for(ci as u32 + 1) as usize;
        let chunk_bytes: u64 = (0..spc).map(|s| sample_size(sample_idx + s)).sum();
        sample_idx += spc;
        if chunk_bytes == 0 {
            continue;
        }
        if out.len() as u64 + chunk_bytes > MAX_GPMF_BYTES {
            break; // stay bounded
        }
        let mut buf = vec![0u8; chunk_bytes as usize];
        reader.seek(SeekFrom::Start(off))?;
        let n = read_full(reader, &mut buf);
        buf.truncate(n);
        out.extend_from_slice(&buf);
    }
    Ok((!out.is_empty()).then_some(out))
}

/// Read as many bytes as possible into `buf`; returns the count.
fn read_full<R: Read>(reader: &mut R, buf: &mut [u8]) -> usize {
    let mut filled = 0;
    while filled < buf.len() {
        match reader.read(&mut buf[filled..]) {
            Ok(0) => break,
            Ok(n) => filled += n,
            Err(_) => break,
        }
    }
    filled
}

// ----- GPMF KLV parsing ---------------------------------------------------

#[derive(Default)]
struct Acc {
    /// DVNM of the device currently being walked.
    current_device: Option<String>,
    /// Most recent SCAL divisors (set just before the data they scale).
    last_scal: Vec<f64>,
    /// Most recent GPSF fix value (0 = no lock).
    last_gpsf: Option<u32>,
    /// First device name that isn't a settings/highlights pseudo-device.
    first_real_device: Option<String>,
    /// Device active when the accepted GPS fix was read.
    gps_device: Option<String>,
    gps: Option<(f64, f64, Option<f64>)>,
    /// Every valid GPS sample over the clip, in order.
    track: Vec<(f64, f64)>,
}

impl Acc {
    fn finish(self) -> GpmfMetadata {
        let track = downsample(&self.track, MAX_TRACK_POINTS);
        let track_distance_m = track_distance(&self.track); // distance from the full series
        GpmfMetadata {
            device_name: self.gps_device.or(self.first_real_device),
            gps: self.gps,
            gps_track: track,
            track_distance_m,
        }
    }
}

/// Keep at most `max` evenly-spaced points (returns the input when already
/// short enough) — same idea as the loudness downsampler.
pub(crate) fn downsample(pts: &[(f64, f64)], max: usize) -> Vec<(f64, f64)> {
    if pts.len() <= max {
        return pts.to_vec();
    }
    (0..max).map(|i| pts[i * pts.len() / max]).collect()
}

/// Great-circle distance (metres) summed along the point series.
pub(crate) fn track_distance(pts: &[(f64, f64)]) -> f64 {
    const R: f64 = 6_371_000.0;
    pts.windows(2)
        .map(|w| {
            let (la1, lo1) = (w[0].0.to_radians(), w[0].1.to_radians());
            let (la2, lo2) = (w[1].0.to_radians(), w[1].1.to_radians());
            let dlat = la2 - la1;
            let dlon = lo2 - lo1;
            let a = (dlat / 2.0).sin().powi(2)
                + la1.cos() * la2.cos() * (dlon / 2.0).sin().powi(2);
            2.0 * R * a.sqrt().min(1.0).asin()
        })
        .sum()
}

/// True for the pseudo-devices GoPro emits alongside the real camera record.
fn is_pseudo_device(name: &str) -> bool {
    let n = name.to_ascii_lowercase();
    n.contains("global settings") || n == "highlights" || n.is_empty()
}

fn walk_gpmf(buf: &[u8], depth: u8, acc: &mut Acc) {
    if depth > MAX_DEPTH {
        return;
    }
    let mut pos = 0usize;
    while pos + 8 <= buf.len() {
        let fourcc = [buf[pos], buf[pos + 1], buf[pos + 2], buf[pos + 3]];
        let type_ = buf[pos + 4];
        let struct_size = buf[pos + 5] as usize;
        let repeat = u16::from_be_bytes([buf[pos + 6], buf[pos + 7]]) as usize;
        let data_len = struct_size * repeat;
        let data_start = pos + 8;
        let data_end = data_start + data_len;
        if data_end > buf.len() {
            break;
        }
        let data = &buf[data_start..data_end];

        if type_ == 0 {
            // Nested container (DEVC, STRM, …): recurse.
            walk_gpmf(data, depth + 1, acc);
        } else {
            handle_leaf(&fourcc, type_, struct_size, repeat, data, acc);
        }

        // Advance past the data, padded to a 4-byte boundary.
        pos = data_start + ((data_len + 3) & !3);
    }
}

fn handle_leaf(
    fourcc: &[u8; 4],
    type_: u8,
    struct_size: usize,
    repeat: usize,
    data: &[u8],
    acc: &mut Acc,
) {
    match fourcc {
        b"DVNM" => {
            // Char string (may be space/null padded).
            let name = String::from_utf8_lossy(data)
                .trim_matches(|c: char| c == '\0' || c.is_whitespace())
                .to_string();
            if !name.is_empty() {
                acc.current_device = Some(name.clone());
                if acc.first_real_device.is_none() && !is_pseudo_device(&name) {
                    acc.first_real_device = Some(name);
                }
            }
        }
        b"SCAL" => {
            acc.last_scal = (0..repeat)
                .filter_map(|i| read_be_int(type_, &data[i * struct_size..(i + 1) * struct_size]))
                .map(|v| v as f64)
                .collect();
        }
        b"GPSF" => {
            acc.last_gpsf = read_be_int(type_, data).map(|v| v as u32);
        }
        b"GPS5" => {
            // Each sample = [lat, lon, alt, 2D speed, 3D speed] as int32, each
            // divided by the matching SCAL divisor. One GPSF (fix) covers the
            // whole element — skip the lot if there's no lock.
            if matches!(acc.last_gpsf, Some(0)) || struct_size < 12 {
                return;
            }
            let scal = |i: usize| -> f64 {
                // SCAL may carry one divisor for all components or one each.
                let s = if acc.last_scal.len() == 1 {
                    acc.last_scal[0]
                } else {
                    acc.last_scal.get(i).copied().unwrap_or(1.0)
                };
                if s.abs() < f64::EPSILON { 1.0 } else { s }
            };
            // Iterate every sample in the element to build the track.
            for s in 0..repeat {
                let off = s * struct_size;
                let Some(rlat) = read_be_int(b'l', &data[off..off + 4]) else { continue };
                let Some(rlon) = read_be_int(b'l', &data[off + 4..off + 8]) else { continue };
                let lat = rlat as f64 / scal(0);
                let lon = rlon as f64 / scal(1);
                // Reject bogus / null-island coordinates.
                if lat.abs() > 90.0 || lon.abs() > 180.0 || (lat == 0.0 && lon == 0.0) {
                    continue;
                }
                if acc.gps.is_none() {
                    let alt = read_be_int(b'l', &data[off + 8..off + 12]).map(|a| a as f64 / scal(2));
                    acc.gps = Some((lat, lon, alt));
                    acc.gps_device = acc.current_device.clone();
                }
                acc.track.push((lat, lon));
            }
        }
        _ => {}
    }
}

/// Read one big-endian integer of the width implied by GPMF `type_`, sign-aware.
/// Supports the widths SCAL/GPSF/GPS5 use: b/B (1), s/S (2), l/L (4), j/J (8).
fn read_be_int(type_: u8, b: &[u8]) -> Option<i64> {
    let signed = matches!(type_, b'b' | b's' | b'l' | b'j');
    match b.len() {
        1 => Some(if signed { b[0] as i8 as i64 } else { b[0] as i64 }),
        2 => {
            let v = u16::from_be_bytes([b[0], b[1]]);
            Some(if signed { v as i16 as i64 } else { v as i64 })
        }
        4 => {
            let v = u32::from_be_bytes([b[0], b[1], b[2], b[3]]);
            Some(if signed { v as i32 as i64 } else { v as i64 })
        }
        8 => {
            let v = u64::from_be_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]);
            Some(v as i64)
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a GPMF leaf element: [FourCC][type][struct_size][repeat:2 BE][data padded].
    fn klv(fourcc: &[u8; 4], type_: u8, struct_size: u8, repeat: u16, data: &[u8]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(fourcc);
        v.push(type_);
        v.push(struct_size);
        v.extend_from_slice(&repeat.to_be_bytes());
        v.extend_from_slice(data);
        while v.len() % 4 != 0 {
            v.push(0);
        }
        v
    }

    /// A nested container element (type 0x00) wrapping child bytes.
    fn container(fourcc: &[u8; 4], children: &[u8]) -> Vec<u8> {
        // size field for a nested item: GPMF uses struct_size=1, repeat=len.
        let len = children.len();
        let mut v = Vec::new();
        v.extend_from_slice(fourcc);
        v.push(0); // nested
        v.push(1);
        v.extend_from_slice(&(len as u16).to_be_bytes());
        v.extend_from_slice(children);
        while v.len() % 4 != 0 {
            v.push(0);
        }
        v
    }

    #[test]
    fn parses_device_name_and_scaled_gps() {
        // DEVC { DVNM="Hero6 Black", STRM { GPSF=3, SCAL=[1e7,1e7,1000], GPS5=[...] } }
        let dvnm = klv(b"DVNM", b'c', 1, 11, b"Hero6 Black");
        let gpsf = klv(b"GPSF", b'L', 4, 1, &3u32.to_be_bytes());
        // SCAL: three int32 divisors.
        let mut scal_data = Vec::new();
        for d in [10_000_000i32, 10_000_000, 1000] {
            scal_data.extend_from_slice(&d.to_be_bytes());
        }
        let scal = klv(b"SCAL", b'l', 4, 3, &scal_data);
        // GPS5: one sample of 5 int32 (lat, lon, alt, s2d, s3d), pre-scaled raw.
        // 33.1265° → 331265000 ; -117.327° → -1173270000 ; 10.0 m → 10000
        let mut gps_data = Vec::new();
        for r in [331_265_000i32, -1_173_270_000, 10_000, 0, 0] {
            gps_data.extend_from_slice(&r.to_be_bytes());
        }
        let gps5 = klv(b"GPS5", b'l', 20, 1, &gps_data);

        let strm = container(b"STRM", &[gpsf, scal, gps5].concat());
        let devc = container(b"DEVC", &[dvnm, strm].concat());

        let mut acc = Acc::default();
        walk_gpmf(&devc, 0, &mut acc);
        let out = acc.finish();

        assert_eq!(out.device_name.as_deref(), Some("Hero6 Black"));
        let (lat, lon, alt) = out.gps.expect("gps");
        assert!((lat - 33.1265).abs() < 1e-6, "lat={lat}");
        assert!((lon + 117.327).abs() < 1e-6, "lon={lon}");
        assert!((alt.unwrap() - 10.0).abs() < 1e-6);
    }

    #[test]
    fn no_lock_skips_gps_but_keeps_device() {
        let dvnm = klv(b"DVNM", b'c', 1, 5, b"HERO9");
        let gpsf = klv(b"GPSF", b'L', 4, 1, &0u32.to_be_bytes()); // no lock
        let scal = klv(b"SCAL", b'l', 4, 1, &1000i32.to_be_bytes());
        let mut gps_data = Vec::new();
        for r in [1i32, 1, 1, 0, 0] {
            gps_data.extend_from_slice(&r.to_be_bytes());
        }
        let gps5 = klv(b"GPS5", b'l', 20, 1, &gps_data);
        let strm = container(b"STRM", &[gpsf, scal, gps5].concat());
        let devc = container(b"DEVC", &[dvnm, strm].concat());

        let mut acc = Acc::default();
        walk_gpmf(&devc, 0, &mut acc);
        let out = acc.finish();
        assert_eq!(out.device_name.as_deref(), Some("HERO9"));
        assert!(out.gps.is_none(), "GPSF=0 must skip the fix");
    }

    #[test]
    fn pseudo_devices_are_not_the_camera_name() {
        assert!(is_pseudo_device("Video Global Settings"));
        assert!(is_pseudo_device("Highlights"));
        assert!(!is_pseudo_device("Hero6 Black"));
    }

    /// Real-file check: runs only where the GoPro sample is present.
    #[test]
    fn reads_real_gopro_sample_if_present() {
        for name in ["hero6.mp4", "karma.mp4"] {
            let p = std::path::PathBuf::from("/mammoth/video_archive/imports").join(name);
            if !p.exists() {
                eprintln!("skip {name}: not present");
                continue;
            }
            let m = read_gpmf(&p);
            eprintln!(
                "GPMF {name}: device={:?} gps={:?} track_points={} distance={:.1}m",
                m.device_name, m.gps, m.gps_track.len(), m.track_distance_m
            );
            assert!(m.device_name.is_some(), "{name}: expected a device name");
            // hero6 is a moving clip — it must yield a multi-point track.
            if name == "hero6.mp4" {
                assert!(m.gps_track.len() > 10, "expected a GPS track");
                assert!(m.track_distance_m > 0.0);
            }
        }
    }
}
