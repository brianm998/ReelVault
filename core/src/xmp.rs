// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

//! Native reader for photo-style EXIF metadata embedded in MP4/MOV
//! container files via the XMP convention.
//!
//! There is no real "EXIF for video" standard — video containers were
//! never designed to carry per-frame photographic settings — but the
//! Adobe XMP packet is the closest thing the industry has settled on.
//! When a video carries an XMP-EXIF packet, every XMP-aware reader
//! (Premiere, Lightroom, Bridge, exiftool) can pull the same fields
//! back out. This module gives VideoRoom a native, dependency-free
//! reader for the same packets, so the catalog can sort and filter by
//! lens, ISO, aperture, shutter speed, etc.
//!
//! ## Where the XMP lives
//!
//! Two well-known atom locations:
//!   - `.mov` (QuickTime) → `moov/udta/XMP_`
//!   - `.mp4` → top-level `uuid` atom whose UUID is the Adobe XMP marker
//!     `BE7ACFCB-97A9-42E8-9C71-999491E3AFAC`
//!
//! Exiftool, Premiere Pro, and other writers pick the right one based
//! on the container's brand. We scan both — extension is not a reliable
//! discriminator (a .mov can be ftyp'd as `mp42` and vice versa).
//!
//! ## Why a custom parser instead of exiftool subprocess
//!
//! VideoRoom is meant to be self-contained: users shouldn't have to
//! install exiftool to get sortable EXIF. And fork-per-video adds up
//! noticeably at the 100k-video scale the catalog is sized for. The
//! atom walk and the regex-based field extraction together run in
//! microseconds on a typical timelapse output.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use regex::Regex;

/// The Adobe XMP UUID, used to mark `uuid` atoms in MP4 that carry an
/// XMP packet. Documented in XMP Specification Part 3 §3.3.
const ADOBE_XMP_UUID: [u8; 16] = [
    0xBE, 0x7A, 0xCF, 0xCB, 0x97, 0xA9, 0x42, 0xE8,
    0x9C, 0x71, 0x99, 0x94, 0x91, 0xE3, 0xAF, 0xAC,
];

/// Atom-walk depth ceiling. Real MP4/MOV trees never go deeper than ~6
/// for our purposes; the cap is purely defensive against pathological
/// or malformed files.
const MAX_DEPTH: u8 = 8;

/// Photo-EXIF fields recovered from a video's embedded XMP packet.
/// Every field is optional — most clips carry only a subset (lens-only
/// for a Premiere export, full EXIF for a tool that built the video
/// from a photo sequence), and many carry none at all.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct XmpMetadata {
    /// Camera manufacturer, from `tiff:Make`.
    pub make: Option<String>,
    /// Camera model, from `tiff:Model`.
    pub model: Option<String>,
    /// Lens designation, from `aux:Lens` (Adobe-canonical) or the
    /// less common `exif:LensModel`.
    pub lens: Option<String>,
    /// ISO speed, from `exif:ISOSpeedRatings` (first entry of the Seq).
    pub iso: Option<i64>,
    /// F-number, from `exif:FNumber`. Stored as the decimal value
    /// (e.g. `1.8`), not the rational.
    pub aperture: Option<f64>,
    /// Exposure time in seconds, from `exif:ExposureTime`. `1/4000` →
    /// `0.00025`; `20` → `20.0`.
    pub exposure_time_s: Option<f64>,
    /// Focal length in millimeters, from `exif:FocalLength`.
    pub focal_length_mm: Option<f64>,
    /// `exif:ExposureMode` translated from its integer code into a
    /// human-readable string (Auto / Manual / Auto-bracket).
    pub exposure_mode: Option<String>,
    /// `exif:ExposureProgram` translated similarly (Manual,
    /// Aperture-priority, …).
    pub exposure_program: Option<String>,
    /// `exif:WhiteBalance` translated (Auto / Manual).
    pub white_balance: Option<String>,
    /// `exif:DateTimeOriginal` parsed into Unix milliseconds (UTC).
    pub date_time_original: Option<i64>,
}

impl XmpMetadata {
    /// True if no fields were populated. Callers use this to decide
    /// whether the XMP read produced anything worth persisting.
    pub fn is_empty(&self) -> bool {
        self.make.is_none()
            && self.model.is_none()
            && self.lens.is_none()
            && self.iso.is_none()
            && self.aperture.is_none()
            && self.exposure_time_s.is_none()
            && self.focal_length_mm.is_none()
            && self.exposure_mode.is_none()
            && self.exposure_program.is_none()
            && self.white_balance.is_none()
            && self.date_time_original.is_none()
    }
}

/// Scan a video file for an embedded XMP packet and parse the fields
/// VideoRoom cares about. Returns `Ok(None)` if no XMP packet is
/// found, which is the common case for ordinary camera-original
/// footage. Returns an IO error only if the file can't be opened or
/// read; malformed atoms or malformed XMP are silently treated as
/// "no data" since we have to be robust against the long tail of
/// container quirks.
pub fn read_xmp(video_path: &Path) -> std::io::Result<Option<XmpMetadata>> {
    let mut file = File::open(video_path)?;
    let file_len = file.seek(SeekFrom::End(0))?;
    file.seek(SeekFrom::Start(0))?;

    let xmp_bytes = match find_xmp_payload(&mut file, file_len)? {
        Some(b) => b,
        None => return Ok(None),
    };

    let xml = match std::str::from_utf8(&xmp_bytes) {
        Ok(s) => s,
        Err(_) => return Ok(None), // malformed XMP — treat as absent
    };
    let parsed = parse_xmp(xml);
    if parsed.is_empty() {
        Ok(None)
    } else {
        Ok(Some(parsed))
    }
}

/// Walk top-level atoms looking for an XMP carrier. Returns the raw
/// XMP packet bytes if found.
fn find_xmp_payload<R: Read + Seek>(reader: &mut R, end: u64) -> std::io::Result<Option<Vec<u8>>> {
    walk_atoms(reader, 0, end, 0)
}

fn walk_atoms<R: Read + Seek>(
    reader: &mut R,
    start: u64,
    end: u64,
    depth: u8,
) -> std::io::Result<Option<Vec<u8>>> {
    if depth > MAX_DEPTH {
        return Ok(None);
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

        // size == 0 means "atom extends to end of its container".
        // size == 1 means a 64-bit length follows the type field.
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
            // Truncated or malformed — stop, don't panic.
            break;
        }
        let payload_start = pos + payload_off;
        let payload_end = pos + atom_size;

        match &atom_type {
            b"uuid" => {
                // 16-byte UUID, then XMP payload (if it's the Adobe marker).
                if payload_end >= payload_start + 16 {
                    let mut uuid_bytes = [0u8; 16];
                    reader.seek(SeekFrom::Start(payload_start))?;
                    if reader.read_exact(&mut uuid_bytes).is_ok() && uuid_bytes == ADOBE_XMP_UUID {
                        let xmp_len = (payload_end - payload_start - 16) as usize;
                        let mut buf = vec![0u8; xmp_len];
                        if reader.read_exact(&mut buf).is_ok() {
                            return Ok(Some(buf));
                        }
                    }
                }
            }
            b"XMP_" => {
                // QuickTime convention: an XMP_ atom directly under
                // moov/udta. Whole payload is the XMP packet.
                let xmp_len = (payload_end - payload_start) as usize;
                let mut buf = vec![0u8; xmp_len];
                reader.seek(SeekFrom::Start(payload_start))?;
                if reader.read_exact(&mut buf).is_ok() {
                    return Ok(Some(buf));
                }
            }
            // Recurse into containers we know can hold XMP carriers.
            // `meta` technically has a 4-byte version/flags header
            // before children, but we treat it permissively — the
            // first 4 bytes will look like a too-small atom header
            // and the walk will harmlessly stop. ExifTool/Adobe don't
            // nest XMP inside `meta` for video containers in practice.
            b"moov" | b"udta" | b"trak" | b"mdia" | b"minf" | b"meta" => {
                if let Some(found) = walk_atoms(reader, payload_start, payload_end, depth + 1)? {
                    return Ok(Some(found));
                }
            }
            _ => {}
        }
        pos = payload_end;
    }
    Ok(None)
}

/// Parse the XMP XML, pulling out the photo-EXIF fields we care
/// about. We don't carry a full XML parser; the XMP format produced
/// by exiftool/Adobe is consistent enough that targeted regex
/// extraction is both correct and fast for our needs. CDATA and
/// comments are not produced by either writer in this domain.
///
/// Each field can appear in either element form
/// (`<exif:FNumber>9/5</exif:FNumber>`) or attribute-shorthand form
/// on an `rdf:Description` (`exif:FNumber="9/5"`). We probe both.
fn parse_xmp(xml: &str) -> XmpMetadata {
    let mut out = XmpMetadata::default();

    if let Some(v) = scrape(xml, "Make") {
        out.make = Some(v);
    }
    if let Some(v) = scrape(xml, "Model") {
        out.model = Some(v);
    }
    // Lens lives under `aux:Lens` for Adobe; we also accept LensModel
    // for writers that prefer the EXIF tag name.
    if let Some(v) = scrape(xml, "Lens").or_else(|| scrape(xml, "LensModel")) {
        out.lens = Some(v);
    }
    if let Some(v) = scrape_iso(xml) {
        out.iso = Some(v);
    }
    if let Some(v) = scrape(xml, "FNumber").and_then(|s| parse_rational(&s)) {
        out.aperture = Some(v);
    }
    if let Some(v) = scrape(xml, "ExposureTime").and_then(|s| parse_rational(&s)) {
        out.exposure_time_s = Some(v);
    }
    if let Some(v) = scrape(xml, "FocalLength").and_then(|s| parse_rational(&s)) {
        out.focal_length_mm = Some(v);
    }
    if let Some(v) = scrape(xml, "ExposureMode") {
        out.exposure_mode = Some(exposure_mode_label(&v));
    }
    if let Some(v) = scrape(xml, "ExposureProgram") {
        out.exposure_program = Some(exposure_program_label(&v));
    }
    if let Some(v) = scrape(xml, "WhiteBalance") {
        out.white_balance = Some(white_balance_label(&v));
    }
    if let Some(v) = scrape(xml, "DateTimeOriginal").and_then(|s| parse_exif_datetime(&s)) {
        out.date_time_original = Some(v);
    }

    out
}

/// Return the first value for `local_name`, looking at both element
/// form (`<ns:Name>value</ns:Name>`) and attribute-shorthand form on
/// any element (`ns:Name="value"`). Namespace prefix is matched
/// permissively so the parser doesn't break if a writer picks a
/// non-standard prefix.
fn scrape(xml: &str, local_name: &str) -> Option<String> {
    // Element form. Allow optional whitespace and attributes on the
    // open tag, and accept the same local name on the close tag
    // regardless of prefix.
    let element_pat = format!(
        r#"<(?:[A-Za-z_][\w.-]*:)?{name}(?:\s[^>]*)?>\s*([^<]*?)\s*</(?:[A-Za-z_][\w.-]*:)?{name}\s*>"#,
        name = regex::escape(local_name)
    );
    if let Ok(re) = Regex::new(&element_pat) {
        if let Some(caps) = re.captures(xml) {
            if let Some(m) = caps.get(1) {
                let v = m.as_str().trim();
                if !v.is_empty() {
                    return Some(decode_entities(v));
                }
            }
        }
    }
    // Attribute-shorthand form. Allow either quoting style.
    let attr_pat = format!(
        r#"(?:^|\s)(?:[A-Za-z_][\w.-]*:)?{name}=("([^"]*)"|'([^']*)')"#,
        name = regex::escape(local_name)
    );
    if let Ok(re) = Regex::new(&attr_pat) {
        if let Some(caps) = re.captures(xml) {
            // capture group 2 = double-quoted, group 3 = single-quoted
            let v = caps
                .get(2)
                .or_else(|| caps.get(3))
                .map(|m| m.as_str())
                .unwrap_or("");
            if !v.is_empty() {
                return Some(decode_entities(v));
            }
        }
    }
    None
}

/// `exif:ISOSpeedRatings` is wrapped in an `rdf:Seq` of one or more
/// `rdf:li` values. We grab the first li — for almost every video
/// there's exactly one entry, and when there are multiple, the first
/// is the canonical ISO value per the EXIF spec.
fn scrape_iso(xml: &str) -> Option<i64> {
    let pat = r"<(?:[A-Za-z_][\w.-]*:)?ISOSpeedRatings(?:\s[^>]*)?>(?s)(.*?)</(?:[A-Za-z_][\w.-]*:)?ISOSpeedRatings\s*>";
    let outer = Regex::new(pat).ok()?;
    let inner = outer.captures(xml)?.get(1)?.as_str();
    let li = Regex::new(r"<(?:[A-Za-z_][\w.-]*:)?li(?:\s[^>]*)?>\s*([^<]*?)\s*</(?:[A-Za-z_][\w.-]*:)?li\s*>")
        .ok()?
        .captures(inner)?
        .get(1)?
        .as_str()
        .trim()
        .to_string();
    li.parse::<i64>().ok()
}

/// EXIF rationals are written as `num/den` (e.g. `1/4000`). Decimal
/// values like `1.8` are also accepted — some writers normalize to
/// decimal even though the XMP spec calls for rational. Returns
/// `None` for unparseable or zero-denominator values.
fn parse_rational(s: &str) -> Option<f64> {
    let t = s.trim();
    if let Some((num, den)) = t.split_once('/') {
        let n: f64 = num.trim().parse().ok()?;
        let d: f64 = den.trim().parse().ok()?;
        if d.abs() < f64::EPSILON {
            return None;
        }
        return Some(n / d);
    }
    t.parse::<f64>().ok()
}

/// EXIF `DateTimeOriginal` can arrive in either of two formats:
///   - Classic EXIF colon form: `YYYY:MM:DD HH:MM:SS`
///   - ISO 8601: `YYYY-MM-DDTHH:MM:SS[.fff][Z|+HH:MM]`
/// Returns Unix milliseconds (UTC). The colon form has no tz info;
/// EXIF tradition is to treat the wall clock as local time, but
/// since we have no way to recover the offset we assume UTC. This
/// matches what `metadata::extract_creation_date` does for
/// ffprobe-derived dates.
fn parse_exif_datetime(s: &str) -> Option<i64> {
    let t = s.trim();
    if let Ok(dt) = chrono::DateTime::parse_from_rfc3339(t) {
        return Some(dt.timestamp_millis());
    }
    if let Ok(naive) = chrono::NaiveDateTime::parse_from_str(t, "%Y:%m:%d %H:%M:%S") {
        return Some(naive.and_utc().timestamp_millis());
    }
    if let Ok(naive) = chrono::NaiveDateTime::parse_from_str(t, "%Y-%m-%dT%H:%M:%S") {
        return Some(naive.and_utc().timestamp_millis());
    }
    None
}

/// EXIF `ExposureMode` integer code → human-readable label.
fn exposure_mode_label(v: &str) -> String {
    match v.trim() {
        "0" => "Auto".to_string(),
        "1" => "Manual".to_string(),
        "2" => "Auto-bracket".to_string(),
        other => other.to_string(),
    }
}

/// EXIF `ExposureProgram` integer code → human-readable label.
fn exposure_program_label(v: &str) -> String {
    match v.trim() {
        "0" => "Not defined".to_string(),
        "1" => "Manual".to_string(),
        "2" => "Program AE".to_string(),
        "3" => "Aperture-priority".to_string(),
        "4" => "Shutter-priority".to_string(),
        "5" => "Creative".to_string(),
        "6" => "Action".to_string(),
        "7" => "Portrait".to_string(),
        "8" => "Landscape".to_string(),
        other => other.to_string(),
    }
}

/// EXIF `WhiteBalance` integer code → human-readable label.
fn white_balance_label(v: &str) -> String {
    match v.trim() {
        "0" => "Auto".to_string(),
        "1" => "Manual".to_string(),
        other => other.to_string(),
    }
}

/// Minimal XML entity decoder. We only see the standard five plus
/// occasional numeric refs in XMP packets in this domain.
fn decode_entities(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'&' {
            // Find the terminating ';'
            if let Some(semi) = s[i..].find(';') {
                let ent = &s[i + 1..i + semi];
                let replacement = match ent {
                    "amp" => Some('&'),
                    "lt" => Some('<'),
                    "gt" => Some('>'),
                    "quot" => Some('"'),
                    "apos" => Some('\''),
                    e if e.starts_with("#x") || e.starts_with("#X") => {
                        u32::from_str_radix(&e[2..], 16).ok().and_then(char::from_u32)
                    }
                    e if e.starts_with('#') => {
                        e[1..].parse::<u32>().ok().and_then(char::from_u32)
                    }
                    _ => None,
                };
                if let Some(c) = replacement {
                    out.push(c);
                    i += semi + 1;
                    continue;
                }
            }
        }
        out.push(bytes[i] as char);
        i += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A minimal but realistic XMP packet that mirrors what exiftool
    /// writes when given the standard EXIF flags. Used by the
    /// pure-parser tests so they don't depend on having exiftool
    /// available on the test machine.
    const SAMPLE_XMP: &str = r#"<?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>
<x:xmpmeta xmlns:x='adobe:ns:meta/' x:xmptk='Image::ExifTool 12.12'>
<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>
 <rdf:Description rdf:about='' xmlns:aux='http://ns.adobe.com/exif/1.0/aux/'>
  <aux:Lens>FE 14mm F1.8 GM</aux:Lens>
 </rdf:Description>
 <rdf:Description rdf:about='' xmlns:exif='http://ns.adobe.com/exif/1.0/'>
  <exif:ExposureMode>1</exif:ExposureMode>
  <exif:ExposureProgram>1</exif:ExposureProgram>
  <exif:ExposureTime>1/4000</exif:ExposureTime>
  <exif:FNumber>9/5</exif:FNumber>
  <exif:FocalLength>14/1</exif:FocalLength>
  <exif:WhiteBalance>0</exif:WhiteBalance>
  <exif:DateTimeOriginal>2026-01-18T16:22:02</exif:DateTimeOriginal>
  <exif:ISOSpeedRatings>
   <rdf:Seq>
    <rdf:li>100</rdf:li>
   </rdf:Seq>
  </exif:ISOSpeedRatings>
 </rdf:Description>
 <rdf:Description rdf:about='' xmlns:tiff='http://ns.adobe.com/tiff/1.0/'>
  <tiff:Make>Sony</tiff:Make>
  <tiff:Model>ILCE-7RM3</tiff:Model>
 </rdf:Description>
</rdf:RDF>
</x:xmpmeta>"#;

    /// Attribute-shorthand variant. Some writers (and exiftool with
    /// `-XMP-... -e`) emit fields as Description attributes instead
    /// of child elements. The parser must handle both.
    const SAMPLE_XMP_ATTR: &str = r#"<?xpacket begin='﻿'?>
<x:xmpmeta xmlns:x='adobe:ns:meta/'>
<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>
 <rdf:Description rdf:about=''
   xmlns:tiff='http://ns.adobe.com/tiff/1.0/'
   xmlns:exif='http://ns.adobe.com/exif/1.0/'
   xmlns:aux='http://ns.adobe.com/exif/1.0/aux/'
   tiff:Make="Olympus" tiff:Model="E-M1X"
   aux:Lens="OLYMPUS M.8mm F1.8"
   exif:FNumber="18/10" exif:ExposureTime="20/1"
   exif:FocalLength="8/1" exif:ExposureMode="1"
   exif:WhiteBalance="0">
  <exif:ISOSpeedRatings><rdf:Seq><rdf:li>1600</rdf:li></rdf:Seq></exif:ISOSpeedRatings>
 </rdf:Description>
</rdf:RDF>
</x:xmpmeta>"#;

    #[test]
    fn parses_element_form() {
        let m = parse_xmp(SAMPLE_XMP);
        assert_eq!(m.make.as_deref(), Some("Sony"));
        assert_eq!(m.model.as_deref(), Some("ILCE-7RM3"));
        assert_eq!(m.lens.as_deref(), Some("FE 14mm F1.8 GM"));
        assert_eq!(m.iso, Some(100));
        assert!((m.aperture.unwrap() - 1.8).abs() < 1e-9);
        assert!((m.exposure_time_s.unwrap() - 1.0 / 4000.0).abs() < 1e-9);
        assert!((m.focal_length_mm.unwrap() - 14.0).abs() < 1e-9);
        assert_eq!(m.exposure_mode.as_deref(), Some("Manual"));
        assert_eq!(m.exposure_program.as_deref(), Some("Manual"));
        assert_eq!(m.white_balance.as_deref(), Some("Auto"));
        assert!(m.date_time_original.is_some());
    }

    #[test]
    fn parses_attribute_form() {
        let m = parse_xmp(SAMPLE_XMP_ATTR);
        assert_eq!(m.make.as_deref(), Some("Olympus"));
        assert_eq!(m.model.as_deref(), Some("E-M1X"));
        assert_eq!(m.lens.as_deref(), Some("OLYMPUS M.8mm F1.8"));
        assert_eq!(m.iso, Some(1600));
        assert!((m.aperture.unwrap() - 1.8).abs() < 1e-9);
        assert!((m.exposure_time_s.unwrap() - 20.0).abs() < 1e-9);
        assert!((m.focal_length_mm.unwrap() - 8.0).abs() < 1e-9);
        assert_eq!(m.exposure_mode.as_deref(), Some("Manual"));
        assert_eq!(m.white_balance.as_deref(), Some("Auto"));
    }

    #[test]
    fn returns_empty_when_no_known_fields() {
        let xml = r#"<x:xmpmeta xmlns:x='adobe:ns:meta/'>
            <rdf:RDF xmlns:rdf='...'>
              <rdf:Description rdf:about=''>
                <dc:creator>Someone</dc:creator>
              </rdf:Description>
            </rdf:RDF>
            </x:xmpmeta>"#;
        let m = parse_xmp(xml);
        assert!(m.is_empty());
    }

    #[test]
    fn rational_parser_handles_decimal_and_zero_denominator() {
        assert_eq!(parse_rational("9/5"), Some(1.8));
        assert_eq!(parse_rational("1/4000"), Some(0.00025));
        assert_eq!(parse_rational("1.8"), Some(1.8));
        assert_eq!(parse_rational("0/0"), None);
        assert_eq!(parse_rational(""), None);
    }

    #[test]
    fn datetime_parses_both_forms() {
        // ISO 8601 with Z
        let a = parse_exif_datetime("2026-01-18T16:22:02Z").unwrap();
        // EXIF colon form (assumed UTC)
        let b = parse_exif_datetime("2026:01:18 16:22:02").unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn entity_decoder_handles_common_refs() {
        assert_eq!(decode_entities("plain"), "plain");
        assert_eq!(decode_entities("a &amp; b"), "a & b");
        assert_eq!(decode_entities("&lt;tag&gt;"), "<tag>");
        assert_eq!(decode_entities("&quot;hi&quot;"), "\"hi\"");
    }

    /// Round-trip test via exiftool. Skipped at runtime when exiftool
    /// is not installed on the test machine so CI doesn't have to
    /// ship one; the parser-only tests above still cover the format.
    #[test]
    fn round_trip_through_exiftool() {
        use std::process::Command;

        let exiftool = Command::new("exiftool").arg("-ver").output().ok();
        let ffmpeg = Command::new("ffmpeg").arg("-version").output().ok();
        let have_tools = exiftool.as_ref().map_or(false, |o| o.status.success())
            && ffmpeg.as_ref().map_or(false, |o| o.status.success());
        if !have_tools {
            eprintln!("skipping round_trip_through_exiftool: exiftool or ffmpeg not available");
            return;
        }

        let tmp = tempfile::tempdir().expect("tmpdir");
        let video_path = tmp.path().join("clip.mov");

        // 1-second 320x240 ProRes clip — small, valid MOV that
        // exiftool will happily attach an XMP atom to.
        let status = Command::new("ffmpeg")
            .args([
                "-nostdin",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=duration=1:size=320x240:rate=30",
                "-c:v",
                "prores_ks",
            ])
            .arg(&video_path)
            .status()
            .expect("ffmpeg spawn");
        assert!(status.success(), "ffmpeg failed to generate fixture clip");

        // Write the XMP-EXIF fields the encoder is expected to use.
        // `-n` disables PrintConv so we can pass raw EXIF integer codes
        // (e.g. ExposureMode=1) without exiftool trying to interpret
        // them as human-readable strings.
        let status = Command::new("exiftool")
            .args([
                "-overwrite_original",
                "-q",
                "-n",
                "-XMP-tiff:Make=Sony",
                "-XMP-tiff:Model=ILCE-7RM3",
                "-XMP-aux:Lens=FE 14mm F1.8 GM",
                "-XMP-exif:ISO=100",
                "-XMP-exif:FNumber=1.8",
                "-XMP-exif:ExposureTime=1/4000",
                "-XMP-exif:FocalLength=14",
                "-XMP-exif:ExposureMode=1",
                "-XMP-exif:ExposureProgram=1",
            ])
            .arg(&video_path)
            .status()
            .expect("exiftool spawn");
        assert!(status.success(), "exiftool failed to embed XMP");

        let xmp = read_xmp(&video_path)
            .expect("read_xmp IO ok")
            .expect("XMP packet found");

        assert_eq!(xmp.make.as_deref(), Some("Sony"));
        assert_eq!(xmp.model.as_deref(), Some("ILCE-7RM3"));
        assert_eq!(xmp.lens.as_deref(), Some("FE 14mm F1.8 GM"));
        assert_eq!(xmp.iso, Some(100));
        assert!((xmp.aperture.unwrap() - 1.8).abs() < 1e-6);
        assert!((xmp.exposure_time_s.unwrap() - 1.0 / 4000.0).abs() < 1e-9);
        assert!((xmp.focal_length_mm.unwrap() - 14.0).abs() < 1e-9);
        assert_eq!(xmp.exposure_mode.as_deref(), Some("Manual"));
        assert_eq!(xmp.exposure_program.as_deref(), Some("Manual"));
    }
}
