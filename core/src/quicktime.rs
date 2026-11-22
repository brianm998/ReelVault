// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Native reader for QuickTime/MP4 *keyed* metadata — the
//! `meta` → `keys` → `ilst` structure Apple uses to record per-clip
//! camera information (lens model, 35mm-equivalent focal length, iris
//! f-number) plus the movie-level facts (make / model / software /
//! creation date / GPS) in iPhone `.MOV` files.
//!
//! ## Why this exists alongside ffprobe
//!
//! ffprobe (`-show_format -show_streams`) surfaces the **movie-level**
//! `moov/meta` keys into `format.tags` — that's where the daemon already
//! reads `com.apple.quicktime.make` / `.model` / `.creationdate` /
//! `.location.ISO6709`. But the lens lives in a **per-track**
//! `moov/trak/meta` box, and ffprobe drops track-level keyed metadata
//! entirely. So an iPhone clip's `com.apple.quicktime.camera.lens_model`
//! ("iPhone 16 Pro back camera 6.765mm f/1.78") is invisible to the
//! ffprobe path — which is why lens never reached the catalog. This
//! module walks every `meta` box in the file, movie- and track-level, and
//! returns a flat `key → value` map so the extractor can recover the
//! fields ffprobe can't see.
//!
//! Same rationale as [`crate::xmp`]: a tiny, dependency-free, seek-based
//! atom walk (it never reads `mdat`) rather than a fork-per-video exiftool
//! subprocess, which matters at the 100k-clip scale.
//!
//! ## The `meta`/`keys`/`ilst` layout
//!
//! ```text
//! meta
//!  ├─ hdlr            handler == 'mdta' for keyed metadata
//!  ├─ keys            1-indexed table of reverse-DNS key strings
//!  │    version+flags (4) | entry_count (4)
//!  │    [ size (4) | namespace e.g. 'mdta' (4) | key bytes (size-8) ] × N
//!  └─ ilst            one item per present key
//!       [ size (4) | index-as-u32 (4)               # 1-based into keys
//!         data box:  size (4) | 'data' (4) |
//!                    type (4) | locale (4) | value bytes ]
//! ```
//!
//! QuickTime `.mov` `meta` boxes have **no** version/flags header before
//! their children, while ISO-MP4 `.mp4` `meta` boxes do. We detect which
//! by peeking for a plausible child atom type at both offsets.

use std::collections::HashMap;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

/// Atom-walk depth ceiling. Defensive against pathological/malformed files;
/// real trees never approach it for our purposes.
const MAX_DEPTH: u8 = 8;

/// Largest `data` payload we'll materialise. Lens/location strings are well
/// under 100 bytes; this only exists to refuse pathological or binary
/// payloads (e.g. `com.apple.quicktime.apple-maker-note`).
const MAX_VALUE_BYTES: u64 = 4096;

/// Walk a QuickTime/MP4 file and collect every keyed-metadata entry
/// (`meta`/`keys`/`ilst`) into a flat `key → value` map, decoding UTF-8 and
/// simple numeric `data` payloads. Movie-level and per-track `meta` boxes
/// are merged; the first value seen for a key wins.
///
/// Returns an empty map (never an error) for files that can't be opened,
/// carry no keyed metadata, or aren't real files at all — e.g. the
/// `photos://<id>` pseudo-paths the iOS build hands the indexer, where the
/// native backend supplies these tags directly. Callers treat "no data" and
/// "couldn't read" identically, exactly like [`crate::xmp`].
pub fn read_quicktime_metadata(path: &Path) -> HashMap<String, String> {
    read_inner(path).unwrap_or_default()
}

fn read_inner(path: &Path) -> std::io::Result<HashMap<String, String>> {
    let mut file = File::open(path)?;
    let end = file.seek(SeekFrom::End(0))?;
    file.seek(SeekFrom::Start(0))?;
    let mut out = HashMap::new();
    walk(&mut file, 0, end, 0, &mut out)?;
    Ok(out)
}

/// Recurse the atom tree, parsing any `meta` box into `out`. Only header
/// bytes and small `meta` payloads are read; large siblings like `mdat` are
/// skipped over by seeking, so this stays cheap even on multi-hundred-MB
/// clips.
fn walk<R: Read + Seek>(
    reader: &mut R,
    start: u64,
    end: u64,
    depth: u8,
    out: &mut HashMap<String, String>,
) -> std::io::Result<()> {
    if depth > MAX_DEPTH {
        return Ok(());
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

        // size == 0 → extends to end of container; size == 1 → 64-bit size
        // follows the type field.
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
            break; // truncated/malformed — stop, don't panic
        }
        let payload_start = pos + payload_off;
        let payload_end = pos + atom_size;

        match &atom_type {
            b"meta" => {
                let child_start = meta_child_start(reader, payload_start, payload_end);
                let _ = parse_meta(reader, child_start, payload_end, out);
            }
            // Containers that can hold a `meta` box (movie- or track-level).
            b"moov" | b"trak" | b"udta" | b"mdia" | b"minf" => {
                walk(reader, payload_start, payload_end, depth + 1, out)?;
            }
            _ => {}
        }
        pos = payload_end;
    }
    Ok(())
}

/// QuickTime `meta` boxes start their children immediately; ISO-MP4 `meta`
/// boxes prefix a 4-byte version/flags FullBox header. Detect by checking
/// whether a known child atom type sits at the start of the payload.
fn meta_child_start<R: Read + Seek>(reader: &mut R, payload_start: u64, payload_end: u64) -> u64 {
    if payload_start + 8 <= payload_end
        && reader.seek(SeekFrom::Start(payload_start)).is_ok()
    {
        let mut hdr = [0u8; 8];
        if reader.read_exact(&mut hdr).is_ok() && is_known_meta_child(&hdr[4..8]) {
            return payload_start; // QuickTime layout
        }
    }
    payload_start + 4 // ISO-MP4 FullBox: skip version/flags
}

fn is_known_meta_child(t: &[u8]) -> bool {
    matches!(
        t,
        b"hdlr" | b"keys" | b"ilst" | b"iinf" | b"pitm" | b"iloc" | b"dinf" | b"ID32"
    )
}

/// Parse a `meta` box's children: build the `keys` index table, then map
/// each `ilst` item's value onto its key. `keys` may appear after `ilst` in
/// principle, so the `ilst` parse is deferred until both are located.
fn parse_meta<R: Read + Seek>(
    reader: &mut R,
    start: u64,
    end: u64,
    out: &mut HashMap<String, String>,
) -> std::io::Result<()> {
    let mut keys: Vec<String> = Vec::new();
    let mut ilst_range: Option<(u64, u64)> = None;

    let mut pos = start;
    while pos + 8 <= end {
        reader.seek(SeekFrom::Start(pos))?;
        let mut hdr = [0u8; 8];
        if reader.read_exact(&mut hdr).is_err() {
            break;
        }
        let size = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
        let atom_type = [hdr[4], hdr[5], hdr[6], hdr[7]];
        if size < 8 || pos + size > end {
            break;
        }
        let payload_start = pos + 8;
        let payload_end = pos + size;
        match &atom_type {
            b"keys" => keys = parse_keys(reader, payload_start, payload_end)?,
            b"ilst" => ilst_range = Some((payload_start, payload_end)),
            _ => {}
        }
        pos = payload_end;
    }

    if let Some((s, e)) = ilst_range {
        parse_ilst(reader, s, e, &keys, out)?;
    }
    Ok(())
}

/// Parse the `keys` table: a version/flags + count header, then `count`
/// entries of `[size][namespace][key bytes]`. Returns the key strings in
/// declaration order (they're referenced 1-based from `ilst`).
fn parse_keys<R: Read + Seek>(reader: &mut R, start: u64, end: u64) -> std::io::Result<Vec<String>> {
    if start + 8 > end {
        return Ok(Vec::new());
    }
    reader.seek(SeekFrom::Start(start))?;
    let mut head = [0u8; 8];
    reader.read_exact(&mut head)?;
    let count = u32::from_be_bytes([head[4], head[5], head[6], head[7]]) as usize;

    let mut keys = Vec::with_capacity(count.min(1024));
    let mut pos = start + 8;
    for _ in 0..count {
        if pos + 8 > end {
            break;
        }
        reader.seek(SeekFrom::Start(pos))?;
        let mut kh = [0u8; 8];
        if reader.read_exact(&mut kh).is_err() {
            break;
        }
        let key_size = u32::from_be_bytes([kh[0], kh[1], kh[2], kh[3]]) as u64;
        // kh[4..8] is the namespace (e.g. 'mdta'); we don't need it.
        if key_size < 8 || pos + key_size > end {
            break;
        }
        let name_len = (key_size - 8) as usize;
        let mut name = vec![0u8; name_len];
        if reader.read_exact(&mut name).is_err() {
            break;
        }
        keys.push(String::from_utf8_lossy(&name).into_owned());
        pos += key_size;
    }
    Ok(keys)
}

/// Parse `ilst` items. Each item atom's *type* is a big-endian u32 index
/// (1-based) into `keys`; inside it sits a `data` box carrying the value.
fn parse_ilst<R: Read + Seek>(
    reader: &mut R,
    start: u64,
    end: u64,
    keys: &[String],
    out: &mut HashMap<String, String>,
) -> std::io::Result<()> {
    let mut pos = start;
    while pos + 8 <= end {
        reader.seek(SeekFrom::Start(pos))?;
        let mut hdr = [0u8; 8];
        if reader.read_exact(&mut hdr).is_err() {
            break;
        }
        let size = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
        let index = u32::from_be_bytes([hdr[4], hdr[5], hdr[6], hdr[7]]) as usize;
        if size < 8 || pos + size > end {
            break;
        }
        let item_start = pos + 8;
        let item_end = pos + size;
        if index >= 1 && index <= keys.len() {
            if let Some(value) = parse_data_box(reader, item_start, item_end)? {
                out.entry(keys[index - 1].clone()).or_insert(value);
            }
        }
        pos = item_end;
    }
    Ok(())
}

/// Find the `data` box inside an `ilst` item and decode its value. The box
/// is `[size]['data'][type:4][locale:4][value…]`, so the value begins 16
/// bytes in.
fn parse_data_box<R: Read + Seek>(
    reader: &mut R,
    start: u64,
    end: u64,
) -> std::io::Result<Option<String>> {
    let mut pos = start;
    while pos + 8 <= end {
        reader.seek(SeekFrom::Start(pos))?;
        let mut hdr = [0u8; 8];
        if reader.read_exact(&mut hdr).is_err() {
            break;
        }
        let size = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
        let atom_type = [hdr[4], hdr[5], hdr[6], hdr[7]];
        if size < 8 || pos + size > end {
            break;
        }
        if &atom_type == b"data" {
            let value_start = pos + 16;
            let value_end = pos + size;
            if value_start <= value_end && value_end - value_start <= MAX_VALUE_BYTES {
                reader.seek(SeekFrom::Start(pos + 8))?;
                let mut ti = [0u8; 4];
                if reader.read_exact(&mut ti).is_err() {
                    return Ok(None);
                }
                let type_indicator = u32::from_be_bytes(ti);
                let len = (value_end - value_start) as usize;
                if len == 0 {
                    return Ok(None);
                }
                let mut buf = vec![0u8; len];
                reader.seek(SeekFrom::Start(value_start))?;
                if reader.read_exact(&mut buf).is_err() {
                    return Ok(None);
                }
                return Ok(decode_data_value(type_indicator, &buf));
            }
            return Ok(None);
        }
        pos += size;
    }
    Ok(None)
}

/// Decode a `data` payload per its well-known QuickTime type indicator.
/// Camera keys are UTF-8 (type 1); the numeric forms are handled too so a
/// value stored as an int/float still arrives as a parseable string.
/// Binary/unknown types are skipped (returns `None`) — we never need them.
fn decode_data_value(type_indicator: u32, bytes: &[u8]) -> Option<String> {
    match type_indicator {
        1 => {
            let s = String::from_utf8_lossy(bytes).trim().to_string();
            (!s.is_empty()).then_some(s)
        }
        21 => {
            // signed big-endian integer (1..8 bytes), sign-extended
            let mut v: i64 = if bytes.first().is_some_and(|b| b & 0x80 != 0) { -1 } else { 0 };
            for &b in bytes {
                v = (v << 8) | (b as i64 & 0xff);
            }
            Some(v.to_string())
        }
        22 => {
            let mut v: u64 = 0;
            for &b in bytes {
                v = (v << 8) | b as u64;
            }
            Some(v.to_string())
        }
        23 if bytes.len() == 4 => {
            Some(f32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]).to_string())
        }
        24 if bytes.len() == 8 => {
            let mut a = [0u8; 8];
            a.copy_from_slice(&bytes[..8]);
            Some(f64::from_be_bytes(a).to_string())
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn atom(typ: &[u8; 4], payload: &[u8]) -> Vec<u8> {
        let size = (8 + payload.len()) as u32;
        let mut v = Vec::with_capacity(size as usize);
        v.extend_from_slice(&size.to_be_bytes());
        v.extend_from_slice(typ);
        v.extend_from_slice(payload);
        v
    }

    fn keys_box(names: &[&str]) -> Vec<u8> {
        let mut p = Vec::new();
        p.extend_from_slice(&[0, 0, 0, 0]); // version/flags
        p.extend_from_slice(&(names.len() as u32).to_be_bytes());
        for n in names {
            let ks = (8 + n.len()) as u32;
            p.extend_from_slice(&ks.to_be_bytes());
            p.extend_from_slice(b"mdta");
            p.extend_from_slice(n.as_bytes());
        }
        atom(b"keys", &p)
    }

    fn data_box(type_indicator: u32, value: &[u8]) -> Vec<u8> {
        let mut p = Vec::new();
        p.extend_from_slice(&type_indicator.to_be_bytes());
        p.extend_from_slice(&[0, 0, 0, 0]); // locale
        p.extend_from_slice(value);
        atom(b"data", &p)
    }

    /// An `ilst` item: its 4-byte "type" is the 1-based key index.
    fn item_box(index: u32, data: &[u8]) -> Vec<u8> {
        let size = (8 + data.len()) as u32;
        let mut v = Vec::new();
        v.extend_from_slice(&size.to_be_bytes());
        v.extend_from_slice(&index.to_be_bytes());
        v.extend_from_slice(data);
        v
    }

    #[test]
    fn parses_quicktime_style_per_track_keys() {
        // moov → trak → meta(keys+ilst), QuickTime layout (no version/flags
        // on `meta`). Exercises recursion + the keys/ilst pairing.
        let lens = "iPhone 16 Pro back camera 6.765mm f/1.78";
        let keys = keys_box(&[
            "com.apple.quicktime.camera.lens_model",
            "com.apple.quicktime.camera.lens_irisfnumber",
        ]);
        let ilst_payload = [
            item_box(1, &data_box(1, lens.as_bytes())),
            item_box(2, &data_box(1, b"F1.78")),
        ]
        .concat();
        let ilst = atom(b"ilst", &ilst_payload);
        let meta = atom(b"meta", &[keys, ilst].concat());
        let trak = atom(b"trak", &meta);
        let moov = atom(b"moov", &trak);

        let mut out = HashMap::new();
        walk(&mut Cursor::new(moov.clone()), 0, moov.len() as u64, 0, &mut out).unwrap();

        assert_eq!(
            out.get("com.apple.quicktime.camera.lens_model").map(String::as_str),
            Some(lens)
        );
        assert_eq!(
            out.get("com.apple.quicktime.camera.lens_irisfnumber").map(String::as_str),
            Some("F1.78")
        );
    }

    #[test]
    fn parses_iso_mp4_style_meta_with_version_flags() {
        // ISO-MP4 `meta` has a 4-byte FullBox header before children.
        let mut meta_payload = vec![0u8, 0, 0, 0]; // version/flags
        let keys = keys_box(&["com.apple.quicktime.make"]);
        let ilst = atom(b"ilst", &item_box(1, &data_box(1, b"Apple")));
        meta_payload.extend_from_slice(&keys);
        meta_payload.extend_from_slice(&ilst);
        let meta = atom(b"meta", &meta_payload);
        let moov = atom(b"moov", &meta);

        let mut out = HashMap::new();
        walk(&mut Cursor::new(moov.clone()), 0, moov.len() as u64, 0, &mut out).unwrap();
        assert_eq!(out.get("com.apple.quicktime.make").map(String::as_str), Some("Apple"));
    }

    #[test]
    fn missing_or_truncated_is_empty_not_panic() {
        assert!(read_quicktime_metadata(Path::new("/nonexistent/file.mov")).is_empty());
        // photos:// pseudo-paths aren't files — must yield an empty map.
        assert!(read_quicktime_metadata(Path::new("photos://ABC-123")).is_empty());
    }

    /// Real-file check: runs only on a machine that has the sample import
    /// (the developer's), skipped elsewhere so CI doesn't need media.
    #[test]
    fn reads_real_iphone_lens_if_present() {
        let p = Path::new("/mammoth/video_archive/imports/IMG_5243.MOV");
        if !p.exists() {
            eprintln!("skipping reads_real_iphone_lens_if_present: no sample file");
            return;
        }
        let m = read_quicktime_metadata(p);
        assert_eq!(
            m.get("com.apple.quicktime.camera.lens_model").map(String::as_str),
            Some("iPhone 16 Pro back camera 6.765mm f/1.78")
        );
    }
}
