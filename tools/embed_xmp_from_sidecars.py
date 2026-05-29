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

Requires `exiftool` on PATH. Tested with exiftool 12.x.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
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
    """
    if not hist:
        return None
    return max(hist.items(), key=lambda kv: (kv[1], -1))[0] if len(hist) == 1 else \
           max(sorted(hist.items()), key=lambda kv: kv[1])[0]

def earliest_value(hist):
    """Return the lexicographically-earliest key in a `{value: count}` dict."""
    if not hist:
        return None
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
# Exiftool invocation

def run_exiftool(video, fields, dry_run, verbose):
    """Spawn one exiftool process per video, returning its exit code.

    `-overwrite_original` is used so we don't leave `.mov_original`
    backups behind; exiftool does a temp-file rename internally so the
    write is atomic on a single filesystem.

    `-n` disables exiftool's PrintConv translation, which is required
    when passing raw EXIF integer codes for ExposureMode / Program /
    WhiteBalance -- otherwise exiftool tries to interpret "1" as a
    human label and drops the value with a warning.
    """
    cmd = ["exiftool", "-overwrite_original", "-n"]
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
        print("ERROR: exiftool not found on PATH.", file=sys.stderr)
        sys.exit(1)

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
