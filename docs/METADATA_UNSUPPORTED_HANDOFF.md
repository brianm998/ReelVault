# Handoff: Implement the 13 Unsupported Metadata Types

## Status: Items 1-7 Complete ✓

All HIGH and MEDIUM priority items have been successfully implemented and committed:

1. ✓ **Item 1: GoPro GPMF Inertial Data** (commit 1780960)
2. ✓ **Item 2: IPTC Keywords & Editorial Metadata** (commit d8d486d)
3. ✓ **Item 3: Chapter Tracks & Embedded Subtitles** (commit f78f33b)
4. ✓ **Item 4: Dolby Vision Detection** (commit 1d438eb)
5. ✓ **Item 5: DJI SRT Embedded Subtitle Detection** (commit 9a68b00)
6. ✓ **Item 6: Sony Professional XML Sidecar** (commit 51e7e53)
7. ✓ **Item 7: Extended 360° / Spatial Audio Parameters** (commit 463605a)

Remaining items (8-13) are LOWER priority and await future work allocation.

## Context

You are working in the ReelVault project at `/Users/brian/git/ReelVault`, checked out on the `develop` branch. ReelVault is a video cataloging app with a Rust core daemon and four client frontends (macOS SwiftUI, iOS SwiftUI, desktop Kotlin Compose, Android Kotlin Compose).

The Rust core extracts metadata from 6 sources today: ffprobe, an embedded XMP atom walk (`core/src/xmp.rs`), QuickTime keyed metadata (`core/src/quicktime.rs`), GoPro GPMF binary track (`core/src/gpmf.rs`), DJI SRT sidecar (`core/src/dji.rs`), and on-demand EBU R128 audio loudness (`core/src/metadata.rs`). A full reference to what is currently extracted is in `docs/METADATA.md`.

This handoff covers implementing the 13 unsupported metadata types listed in `docs/METADATA.md` §11. They are ordered from highest value (broadest video library coverage, most impactful filtering) to lowest. **Do them in that order and stop when you run out of time — do not do low-value items at the expense of skipping high-value ones.**

---

## Architecture Primer (read this before touching any code)

### How a new metadata field gets added end-to-end

Every field follows the same 6-step path:

1. **Rust extraction** — a new or modified module in `core/src/` reads the data from the file.
2. **DB column** — `ALTER TABLE metadata ADD COLUMN …` is appended to the migration list in `core/src/db.rs` (around line 400). The guard key is `"metadata.<column_name>"`. Never change `schema.sql` — the live schema is built from the migration list.
3. **Rust `store_metadata`** — `core/src/metadata.rs` `store_metadata()` populates the new column in the `INSERT … ON CONFLICT DO UPDATE` statement (around line 416–474).
4. **Proto** — `core/proto/reelvault.proto` adds a field to `VideoSummary` (list-level, for filtering/sorting/grid slots) and/or `VideoMetadata` (detail-level, for the inspector). Use the next available field number (currently 55+ for VideoMetadata, 48+ for VideoSummary). **After editing the proto, run `kit/regen-proto.sh` then immediately `git checkout kit/Sources/ReelVaultKit/grpc/reelvault.grpc.swift` — only commit `reelvault.pb.swift`.**
5. **Shared Kotlin model** — `shared/src/main/kotlin/com/reelvault/data/models/Video.kt` adds the field to `VideoSummary` and/or `VideoMetadata` data classes, plus any derived labels (like `slowMotionLabel`).
6. **4 client UIs** — surface in the inspector panel and optionally add a filter. Per CLAUDE.md every feature ships in all 4 clients in the same commit unless there's a documented platform exception.

### Key files

| File | Role |
|---|---|
| `core/src/metadata.rs` | Central extraction + DB write. `store_metadata()` is the main function. |
| `core/src/db.rs` | Migration list around line 320–470. Add `ALTER TABLE` here. |
| `core/proto/reelvault.proto` | gRPC wire format. Edit, then regen. |
| `shared/src/main/kotlin/com/reelvault/data/models/Video.kt` | Kotlin model for all 4 clients. |
| `shared/src/main/kotlin/com/reelvault/data/repository/VideoRepository.kt` | Maps proto response → Kotlin model. |
| `core/src/service.rs` | gRPC service impl — maps DB row → proto response. |
| `macos/ReelVault/Views/DetailGraphsPanel.swift` | macOS inspector graphs/detail rows |
| `ios/ReelVaultiOS/Views/VideoDetailView.swift` | iOS detail rows |
| `desktop/src/main/kotlin/com/reelvault/ui/screens/DetailScreen.kt` | Kotlin detail rows |
| `android/src/main/kotlin/com/reelvault/android/ui/screens/VideoDetailScreen.kt` | Android detail rows |
| `kit/Sources/ReelVaultKit/` | Shared Swift models/view-models for macOS + iOS |

### Build commands

```bash
# Rust core (run from core/):
cargo build
cargo test    # must stay at 157 passing; add tests for new extractors

# Desktop Kotlin (run from repo root):
TMPDIR=/tmp/rv-desktop ./gradlew :desktop:compileKotlin

# Android Kotlin:
TMPDIR=/tmp/rv-android ./gradlew :android:compileDebugKotlin

# macOS Swift (run from repo root):
swift build --package-path macos --scratch-path /tmp/macos-scratch
```

Proto regen:
```bash
cd kit && ./regen-proto.sh
# Then:
git checkout kit/Sources/ReelVaultKit/grpc/reelvault.grpc.swift
# Only commit reelvault.pb.swift
```

---

## Obtaining Test Videos

Most of these metadata types require specific camera models or post-production tools. Below are the best ways to get test samples for each type without buying expensive hardware.

### Free / Public Sample Repositories

- **Coverr** (https://coverr.co) — free 4K stock footage, some with HDR
- **pexels.com/videos** — free stock, variety of cameras
- **pixabay.com/videos** — free stock
- **archive.org** — vast historical footage, many formats
- **sample-videos.com** — format-specific test videos
- **Blender Foundation open films** — high-quality MKV/MOV with rich metadata
- **OpenFootage.net** — free 4K, drone, timelapses
- **NASA Goddard / USGS EarthExplorer** — real drone footage with GPS metadata

### Camera-Specific Samples

- **GoPro Labs samples** (https://community.gopro.com/s/article/GoPro-Labs) — GPMF with full ACCL/GYRO/TMPC data
- **DJI sample footage** (https://www.dji.com/downloads/products) — includes SRT sidecars with gimbal angles
- **Sony VENICE / FX9 samples** (https://www.sony.com/en/articles/sample-footage) — ProRes with XMP and some maker-note data
- **ARRI Sample Footage** (https://www.arri.com/en/learn-help/learn-help-camera-system/sample-footage) — full ALF metadata, LOGC3, ARRIRAW
- **RED Digital Cinema samples** (https://www.red.com/sample-r3d-footage) — `.R3D` with full per-frame metadata
- **Blackmagic Design samples** (https://www.blackmagicdesign.com/support/family/davinci-resolve-and-fusion) — BRAW, ProRes, DNxHD

### ExifTool for Inspection and Creating Test Files

`exiftool` is invaluable for examining what's actually in a file and for injecting metadata into test files:

```bash
brew install exiftool     # macOS
apt install libimage-exiftool-perl   # Linux

# Inspect everything:
exiftool -G -a video.mp4

# Inject IPTC into a test file:
exiftool -IPTC:Keywords="nature,4k" -IPTC:Caption-Abstract="Test clip" test.mp4

# Inject XMP:
exiftool -XMP-dc:Description="Test" -XMP-IPTC:Source="Test source" test.mp4

# Check what GoPro GPMF streams are present:
ffprobe -v quiet -print_format json -show_streams gopro.mp4 | jq '.streams[] | select(.codec_tag_string == "gpmd")'

# Extract raw GPMF binary for analysis:
ffmpeg -i gopro.mp4 -map 0:d:0 -c copy gpmf_raw.bin

# Inspect MXF:
exiftool -G movie.mxf
mxfdump movie.mxf   # from MXFLib if installed

# Show Dolby Vision:
ffprobe -v quiet -print_format json -show_streams dolby.mp4 | jq '.streams[0].side_data_list'
```

### Creating Synthetic Test Files

For formats hard to obtain (ARRI, RED, MXF), create synthetic files with the right metadata injected:

```bash
# Create a 5-second H.264 test video:
ffmpeg -f lavfi -i testsrc=duration=5:size=1280x720:rate=30 -c:v libx264 test.mp4

# Add IPTC metadata via exiftool:
exiftool -IPTC:By-line="Test Camera Op" -IPTC:Caption-Abstract="Scene 1 Take 3" test.mp4

# Add chapter markers:
ffmpeg -i test.mp4 -i chapters.txt -map_metadata 1 -codec copy with_chapters.mp4
# chapters.txt format:
#   ;FFMETADATA1
#   [CHAPTER]
#   TIMEBASE=1/1000
#   START=0
#   END=30000
#   title=Intro

# Create a synthetic 360° file with spherical metadata:
ffmpeg -f lavfi -i testsrc=duration=5:size=3840x1920:rate=30 \
  -c:v libx264 -metadata:s:v "spherical-video=true" equirect.mp4
exiftool -XMP-GSpherical:Spherical=true -XMP-GSpherical:Stitched=true \
  -XMP-GSpherical:ProjectionType=equirectangular equirect.mp4
```

---

## Item 1: GoPro GPMF Inertial Data (ACCL, GYRO, TMPC)  
**Priority: HIGH — GoPro is a top-3 camera brand in most video libraries**

### What to build

Read the per-frame accelerometer (`ACCL`, m/s²), gyroscope (`GYRO`, rad/s), and temperature (`TMPC`, °C) streams from the full GPMF payload and store a downsampled motion-envelope series alongside the audio loudness series. Expose a motion graph in the inspector alongside the loudness graph.

The immediate deliverable is: **motion intensity over time** — the magnitude of the 3D acceleration vector `sqrt(ax²+ay²+az²)` averaged into the same 480-point series as audio loudness. This is the most directly useful form for "how bumpy was this shot?" analysis without needing to render quaternions.

### How GPMF ACCL/GYRO differs from GPS5

`GPS5` is read from only the first GPMF chunk (first ~1 second) in the current code. For the motion graph you need **all chunks** across the whole clip. The GPMF track's sample table (`stbl`) provides the chunk offsets (`stco`/`co64`) and sizes (`stsz`). The current code already walks the `stbl` to find the first chunk — extend it to read all chunks.

### Test footage

```bash
# Download a GoPro sample with verified GPMF telemetry (free, public domain):
curl -L "https://raw.githubusercontent.com/gopro/gpmf-parser/main/samples/hero5.mp4" \
  -o /tmp/hero5.mp4

# Verify ACCL stream is present:
ffprobe -v quiet -print_format json -show_streams /tmp/hero5.mp4 | \
  jq '.streams[] | select(.codec_tag_string == "gpmd")'

# Extract the raw GPMF binary to inspect manually:
ffmpeg -i /tmp/hero5.mp4 -map 0:d:0 -c copy /tmp/hero5_gpmf.bin 2>/dev/null
# Then read with: xxd /tmp/hero5_gpmf.bin | head -100
# Or use the gpmf-parser reference tool if installed
```

The gopro/gpmf-parser GitHub repo (https://github.com/gopro/gpmf-parser) has:
- Multiple sample `.mp4` files with verified telemetry (hero5.mp4, hero6.mp4, max.mp4, karma.mp4)
- A C reference parser showing exactly how to decode ACCL/GYRO with their SCAL divisors and unit conversions
- The `STNM` stream name key that labels each stream ("Accelerometer", "Gyroscope", etc.)

### Rust changes

Modify `core/src/gpmf.rs`:
1. Add `pub accel_magnitude: Vec<f32>` and `pub gyro_magnitude: Vec<f32>` to `GpmfMetadata`.
2. Extend `read_gpmf()` to walk ALL chunk offsets from the sample table, not just the first.
3. Parse `ACCL` (type `s`, 3 × i16 per repeat, divide by SCAL) and `GYRO` (same layout).
4. Compute magnitude = `sqrt(x²+y²+az²)` per sample, collect into Vec<f32>, downsample to 480 points using the existing `downsample_avg()` pattern from the loudness extractor.
5. `TMPC` is a scalar float per sample (type `f`, 4 bytes) — collect into Vec<f32>, downsample.

In `core/src/metadata.rs`:
- Add `accel_series: Option<Vec<u8>>` (bincode or raw f32 LE) and `gyro_series: Option<Vec<u8>>` computed from the GPMF result.

### DB changes (`core/src/db.rs`)

Add to the migration list:
```rust
("metadata.accel_magnitude", "ALTER TABLE metadata ADD COLUMN accel_magnitude BLOB"),
("metadata.gyro_magnitude",  "ALTER TABLE metadata ADD COLUMN gyro_magnitude BLOB"),
```

### Proto changes (`core/proto/reelvault.proto`)

Add to `VideoMetadata` (use next available field numbers after 53):
```proto
// GoPro GPMF motion series: accelerometer magnitude envelope and gyroscope
// magnitude envelope, each downsampled to 480 float32 values stored as
// little-endian bytes (empty when no GPMF or non-GoPro).
bytes accel_magnitude = 54;
bytes gyro_magnitude  = 55;
```

### UI changes (all 4 clients)

Add a **Motion** graph tab in the detail inspector, next to the audio loudness graph. Render `accel_magnitude` as a line graph (or area graph) — the same rendering approach as the loudness bar graph but for motion intensity. Label the Y-axis "m/s²". Show `gyro_magnitude` as a secondary (lighter) series overlaid or below.

Gate the tab on `accel_magnitude.isNotEmpty()` — non-GoPro clips skip it entirely.

---

## Item 2: IPTC Keywords and Editorial Metadata  
**Priority: HIGH — stock footage, broadcast, and journalism libraries use IPTC universally**

### What to build

Read IPTC Core / Extension fields from the XMP packet already being parsed in `core/src/xmp.rs`. The fields live in the `Iptc4xmpCore:` and `Iptc4xmpExt:` XML namespaces within the same XMP document.

### Key fields to extract

| IPTC XMP field | Useful for |
|---|---|
| `Iptc4xmpCore:Scene` (code list) | Scene type classification |
| `Iptc4xmpCore:SubjectCode` | IPTC Subject News Codes |
| `Iptc4xmpCore:CreatorContactInfo/CiUrlWork` | Creator URL |
| `Iptc4xmpExt:DigitalSourceType` | AI-generated vs. camera-original |
| `dc:description` (Dublin Core) | Short description / caption |
| `dc:creator` | Photographer / videographer |
| `dc:rights` | Copyright statement |
| `dc:subject` (bag of strings) | Free-form keywords |
| `photoshop:Source` | Wire service source (AP, Reuters) |
| `photoshop:Headline` | News headline |

Start with `dc:description`, `dc:creator`, `dc:rights`, and `dc:subject` (keyword bag) as these appear in the widest range of files — any file exported from Lightroom, Premiere, Resolve, or touched by exiftool may have them.

### Test footage

```bash
# Create a test MP4 with IPTC metadata injected:
ffmpeg -f lavfi -i testsrc=duration=5:size=1280x720:rate=30 -c:v libx264 /tmp/iptc_test.mp4
exiftool \
  -XMP-dc:Description="A test clip of a mountain sunrise." \
  -XMP-dc:Creator="Jane Photographer" \
  -XMP-dc:Rights="© 2026 Jane Photographer" \
  -XMP-dc:Subject="mountain,sunrise,landscape" \
  -XMP-photoshop:Headline="Mountain Sunrise Test" \
  /tmp/iptc_test.mp4

# Verify it round-tripped:
exiftool -G /tmp/iptc_test.mp4 | grep -i "description\|creator\|rights\|subject\|headline"
```

Real IPTC-bearing footage:
- Any video downloaded from Getty, AP, Reuters, or major stock agencies
- Files touched by Adobe Bridge or Lightroom's video export
- Files processed by exiftool with IPTC injection

### Rust changes

Extend `core/src/xmp.rs`:
1. Add fields to `XmpMetadata`:
   ```rust
   pub description: Option<String>,
   pub creator: Option<String>,
   pub rights: Option<String>,
   pub keywords: Vec<String>,  // dc:subject bag
   pub headline: Option<String>,
   ```
2. In `parse_xmp()`, add regex patterns for:
   - `dc:description` (may be nested in `rdf:Alt/rdf:li`)
   - `dc:creator` (may be `rdf:Seq/rdf:li` or a plain string)
   - `dc:rights` (nested `rdf:Alt/rdf:li`)
   - `dc:subject` (a `rdf:Bag` of `rdf:li` elements)
   - `photoshop:Headline`
3. For the `dc:subject` bag, extract all `<rdf:li>` children and collect into `Vec<String>`.

### DB changes

```rust
("metadata.description",    "ALTER TABLE metadata ADD COLUMN description TEXT"),
("metadata.creator",        "ALTER TABLE metadata ADD COLUMN creator TEXT"),
("metadata.rights",         "ALTER TABLE metadata ADD COLUMN rights TEXT"),
("metadata.keywords",       "ALTER TABLE metadata ADD COLUMN keywords TEXT"),  // JSON array
("metadata.headline",       "ALTER TABLE metadata ADD COLUMN headline TEXT"),
```

### Proto changes

Add to `VideoMetadata`:
```proto
string description = 56;
string creator     = 57;
string rights      = 58;
repeated string keywords = 59;
string headline    = 60;
```

Add `keywords` to the FTS5 `video_search` virtual table in `core/src/search.rs` so keywords are full-text searchable.

Add `description`, `creator`, `headline` to `VideoSummary` only if you want them filterable/sortable from the grid (otherwise detail-only is fine for v1).

### UI changes

Add to the inspector's existing **Camera** or a new **Editorial** section:
- Description (multiline text)
- Creator
- Rights
- Keywords (tag-chip display, same style as user tags)
- Headline

Keywords from IPTC should be displayed distinctly from user-applied ReelVault tags (different color or icon) since they are read-only extracted values.

---

## Item 3: Chapter Tracks and Embedded Subtitles  
**Priority: MEDIUM — common in edited/delivered content, tutorial videos, podcasts**

### What to build

Detect chapter tracks and subtitle tracks from ffprobe output (already parsed in `core/src/metadata.rs`). Store chapter count and whether subtitles are embedded. Optionally store chapter titles as a JSON array for display in the inspector.

### ffprobe already gives you this — no new extractor needed

Chapter data is in the ffprobe JSON under `format.chapters` (an array of `{id, start_time, end_time, tags: {title}}`). Subtitle streams are already in `streams[]` with `codec_type == "subtitle"`.

```bash
# Check chapters:
ffprobe -v quiet -print_format json -show_chapters input.mp4 | jq '.chapters'

# Check subtitle streams:
ffprobe -v quiet -print_format json -show_streams input.mp4 | \
  jq '.streams[] | select(.codec_type == "subtitle")'
```

### Creating test files

```bash
# Create a file with 3 chapters:
cat > /tmp/chapters.txt << 'EOF'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=10000
title=Introduction
[CHAPTER]
TIMEBASE=1/1000
START=10000
END=25000
title=Main Content
[CHAPTER]
TIMEBASE=1/1000
START=25000
END=30000
title=Conclusion
EOF

ffmpeg -f lavfi -i testsrc=duration=30:size=1280x720:rate=30 -c:v libx264 /tmp/no_chapters.mp4
ffmpeg -i /tmp/no_chapters.mp4 -i /tmp/chapters.txt -map_metadata 1 -codec copy /tmp/with_chapters.mp4

# Verify:
ffprobe -v quiet -print_format json -show_chapters /tmp/with_chapters.mp4 | jq '.chapters[].tags.title'

# Add subtitles:
echo "1\n00:00:00,000 --> 00:00:03,000\nHello, this is a subtitle." > /tmp/sub.srt
ffmpeg -i /tmp/with_chapters.mp4 -i /tmp/sub.srt -c copy -c:s mov_text /tmp/with_subs.mp4
```

### Rust changes

In `core/src/metadata.rs`, `FFProbeOutput` already has parsed stream list. Add:
1. Parse `probe_output.chapters` (add `chapters: Vec<FFProbeChapter>` to the `FFProbeOutput` struct with `start_time`, `end_time`, and `tags.title`).
2. Extract chapter count and JSON array of `{title, start_ms, end_ms}`.
3. Count subtitle streams from `streams`.

### DB changes

```rust
("metadata.chapter_count",   "ALTER TABLE metadata ADD COLUMN chapter_count INTEGER DEFAULT 0"),
("metadata.chapters_json",   "ALTER TABLE metadata ADD COLUMN chapters_json TEXT"),
("metadata.subtitle_tracks", "ALTER TABLE metadata ADD COLUMN subtitle_tracks INTEGER DEFAULT 0"),
```

### Proto changes

Add to `VideoSummary`:
```proto
int32 chapter_count    = 48;  // 0 when no chapters
int32 subtitle_tracks  = 49;  // 0 when none
```

Add to `VideoMetadata`:
```proto
// JSON array [{title, start_ms, end_ms}, …] or empty string.
string chapters_json   = 61;
```

### UI changes

In the inspector's **File** section: show "3 chapters" when chapter count > 0, with a disclosure chevron that expands to list the chapter titles and timestamps. Show "N subtitle tracks" (with codec names, e.g. "SRT, CC") in the **Audio** section. Add `has_chapters` and `has_subtitles` attribute filter options.

---

## Item 4: Dolby Vision Detection  
**Priority: MEDIUM — growing on iPhone, Apple TV, Netflix deliverables**

### What to build

Detect whether a clip carries Dolby Vision metadata (the RPU stream or the `dovi` colour volume descriptor) and which profile it is. Store profile number and cross-compatibility layer.

### Detection approach

Dolby Vision metadata appears in two ways in ffprobe output:

1. **`side_data_list` entry with `side_data_type == "DOVI configuration record"`** — present on DV-in-MP4 (iPhone ProRes HLG, iPhone HEVC Dolby Vision). Contains profile and level.
2. **A `dovi` stream entry** — present in some MKV / TS containers.

```bash
ffprobe -v quiet -print_format json -show_streams iphone_dv.mov | \
  jq '.streams[0].side_data_list[] | select(.side_data_type | contains("DOVI"))'
```

iPhone 12+ records Dolby Vision Profile 8.4 (HLG-compatible base layer, HEVC). Apple TV recordings are Profile 5. Netflix deliverables are Profile 4 or 8.1. Profile 8.x has an HDR10-compatible base layer; Profile 5 does not.

### Test footage

- Record a 5-second clip on an iPhone 12+ (or any Dolby Vision-capable iPhone) and AirDrop it — it will be HEVC with Dolby Vision Profile 8.4
- Apple TV+ sample trailers (downloaded via Apple TV app, DRM-free previews) are Profile 5
- Synthetic: `ffmpeg` cannot create real DV RPU data, so use a real iPhone clip

```bash
# Check if any stream has DV side data:
ffprobe -v quiet -print_format json -show_streams "$FILE" | \
  jq 'any(.streams[].side_data_list[]?; .side_data_type == "DOVI configuration record")'
```

### Rust changes

In `core/src/metadata.rs`, inside `store_metadata()`, after the existing `spatial` / `projection` detection block:

```rust
// Dolby Vision: look for a DOVI configuration record in any stream's side_data_list.
let dovi_profile: Option<i32> = probe_output.streams.iter().find_map(|s| {
    s.side_data_list.as_ref()?.iter().find_map(|sd| {
        if sd.side_data_type.as_deref()?.contains("DOVI") {
            sd.dv_profile.map(|p| p as i32)
        } else {
            None
        }
    })
});
let dolby_vision = dovi_profile.is_some();
```

You will need to add `dv_profile: Option<u8>` to the `SideData` struct (ffprobe JSON has it as `dv_profile`).

### DB / Proto / UI

```rust
// db.rs migration:
("metadata.dolby_vision_profile", "ALTER TABLE metadata ADD COLUMN dolby_vision_profile INTEGER"),
```

```proto
// VideoSummary — influences dynamic_range label:
// proto reuses dynamic_range; update derivation to emit "Dolby Vision P8.4" etc.
// VideoMetadata:
int32 dolby_vision_profile = 62;  // -1 absent, 0-9 profile number
```

Update the `dynamic_range` derivation in Rust (`prettify_log_curve` / the derivation block) to emit labels like `"Dolby Vision (P8.4)"` or `"Dolby Vision (P5)"` when DV metadata is present, superseding the plain `"HDR (HLG)"` label.

---

## Item 5: DJI SRT — Embedded Subtitle Track  
**Priority: MEDIUM — many DJI models put SRT in the container, not as a sidecar**

### What to build

When no sidecar `.SRT` file is found (the common case for DJI files copied from a card reader without sidecars, or downloaded from a cloud), detect whether the DJI telemetry is in an embedded subtitle stream and parse it identically.

### Detection

```bash
ffprobe -v quiet -print_format json -show_streams dji_clip.mp4 | \
  jq '.streams[] | select(.codec_type == "subtitle")'
# DJI embedded subtitle codec is typically "srt" or "mov_text", sometimes "subrip"
```

If a subtitle stream exists and its first packet looks like DJI SRT content (check for `[iso :` or `GPS(` patterns), treat it as DJI telemetry.

### Rust changes

In `core/src/dji.rs`, add `read_embedded_subtitle(video_path: &Path) -> DjiTelemetry`:

```rust
pub fn read_embedded_subtitle(video_path: &Path) -> DjiTelemetry {
    // Use ffmpeg to extract the subtitle stream to stdout:
    let output = std::process::Command::new("ffmpeg")
        .args(["-i", video_path.to_str().unwrap_or(""),
               "-map", "0:s:0",   // first subtitle stream
               "-c:s", "srt",
               "-f", "srt", "-"])
        .output();
    let Ok(output) = output else { return DjiTelemetry::default() };
    let text = String::from_utf8_lossy(&output.stdout);
    parse_srt(&text)
}
```

In `core/src/metadata.rs`, update the DJI call site: if no sidecar and the file is a DJI container and there is a subtitle stream, call `read_embedded_subtitle`.

---

## Item 6: Sony Professional XML Sidecar  
**Priority: MEDIUM — Sony VENICE, FX9, FX6, FX3 libraries**

### What to build

Read the Sony professional sidecar XML (`<clip>.XML` next to `<clip>.MP4` or `<clip>.MXF`) and extract: clip name, scene, take, ND filter position, audio channel labels, and LUT name. These are used in broadcast/scripted productions.

### XML structure example

```xml
<?xml version="1.0" encoding="utf-8"?>
<NonRealTimeMeta xmlns="urn:schemas-professionalDisc:nonRealTimeMeta:ver.2.20" ...>
  <ClipContent>
    <ClipName>A001C001_220807</ClipName>
    <ClipNumber>1</ClipNumber>
    <SceneInformation Scene="1" Take="1"/>
    <ShotMark IsFirst="true" IsFinal="true"/>
    <AudioRecordingInfo AudioChannels="4">
      <AudioChannel No="1" ChannelLevel="-20dBFS" TrackCh="1"/>
    </AudioRecordingInfo>
    <IrisFNumber>2.8</IrisFNumber>
    <NDFilter Value="1/4"/>
    <LUT LUTName="Venice_Look1.cube"/>
  </ClipContent>
</NonRealTimeMeta>
```

### Finding test footage

Sony offers free VENICE sample clips at https://www.sony.com/en/articles/sample-footage — download any that include the XML sidecar. The FX3 and FX6 also produce these XMLs; community footage on YCImaging.com and other tutorial channels sometimes includes them.

```bash
# Verify XML presence next to a Sony clip:
ls /path/to/sony/A001C001_220807.{MP4,XML}

# Inspect XML structure:
cat /path/to/A001C001_220807.XML | xmllint --format - | head -50
```

### Rust changes

Create `core/src/sony_xml.rs`:

```rust
pub struct SonyXmlMetadata {
    pub clip_name: Option<String>,
    pub scene: Option<String>,
    pub take: Option<String>,
    pub nd_filter: Option<String>,     // "1/4", "1/8", etc.
    pub lut_name: Option<String>,
    pub iris_f_number: Option<f64>,
}

pub fn read_sidecar(video_path: &Path) -> SonyXmlMetadata {
    // Try <stem>.XML and <stem>.xml next to the video.
    // Parse with quick regex or simple string search — no full XML parser dependency.
}
```

### DB / Proto

```rust
("metadata.production_scene",  "ALTER TABLE metadata ADD COLUMN production_scene TEXT"),
("metadata.production_take",   "ALTER TABLE metadata ADD COLUMN production_take TEXT"),
("metadata.nd_filter",         "ALTER TABLE metadata ADD COLUMN nd_filter TEXT"),
("metadata.lut_name",          "ALTER TABLE metadata ADD COLUMN lut_name TEXT"),
```

Add to `VideoMetadata` proto. These are detail-only (not summary-level) for v1.

---

## Item 7: Extended 360° / Spatial Audio Parameters  
**Priority: MEDIUM — 360° cameras (Insta360, Ricoh Theta, GoPro MAX) are common**

### What to build

From the ffprobe spherical video XMP (`streams[video].side_data_list`) and audio codec tags, extract:
- Initial heading (yaw), pitch, roll for player positioning
- Stereo mode (top-bottom, left-right, mono)
- Ambisonics channel order and normalisation (from audio stream tags: `ambisonics_channel_ordering`, `normalization`)

### Test footage

```bash
# Insta360 sample files (free download):
# https://www.insta360.com/download/insta360-oneRS

# GoPro MAX sample:
# https://community.gopro.com/s/article/GoPro-MAX-Sample-Files

# Inspect spherical metadata:
ffprobe -v quiet -print_format json -show_streams 360video.mp4 | \
  jq '.streams[0].side_data_list[] | select(.side_data_type | contains("spherical"))'

# Inspect ambisonics audio tags:
ffprobe -v quiet -print_format json -show_streams 360video.mp4 | \
  jq '.streams[] | select(.codec_type == "audio") | .tags'
```

### Rust changes

In `core/src/metadata.rs`, extend the existing spherical detection block to also extract `initial_heading`, `initial_pitch`, `initial_roll` (floats), `stereo_mode` (string), and ambisonics channel ordering.

Add DB columns and proto fields accordingly. Surface in the inspector under the **Video** section alongside the existing `projection` row.

---

## Items 8–13: Lower Priority — Brief Specs

These are lower-priority because the footage is less common in typical libraries, the metadata is less useful for filtering/discovery, or the implementation requires significant effort relative to the benefit. Implement only if time permits after Items 1–7.

### Item 8: ARRI Metadata (`.AML` sidecar)

Create `core/src/arri_aml.rs`. Parse the ARRI Metadata Language XML sidecar (`<clip>.AML` next to `<clip>.mov` or `<clip>.mxf`). Target fields: exposure index (EI), ND filter, white balance (Kelvin), clip name, scene, take, LUT name, ARRI Look name. ARRI sample footage with AML files: https://www.arri.com/en/learn-help/learn-help-camera-system/sample-footage. Same sidecar pattern as Sony XML above.

### Item 9: RED `.R3D` Container Metadata

ffprobe can open `.R3D` files as of FFmpeg 5.x. Parse the ffprobe output for RED-specific tags that ffprobe surfaces into `format.tags` and `streams[video].tags`: `reel`, `scene`, `take`, `iso`, `exposure_compensation`, `white_balance_kelvin`, `tint`, `color_science`, `gamma_curve`, `color_space_r3d`. No new extractor needed — these come through the existing ffprobe path. Just add the tag-reading code in `metadata.rs` for these field names. RED sample R3D files: https://www.red.com/sample-r3d-footage.

### Item 10: MXF Operational Pattern and Descriptors

Read MXF-specific metadata using ffprobe's MXF demuxer output. ffprobe surfaces `format.tags` with MXF-specific keys including `material_package_ul`, `clip_name`, `reel`, `scene`, `take`, and `creation_date`. Subtitle tracks from MXF (CEA-608 in XDCAM) are covered by Item 3's generic subtitle detection. Full MXF KLV parsing (operational pattern, essence descriptor detail) would require a native MXF atom walker — treat as future work.

### Item 11: EXIF Maker Notes

This is a very large scope item. `exiftool` supports hundreds of manufacturer-specific maker note formats. A practical v1 approach: run `exiftool -json -MakerNotes <file>` as a subprocess and extract a small curated set of per-manufacturer fields. Target: Sony picture profile (PP4, PP10, S-Log3), Nikon picture control, Canon picture style, Panasonic film mode. Gate the subprocess call on the camera make so it only runs for supported manufacturers. Store as `metadata.maker_notes_json` (TEXT). This adds an exiftool dependency that is currently absent — make it optional and gracefully degrade when exiftool is absent.

### Item 12: ICC Color Profile

Read the `colr` atom from the MP4/MOV container (an atom ReelVault's QuickTime walker already traverses but ignores). The `colr` atom has a 4-byte type tag (`nclx` for parametric or `rICC` / `prof` for an embedded ICC profile binary). For `nclx`: extract primaries, transfer, matrix, full-range flag — these are already exposed via ffprobe's `color_primaries` / `color_transfer`. For embedded ICC: extract the profile description string (inside the ICC binary at a well-known offset). Add `icc_profile_name: Option<String>` to the DB and proto. Extend `core/src/quicktime.rs` to also record the `colr` atom payload.

### Item 13: Frame.io / Editorial Identifiers

Read editorial metadata from the XMP packet's `xmpDM:` namespace (the XMP Dynamic Media schema that Premiere, Frame.io, and FCP use for scene/shot/take/reel). Add to the `parse_xmp()` function in `core/src/xmp.rs`: `xmpDM:scene`, `xmpDM:shot`, `xmpDM:take`, `xmpDM:reel`, `xmpDM:cameraAngle`, `xmpDM:cameraModel` (may differ from EXIF model), `xmpDM:director`, `xmpDM:cameraOperator`. Store as `metadata.scene`, `metadata.take`, `metadata.reel` (reuse the columns from Item 6 Sony XML if you've done that first — they share the same semantic fields). This ensures both Frame.io/Premiere-exported files and Sony-sidecar files populate the same DB columns.

---

## Testing Requirements

Every new extractor module must have unit tests in Rust. The existing pattern (in `core/src/gpmf.rs`, `core/src/dji.rs`, `core/src/xmp.rs`) is:

1. Inline `#[cfg(test)]` module at the bottom of the file
2. Embed small synthetic test payloads as `const` byte arrays or strings
3. Test the parser against the synthetic payload, not against real video files (real files can't be committed)

For file-dependent tests (things that require a real GoPro `.mp4`), gate behind `#[ignore]` with a doc comment explaining how to get the test file.

The Rust test suite must remain at 157 passing (or higher) after your changes.

```bash
# Run tests:
cd core && cargo test

# Run a specific module's tests:
cd core && cargo test gpmf
cd core && cargo test xmp
```

---

## Commit Strategy

Each unsupported metadata type is a separate commit. Do not batch multiple types into one commit. Format:

```
feat: <short description>  — e.g. "feat: IPTC keywords + editorial fields from XMP"
```

After all commits on your branch, merge to `develop` (the working branch). If you hit a merge conflict in `core/src/dji.rs` or `core/src/metadata.rs` (these are active files), always resolve by keeping the more complete version.

Do not touch `core/src/gpmf.rs`'s existing GPS5 extraction or the existing `read_quicktime_metadata()` logic beyond what is needed for the new fields — those are battle-tested and the tests will catch regressions.

---

## Reference Files to Read Before Starting

In priority order:

1. `docs/METADATA.md` — the full reference for what is already extracted
2. `core/src/metadata.rs` — the extraction pipeline, `store_metadata()`, INSERT SQL
3. `core/src/gpmf.rs` — existing GPMF reader (model for Item 1)
4. `core/src/xmp.rs` — existing XMP reader (model for Items 2 and 13)
5. `core/src/dji.rs` — existing DJI reader (model for Item 5)
6. `core/src/db.rs` lines 320–470 — migration list (where to add columns)
7. `core/proto/reelvault.proto` — proto fields (add new fields here)
8. `shared/src/main/kotlin/com/reelvault/data/models/Video.kt` — Kotlin models
9. `CLAUDE.md` — project-wide coding rules, 4-client parity requirement
