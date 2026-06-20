# ReelVault Metadata Reference

This document describes every piece of metadata ReelVault can extract from video files, where each field comes from, how the extraction pipeline works, and what metadata types exist in the wild that ReelVault does not yet support.

---

## Table of Contents

1. [Overview: The Extraction Pipeline](#1-overview-the-extraction-pipeline)
2. [Source 1 — ffprobe (Container & Stream Tags)](#2-source-1--ffprobe-container--stream-tags)
3. [Source 2 — XMP Packet (Embedded Photo EXIF)](#3-source-2--xmp-packet-embedded-photo-exif)
4. [Source 3 — QuickTime Keyed Metadata (Apple Atoms)](#4-source-3--quicktime-keyed-metadata-apple-atoms)
5. [Source 4 — GoPro GPMF Telemetry Track](#5-source-4--gopro-gpmf-telemetry-track)
6. [Source 5 — DJI SRT Sidecar](#6-source-5--dji-srt-sidecar)
7. [Source 6 — Audio Loudness (On-Demand)](#7-source-6--audio-loudness-on-demand)
8. [Field Priority Chains](#8-field-priority-chains)
9. [Complete Field Reference](#9-complete-field-reference)
10. [How Metadata Is Used in ReelVault](#10-how-metadata-is-used-in-reelvault)
11. [Unsupported Metadata Types](#11-unsupported-metadata-types)

---

## 1. Overview: The Extraction Pipeline

When ReelVault indexes a video file it runs a multi-source extraction pipeline. Each source is independent and non-fatal: if any one reader fails (bad file, unrecognised container, missing sidecar), the rest still run and the catalog gets whatever data they produced.

```
Video file
│
├── ffprobe ──────────────────────────────────────── technical + container tags
│     → duration, codec, fps, resolution, bitrate
│     → format.tags: make, model, GPS (ISO 6709), creation date
│     → stream tags: rotate, timecode, capture fps
│     → stream side data: display matrix (iPhone portrait), spherical XMP
│
├── XMP atom walk (xmp.rs) ───────────────────────── embedded photo EXIF
│     → moov/udta/XMP_  (MOV)
│     → top-level uuid BE7ACFCB… (MP4)
│     → lens, ISO, aperture, shutter, focal length, exposure mode/program,
│        white balance, make, model, capture date
│
├── QuickTime key walk (quicktime.rs) ────────────── Apple per-track atoms
│     → moov/meta  (movie level)
│     → moov/trak/meta  (per-track — ffprobe drops these)
│     → com.apple.quicktime.camera.lens_model
│     → com.apple.quicktime.location.ISO6709
│     → com.apple.quicktime.make / .model / .creationdate
│
├── GPMF binary track (gpmf.rs) ──────────────────── GoPro telemetry
│     → gpmd stream in mp4 stbl (stco/stsz/stsc)
│     → first GPMF chunk only (≈ first second of telemetry)
│     → DVNM: camera/device name
│     → GPS5: GPS track (lat, lon, alt, speed, fix accuracy)
│     → downsampled polyline + total distance
│
├── DJI SRT sidecar (dji.rs) ────────────────────── drone telemetry
│     → <clip>.SRT / <clip>.srt (whole file, not just head)
│     → GPS, ISO, aperture, shutter speed, capture timestamp
│     → full flight path (downsampled polyline + total distance)
│
└── Audio loudness (on-demand) ──────────────────── EBU R128
      → ffmpeg ebur128 filter, ametadata output
      → momentary-loudness series (M: values, ~10/s)
      → downsampled to 480 points for display
```

All extracted values flow into the `metadata` table in the SQLite catalog. Fields that appear in more than one source follow explicit **priority chains** (see [Section 8](#8-field-priority-chains)).

---

## 2. Source 1 — ffprobe (Container & Stream Tags)

**File:** `core/src/metadata.rs` (wraps `ffprobe`)

ffprobe is invoked once per file with `-show_format -show_streams -print_format json`. It is the primary — and fastest — source for technical metadata.

### 2.1 Technical Fields

These come from the ffprobe JSON output with no ambiguity:

| Field | ffprobe key | Notes |
|---|---|---|
| `duration_ms` | `format.duration` | Seconds × 1000, cast to i64 |
| `codec_video` | `streams[video].codec_name` | e.g. `h264`, `hevc`, `prores`, `av1` |
| `codec_audio` | `streams[audio].codec_name` | First audio stream only |
| `width` / `height` | `streams[video].width/height` | After rotation correction (see below) |
| `fps` | `streams[video].r_frame_rate` | Rational string `"30000/1001"` → 29.97 |
| `bitrate` | `format.bit_rate` | Container-level, bits/s |
| `frame_count` | `streams[video].nb_frames` | May be absent; estimated from duration × fps if so |

### 2.2 Rotation Correction

iPhones and some cameras record portrait video as a landscape sensor frame and store the display rotation in one of two places:

- **`streams[video].tags.rotate`** — older H.264 MOVs (e.g. `"90"`)
- **`streams[video].side_data_list[].rotation`** — modern HEVC MOVs (iOS 11+), where the `rotate` tag is absent entirely

ReelVault checks both and swaps width ↔ height for 90° and 270° clips so every consumer — card aspect ratios, inspector panels, portrait badges — sees the correct display dimensions.

### 2.3 Container Tags (Make, Model, GPS, Date)

ffprobe surfaces movie-level QuickTime/MP4 container tags into `format.tags`. ReelVault reads these tag names (all variants, case-insensitive):

| Field | Tag names checked |
|---|---|
| Camera make | `com.apple.quicktime.make`, `Make`, `make` |
| Camera model | `com.apple.quicktime.model`, `Model`, `model` |
| Lens | `com.apple.quicktime.camera.lens_model`, `Lens`, `LensModel`, `lens_model` |
| Creation date | `com.apple.quicktime.creationdate`, `creation_time`, `date`, `DATE` |
| GPS (ISO 6709) | `com.apple.quicktime.location.ISO6709`, `location` |

The same tags are also checked on the video stream (`streams[video].tags`) as a fallback.

ISO 6709 location strings (e.g. `+37.3317-122.0307+015.000/`) are parsed natively into decimal latitude, longitude, and altitude.

### 2.4 Color, HDR, and Dynamic Range

ffprobe exposes transfer characteristics and color primaries as strings on the video stream:

| Field | ffprobe key | Example values |
|---|---|---|
| `color_transfer` | `streams[video].color_transfer` | `"smpte2084"`, `"arib-std-b67"`, `"bt709"` |
| `color_primaries` | `streams[video].color_primaries` | `"bt2020"`, `"smpte432"` |
| `color_space` | `streams[video].color_space` | `"bt709"`, `"bt2020nc"` |
| `hdr` (bool) | derived | True when transfer is PQ (`smpte2084`) or HLG (`arib-std-b67`) |

From these, ReelVault derives a friendly **`dynamic_range`** label:

| Label | Detection rule |
|---|---|
| `"HDR (PQ)"` | transfer = `smpte2084` |
| `"HDR (HLG)"` | transfer = `arib-std-b67` |
| `"HDR (DCI)"` | primaries = `smpte432` / `smpte431` |
| `"Log (S-Log3)"` | codec = `hevc` + transfer = `slog3` |
| `"Log (S-Log2)"` | transfer = `slog2` |
| `"Log (V-Log)"` | codec = `hevc` + transfer = `bt709` + primaries = `bt2020` |
| `"Log (C-Log3)"` | transfer = `bt709` + primaries = `bt709` (Canon heuristic) |
| `"Log (N-Log)"` | codec = `h264` + transfer = `log` |
| `"RAW"` | codec = `prores` + pixel format is ProRes RAW variant |
| `"SDR"` | everything else |

**Note:** `color_space` is stored in the database but not yet surfaced in any filter or inspector panel. The derived `dynamic_range` label is what the UI exposes.

### 2.5 Bit Depth

Inferred from the pixel format string (`streams[video].pix_fmt`):

| Bit depth | Pixel formats |
|---|---|
| 8-bit | `yuv420p`, `yuvj420p`, `yuv422p`, `rgb24`, … |
| 10-bit | `yuv420p10le`, `yuv422p10le`, `p010le`, `gbrp10le`, … |
| 12-bit | `yuv420p12le`, `yuv444p12le`, … |
| 16-bit | `yuv420p16le`, `rgba64le`, … |

### 2.6 Audio Fields

From the first audio stream:

| Field | Source |
|---|---|
| `audio_channels` | `streams[audio].channels` |
| `audio_sample_rate` | `streams[audio].sample_rate` |
| `audio_bit_depth` | derived from `pix_fmt` for PCM codecs (`pcm_s16le` → 16, `pcm_s24le` → 24, etc.); 0 for compressed codecs (AAC, AC3) |
| `audio_language` | `streams[audio].tags.language` — `"und"` normalized to `""` |
| `audio_track_count` | count of all audio streams |

### 2.7 Slow Motion / Capture FPS

Some cameras record at a high sensor rate (e.g. 240 fps) but mark the container playback rate at a lower value (e.g. 30 fps) for natural slow-motion on import. ReelVault reads `capture_fps` from:

- `streams[video].tags.com.apple.proapps.recordedFrameRate` (Apple ProApps timecode tag)
- `streams[video].tags.framerate` / `frame_rate`

When `capture_fps > fps + 1`, the clip is classified as slow-motion. The summary and inspector display a `"240 → 30 fps"` label.

### 2.8 SMPTE Timecode

ReelVault looks for a `tmcd` (timecode) stream in the ffprobe stream list. Its tags are checked for:

- `streams[tmcd].tags.timecode`
- `streams[video].tags.timecode`

If found, the SMPTE start timecode is stored as a string (e.g. `"01:00:00:00"`).

### 2.9 Spatial (Apple Vision Pro) and 360° Video

**Spatial/stereoscopic:** detected when any stream has `codec_tag_string == "MHm1"` or `"MHM1"` (MV-HEVC, the multi-view HEVC format Apple Vision Pro uses for spatial video).

**360°/spherical:** detected from `streams[video].side_data_list` entries with a `spherical_mapping` projection. The projection name (`"equirectangular"`, `"cubemap"`, etc.) is stored verbatim. The `is360` boolean is derived client-side from `projection.isNotEmpty()`.

---

## 3. Source 2 — XMP Packet (Embedded Photo EXIF)

**File:** `core/src/xmp.rs`

### 3.1 Why XMP?

There is no true "EXIF for video" standard — video containers were not designed to carry per-frame photographic settings. The Adobe XMP packet is the closest industry standard: it is what Premiere Pro, Lightroom, Bridge, and DaVinci Resolve all write and read. Embedding an XMP packet in a video file allows tools like these to round-trip lens, ISO, aperture, and other photographic settings.

ffprobe does not read XMP packets, so ReelVault has a custom, dependency-free native reader.

### 3.2 Where XMP Lives in a File

| Container | XMP location |
|---|---|
| `.mov` (QuickTime) | `moov/udta/XMP_` atom |
| `.mp4` | Top-level `uuid` atom with UUID `BE7ACFCB-97A9-42E8-9C71-999491E3AFAC` |

ReelVault scans both locations regardless of the file extension (a `.mov` may have `ftyp` brand `mp42` and vice versa).

### 3.3 Fields Extracted

XMP is an XML document embedded in the atom payload. ReelVault uses regex-based extraction (no full XML parse) for these fields:

| Field | XMP namespace:attribute | Example |
|---|---|---|
| Camera make | `tiff:Make` | `"SONY"` |
| Camera model | `tiff:Model` | `"ILCE-7RM4"` |
| Lens | `exifEX:LensModel` (preferred), `aux:Lens` (fallback) | `"FE 24-70mm F2.8 GM II"` |
| ISO | `exif:ISOSpeedRatings` (first entry of `rdf:Seq`) | `800` |
| Aperture | `exif:FNumber` (decimal or rational `"28/10"`) | `2.8` |
| Exposure time | `exif:ExposureTime` (rational `"1/4000"` → 0.00025) | `0.00025` |
| Focal length | `exif:FocalLength` (mm, rational or decimal) | `35.0` |
| Exposure mode | `exif:ExposureMode` (integer code, translated) | `"Manual"` / `"Auto"` / `"Auto-bracket"` |
| Exposure program | `exif:ExposureProgram` (integer code, translated) | `"Manual"` / `"Aperture priority"` / `"Shutter priority"` / `"Program"` |
| White balance | `exif:WhiteBalance` (0 = Auto, 1 = Manual) | `"Auto"` / `"Manual"` |
| Capture date | `exif:DateTimeOriginal` | UTC Unix milliseconds |

`exifEX:LensModel` is preferred over `aux:Lens` because it is the full, disambiguating identity string (includes focal range and aperture), whereas `aux:Lens` is a lossier sidecar value sometimes missing model-year suffixes.

### 3.4 Who Writes XMP into Video Files

- **Premiere Pro** — exports with XMP when the sequence has lens/camera info
- **DaVinci Resolve** — can embed XMP on export
- **exiftool** — can inject XMP into any container
- **Sony cameras** — some models embed a partial XMP block with `tiff:Make`/`tiff:Model`
- **Photo-to-video tools** — Final Cut Pro timelapse sequences, LRTimelapse, etc.

Ordinary camera-original footage (from an iPhone, GoPro, or most Mirrorless bodies without an ATOMOS recorder) typically carries **no XMP at all**, so this source produces nothing for most clips.

---

## 4. Source 3 — QuickTime Keyed Metadata (Apple Atoms)

**File:** `core/src/quicktime.rs`

### 4.1 Why Not Just ffprobe?

ffprobe surfaces the **movie-level** `moov/meta` keys into `format.tags` — that is how the make, model, creation date, and GPS location from iPhones reach the catalog. But the **lens** lives in a **per-track** `moov/trak/meta` box, and ffprobe silently drops all per-track keyed metadata. An iPhone clip's lens string (`"iPhone 16 Pro back camera 6.765mm f/1.78"`) is invisible to the ffprobe path entirely.

QuickTime keyed metadata uses a `meta` → `keys` → `ilst` structure:

```
meta
 ├─ hdlr    (handler == 'mdta' for keyed metadata)
 ├─ keys    1-indexed table of reverse-DNS key strings
 └─ ilst    one item per key: its value, typed
```

### 4.2 Keys Extracted

ReelVault walks every `meta` box in the file — movie-level and per-track — and merges the results into a flat `key → value` map. The first value seen for a key wins. The keys the catalog cares about:

| Key | Usage |
|---|---|
| `com.apple.quicktime.camera.lens_model` | Lens name — the main use of this module |
| `com.apple.quicktime.location.ISO6709` | GPS — fallback to ffprobe's format.tags parse |
| `com.apple.quicktime.make` | Camera make (normally also in ffprobe format.tags) |
| `com.apple.quicktime.model` | Camera model |
| `com.apple.quicktime.creationdate` | Capture timestamp |

**Note:** `com.apple.quicktime.apple-maker-note` (Maker Notes) is present in Apple QuickTime atoms but contains binary data. ReelVault silently skips any `data` payload larger than 4 KB, so maker notes are never materialised.

### 4.3 MOV vs. MP4 Dialect Difference

QuickTime `.mov` `meta` boxes have **no** version/flags header before their children, while ISO-MP4 `.mp4` `meta` boxes do (4 bytes). ReelVault detects which by peeking for a plausible child atom type at both offsets, so both container types parse correctly.

---

## 5. Source 4 — GoPro GPMF Telemetry Track

**File:** `core/src/gpmf.rs`

### 5.1 What GPMF Is

GoPro cameras (HERO5 and later, Fusion, MAX, Karma drone) record a timed binary metadata track in the **GPMF** format (GoPro Metadata Format). The track has the codec tag `gpmd` in the MP4 sample table. ffprobe surfaces it only as an opaque `bin_data` stream — the actual data is invisible without a native parser.

GPMF uses a nested KLV (Key-Length-Value) structure:

```
[FourCC: 4 bytes][type: 1][struct_size: 1][repeat: 2 BE] [data: struct_size × repeat bytes, padded to 4]
```

`type == 0` marks a nested container (`DEVC` = device, `STRM` = stream). All multi-byte numbers are big-endian.

### 5.2 Extraction Scope

ReelVault reads only the **first chunk** of GPMF data (roughly the first second of telemetry, found via the `gpmd` track's `stbl` atom — `stsd`/`stsz`/`stco`/`co64`/`stsc`). This bounds the read to a few KB regardless of clip length, and the first chunk carries a complete device record plus the initial GPS fixes.

The full GPMF payload then has every GPS sample read for the track distance and polyline.

### 5.3 Fields Extracted

| GPMF key | Field | Notes |
|---|---|---|
| `DVNM` | `device_name` / `camera_model` | e.g. `"Hero6 Black"`, `"GoPro MAX"`. Pseudo-device names ("Video Global Settings", "Highlights") are filtered out. |
| `GPS5` | `gps` (first fix) | `(latitude, longitude, altitude)`. Scaled by per-stream `SCAL` divisor. Skipped when altitude bits signal no GPS lock. |
| `GPS5` (all) | `gps_track` | Every valid GPS sample, downsampled to ≤ 512 points. |
| — | `track_distance_m` | Great-circle sum of all consecutive GPS fixes in metres. |

The `GPS5` stream records: latitude, longitude, altitude (m), 2D speed (m/s), 3D speed (m/s) at approximately 18 Hz.

**Not extracted from GPMF:** acceleration (`ACCL`), gyroscope (`GYRO`), image stabilisation (`IORI`), exposure values (`SHUT`/`WBAL`), magnetic compass (`CORI`), temperature (`TMPC`). These are present in the binary stream but not yet read by ReelVault.

---

## 6. Source 5 — DJI SRT Sidecar

**File:** `core/src/dji.rs`

### 6.1 What the SRT Contains

DJI drones record per-frame flight and camera telemetry as a subtitle track — either as a sidecar `<clip>.SRT` file placed alongside the video, or as an embedded subtitle stream. ReelVault reads the **sidecar** file only (the embedded subtitle path is a future extension).

DJI has shipped two SRT format generations:

**Modern** (Air 2S, Mini 3, Mavic 3, and later):
```
[latitude: 41.421] [longitude: 2.229] [altitude: 117.0]
[iso : 100] [shutter : 1/80.0] [fnum : 280] [ct : 5500]
2022-08-07 13:40:40,774,808
```

**Older** (Mavic Pro, Phantom series):
```
GPS(149.02,-20.25,16) ISO:100 Shutter:60 Fnum:2.2 EV:0
```

Note the older format swaps longitude and latitude: `GPS(lon,lat,alt)` — this is handled explicitly.

### 6.2 Fields Extracted

| SRT field | ReelVault field | Notes |
|---|---|---|
| `latitude` / `longitude` / `altitude` | `gps` | First non-zero fix |
| `iso` / `IR:` | `iso` | Integer |
| `fnum` / `Fnum:` | `aperture` | Divided by 100 for modern format (`280` → `2.80`) |
| `shutter` / `Shutter:` | `exposure_time_s` | Rational string `"1/80.0"` → `0.0125` |
| Timestamp line | `creation_date_ms` | UTC Unix milliseconds (SRT carries no timezone offset) |
| All GPS fixes | `gps_track` | Full file read; downsampled to ≤ 512 points |
| — | `track_distance_m` | Great-circle sum in metres |

**Not extracted from DJI SRT:** barometric altitude (when separate from GPS altitude), magnetic compass heading, gimbal angles, RC signal strength, battery level, wind speed, home point distance. These appear in some DJI SRT variants but are not read.

### 6.3 When the SRT Is Read

The sidecar read is gated on two conditions:

1. No GPS fix was found from any other source (container tags, QuickTime atoms, GPMF) — a DJI clip never carries container GPS, so a GPS-bearing clip (iPhone, GoPro) cannot be one.
2. The file extension is `.mp4`, `.mov`, or `.mkv` — the containers DJI produces.

---

## 7. Source 6 — Audio Loudness (On-Demand)

**File:** `core/src/metadata.rs` (`extract_audio_loudness`)

Audio loudness is **not** extracted during indexing. It is computed on the first access (when a client opens the detail view of a clip) and cached in `metadata.audio_loudness` as a binary blob. It is invalidated if the file changes (mtime change detected at next scan).

### 7.1 Method

ffmpeg is invoked with the `ebur128` filter chain:

```
ffmpeg -i <file> -vn -af ebur128=metadata=1,ametadata=mode=print:file=- -f null -
```

The `ametadata=print:file=-` step prints each momentary-loudness reading to stdout as:
```
lavfi.r128.M=-14.3
```

ReelVault collects every `M:` value (approximately 10 per second of audio), then downsamples to 480 points using simple block averaging. This is enough for a smooth waveform-style graph in the inspector without storing a large blob for hour-long recordings.

### 7.2 EBU R128 Standard

EBU R128 is the European broadcasting standard for loudness measurement. The momentary loudness (`M`) gate measures over a 400 ms window with a -10 LUFS relative gate. The scale is LUFS (Loudness Units relative to Full Scale), where silence is approximately -∞ and 0 LUFS is digital full scale.

### 7.3 Why ffmpeg 8.x Requires a Different Invocation

ffmpeg 8.x changed the `ebur128` filter to log its `M:` readings only at `verbose` log level (previously at `info`). ReelVault bypasses this by using `ametadata=print:file=-` to write the metadata to stdout rather than reading from the log — making the extraction version-independent.

---

## 8. Field Priority Chains

When multiple sources can provide the same field, an explicit priority chain determines which value wins. Later sources are only consulted when earlier ones return nothing.

| Field | Priority (highest → lowest) |
|---|---|
| Camera make | XMP `tiff:Make` → ffprobe `format.tags` → ffprobe `stream.tags` |
| Camera model | XMP `tiff:Model` → ffprobe `format.tags` → ffprobe `stream.tags` → GPMF `DVNM` |
| Lens | ffprobe tags → XMP `exifEX:LensModel` / `aux:Lens` → QuickTime `com.apple.quicktime.camera.lens_model` |
| GPS | ffprobe `format.tags` → ffprobe `stream.tags` → QuickTime ISO 6709 key → GPMF `GPS5` → DJI SRT |
| Creation date | ffprobe `creation_time` → XMP `DateTimeOriginal` → DJI SRT timestamp |
| ISO | XMP → DJI SRT |
| Aperture | XMP → DJI SRT |
| Exposure time | XMP → DJI SRT |
| GPS track | GPMF (preferred) → DJI SRT |

**Make + model merge:** make and model are merged per-field, not as a unit. A sidecar that writes `tiff:Model` but not `tiff:Make` must not blank out ffprobe's make — otherwise the same camera appears twice in the catalog under different strings (e.g. `"ILCE-7RM4"` alongside `"SONY ILCE-7RM4"`).

**Marketing names:** the stored `camera_model` (e.g. `"SONY ILCE-7RM3"`) is looked up in a hand-curated table to produce `camera_display_name` (e.g. `"Sony a7R III"`). The lookup is case-insensitive. Users can extend and override the built-in table via the Camera Names editor in any client. When a make prefix is missing from a bare model code (e.g. `"ILCE-7RM4"` without `"SONY"`), a recovery heuristic re-attaches the likely make before the lookup.

---

## 9. Complete Field Reference

### 9.1 `VideoSummary` — Grid / List Level

Returned by `ListVideos`. Lightweight — no blobs, no large strings.

| Field | Type | Source |
|---|---|---|
| `id` | String | DB primary key |
| `filename` | String | Filesystem |
| `path` | String | Filesystem |
| `durationMs` | Long | ffprobe |
| `width` / `height` | Int | ffprobe (rotation-corrected) |
| `codecVideo` / `codecAudio` | String | ffprobe |
| `fps` | Double | ffprobe |
| `sizeBytes` | Long | Filesystem |
| `indexedAt` | Long | DB timestamp |
| `creationDate` | Long | ffprobe → XMP → DJI SRT |
| `cameraModel` | String | Priority chain |
| `cameraDisplayName` | String | Marketing name lookup |
| `lensModel` | String | Priority chain |
| `gpsLatitude` / `gpsLongitude` | Double | Priority chain |
| `iso` | Int | XMP → DJI SRT |
| `aperture` | Double | XMP → DJI SRT |
| `exposureTimeS` | Double | XMP → DJI SRT |
| `focalLengthMm` | Double | XMP |
| `bitrateKbps` | Int | ffprobe |
| `frameCount` | Long | ffprobe `nb_frames` (estimated if absent) |
| `dynamicRange` | String | Derived from color_transfer / color_primaries |
| `timecode` | String | ffprobe tmcd stream |
| `captureFps` | Double | ffprobe stream tags |
| `bitDepth` | Int | Derived from pixel format |
| `audioBitDepth` | Int | Derived from codec |
| `audioLanguage` | String | ffprobe stream.tags |
| `audioTrackCount` | Int | ffprobe |
| `spatial` | Boolean | ffprobe MV-HEVC detection |
| `projection` | String | ffprobe spherical side data |
| `gpsTrackDistanceM` | Double | GPMF → DJI SRT |
| `rating` | Int | User-assigned (0–5) |
| `colorLabel` | String | User-assigned |

### 9.2 `VideoMetadata` — Detail Level

Returned by `GetMetadata`. Superset of summary; adds blobs and rarely-needed fields.

All `VideoSummary` fields, plus:

| Field | Type | Source / Notes |
|---|---|---|
| `gpsAltitude` | Double | Priority chain |
| `colorTransfer` | String | ffprobe raw value (e.g. `"smpte2084"`) |
| `colorPrimaries` | String | ffprobe raw value |
| `exposureMode` | String | XMP (translated integer) |
| `exposureProgram` | String | XMP (translated integer) |
| `whiteBalance` | String | XMP (translated integer) |
| `gpsTrack` | String | JSON `[[lat,lon],…]` polyline, ≤ 512 points; GPMF → DJI |
| `notes` | String | User-authored free text |
| `tags` | List\<String\> | User-assigned tags |
| `collections` | List\<String\> | Collection memberships |
| `audioChannels` | Int | ffprobe |
| `audioSampleRate` | Int | ffprobe |
| `modificationDate` | Long | Filesystem mtime |

### 9.3 Derived / Computed Fields (Client-Side)

These are not stored in the DB — they are computed from stored fields:

| Field | Derivation |
|---|---|
| `resolution` | `"${width} x ${height}"` |
| `is360` | `projection.isNotEmpty()` |
| `hasAudio` | `codecAudio.isNotEmpty()` |
| `hasLocation` | `gpsLatitude != 0.0 || gpsLongitude != 0.0` |
| `isPortrait` | `height > width` |
| `slowMotionLabel` | `captureFps > fps + 1` → `"240 → 30 fps"` |
| `gpsTrackDistanceLabel` | `"0.30 km"` / `"180 m"` |
| `bitDepthLabel` | `"10-bit"` |
| `durationFormatted` | `"HH:MM:SS"` or `"M:SS"` or `"Xs"` |
| `frameCountFormatted` | exact or `"~"` estimate |

---

## 10. How Metadata Is Used in ReelVault

### 10.1 Browsing and Discovery

**Grid cards** display configurable "stat slots" — up to two small badges per card showing any combination of: resolution, codec, dynamic range, camera, lens, ISO, aperture, shutter, fps, bitrate, duration, file size, GPS track distance, bit depth, audio channels, timecode. Users choose which slots to show via a per-client preference panel.

**List view** adds sortable columns for all scalar fields.

**Sorting** is available on: creation date, duration, file size, fps, bitrate, width, height, iso, aperture, rating, camera model, lens model.

### 10.2 Filtering

The **Library Filter** bar appears above the grid on all clients. Active filters are ANDed together. Available filters:

| Filter | Field(s) | Type |
|---|---|---|
| Text search | filename, tags, notes | FTS5 full-text |
| Rating | `rating` | Min-star threshold (≥ N) |
| Color label | `colorLabel` | Exact match |
| Has location | `gpsLatitude`/`gpsLongitude` | Presence (yes/no) |
| Has audio | `codecAudio` | Presence |
| Orientation | width vs. height | Portrait / Landscape |
| Slow motion | `captureFps > fps + 1` | Boolean |
| Spatial | `spatial` | Boolean |
| 360° | `projection != ""` | Boolean |
| Geo proximity | GPS + radius | Circle filter (lat/lon/km) |
| Metadata attribute | any scalar field | "is" / "is not", with negation |

**Metadata attribute filters** cover: camera model, lens model, codec, dynamic range, fps, resolution class, bit depth, audio channels, audio language, audio track count.

Smart collections use the same filter grammar but stored in the database and applied on demand.

### 10.3 Map View

All four clients include a map view that plots every geotagged video in the current filter as a clustered pin map. When "Show on Map" is triggered from the detail/inspector panel for a GoPro or DJI clip, the GPS track polyline (`gpsTrack`) is drawn as an orange overlay on the map, and the camera auto-fits to the track bounding box.

The iOS and macOS maps use `MapKit` (MKMapView / SwiftUI Map); the desktop Kotlin client uses JXMapViewer2 (OSM tiles); Android uses OSMDroid (OSM tiles).

### 10.4 Inspector Panels

The detail/inspector panel on all clients shows the full `VideoMetadata` set in labelled rows, grouped by section:

- **File** — filename, path, size, indexed date, modification date
- **Video** — resolution, codec, fps, capture fps (if slow-motion), bit depth, dynamic range, HDR, timecode, frame count, duration, bitrate
- **Color** — transfer function, color primaries, projection (if 360°), spatial (if Vision Pro)
- **Audio** — codec, channels, sample rate, bit depth, language, track count
- **Camera** — body (with toggleable internal name), lens, ISO, aperture, shutter, focal length, exposure mode, exposure program, white balance
- **Location** — GPS lat/lon, altitude, GPS track distance
- **Graphs** — audio loudness waveform (on-demand), GPS track (link to map)

Rows with no data are hidden — an SDR H.264 clip does not show empty color transfer / dynamic range rows.

### 10.5 Audio Loudness Graph

The inspector's **Graphs** tab shows a waveform-style loudness graph for the clip's audio. The 480-point EBU R128 momentary-loudness series is rendered as a bar graph scaled from silence to 0 LUFS. The graph is computed on first access and cached; a "recompute" affordance is available for clips where the audio has changed.

### 10.6 Tagging and Stacking

Tags, ratings, and color labels are user-applied and stored in the DB, not in the file. Smart collections layer on top of any combination of stored and extracted metadata fields.

---

## 11. Unsupported Metadata Types

These metadata formats exist in the wild and carry potentially useful information, but are not currently read by ReelVault.

### 11.1 EXIF Maker Notes

All major camera manufacturers embed proprietary binary blobs in EXIF (`MakerNote` tag) that hold data not standardised in regular EXIF: in-body IS settings, eye-detect AF state, picture profiles, lens corrections applied, shading table selection, and more. Reading maker notes requires per-manufacturer reverse-engineered parsers (ExifTool maintains a massive library of these). ReelVault skips the `com.apple.quicktime.apple-maker-note` QuickTime atom and has no ExifTool dependency.

**What's missing:** Sony in-camera picture profile (PP4, PP10/S-Log3), Nikon picture control, Canon picture style, Panasonic film mode, Fujifilm film simulation.

### 11.2 IPTC / IIM Metadata

IPTC IIM (the older binary format) and IPTC Core / Extension (the XMP-embedded version) are used by photojournalism and stock-photo workflows to store credit lines, captions, keywords, copyright, scene codes, and location names. They are embedded as an `IPTC` atom or inside the XMP packet. ReelVault reads the XMP packet but ignores all `Iptc4xmpCore:` and `Iptc4xmpExt:` namespaces within it.

**What's missing:** credit, byline, headline, caption/abstract, source, copyright notice, IPTC subject codes, scene codes, city/country/state from IPTC.

### 11.3 Dolby Vision Metadata

Dolby Vision encodes per-frame color-grading metadata in a separate "RPU" (Reference Processing Unit) stream alongside the base video layer. ReelVault detects HDR via transfer function but does not distinguish Dolby Vision Profile 5 / 8.1 / 8.4 from other HDR formats. The Dolby Vision profile number and compatibility ID are not extracted.

**What's missing:** Dolby Vision profile number, base layer compatibility (SDR / HDR10 compatible), RPU presence flag.

### 11.4 GoPro GPMF — Inertial and Environmental Data

GPMF contains far more than GPS. The following streams are present in HERO5+ footage but not read by ReelVault:

| GPMF key | Data |
|---|---|
| `ACCL` | 3-axis accelerometer (m/s²), ~200 Hz |
| `GYRO` | 3-axis gyroscope (rad/s), ~200 Hz |
| `CORI` | Camera orientation (quaternions) |
| `IORI` | Image-stabilised orientation |
| `GRAV` | Gravity vector |
| `TMPC` | Camera temperature (°C) |
| `SHUT` | Exposure time per frame |
| `WBAL` | White balance (Kelvin) |
| `WRGB` | Per-channel white balance |
| `ISOE` | ISO equivalent |
| `SROT` | Sensor read-out time |
| `MAGN` | Magnetometer data |
| `COND` | Camera condition / lens info |
| `ORIO` | Orientation lock |

Accelerometer + gyroscope + camera orientation together enable post-stabilisation and motion analysis (the kind of visualisation GoPro's own telemetry tools produce). These require reading all GPMF chunks across the entire clip, not just the first one.

### 11.5 DJI — Embedded Subtitle Track and Extended Flight Data

ReelVault reads only a **sidecar** SRT file. It does not read:

- **Embedded subtitle stream** — some DJI models mux the SRT data as a subtitle track directly into the MP4/MKV container, with no sidecar file. Detection requires scanning ffprobe's subtitle streams.
- **Gimbal angles** — yaw, pitch, roll of the camera gimbal; present in some SRT variants
- **Barometric altitude** — separate from GPS altitude; more accurate at low speeds
- **Home point distance** — distance from takeoff point
- **RC signal strength** — useful for post-analysis of footage near signal-loss zones
- **Battery percentage** — logged per-frame in some DJI SRT variants
- **Wind speed** — available in some Mavic 3 SRT outputs

DJI also produces a sidecar `.SRT` file on some models but stores additional telemetry in a separate `.CSV` or binary `.DAT` log file (the DJI Flight Log / `FLY*.DAT`). These are not touched by ReelVault.

### 11.6 Sony Professional Metadata (`.XML` Sidecar)

Sony's XDCAM, VENICE, FX9, and FX6 cameras write a sidecar XML file (`.XML` next to the clip) containing:

- Reel name, scene, take, shot mark
- Audio channel labels and levels
- ND filter position
- SDI output configuration
- Codec profile details (XAVC S class, profile, level)
- LUT name applied on-set

These are used in broadcast and scripted productions for editorial metadata round-tripping. ReelVault does not read Sony sidecar XMLs.

### 11.7 ARRI Metadata

ARRI cameras (ALEXA 35, MINI LF, etc.) embed rich metadata in the MXF or MOV container using the ARRI-specific `ARI` atom or MXF descriptors:

- ASC CDL color correction coefficients
- Look file (LUT) applied
- White balance (Kelvin)
- Exposure index (EI)
- ARRIRAW sensor characteristics
- Lens data (frame line, iris, focus distance, depth of field)

ARRI also produces sidecar `.ALV` (lens/VFX data), `.AML` (ARRI Metadata Language XML), and `.ALEV3` files for productions that need full metadata round-trips.

### 11.8 RED Metadata

RED cameras record in the proprietary `.R3D` container, which embeds per-frame metadata including:

- ISO, aperture, shutter angle
- White balance, tint
- RED color science version (REDCOLOR4, IPP2)
- Sensor crop mode (5K, 6K, 8K, etc.)
- In-camera CDL (creative look)
- Exposure metadata (REDLOG FILM, LOG3G10, etc.)

ReelVault's ffprobe path can surface basic container metadata from `.R3D` files that ffprobe can open, but the RED-specific atoms are not read.

### 11.9 MXF Operational Pattern and Essence Descriptors

MXF (Material eXchange Format) is the broadcast standard container used by XDCAM, P2, AVC-Intra, DNx, and similar workflows. MXF carries:

- Operational pattern (OP1a single-track, OP-Atom per-track, etc.)
- Essence descriptor (video signal standard, stored F-number, etc.)
- Tape reel / clip name
- AS-11 UK DPP / AS-10 / AS-02 compliance descriptor sets
- Timecode tracks (including drop/non-drop, multiple source timecodes)
- Audio channel labels (per AES/EBU channel assignment)
- Embedded KLV metadata in the essence stream

ReelVault indexes `.mxf` files through the ffprobe path and captures timecode and basic codec info, but the MXF-specific descriptors and AS-11 metadata blocks are not read.

### 11.10 ICC Color Profiles

ICC profiles embedded in video files (as a `colr` atom or in the MXF color descriptor) contain the full gamut / white-point / transfer-curve characterisation of the mastering color space. This data is present in HDR10 masters and wide-gamut deliverables. ReelVault stores the raw `color_transfer` and `color_primaries` strings but does not parse the embedded ICC profile binary.

### 11.11 Chapter Tracks and Embedded Subtitles

MP4 and MOV files can carry chapter tracks (a timed text track whose entries define chapter titles and positions) and embedded subtitle / closed-caption tracks (CEA-608, CEA-708, SRT, WebVTT, TTML/IMSC). ffprobe surfaces these as streams. ReelVault does not index chapter titles, chapter timestamps, or subtitle text.

### 11.12 360° Extended Metadata

360° and spatial audio formats carry metadata beyond the basic projection type:

- **Equirectangular metadata** — field of view, initial heading, initial pitch, initial roll (for player positioning)
- **Ambisonics audio** — channel order convention (ACN, FuMa), normalisation (SN3D, N3D), order (1st–4th order)
- **Spatial audio** — Apple Spatial Audio head-tracking parameters, binaural rendering metadata
- **Stereo 3D** — top-bottom / left-right / frame-sequential arrangement, baseline, convergence

ReelVault records the projection name (`equirectangular`, etc.) and the `spatial` boolean but not these extended parameters.

### 11.13 Frame.io / Editorial Metadata

Some delivery pipelines embed editorial identifiers in QuickTime user data (`moov/udta`) or in custom XMP namespaces:

- Scene / shot / take numbers (`SHOT`, `TAKE` user data atoms, or Frame.io XMP)
- Slate information (clapper name, director, camera operator)
- Production company and project name
- Reel name and tape ID
- ALE (Avid Log Exchange) comment fields

These exist primarily in files that have passed through an on-set logger (Silverstack, Lumberjack, Shot Hub) or a post-production system. ReelVault does not read them.

---

*Last updated: 2026-06-19*
