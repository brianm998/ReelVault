# ReelVault Core (`reelvault-core`)

The Rust gRPC daemon behind ReelVault. It owns the SQLite catalog, FFprobe
metadata extraction, thumbnail + Lightroom-scrub-frame generation,
scan/indexing, search, and filter aggregation. Both clients (Kotlin Compose,
SwiftUI) talk to it over gRPC on loopback.

For the overall project, run instructions, and CLI flags, see the
[top-level README](../README.md). This file documents the parts of the core
that behave **differently per platform** — chiefly ProRes RAW thumbnails.

## Building

```bash
cd core
cargo build --release        # → target/release/reelvault-core
```

- **Rust 1.75+** and **FFmpeg / FFprobe on `PATH`** are required on every
  platform.
- The core compiles **identically on macOS, Linux, and Windows.** It has no
  platform-specific Rust dependencies; CI builds + tests it on
  `ubuntu-latest`, `macos-latest`, and `windows-latest`
  ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)).
- **macOS build machines** additionally use **`swiftc`** (ships with Xcode /
  the Command Line Tools) to compile the small ProRes RAW helper described
  below. This is *not* a hard requirement: if `swiftc` is absent, `build.rs`
  prints a warning, the helper is simply not embedded, and the daemon falls
  back to the QuickLook poster path. Linux/Windows builds never invoke
  `swiftc` at all.

## Thumbnails & scrub frames

Every video gets a still thumbnail (small/medium/large) plus 10 evenly-spaced
"scrub" frames for Lightroom-style hover scrubbing. For ordinary codecs these
are produced by **ffmpeg** the same way on every platform, and a missing
thumbnail is regenerated on demand (so clearing the cache self-heals).

### ProRes RAW is the platform-specific case

ffmpeg cannot *develop* ProRes RAW — specifically the Atomos S-Log3 /
S-Gamut3.Cine variant. Its decoder is experimental and produces dark, flat,
wrong-gamut frames, and it can't apply the log→display transform. The only
decoders that read ProRes RAW correctly are Apple's, which are **macOS-only**:

- **`qlmanage`** (QuickLook) — renders a single correctly-developed *poster*
  frame. It has **no** timestamp / frame-index argument, so it can only ever
  give you one frame.
- **AVFoundation** (`AVAssetImageGenerator`) — the same pipeline AVPlayer uses;
  decodes correctly **and** seeks to an arbitrary timestamp.

So the core resolves ProRes RAW frames like this:

| Platform | Still thumbnail | Scrub frames |
| --- | --- | --- |
| **macOS** | QuickLook poster → ffmpeg | AVFoundation per-timestamp (`rv-frameshot`) → QuickLook poster (repeated) → ffmpeg |
| **Linux / Windows** | ffmpeg | ffmpeg |

A still is a single frame, so the QuickLook poster (correctly developed, no
timestamp needed) is all it requires; only the scrub strip needs distinct
per-timestamp frames, which is what `rv-frameshot` adds.

On Linux/Windows the QuickLook/AVFoundation branch is compiled out
(`use_quicklook()` is `cfg!(target_os = "macos") && codec == "prores_raw"`), so
ProRes RAW falls straight through to ffmpeg — flatter/darker frames, and every
scrub position shows the same frame. There is no comparable free
cross-platform ProRes RAW developer to drop in; this is an inherent limitation
of the format off macOS. (ProRes RAW is an Apple/Atomos-centric format, so the
affected non-Mac user set is small.)

### The `rv-frameshot` helper (macOS)

AVFoundation can't be called from Rust without linking Apple frameworks, which
would break the cross-platform build. Instead:

1. [`macos/rv-frameshot.swift`](macos/rv-frameshot.swift) is a tiny standalone
   Swift program — `rv-frameshot <input> <output.jpg> <timeSeconds> <maxPx>` —
   that extracts one frame via `AVAssetImageGenerator`.
2. [`build.rs`](build.rs) compiles it with `swiftc` **only when the build
   target is macOS** and embeds the resulting binary's bytes into the daemon
   (`FRAMESHOT_BIN: Option<&[u8]>`, via `$OUT_DIR/frameshot.rs`). Off macOS, or
   without `swiftc`, that constant is `None`.
3. At runtime the daemon writes those bytes to a temp file once and `exec`s it
   as a subprocess (see `frameshot_extract` in
   [`src/thumbnails.rs`](src/thumbnails.rs)).

So **AVFoundation lives in a separate, embedded executable** — the Rust binary
never links it. That's what lets the same source compile and run everywhere
while only macOS gets the good ProRes RAW frames.

### Operational notes

- Thumbnails are cached at `~/Library/Caches/ReelVault` (macOS),
  `$XDG_CACHE_HOME/reelvault` (Linux), or the platform cache dir (Windows),
  named `{video_id}_{size}.jpg` and `{video_id}_scrub_{0..9}.jpg`.
- Regeneration is on-demand and query-time only — no library rescan is needed
  after clearing the cache. Restart the *new* daemon binary first so the
  regenerated frames use the current code path.
