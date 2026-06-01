#!/usr/bin/env python3
"""Embed XMP-EXIF metadata into timelapse video files from JSON sidecars.

A sidecar JSON file (e.g. `2026/01_18_2026-m1x-1.json`) carries a
histogram of every EXIF field across the source frames that fed a
timelapse. This script reads one (or many) sidecars, picks a single
representative value per field, and bakes the result into the matching
output video as a standard XMP-EXIF packet via exiftool.

What gets written, sourced from each sidecar:
    Make, Model, LensModel       -> XMP-tiff / XMP-aux
    ISO, FNumber, ExposureTime,  -> XMP-exif (the photo-EXIF block)
    FocalLength, ExposureMode,
    ExposureProgram, WhiteBalance
    DateTimeOriginal             -> XMP-exif (earliest source-frame time)

For fields that vary across the timelapse (e.g. ISO in auto-exposure
mode), the *mode* value -- the one with the highest count -- is what
lands in the video. The EXIF field schema can only carry one scalar
per shot, so this is the best single-number summary. DateTimeOriginal
is special-cased: it uses the *earliest* timestamp, which is the
intuitive "when did this timelapse start" value.

Pairing rule:
    Given a sidecar at `<dir>/<stem>.json`, every video found anywhere
    under `<dir>/` whose filename starts with `<stem>` (followed by
    `_`, `-`, or `.`) is treated as belonging to that sidecar. When
    multiple sidecars match the same video, the longest stem wins ---
    so `01_18-m1x-1-aurora.json` overrides the broader
    `01_18-m1x-1.json` for any `-aurora` variant.

Usage:
    embed_xmp_from_sidecars.py [options] <path> [<path> ...]

    Each <path> is either:
      - a `.json` sidecar (processed directly), or
      - a directory (walked recursively for `.json` sidecars).

Options:
    --dry-run   Show the exiftool commands that would run; modify nothing.
    -v          Verbose: show every flag and every exiftool invocation.

Examples:
    embed_xmp_from_sidecars.py ~/Pictures/timelapses/2026/
    embed_xmp_from_sidecars.py --dry-run ~/Pictures/timelapses/2026/01_18_2026-m1x-1.json

Requires `exiftool` on PATH. Minimum version 12.44 (for LargeFileSupport in
QuickTime/MOV files). exiftool 13.x recommended. See _check_exiftool_version()
for upgrade instructions if you hit a "Truncated mdat atom" error.
"""

import argparse
import json
import os
import re
import shutil
import struct
import subprocess
import sys
from fractions import Fraction
from pathlib import Path

# Container extensions we know how to tag. Match case-insensitively in the
# walker; the set itself is lowercase for the comparison.
VIDEO_EXTENSIONS = {".mov", ".mp4", ".m4v"}

# EXIF integer codes for the three enum-typed fields, keyed by every
# exiftool-rendered label I've seen the sidecar pipeline emit. Keeping
# the matching exact (not lowercase-permissive) so a misspelled value
# fails loudly rather than silently becoming "Auto".
EXPOSURE_PROGRAM_MAP = {
    "Manual":                       1,
    "Program AE":                   2,
    "Normal program":               2,
    "Aperture-priority":            3,
    "Aperture-priority AE":         3,
    "Aperture priority AE":         3,
    "Shutter-priority":             4,
    "Shutter-priority AE":          4,
    "Shutter speed priority AE":    4,
    "Creative":                     5,
    "Creative (Slow speed)":        5,
    "Action":                       6,
    "Action (High speed)":          6,
    "Portrait":                     7,
    "Portrait mode":                7,
    "Landscape":                    8,
    "Landscape mode":               8,
}
EXPOSURE_MODE_MAP = {
    "Auto":         0,
    "Manual":       1,
    "Auto-bracket": 2,
    "Auto bracket": 2,
}
# WhiteBalance is binary in the EXIF spec: 0 = Auto, 1 = anything else.
# "Daylight", "Cloudy", "Custom" all become 1.

# ---------------------------------------------------------------------------
# Histogram helpers

def mode_value(hist):
    """Return the key with the largest value in a `{value: count}` dict.

    Returns None for None / empty. Ties are broken by the lexicographic
    minimum of the keys to make the script deterministic across runs.

    Older sidecars may store a plain scalar instead of a histogram dict;
    those are returned as-is.
    """
    if not hist:
        return None
    if not isinstance(hist, dict):
        return hist      # plain scalar from an older sidecar format
    return max(hist.items(), key=lambda kv: (kv[1], -1))[0] if len(hist) == 1 else \
           max(sorted(hist.items()), key=lambda kv: kv[1])[0]

def earliest_value(hist):
    """Return the lexicographically-earliest key in a `{value: count}` dict.

    Plain scalars (older sidecar format) are returned as-is.
    """
    if not hist:
        return None
    if not isinstance(hist, dict):
        return hist      # plain scalar from an older sidecar format
    return min(hist.keys())

# ---------------------------------------------------------------------------
# Sidecar -> exiftool flag conversion

def _strip_mm(s):
    """Strip a trailing ' mm' or 'mm' (case-insensitive) from a string."""
    if s is None:
        return None
    return re.sub(r'\s*mm\s*$', '', s, flags=re.IGNORECASE).strip()

def _normalize_exif_date(s):
    """Return the date as a form exiftool will accept on the write side.

    Exiftool wants `YYYY:MM:DD HH:MM:SS[.ss][±HH:MM|Z]` -- the EXIF
    colon form -- for any date field, including XMP-EXIF. Sidecars
    already emit that form, so we pass it through. If we ever see ISO
    8601 with `T` separator we convert *back* to the colon form
    (rather than the other direction) so exiftool's strict parser is
    happy.
    """
    if s is None:
        return None
    m = re.match(r'^(\d{4})-(\d{2})-(\d{2})T(\d{2}:\d{2}:\d{2})(.*)$', s)
    if m:
        # ISO 8601 -> EXIF colon form, preserving any trailing tz suffix.
        return f"{m.group(1)}:{m.group(2)}:{m.group(3)} {m.group(4)}{m.group(5)}"
    return s

def sidecar_to_fields(sidecar):
    """Return a list of (label, exiftool-flag, value) triples to apply.

    Skips fields the sidecar didn't carry. The label is descriptive only
    -- used for dry-run / verbose output, not passed to exiftool.
    """
    fields = []

    def add(label, flag, value):
        if value is None:
            return
        s = str(value).strip()
        if not s:
            return
        fields.append((label, flag, s))

    add("Make",         "-XMP-tiff:Make",  mode_value(sidecar.get("Make")))
    add("Model",        "-XMP-tiff:Model", mode_value(sidecar.get("Model")))
    add("Lens",         "-XMP-aux:Lens",   mode_value(sidecar.get("LensModel")))
    add("ISO",          "-XMP-exif:ISO",   mode_value(sidecar.get("ISO")))
    add("FNumber",      "-XMP-exif:FNumber",      mode_value(sidecar.get("FNumber")))
    add("ExposureTime", "-XMP-exif:ExposureTime", mode_value(sidecar.get("ExposureTime")))
    add("FocalLength",  "-XMP-exif:FocalLength",
        _strip_mm(mode_value(sidecar.get("FocalLength"))))

    em = mode_value(sidecar.get("ExposureMode"))
    if em is not None:
        code = EXPOSURE_MODE_MAP.get(em)
        if code is None:
            print(f"WARNING: unknown ExposureMode '{em}' — skipped", file=sys.stderr)
        else:
            add("ExposureMode", "-XMP-exif:ExposureMode", code)

    ep = mode_value(sidecar.get("ExposureProgram"))
    if ep is not None:
        code = EXPOSURE_PROGRAM_MAP.get(ep)
        if code is None:
            print(f"WARNING: unknown ExposureProgram '{ep}' — skipped", file=sys.stderr)
        else:
            add("ExposureProgram", "-XMP-exif:ExposureProgram", code)

    wb = mode_value(sidecar.get("WhiteBalance"))
    if wb is not None:
        # EXIF spec: 0 = Auto, 1 = anything else.
        add("WhiteBalance", "-XMP-exif:WhiteBalance", 0 if wb == "Auto" else 1)

    dt = earliest_value(sidecar.get("DateTimeOriginal"))
    if dt:
        add("DateTimeOriginal", "-XMP-exif:DateTimeOriginal", _normalize_exif_date(dt))

    return fields

# ---------------------------------------------------------------------------
# Filesystem walking and pairing

def _walk_with_symlinks(root, want_suffixes):
    """Recursively yield every file under `root` whose suffix (lowercased)
    matches `want_suffixes`. Follows symlinks — the timelapses tree uses
    `2026-video -> /mammoth/.../2026-video` symlinks to keep heavy output
    on a separate volume, and Python's Path.rglob ignores symlinks by
    default.

    A `visited` set on resolved inode paths guards against the (rare)
    case of a symlink cycle.
    """
    visited = set()
    for dirpath, dirnames, filenames in os.walk(str(root), followlinks=True):
        real = os.path.realpath(dirpath)
        if real in visited:
            # Cycle: prune this branch.
            dirnames[:] = []
            continue
        visited.add(real)
        for fn in filenames:
            if Path(fn).suffix.lower() in want_suffixes:
                yield Path(dirpath) / fn

def collect_sidecars(paths):
    """Yield every `.json` sidecar path under each input path, in order.

    `paths` may mix files and directories. Directories are walked
    recursively, following symlinks. Non-JSON files are silently
    skipped; missing paths produce a stderr warning and are skipped.
    """
    seen = set()
    for raw in paths:
        p = Path(raw).expanduser().resolve()
        if not p.exists():
            print(f"WARNING: path does not exist: {p}", file=sys.stderr)
            continue
        if p.is_file():
            if p.suffix.lower() == '.json' and p not in seen:
                seen.add(p)
                yield p
        elif p.is_dir():
            for sc in sorted(_walk_with_symlinks(p, {'.json'})):
                if sc not in seen:
                    seen.add(sc)
                    yield sc

def collect_videos_under(sidecar_parents):
    """Return a set of video paths anywhere under the given directories.

    Filters by VIDEO_EXTENSIONS (case-insensitive on the suffix).
    Follows symlinks — important for catalogs like timelapses/<year>
    whose `<year>-video/` subdirectory is a symlink to bulk storage.
    """
    out = set()
    for parent in sidecar_parents:
        for v in _walk_with_symlinks(parent, VIDEO_EXTENSIONS):
            out.add(v)
    return out

def pair_videos_to_sidecars(videos, sidecars):
    """Return ([(video, sidecar)], [unpaired_videos]).

    Longest-prefix-stem match, with a `_`, `-`, or `.` boundary required
    after the stem so that e.g. sidecar stem `m1x-1` doesn't accidentally
    claim a `m1x-10`-named video.
    """
    by_stem = {sc.stem: sc for sc in sidecars}
    pairs = []
    unpaired = []
    for v in sorted(videos):
        best = None  # (stem_len, stem, sidecar_path)
        for stem, sc in by_stem.items():
            if not v.name.startswith(stem):
                continue
            rest = v.name[len(stem):]
            if rest and rest[0] not in {'_', '-', '.'}:
                continue
            entry = (len(stem), stem, sc)
            if best is None or entry > best:
                best = entry
        if best is None:
            unpaired.append(v)
        else:
            pairs.append((v, best[2]))
    return pairs, unpaired

# ---------------------------------------------------------------------------
# In-place XMP atom writer
#
# QuickTime timelapse files produced by this pipeline use "fast-start"
# atom ordering: ftyp + moov + mdat. The moov header is tiny (20–50 KB)
# and already contains a udta/XMP_ atom with several KB of space padding
# left over from the original encode. The mdat (the actual video frames)
# follows and can be hundreds of MB to tens of GB.
#
# exiftool's default write strategy rewrites the *entire* file: it copies
# the mdat verbatim from source to a temp file while rewriting the moov.
# Over an SMB NAS that becomes a multi-minute full-file transfer for every
# embed operation, which consistently times out for large ProRes clips.
#
# For files that match the fast-start + existing-XMP_ pattern we take a
# completely different approach: seek directly to the XMP_ atom body,
# overwrite it in place (same byte count, padded with spaces to fill the
# atom exactly), and never read or write the mdat at all. This reduces a
# 4.9 GB write to a ~3 KB write regardless of file size.
#
# Falls back to exiftool when the file lacks the required structure or when
# the new XMP content is larger than the existing atom (shouldn't happen
# in practice: the encoder leaves ~2 KB of padding).

# XMP namespace URI for each prefix we use.
_XMP_NS = {
    'exif': 'http://ns.adobe.com/exif/1.0/',
    'tiff': 'http://ns.adobe.com/tiff/1.0/',
    'aux':  'http://ns.adobe.com/exif/1.0/aux/',
}

# Maps the exiftool tag flag we generate in sidecar_to_fields() to a tuple
# of (xmp_ns_prefix, xmp_local_name, xmp_value_type).
#
# xmp_value_type meanings:
#   'str'         simple string content (Make, Model, Lens, …)
#   'integer'     write as integer (ExposureMode, ExposureProgram, WhiteBalance)
#   'rational'    rationalize to 'n/d' fraction (FNumber, ExposureTime, FocalLength)
#   'seq_integer' rdf:Seq of one integer (ISOSpeedRatings)
#   'date'        EXIF colon date → ISO 8601 (DateTimeOriginal)
_XMP_FLAG_MAP = {
    '-XMP-tiff:Make':              ('tiff', 'Make',              'str'),
    '-XMP-tiff:Model':             ('tiff', 'Model',             'str'),
    '-XMP-aux:Lens':               ('aux',  'Lens',              'str'),
    '-XMP-exif:ISO':               ('exif', 'ISOSpeedRatings',   'seq_integer'),
    '-XMP-exif:FNumber':           ('exif', 'FNumber',           'rational'),
    '-XMP-exif:ExposureTime':      ('exif', 'ExposureTime',      'rational'),
    '-XMP-exif:FocalLength':       ('exif', 'FocalLength',       'rational'),
    '-XMP-exif:ExposureMode':      ('exif', 'ExposureMode',      'integer'),
    '-XMP-exif:ExposureProgram':   ('exif', 'ExposureProgram',   'integer'),
    '-XMP-exif:WhiteBalance':      ('exif', 'WhiteBalance',      'integer'),
    '-XMP-exif:DateTimeOriginal':  ('exif', 'DateTimeOriginal',  'date'),
}


def _qt_iter_atoms(data, start, end):
    """Yield (tag_str, body_start, body_end) for QuickTime atoms in
    data[start:end]. Handles both 32-bit and 64-bit (largesize) atoms."""
    pos = start
    while pos + 8 <= end:
        sz, raw_tag = struct.unpack_from('>I4s', data, pos)
        tag = raw_tag.decode('latin1', errors='replace')
        hdr = 8
        if sz == 1:        # largesize: next 8 bytes hold the real size
            if pos + 16 > end:
                break
            sz = struct.unpack_from('>Q', data, pos + 8)[0]
            hdr = 16
        elif sz == 0:      # "extends to EOF" sentinel
            sz = end - pos
        yield tag, pos + hdr, min(pos + sz, end)
        pos += sz


def _find_xmp_atom(video_path):
    """Locate the XMP_ atom body inside a fast-start QuickTime file.

    Reads the first 200 KB of the file (sufficient for any realistic moov)
    and walks the atom tree looking for: ftyp + moov (before mdat) + udta +
    XMP_.

    Returns (body_file_offset, body_byte_count) on success, or None if the
    file does not have the required fast-start + XMP_ layout.
    """
    try:
        with open(str(video_path), 'rb') as fh:
            header = fh.read(200_000)
    except OSError:
        return None

    # Locate moov and mdat at the top level.
    moov_range = None   # (body_start, body_end)
    moov_file_offset = None
    mdat_file_offset = None

    for tag, bs, be in _qt_iter_atoms(header, 0, len(header)):
        atom_start = bs - 8  # conservative; works for both 32-bit and largesize
        if tag == 'moov' and moov_range is None:
            moov_range = (bs, be)
            moov_file_offset = atom_start
        elif tag == 'mdat' and mdat_file_offset is None:
            mdat_file_offset = atom_start

    if moov_range is None:
        return None
    # Fast-start check: moov must come before mdat.
    if mdat_file_offset is not None and mdat_file_offset < moov_file_offset:
        return None

    # Walk moov → udta → XMP_.
    for tag, bs, be in _qt_iter_atoms(header, *moov_range):
        if tag != 'udta':
            continue
        for tag2, bs2, be2 in _qt_iter_atoms(header, bs, be):
            if tag2 != 'XMP_':
                continue
            atom_start = bs2 - 8
            raw_sz = struct.unpack_from('>I', header, atom_start)[0]
            if raw_sz == 1:
                atom_total = struct.unpack_from('>Q', header, atom_start + 8)[0]
                body_offset = atom_start + 16
            else:
                atom_total = raw_sz
                body_offset = atom_start + 8
            body_size = atom_total - (body_offset - atom_start)
            return body_offset, body_size

    return None


def _find_moov_at_end(video_path):
    """Locate the moov atom at the end of a mdat-first QuickTime file.

    Reads the first 200 bytes to determine that the file is mdat-first
    (mdat precedes moov at top level), computes the moov offset from the
    mdat atom size, then seeks and reads the moov bytes directly.

    Returns (moov_file_offset, moov_bytes) on success, or None if the
    file is not mdat-first or cannot be read.
    """
    try:
        with open(str(video_path), 'rb') as fh:
            preamble = fh.read(200)
            fh.seek(0, 2)
            file_size = fh.tell()
    except OSError:
        return None

    # Walk top-level atoms in the preamble to find mdat position.
    pos = 0
    mdat_end = None
    while pos + 8 <= len(preamble):
        sz = struct.unpack_from('>I', preamble, pos)[0]
        tag = preamble[pos + 4: pos + 8]
        hdr = 8
        if sz == 1:
            if pos + 16 > len(preamble):
                break
            sz = struct.unpack_from('>Q', preamble, pos + 8)[0]
            hdr = 16
        elif sz == 0:
            sz = file_size - pos
        if tag == b'moov':
            return None          # fast-start — _find_xmp_atom handles this
        if tag == b'mdat':
            mdat_end = pos + sz
            break
        pos += sz

    if mdat_end is None:
        return None              # no mdat found in preamble

    # moov should start immediately after mdat.
    moov_offset = mdat_end
    moov_size = file_size - moov_offset
    if moov_size < 8:
        return None

    try:
        with open(str(video_path), 'rb') as fh:
            fh.seek(moov_offset)
            moov_bytes = fh.read(moov_size)
    except OSError:
        return None

    if len(moov_bytes) < 8 or moov_bytes[4:8] != b'moov':
        return None

    return moov_offset, moov_bytes


def _find_xmp_in_moov_bytes(moov_bytes):
    """Find the XMP_ atom body inside a moov bytes buffer.

    Returns (body_local_offset, body_size) — offsets relative to the start
    of moov_bytes — or None if no XMP_ atom is present.
    """
    moov_sz = len(moov_bytes)
    for tag, bs, be in _qt_iter_atoms(moov_bytes, 8, moov_sz):
        if tag != 'udta':
            continue
        for tag2, bs2, be2 in _qt_iter_atoms(moov_bytes, bs, be):
            if tag2 != 'XMP_':
                continue
            atom_start = bs2 - 8      # conservative (XMP_ is never largesize)
            raw_sz = struct.unpack_from('>I', moov_bytes, atom_start)[0]
            if raw_sz == 1:
                atom_total = struct.unpack_from('>Q', moov_bytes, atom_start + 8)[0]
                body_local = atom_start + 16
            else:
                atom_total = raw_sz
                body_local = atom_start + 8
            body_size = atom_total - (body_local - atom_start)
            return body_local, body_size
    return None


def _moov_with_xmp_inserted(moov_bytes, xmp_atom_bytes):
    """Return moov_bytes with xmp_atom_bytes appended inside the udta atom.

    If the moov has no udta, creates one. Caller is responsible for ensuring
    there is no existing XMP_ in udta (use _find_xmp_in_moov_bytes to check).

    Updates the moov and udta size fields. Returns new bytes, or None on error.
    """
    moov_sz = len(moov_bytes)
    if moov_sz < 8 or moov_bytes[4:8] != b'moov':
        return None

    # Locate udta inside moov body (offset 8 onwards).
    udta_pos = None
    udta_sz = None
    pos = 8
    while pos + 8 <= moov_sz:
        atom_sz = struct.unpack_from('>I', moov_bytes, pos)[0]
        tag = moov_bytes[pos + 4: pos + 8]
        hdr = 8
        if atom_sz == 1:
            if pos + 16 > moov_sz:
                break
            atom_sz = struct.unpack_from('>Q', moov_bytes, pos + 8)[0]
            hdr = 16
        elif atom_sz == 0:
            atom_sz = moov_sz - pos
        if tag == b'udta':
            udta_pos = pos
            udta_sz = atom_sz
            break
        pos += atom_sz

    if udta_pos is None:
        # No udta — create one containing just the XMP_ atom.
        udta_total = 8 + len(xmp_atom_bytes)
        new_udta = struct.pack('>I4s', udta_total, b'udta') + xmp_atom_bytes
        new_moov_sz = moov_sz + udta_total
        return (struct.pack('>I4s', new_moov_sz, b'moov')
                + moov_bytes[8:]
                + new_udta)
    else:
        # Append XMP_ at the end of the existing udta.
        new_udta_sz = udta_sz + len(xmp_atom_bytes)
        new_udta = (struct.pack('>I4s', new_udta_sz, b'udta')
                    + moov_bytes[udta_pos + 8: udta_pos + udta_sz]
                    + xmp_atom_bytes)
        new_moov_sz = moov_sz + len(xmp_atom_bytes)
        return (struct.pack('>I4s', new_moov_sz, b'moov')
                + moov_bytes[8: udta_pos]
                + new_udta
                + moov_bytes[udta_pos + udta_sz:])


def _xmp_fmt_rational(val):
    """Return the XMP rational representation of val ('numerator/denominator').

    Python's Fraction can parse both decimal strings ('13.0') and existing
    rational strings ('1/500'), so no special-casing is needed.
    """
    try:
        f = Fraction(str(val).strip()).limit_denominator(0xFFFF_FFFF)
        return f'{f.numerator}/{f.denominator}'
    except (ValueError, ZeroDivisionError):
        return str(val)


def _xmp_fmt_date(val):
    """Convert an EXIF colon date ('YYYY:MM:DD HH:MM:SS') to ISO 8601."""
    m = re.match(r'^(\d{4}):(\d{2}):(\d{2}) (\d{2}:\d{2}:\d{2}.*)$', str(val))
    if m:
        return f'{m.group(1)}-{m.group(2)}-{m.group(3)}T{m.group(4)}'
    return str(val)   # already ISO 8601 or unrecognised — pass through


def _build_xmp_packet(fields, target_bytes):
    """Build a padded XMP packet of exactly *target_bytes* UTF-8 bytes.

    The format exactly matches what exiftool 13.x writes:
      <?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>
      <x:xmpmeta xmlns:x='adobe:ns:meta/' x:xmptk='Image::ExifTool 13.55'>
      <rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>
        <rdf:Description rdf:about='' xmlns:exif='…'>
          [exif fields]
        </rdf:Description>
        …
      </rdf:RDF>
      </x:xmpmeta>
      [space padding]
      <?xpacket end="w"?>

    Returns bytes on success, or None if the serialised content exceeds
    target_bytes (caller should fall back to exiftool).
    """
    # '\\ufeff' is the UTF-8 BOM embedded as the 'begin' attribute value,
    # matching exiftool's convention. Encoding this Python string with UTF-8
    # produces the correct EF BB BF byte sequence inside the attribute.
    PREAMBLE = "<?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>\n".encode('utf-8')
    FOOTER   = b'<?xpacket end="w"?>'

    # Group fields by namespace (stable ordering matches exiftool output).
    ns_order = ['exif', 'tiff', 'aux']
    ns_fields = {ns: [] for ns in ns_order}
    for _label, flag, value in fields:
        mapping = _XMP_FLAG_MAP.get(flag)
        if mapping is None:
            return None   # unknown flag — can't build XMP; caller falls back
        ns, xmp_name, xmp_type = mapping
        ns_fields[ns].append((xmp_name, value, xmp_type))

    lines = [
        "<x:xmpmeta xmlns:x='adobe:ns:meta/' x:xmptk='Image::ExifTool 13.55'>",
        "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>",
    ]
    for ns in ns_order:
        flds = ns_fields.get(ns, [])
        if not flds:
            continue
        lines += [
            '',
            f" <rdf:Description rdf:about=''",
            f"  xmlns:{ns}='{_XMP_NS[ns]}'>",
        ]
        for xmp_name, value, xmp_type in flds:
            if xmp_type == 'seq_integer':
                lines += [
                    f"  <{ns}:{xmp_name}>",
                    f"   <rdf:Seq>",
                    f"    <rdf:li>{int(value)}</rdf:li>",
                    f"   </rdf:Seq>",
                    f"  </{ns}:{xmp_name}>",
                ]
            elif xmp_type == 'rational':
                lines.append(f"  <{ns}:{xmp_name}>{_xmp_fmt_rational(value)}</{ns}:{xmp_name}>")
            elif xmp_type == 'date':
                lines.append(f"  <{ns}:{xmp_name}>{_xmp_fmt_date(value)}</{ns}:{xmp_name}>")
            else:  # 'str' or 'integer'
                lines.append(f"  <{ns}:{xmp_name}>{value}</{ns}:{xmp_name}>")
        lines.append(f" </rdf:Description>")
    lines += ['', '</rdf:RDF>', '</x:xmpmeta>']

    inner = '\n'.join(lines).encode('utf-8')

    # Layout: PREAMBLE + inner + '\n' + padding·spaces + FOOTER == target_bytes
    padding = target_bytes - len(PREAMBLE) - len(inner) - 1 - len(FOOTER)
    if padding < 0:
        return None   # content too large
    return PREAMBLE + inner + b'\n' + b' ' * padding + FOOTER


_XMP_NEW_BODY_SIZE = 4096    # body size for freshly inserted XMP_ atoms


def _write_xmp_body_at(video, body_file_offset, body_size, fields,
                       dry_run, verbose, mode_label):
    """Shared helper: build XMP packet and overwrite body_size bytes at body_file_offset.

    mode_label is a short string like 'fast-start' or 'mdat-first' for messages.
    Returns True on success, False on failure (caller should fall back to exiftool).
    """
    new_xmp = _build_xmp_packet(fields, body_size)
    if new_xmp is None:
        if verbose:
            print(f"    (in-place {mode_label}: new XMP doesn't fit in "
                  f"{body_size}B atom — falling back to exiftool)")
        return False

    assert len(new_xmp) == body_size, \
        f"internal: XMP packet size mismatch ({len(new_xmp)} != {body_size})"

    if dry_run:
        print(f"    DRY-RUN (in-place {mode_label}): overwrite XMP_ body "
              f"({body_size} B at file offset {body_file_offset})")
        return True

    if verbose:
        print(f"    (in-place {mode_label}: writing {body_size} B XMP "
              f"at offset {body_file_offset})")

    try:
        with open(str(video), 'r+b') as fh:
            fh.seek(body_file_offset)
            written = fh.write(new_xmp)
        if written != body_size:
            raise OSError(f"short write: {written}/{body_size} bytes")
    except OSError as e:
        print(f"WARNING: in-place XMP write failed for {video.name}: {e}",
              file=sys.stderr)
        return False

    return True


def write_xmp_inplace(video, fields, dry_run, verbose):
    """Overwrite or insert the XMP_ atom in a QuickTime file without
    touching the mdat (video data) atom at all.

    Two layouts are handled:

    Fast-start (moov before mdat):
        The file already carries a udta/XMP_ atom with space padding from
        a prior export.  We seek to the existing atom body and overwrite it
        in-place without reading or writing any mdat bytes.

    Mdat-first (mdat before moov, moov at end):
        The moov atom sits at the tail of the file.  We read the entire
        moov (~40 KB), insert a new XMP_ atom into its udta sub-atom (or
        create udta if absent), then write the new moov back at the same
        file offset.  Writing only ~40 KB instead of a full 5–27 GB rewrite
        makes this near-instant over SMB/NAS.

    Returns True on success (or dry-run success).
    Returns False if the file cannot be handled in-place — the caller
    should fall back to exiftool.
    """
    # ---- Path A: fast-start with existing XMP_ atom ----
    ctx = _find_xmp_atom(video)
    if ctx is not None:
        body_offset, body_size = ctx
        return _write_xmp_body_at(video, body_offset, body_size,
                                   fields, dry_run, verbose, 'fast-start')

    # ---- Path B: mdat-first, moov at end ----
    moov_ctx = _find_moov_at_end(video)
    if moov_ctx is None:
        return False   # unrecognised layout — fall back to exiftool

    moov_offset, moov_bytes = moov_ctx

    # If moov already has an XMP_ atom, overwrite it in-place.
    xmp_in_moov = _find_xmp_in_moov_bytes(moov_bytes)
    if xmp_in_moov is not None:
        body_local, body_size = xmp_in_moov
        return _write_xmp_body_at(video, moov_offset + body_local, body_size,
                                   fields, dry_run, verbose, 'mdat-first')

    # No XMP_ in moov — build one and extend moov in-place.
    xmp_body = _build_xmp_packet(fields, _XMP_NEW_BODY_SIZE)
    if xmp_body is None:
        return False   # shouldn't happen for our field set
    xmp_atom = struct.pack('>I4s', 8 + _XMP_NEW_BODY_SIZE, b'XMP_') + xmp_body

    new_moov = _moov_with_xmp_inserted(moov_bytes, xmp_atom)
    if new_moov is None:
        return False

    delta = len(new_moov) - len(moov_bytes)   # always > 0 (we added XMP_)
    if dry_run:
        print(f"    DRY-RUN (in-place mdat-first insert): extend moov "
              f"{len(moov_bytes)}→{len(new_moov)} B (+{delta} B) "
              f"at offset {moov_offset} in {video.name}")
        return True

    if verbose:
        print(f"    (in-place mdat-first insert: writing {len(new_moov)} B moov "
              f"at offset {moov_offset}, file grows by {delta} B)")

    try:
        with open(str(video), 'r+b') as fh:
            fh.seek(moov_offset)
            written = fh.write(new_moov)
            if written != len(new_moov):
                raise OSError(f"short write: {written}/{len(new_moov)} bytes")
            fh.truncate(moov_offset + len(new_moov))
    except OSError as e:
        print(f"WARNING: in-place moov insert failed for {video.name}: {e}",
              file=sys.stderr)
        return False

    return True


# ---------------------------------------------------------------------------
# Exiftool invocation

def run_exiftool(video, fields, dry_run, verbose):
    """Embed XMP fields into a video file, returning 0 on success.

    Fast path — in-place XMP atom overwrite/insert:
        For fast-start files (moov before mdat) that already carry an XMP_
        atom, we overwrite the atom body.  For mdat-first files (moov after
        mdat, at end of file) we read the ~40 KB moov, insert or overwrite
        the XMP_ atom, and write the moov back — file extends by ~4 KB.
        Either way, we never read or write the mdat, turning a 5–27 GB
        full-file rewrite into a ~40 KB seek+write.  Falls back to exiftool
        only for layouts that cannot be handled this way.

    Exiftool fallback:
        `-overwrite_original` avoids `.mov_original` backups; exiftool
        writes to `<file>_exiftool_tmp` then renames atomically.

        `-n` disables PrintConv so raw EXIF integer codes for
        ExposureMode / ExposureProgram / WhiteBalance are written
        verbatim instead of being looked up as human labels.

        `-api LargeFileSupport=1` enables writing to QuickTime atoms
        with 64-bit largesize headers (body > 4 GB).  Required for 4K+
        ProRes clips; without it exiftool emits "Truncated mdat atom".

        Before calling exiftool we remove any stale `<video>_exiftool_tmp`
        left over from a previous aborted write (which would otherwise
        cause "Temporary file already exists").
    """
    # ---- Fast path ----
    if write_xmp_inplace(video, fields, dry_run, verbose):
        return 0

    # ---- Exiftool fallback ----
    if not dry_run:
        stale_tmp = video.with_name(video.name + "_exiftool_tmp")
        if stale_tmp.exists():
            if verbose:
                print(f"    (removing stale {stale_tmp.name})")
            try:
                stale_tmp.unlink()
            except OSError as e:
                print(f"WARNING: could not remove stale temp {stale_tmp}: {e}",
                      file=sys.stderr)

    cmd = ["exiftool", "-overwrite_original", "-n",
           "-api", "LargeFileSupport=1"]
    if not verbose:
        cmd.append("-q")
    for _label, flag, value in fields:
        cmd.append(f"{flag}={value}")
    cmd.append(str(video))

    if dry_run:
        printable = ' '.join(
            arg if not any(c in arg for c in ' "\'\t') else
            "'" + arg.replace("'", "'\\''") + "'"
            for arg in cmd
        )
        print(f"    DRY-RUN: {printable}")
        return 0

    return subprocess.run(cmd).returncode

# ---------------------------------------------------------------------------
# exiftool version gate

# LargeFileSupport for QuickTime/MOV atoms was added in exiftool 12.44.
# Without it, any file whose mdat atom carries a 64-bit largesize header
# indicating > 4 GB of media data will fail with "Truncated mdat atom".
# 4K/6K ProRes clips are routinely 4–12 GB, so this is the whole class.
_EXIFTOOL_MIN = (12, 44)

def _check_exiftool_version():
    """Warn if exiftool is too old to handle large QuickTime atoms.

    Runs `exiftool -ver`, parses the library version, and prints a
    detailed warning (to stderr) if it is older than 12.44.  The check
    is advisory — files whose mdat atom fits inside 4 GB will still work
    with older versions, so we don't abort.

    Returns the raw version string (or None if it couldn't be obtained).
    """
    try:
        result = subprocess.run(
            ["exiftool", "-ver"],
            capture_output=True, text=True, timeout=10,
        )
        ver_str = result.stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return None  # can't determine version — carry on

    m = re.match(r'^(\d+)\.(\d+)', ver_str)
    if not m:
        return ver_str  # unrecognised format — don't warn

    major, minor = int(m.group(1)), int(m.group(2))
    if (major, minor) < _EXIFTOOL_MIN:
        min_str = f"{_EXIFTOOL_MIN[0]}.{_EXIFTOOL_MIN[1]}"
        print(
            f"WARNING: exiftool {ver_str} is too old for large QuickTime/MOV files.\n"
            f"\n"
            f"  exiftool >= {min_str} is required to write metadata into MOV/MP4\n"
            f"  files where the video data (mdat atom) exceeds 4 GB, which is\n"
            f"  normal for 4K and larger ProRes clips. Without an upgrade, those\n"
            f"  files will fail with:\n"
            f"    Error: Truncated mdat atom\n"
            f"\n"
            f"  Files under ~4 GB should still succeed with your current version.\n"
            f"\n"
            f"  How to upgrade:\n"
            f"    macOS (Homebrew):  brew install exiftool\n"
            f"                       # or, if already installed:\n"
            f"                       brew upgrade exiftool\n"
            f"    All platforms:     https://exiftool.org  (download the\n"
            f"                       'ExifTool-XX.YY.dmg' or 'Image-ExifTool-XX.YY.tar.gz')\n",
            file=sys.stderr,
        )

    return ver_str


# ---------------------------------------------------------------------------
# Main

def main():
    parser = argparse.ArgumentParser(
        description="Embed XMP-EXIF into videos from JSON sidecars.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split('\n\n', 1)[1] if __doc__ else None,
    )
    parser.add_argument("paths", nargs="+",
                        help="One or more sidecar .json files or directories.")
    parser.add_argument("--dry-run", action="store_true",
                        help="Print exiftool commands; modify nothing.")
    parser.add_argument("-v", "--verbose", action="store_true",
                        help="Show every field and every exiftool invocation.")
    args = parser.parse_args()

    if shutil.which("exiftool") is None:
        print(
            "ERROR: exiftool not found on PATH.\n"
            "\n"
            "  How to install:\n"
            "    macOS (Homebrew):  brew install exiftool\n"
            "    All platforms:     https://exiftool.org",
            file=sys.stderr,
        )
        sys.exit(1)

    _check_exiftool_version()

    sidecars = list(collect_sidecars(args.paths))
    if not sidecars:
        print("No JSON sidecars found under the given paths.", file=sys.stderr)
        sys.exit(1)

    sidecar_parents = {sc.parent for sc in sidecars}
    videos = collect_videos_under(sidecar_parents)
    pairs, unpaired = pair_videos_to_sidecars(videos, sidecars)

    if args.verbose or args.dry_run:
        print(f"Found {len(sidecars)} sidecar(s), {len(videos)} video file(s); "
              f"paired {len(pairs)}, {len(unpaired)} have no matching sidecar.")

    if not pairs:
        print("Nothing to do — no videos matched any sidecar by name.",
              file=sys.stderr)
        sys.exit(1)

    ok = fail = skipped = 0

    # Cache parsed sidecars so a single sidecar feeding many video
    # variants doesn't re-parse on every iteration.
    cache = {}
    for video, sc in pairs:
        if sc not in cache:
            try:
                with open(sc) as f:
                    cache[sc] = json.load(f)
            except (OSError, json.JSONDecodeError) as e:
                print(f"FAIL  {video.name}: can't read {sc.name}: {e}",
                      file=sys.stderr)
                cache[sc] = None
        sidecar_data = cache[sc]
        if sidecar_data is None:
            fail += 1
            continue

        fields = sidecar_to_fields(sidecar_data)
        if not fields:
            print(f"SKIP  {video.name}: sidecar has no relevant fields "
                  f"({sc.name})")
            skipped += 1
            continue

        if args.verbose or args.dry_run:
            print(f"\n{video}")
            print(f"  ← {sc}")
            for label, _flag, value in fields:
                print(f"    {label:18s} {value}")

        rc = run_exiftool(video, fields, args.dry_run, args.verbose)
        if rc == 0:
            ok += 1
            if not (args.verbose or args.dry_run):
                print(f"OK    {video.name}")
        else:
            fail += 1
            print(f"FAIL  {video.name}: exiftool exit {rc}", file=sys.stderr)

    print(f"\n--- {ok} updated, {skipped} skipped, {fail} failed.")
    if unpaired:
        print(f"--- {len(unpaired)} video(s) had no matching sidecar "
              f"(left untouched).")

    sys.exit(0 if fail == 0 else 2)


if __name__ == "__main__":
    main()
