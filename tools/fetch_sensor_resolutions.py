#!/usr/bin/env python3
"""Maintain core/data/sensor_resolutions.json — the build-time table of
native camera sensor resolutions used by the Rust core's full-resolution
classifier.

The JSON file is the source of truth. This script's jobs are:

  1. Validate the file's shape (top-level keys, that every camera entry
     is a non-empty list of [w, h] pairs of positive integers).
  2. Re-emit it in canonical form (sorted camera keys, 2-space indent,
     UTF-8, trailing newline) so diffs stay small when devs hand-edit.
  3. Optionally augment it with new entries fetched from Wikidata's
     SPARQL endpoint (off by default; opt in with --fetch).

The Rust runtime independently implements the same Wikidata lookup for
on-demand fallback when an unknown camera is encountered at scan time
(see core/src/sensor_cache.rs). The two are intentionally separate
because the build-time path can take its time and run interactively,
while the runtime path needs to be non-blocking and cache-friendly.

Usage:
    fetch_sensor_resolutions.py                  # validate + reformat
    fetch_sensor_resolutions.py --fetch          # also query Wikidata
    fetch_sensor_resolutions.py --dry-run        # show diff, write nothing
    fetch_sensor_resolutions.py --verbose

Key format: uppercase, single-spaced "MAKE MODEL" matching
core/src/camera_names.rs::normalise(). Example: "SONY ILCE-7RM3".

The Wikidata properties for sensor dimensions vary across camera
entries; the query below is best-effort and may miss cameras whose
sensor specs are listed only on a separate "image sensor" item. Manual
seed entries always win — --fetch is purely additive (never overwrites
an existing camera).
"""

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_PATH = SCRIPT_DIR.parent / "core" / "data" / "sensor_resolutions.json"

WIKIDATA_ENDPOINT = "https://query.wikidata.org/sparql"
WIKIDATA_USER_AGENT = (
    "ReelVault/0.1 (https://github.com/reelvault/reelvault; "
    "sensor-resolution fetcher) Python-urllib/3"
)
# Conservative timeout — the public endpoint can be slow.
WIKIDATA_TIMEOUT_S = 60

# Best-effort SPARQL. Pulls cameras (Q1378085 = digital camera, with
# subclasses) that have a manufacturer and at least one of width/height
# in pixels. Wikidata models sensor dimensions inconsistently, so we
# accept either:
#   - Pixel-dimension width/height directly on the camera (P2049 / P2048)
#   - "image resolution" megapixel count (P2079) which we deliberately
#     ignore here because converting MP back to (w, h) requires knowing
#     the sensor aspect, which often isn't on the item.
# Filter is intentionally loose; we deduplicate Python-side after.
WIKIDATA_QUERY = """
SELECT DISTINCT ?cam ?camLabel ?makeLabel ?modelId ?width ?height WHERE {
  ?cam wdt:P31/wdt:P279* wd:Q1378085 .
  ?cam wdt:P176 ?make .
  OPTIONAL { ?cam wdt:P2049 ?width . }
  OPTIONAL { ?cam wdt:P2048 ?height . }
  OPTIONAL { ?cam wdt:P1545 ?modelId . }
  FILTER(BOUND(?width) && BOUND(?height))
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
}
LIMIT 5000
"""


# ---------------------------------------------------------------------------
# Normalisation — must match core/src/camera_names.rs::normalise().

def normalise(s: str) -> str:
    """Uppercase + collapse runs of whitespace to a single space.

    Mirrors core/src/camera_names.rs::normalise() byte-for-byte so the
    keys this script writes always match the keys the Rust classifier
    looks up.
    """
    out = []
    last_space = True
    for ch in s:
        if ch.isspace():
            if not last_space:
                out.append(" ")
                last_space = True
        else:
            out.append(ch.upper())
            last_space = False
    while out and out[-1] == " ":
        out.pop()
    return "".join(out)


# ---------------------------------------------------------------------------
# Validation

def _is_wh_pair(v) -> bool:
    return (
        isinstance(v, list)
        and len(v) == 2
        and all(isinstance(n, int) and n > 0 for n in v)
    )


def validate(data: dict) -> list[str]:
    """Return a list of human-readable problems with `data`. Empty = OK."""
    errors = []
    if "cameras" not in data:
        return ["missing top-level 'cameras' key"]
    cameras = data["cameras"]
    if not isinstance(cameras, dict):
        return ["'cameras' must be an object"]

    for key, value in cameras.items():
        canonical = normalise(key)
        if key != canonical:
            errors.append(
                f"key {key!r} is not canonical (should be {canonical!r}) — "
                f"see core/src/camera_names.rs::normalise()"
            )
        if not isinstance(value, dict):
            errors.append(
                f"camera {key!r}: value must be an object with `natives` "
                f"and `video_max` keys"
            )
            continue
        natives = value.get("natives")
        if not isinstance(natives, list) or not natives:
            errors.append(f"camera {key!r}: `natives` must be a non-empty list")
        else:
            for i, pair in enumerate(natives):
                if not _is_wh_pair(pair):
                    errors.append(
                        f"camera {key!r}.natives[{i}]: must be [w, h] of "
                        f"positive integers, got {pair!r}"
                    )
        video_max = value.get("video_max", None)
        if video_max is not None and not _is_wh_pair(video_max):
            errors.append(
                f"camera {key!r}.video_max: must be null or [w, h] of "
                f"positive integers, got {video_max!r}"
            )
    return errors


# ---------------------------------------------------------------------------
# Wikidata enrichment

def fetch_from_wikidata(verbose: bool = False) -> dict[str, list[list[int]]]:
    """Query Wikidata for cameras with pixel-dimension width/height.

    Returns a mapping of canonical "MAKE MODEL" → [[w, h], ...]. Empty
    on any network failure — Wikidata is treated as opportunistic, not
    authoritative.
    """
    body = urllib.parse.urlencode({"query": WIKIDATA_QUERY, "format": "json"})
    req = urllib.request.Request(
        WIKIDATA_ENDPOINT,
        data=body.encode("utf-8"),
        method="POST",
        headers={
            "User-Agent": WIKIDATA_USER_AGENT,
            "Accept": "application/sparql-results+json",
            "Content-Type": "application/x-www-form-urlencoded",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=WIKIDATA_TIMEOUT_S) as resp:
            raw = resp.read()
    except (urllib.error.URLError, TimeoutError) as exc:
        print(f"warning: Wikidata fetch failed: {exc}", file=sys.stderr)
        return {}

    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(f"warning: Wikidata returned non-JSON: {exc}", file=sys.stderr)
        return {}

    out: dict[str, list[list[int]]] = {}
    for row in payload.get("results", {}).get("bindings", []):
        try:
            make = row["makeLabel"]["value"].strip()
            model_id = row.get("modelId", {}).get("value") or row["camLabel"]["value"]
            model = str(model_id).strip()
            width = int(float(row["width"]["value"]))
            height = int(float(row["height"]["value"]))
        except (KeyError, ValueError, TypeError):
            continue
        if width <= 0 or height <= 0 or not make or not model:
            continue
        key = normalise(f"{make} {model}")
        out.setdefault(key, [])
        if [width, height] not in out[key]:
            out[key].append([width, height])

    if verbose:
        print(f"Wikidata returned {len(out)} candidate cameras", file=sys.stderr)
    return out


def merge_additive(
    existing: dict[str, dict],
    additions: dict[str, list[list[int]]],
    verbose: bool = False,
) -> int:
    """Add Wikidata entries that aren't already in `existing`. Returns
    the number of newly-added cameras.

    Resolutions for an already-present camera are NEVER touched — the
    seed is treated as authoritative because hand-curation is more
    reliable than Wikidata's variable property coverage. Wikidata-sourced
    entries get a null `video_max` because the SPARQL query covers
    pixel dimensions but not in-camera video specifics.
    """
    added = 0
    for key, pairs in sorted(additions.items()):
        if key in existing:
            continue
        existing[key] = {
            "natives": sorted(pairs),
            "video_max": None,
        }
        added += 1
        if verbose:
            print(f"  + {key}: natives={pairs} video_max=null", file=sys.stderr)
    return added


# ---------------------------------------------------------------------------
# Read / write

def load(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def dump(path: Path, data: dict, dry_run: bool = False) -> bool:
    """Write `data` back to `path` in canonical form. Returns True if
    the on-disk file would change (always True in dry-run mode if a
    diff exists)."""
    data["cameras"] = dict(sorted(data["cameras"].items()))
    data["generated_at"] = datetime.now(timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%SZ"
    )
    serialised = json.dumps(data, indent=2, ensure_ascii=False) + "\n"

    existing = path.read_text(encoding="utf-8") if path.exists() else ""
    if serialised == existing:
        return False

    if dry_run:
        print(f"(dry-run) would update {path}", file=sys.stderr)
    else:
        path.write_text(serialised, encoding="utf-8")
        print(f"wrote {path}", file=sys.stderr)
    return True


# ---------------------------------------------------------------------------
# CLI

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate, reformat, and optionally augment "
                    "core/data/sensor_resolutions.json.",
    )
    parser.add_argument(
        "--fetch",
        action="store_true",
        help="Also query Wikidata and merge any new cameras into the "
             "table. Existing entries are never overwritten.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate + show what would change, but write nothing.",
    )
    parser.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="Show every newly-added Wikidata entry.",
    )
    parser.add_argument(
        "--path",
        type=Path,
        default=DATA_PATH,
        help=f"Override the JSON path (default: {DATA_PATH}).",
    )
    args = parser.parse_args(argv)

    if not args.path.exists():
        print(f"error: {args.path} not found", file=sys.stderr)
        return 1

    data = load(args.path)

    errors = validate(data)
    if errors:
        print(f"validation failed for {args.path}:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 2

    if args.fetch:
        additions = fetch_from_wikidata(verbose=args.verbose)
        added = merge_additive(data["cameras"], additions, verbose=args.verbose)
        print(f"merged {added} new cameras from Wikidata", file=sys.stderr)

    changed = dump(args.path, data, dry_run=args.dry_run)
    if not changed:
        print(f"{args.path}: already canonical", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
