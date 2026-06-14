# Running the ReelVault Core Directly on iOS

> **Audience:** an engineer/agent who will implement on-device iOS support for
> the Rust core (`core/`), reaching **full functional parity** with the desktop
> daemon. This document is the design + work breakdown. It is grounded in the
> code as of this writing — file/line references are included so you can verify
> every claim before acting on it.
>
> **Status:** design proposal. Nothing here is built yet. Decisions marked
> **[DECISION NEEDED]** must be resolved (mostly by the project owner) before or
> during implementation.

---

## 1. Goal and the two use cases

ReelVault wants an iOS client that supports:

1. **Remote mode** — the phone is on the same Wi‑Fi as a machine running the
   Rust daemon, and browses that catalog over the network.
2. **On-device mode** — the phone catalogs and views videos that already live on
   the device (Photos library / Files), with the core running **inside the app**.

This document is about making use case 2 work *with all existing functionality*
(metadata extraction, still + scrub thumbnails, ProRes RAW frames, proxy
generation, grouping, proxy detection, tags/collections, search, watching).

Use case 1 needs almost no core change and is covered briefly in
[§9](#9-use-case-1-remote-mode-ships-independently). **Ship it first** — it is
days of work, not weeks, and it de-risks the client UI.

---

## 2. TL;DR verdict

- **The language/runtime port is not the hard part.** Rust cross-compiles to
  `aarch64-apple-ios` cleanly. `rusqlite` (bundled SQLite), `tokio`, `tonic`,
  `prost`, `image`, `rayon`, `serde`, `chrono`, `regex`, `walkdir` all build for
  iOS. Roughly **half the crate is reused as-is**: `db.rs`, `search.rs`,
  `grouping.rs`, `post_index.rs`, `metadata_keys.rs`, `camera_names.rs`,
  `full_resolution.rs`, `path_templates.rs`, `imagehash.rs`, and the *business
  logic* in `service.rs`.
- **The hard part is that the entire media pipeline shells out to external
  command-line binaries, and iOS forbids that.** A sandboxed iOS app cannot
  `fork`/`exec` a separate executable (`ffmpeg`, `ffprobe`, `exiftool`, `curl`,
  `qlmanage`), and cannot stage-and-exec a compiled helper (`rv-frameshot`).
  Every one of those call sites must be replaced with a **linked library or an
  Apple framework**. See [§4.1](#41-constraint-1-no-subprocesses-the-big-one).
- **The deployment model also changes.** There is no long-lived background
  daemon on iOS, and the catalog's "filesystem path + recursive watcher" data
  model doesn't match the Photos/Files sandbox. These are adaptations, not
  rewrites, but they touch real surface area.
- **Key architectural lever:** keep the gRPC seam and run the server
  **in-process** inside the app. Then *both* use cases collapse to "connect to a
  gRPC endpoint" — remote vs. local is just a different host — and the existing
  SwiftUI gRPC client and `service.rs`/`into_server()` are reused unchanged. The
  only genuinely new core work becomes the **media backend**, the **ingest/data
  model**, and **lifecycle**. See [§7](#7-how-the-app-embeds-the-core).
- **Distribution risk:** the crate is **GPL‑3.0‑or‑later** (`core/Cargo.toml`),
  and a GPL binary linked against a GPL FFmpeg build is in long-standing tension
  with the App Store's terms. This is a real blocker for App Store distribution
  and must be resolved at the project level. See
  [§8.4](#84-licensing-gpl-3-vs-the-app-store-decision-needed).

**Bottom line:** the core does **not** need a ground-up rewrite. It needs (a) a
media-I/O abstraction with a native iOS implementation, (b) an
ingest/data-model adaptation for the Photos/Files sandbox, and (c) a change from
"spawn a daemon" to "embed a library / run the server in-process". Estimate is
in [§10](#10-phased-plan).

---

## 3. How the core works today (the parts that matter for porting)

### 3.1 Process shape

`core/src/main.rs` is a daemon: it binds a **TCP** gRPC server on loopback
(`127.0.0.1:50051` by default, `core/src/main.rs:156`–`184`), opens a SQLite
catalog, optionally runs as a launchd/systemd/Windows service, and raises the
file-descriptor `rlimit` via `nix` (`core/src/main.rs:201`, `#[cfg(unix)]`).

The actual API lives in `core/src/service.rs`: `ReelVaultService`
(`service.rs:34`) is constructed with `ReelVaultService::new(db, config)`
(`service.rs:74`) and turned into a tonic server with `into_server()`
(`service.rs:133`). It holds `Arc<Database>`, `Arc<Config>`, a watcher handle, a
`broadcast` bus for live `CatalogEvent`s, and per-video scrub locks. **The
service methods are the whole product surface** — ~60 RPCs (`service.rs`, every
`async fn` from line 1077 onward; mirror of `core/proto/reelvault.proto`).

> **Implication:** anything that can call `ReelVaultService` methods (in-process
> or over gRPC) gets the full product. The transport is incidental.

### 3.2 The media pipeline = external processes

This is the crux. Every media operation runs a CLI tool:

| Operation | Tool | Call site(s) |
|---|---|---|
| Metadata extraction | `ffprobe -print_format json` | `core/src/metadata.rs:48` |
| ffprobe availability probe | `ffprobe` | `core/src/metadata.rs:279` |
| Still thumbnail (frame @ 50%) | `ffmpeg -ss …` | `core/src/thumbnails.rs:106`, resize `:142`, `:166` |
| Scrub thumbnails (10 frames) | `ffmpeg` | `core/src/thumbnails.rs:297` |
| On-demand frame at width | `ffmpeg` | `core/src/thumbnails.rs:421` |
| ProRes RAW frame (timestamped) | `rv-frameshot` (AVFoundation helper) | `core/src/thumbnails.rs:640` |
| Poster fallback | `qlmanage -t` | `core/src/thumbnails.rs:675` (macOS only) |
| QuickLook PNG → JPEG | `ffmpeg` | `core/src/thumbnails.rs:693` |
| Proxy transcode (H.264/AAC) | `ffmpeg … libx264` | `core/src/proxies.rs:766` |
| Write GPS/location tag | `ffmpeg -metadata` | `core/src/metadata.rs:335` |
| Write creation-time tag | `ffmpeg -metadata` | `core/src/metadata.rs:418`, `:457` |
| XMP sidecar read/write | `exiftool` | `core/src/xmp.rs:645`, `:681` |
| Camera sensor-spec fetch | `curl` | `core/src/sensor_cache.rs:245` |

The binary resolution is centralized: `core/src/ffmpeg.rs` resolves `ffmpeg`/
`ffprobe` to a bundled-next-to-the-daemon copy or `PATH`, and **every call site
goes through `ffmpeg_command()` / `ffprobe_command()`** (`ffmpeg.rs:79`, `:84`).
That centralization is a gift — it's the natural seam to abstract (see
[§6.1](#61-the-media-backend-trait-the-core-of-the-port)).

`rv-frameshot` is special: `core/build.rs` (`emit_frameshot`, line 19) compiles
`core/macos/rv-frameshot.swift` with `swiftc` **only when the target OS is
macOS**, embeds the Mach-O bytes, and `core/src/thumbnails.rs:600`–`620` writes
those bytes to a temp file, `chmod 0755`, and execs it. **None of this works on
iOS** (no stage-and-exec), but the build guard already emits
`FRAMESHOT_BIN = None` for non-macOS targets, so an iOS build compiles — it just
has no frame extractor until we add one.

Concurrency of these external processes is bounded by a blocking semaphore
(`core/src/concurrency.rs`, `acquire_ffmpeg_permit()`), default = CPU count.

### 3.3 Data model and watching

- Identity is a **filesystem path**: `videos.path TEXT UNIQUE` (see `CLAUDE.md`
  schema; `db.rs`). Scanning walks `library_locations` recursively with
  `walkdir` (`core/src/indexing.rs`).
- The watcher (`core/src/watcher.rs:37`, `:202`) wraps
  `notify::RecommendedWatcher` (FSEvents/inotify) with a poll fallback for
  NFS/SMB, and feeds change events into the `CatalogEvent` broadcast.
- `post_index.rs` folds auto-grouping and proxy-detection into the scan; the
  CPU-heavy part is JPEG decode + dHash (`imagehash.rs`, pure Rust — **iOS-safe**).
- Caches/config paths are chosen per-OS in `core/src/config.rs:225` (a
  `macos`/`windows`/`else` split) and `core/src/main.rs:301`.

---

## 4. The four iOS constraints that drive the design

### 4.1 Constraint 1 — no subprocesses (the big one)

App Store / sandboxed iOS apps cannot launch separate executables. `posix_spawn`
/ `NSTask` are unavailable, there is no `/usr/bin`, and you cannot write a binary
to a temp dir and exec it (W^X / code-signing / no JIT entitlement). **Every row
in the table in [§3.2](#32-the-media-pipeline--external-processes) must be
re-implemented against a linked library or Apple framework.** This is the
single largest body of work.

### 4.2 Constraint 2 — no daemon, and the app gets suspended

There is no persistent background process. The app is foregrounded, backgrounded,
and suspended at the OS's discretion. Consequences:

- Don't ship `main.rs` as-is. Embed the core as a **library**.
- Long scans must cooperate with the app lifecycle (`BGProcessingTaskRequest`
  for opportunistic background indexing; checkpoint so a killed scan resumes —
  `post_index.rs` is already idempotent/catch-up by design).
- The `notify`-based watcher does not fit (see Constraint 3).

### 4.3 Constraint 3 — sandboxed storage, no stable paths

"Videos already on the device" almost always means:

- **Photos library** — accessed as `PHAsset` via PhotoKit. A `PHAsset` has a
  stable **local identifier**, *not* a stable filesystem path. You read frames
  via `PHImageManager`/`AVAsset`, not by opening a path.
- **Files / iCloud Drive / external drives** — accessed via document pickers and
  **security-scoped bookmarks**, which must be resolved and
  `startAccessingSecurityScopedResource()`'d each session.

The core's `videos.path TEXT UNIQUE` identity and `walkdir` recursion don't map
to either. This needs an **ingest/identity adaptation** ([§6.9](#69-ingest--data-model-photos--files)).
`notify` recursive watching also doesn't apply — use PhotoKit change observers
(`PHPhotoLibraryChangeObserver`) and/or scan-on-foreground instead.

### 4.4 Constraint 4 — build, link, and codesign

- Build `staticlib`/`cdylib` for `aarch64-apple-ios` (device),
  `aarch64-apple-ios-sim` + `x86_64-apple-ios-sim` (simulator), packaged as an
  **XCFramework**.
- `build.rs` runs on the **host** during cross-compilation: `tonic-build`
  (needs `protoc`) and the sensor-table codegen are fine; the `swiftc`
  `rv-frameshot` step is correctly skipped for non-macOS targets.
- If you link FFmpeg (Option A below), you need iOS-built `libav*` static
  libraries (e.g. the `ffmpeg-kit`/`mobile-ffmpeg` iOS XCFrameworks, or a custom
  build), plus `-framework VideoToolbox/CoreMedia/AudioToolbox`.
- No JIT, no dynamically-loaded code. Everything statically linked and signed.

---

## 5. Module-by-module port classification

Legend: **Reuse** = compiles and runs on iOS unchanged · **Adapt** = small
platform arm or behavior change · **Replace** = needs a new implementation.

| Module | Verdict | Notes |
|---|---|---|
| `db.rs` | **Reuse** | rusqlite bundled SQLite builds for iOS. Path goes to the app container. |
| `search.rs` | **Reuse** | FTS5 is in bundled SQLite. |
| `grouping.rs` | **Reuse** | Pure logic. |
| `post_index.rs` | **Reuse** | dHash pipeline is pure Rust (`image` crate). |
| `imagehash.rs` | **Reuse** | Pure Rust JPEG/PNG decode + dHash. |
| `metadata_keys.rs`, `camera_names.rs`, `full_resolution.rs`, `path_templates.rs`, `grouping.rs` | **Reuse** | Pure logic / generated tables. |
| `concurrency.rs` | **Adapt** | Keep the semaphore; retune defaults for mobile thermals; it now throttles native decode/encode, not subprocesses. |
| `config.rs` | **Adapt** | Add an iOS arm to path resolution (`config.rs:225`). `dirs::cache_dir()` already returns the app-container Caches on iOS — verify. |
| `service.rs` | **Adapt** | Business logic reused. Watcher boot (`service.rs:104`) and any path assumptions need iOS-aware behavior. Methods call the new media backend via a trait, not `ffmpeg::*` directly. |
| `error.rs` | **Reuse** | — |
| `main.rs` | **Replace** | Not used on iOS. Replaced by a library entry point (`start_embedded()` / FFI). No `nix` rlimit, no service manager. |
| `ffmpeg.rs` | **Replace** | Becomes the CLI implementation of the media-backend trait (desktop only). iOS gets a native impl. |
| `metadata.rs` (extraction) | **Replace** (extraction path) | ffprobe JSON → native metadata. The *storage/merge* logic (`store_metadata`, XMP merge) is **Reuse** once it's fed equivalent fields. |
| `thumbnails.rs` | **Replace** | All frame extraction is ffmpeg/`qlmanage`/`rv-frameshot`. Reimplement against AVFoundation/`libav`. Sizing/caching/filename logic is reusable. |
| `proxies.rs` | **Replace** (encode path) | ffmpeg `libx264` → VideoToolbox/`libav`. Detection (`detect_proxies`, dHash) is reuse. |
| `xmp.rs` | **Replace** | `exiftool` → a Rust EXIF/XMP crate, or scope out for v1. |
| `sensor_cache.rs` | **Replace** (trivial) | `curl` → `reqwest`/`hyper` (already have tokio). |
| `watcher.rs` | **Replace** | `notify` → PhotoKit change observer / foreground rescan. |
| `indexing.rs` | **Adapt** | Orchestration reused; the "walk a path tree" enumeration is replaced by Photos/Files enumeration. |

---

## 6. Work breakdown

### 6.1 The media-backend trait (the core of the port)

Introduce a trait that captures everything the core currently does by shelling
out, and make `service.rs`/`thumbnails.rs`/`proxies.rs`/`metadata.rs` depend on
the trait instead of `crate::ffmpeg::*`. Provide two implementations selected at
build/runtime:

- `CliMediaBackend` — wraps the existing `ffmpeg`/`ffprobe` calls. Desktop keeps
  working with **zero behavior change**.
- `NativeMediaBackend` (iOS) — see [§6.2](#62-options-for-the-native-backend).

Sketch (adjust to match the real signatures in `metadata.rs`/`thumbnails.rs`):

```rust
/// Everything the core used to shell out to a CLI tool for.
/// All methods are blocking and run under `acquire_*_permit()`.
pub trait MediaBackend: Send + Sync {
    /// Replaces `ffprobe -print_format json`. Must populate the same fields
    /// that `metadata::FFProbeOutput` exposes downstream (duration, codecs,
    /// width/height, fps, bitrate, color/HDR, audio channels, creation date,
    /// camera/lens tags, GPS). Return a normalized struct, not raw JSON.
    fn probe(&self, video: &MediaSource) -> Result<ProbeResult>;

    /// Replaces `ffmpeg -ss <t> -frames:v 1`. Decode one frame at `time_secs`,
    /// longest side <= `max_px`, write JPEG to `out`.
    fn extract_frame(&self, video: &MediaSource, time_secs: f64,
                     max_px: i32, out: &Path) -> Result<()>;

    /// Replaces the proxy transcode. H.264/HEVC + AAC, faststart, target height.
    fn transcode_proxy(&self, video: &MediaSource, out: &Path,
                       target_height: i32, progress: &mut dyn FnMut(f64)) -> Result<()>;

    /// Replaces `ffmpeg -metadata` tag writes. May be a no-op on iOS for
    /// Photos-library originals (you cannot mutate them in place).
    fn write_creation_time(&self, video: &MediaSource, ts_ms: i64) -> Result<()>;
    fn write_location(&self, video: &MediaSource, lat: f64, lon: f64) -> Result<()>;
}

/// Identity becomes backend-specific: a filesystem path on desktop, a PHAsset
/// local-identifier or security-scoped bookmark on iOS.
pub enum MediaSource {
    Path(PathBuf),
    PhotoAsset(String),     // PHAsset.localIdentifier
    Bookmark(Vec<u8>),      // security-scoped bookmark data
}
```

Notes:

- Keep `extract_frame` as the single primitive. Today the still path
  (`thumbnails.rs:106`), the scrub-frame loop (`:297`), the on-demand width path
  (`:421`), and the ProRes RAW path (`:640`) are four different invocations of
  "give me a frame at time T at size S". One trait method covers all four; the
  caller logic (frame counts, sizes, caching, filenames) stays in `thumbnails.rs`.
- `ProbeResult` must be field-compatible with what `metadata::store_metadata`
  consumes so the **XMP merge logic** (`metadata.rs:110`+) keeps working
  unchanged. Don't reshape the downstream contract; just change its source.
- The semaphore in `concurrency.rs` still wraps each call.

### 6.2 Options for the native backend

**[DECISION NEEDED]** Pick one (or the hybrid). This is the most consequential
technical choice in the port.

**Option A — link FFmpeg (`libavformat`/`libavcodec`/`libavutil`/`libswscale`).**
Use an FFI crate (`rsmpeg` or `ffmpeg-next`) against iOS-built static `libav*`.

- ➕ True parity: exactly the formats/behaviors the desktop has, including odd
  codecs and container tags ffprobe reads.
- ➕ The metadata mapping is closest to the existing `FFProbeOutput` shape.
- ➖ Heavy: cross-compiling/bundling `libav*` for iOS, big binary, longer builds.
- ➖ **GPL/App Store distribution tension** (see [§8.4](#84-licensing-gpl-3-vs-the-app-store-decision-needed)).

**Option B — Apple frameworks (AVFoundation + VideoToolbox + CoreMedia).**
A thin Swift/Obj‑C shim, called from Rust over FFI, implements the trait.

- ➕ Native, hardware-accelerated, no third-party media libs, App-Store-clean,
  smaller binary. Proxy encode via VideoToolbox is the *right* iOS path.
- ➕ This is already the project's trusted path for ProRes RAW frames
  (`rv-frameshot` is AVFoundation). You're generalizing a pattern that exists.
- ➖ Codec coverage = what iOS AVFoundation decodes (H.264/HEVC/ProRes, common
  containers). Footage AVFoundation won't open is a feature gap vs. desktop.
- ➖ Metadata surface differs from ffprobe's container-tag view; you must map
  `AVAsset`/`AVMetadataItem` → `ProbeResult` and accept some fields are absent.
- ❓ **ProRes RAW decode on iOS** is not guaranteed across OS versions/hardware
  the way it is on macOS. The `rv-frameshot` precedent proves *macOS*; verify on
  target iOS versions before promising RAW parity. (Open question in [§11](#11-open-questions--decisions-needed).)

**Option C — Hybrid (recommended for "all functionality").** Native (Option B)
as the primary backend; fall back to a linked decoder (Option A, possibly a
trimmed `libav`) only for sources AVFoundation can't open. This is exactly how
`thumbnails.rs` is *already* structured (`frameshot_extract` → `quicklook_poster`
fallback chain) — generalize that fallback idea across the whole backend.

> Recommendation: **start with Option B** to get a working app fast and
> App-Store-clean, measure the real codec gap on the target footage, then add a
> linked-decoder fallback only if the gap matters. If "literally every format
> desktop supports" is a hard requirement on day one, you're committing to
> Option A/C and to resolving the licensing question up front.

### 6.3 Thumbnails

Reimplement the four extraction call sites in `thumbnails.rs` on top of
`MediaBackend::extract_frame`. Keep:

- the size constants and `generate_default_sizes` structure (`thumbnails.rs:37`),
- the 10-frame scrub generation (`SCRUB_FRAME_COUNT`, `:206`),
- on-demand width generation (`:382`),
- caching, filenames (`thumbnail_filename`, `:339`), and the per-video scrub
  locks in `service.rs`.

The QuickLook PNG→JPEG transcode (`:693`) disappears — the native backend
writes JPEG directly. `qlmanage` (`:675`) is macOS-only and goes away on iOS.

### 6.4 Proxy generation

`proxies.rs:717`–`766` transcodes to H.264/AAC with `-movflags +faststart` and
parses `frame=` progress from stdout. On iOS, implement `transcode_proxy` with
`AVAssetExportSession` or a VideoToolbox compression session, mapping progress to
the same 0–85% band the streaming RPC expects (`proxies.rs:766`+,
`GenerateProxy` stream in the proto). `needs_proxy` / `detect_proxies` (dHash)
are reused unchanged.

### 6.5 Tag writing (creation time / GPS)

`metadata.rs:296` (`write_location_tag`) and `:386` (`write_creation_time_tag`)
mutate the *original file* via `ffmpeg -metadata`. On iOS you generally
**cannot** rewrite a Photos-library original in place. Decide per-source:

- Photos originals: write through PhotoKit change requests where allowed, or
  treat these as no-ops and keep the edit only in the catalog DB.
- Files/bookmarked: possible via export to a new asset; in-place rewrite is
  usually not worth it.

**[DECISION NEEDED]**: is "write metadata back to the source file" in scope for
iOS v1, or catalog-only? Recommendation: catalog-only for v1.

### 6.6 XMP sidecars (`exiftool`)

`xmp.rs` shells out to `exiftool` for sidecar read/write and feeds the XMP merge
in `metadata.rs:110`+. Options: (a) a pure-Rust EXIF/XMP reader
(`kamadak-exif` + an XMP/RDF parse) for the read path, which is what the merge
actually needs; (b) scope XMP *out* of iOS v1 (the merge already degrades
gracefully when the sidecar half is absent). Recommendation: **(b) for v1**,
revisit if users keep sidecars alongside Files-app footage.

### 6.7 Sensor-spec fetch (`curl`)

`sensor_cache.rs:245` calls `curl`. Replace with `reqwest` (or `hyper`, since
tokio is already present). Trivial, and it removes a subprocess. Do this even on
desktop — it's a strict improvement.

### 6.8 Watching / live updates

`watcher.rs` (`notify::RecommendedWatcher`, `:202`) does not map to iOS. Replace
with:

- `PHPhotoLibraryChangeObserver` for the Photos library → publish into the
  existing `CatalogChange` broadcast (`service.rs` `catalog_events`) so
  `SubscribeCatalogEvents` keeps working.
- Foreground rescan / `BGProcessingTask` opportunistic scans for Files-based
  libraries.

The broadcast bus and the `SubscribeCatalogEvents` RPC are reused; only the
*event source* changes.

### 6.9 Ingest & data model (Photos / Files)

This is the schema-touching part.

- Add a notion of **source kind + stable identity** alongside `videos.path`:
  a `PHAsset.localIdentifier` (Photos) or resolved security-scoped bookmark
  (Files). Keep `path` for desktop; add columns or a typed identity so iOS rows
  are addressable without a real filesystem path.
- The enumerator that today is `walkdir` over `library_locations`
  (`indexing.rs`) becomes a PhotoKit fetch (`PHAsset` of media type video) and/or
  a bookmarked-folder enumeration. The rest of the indexing orchestration
  (`indexing.rs`, `post_index.rs`) is reused.
- Frame/decode access goes through `MediaSource` ([§6.1](#61-the-media-backend-trait-the-core-of-the-port)).

**[DECISION NEEDED]**: schema migration approach. Adding a `source_kind` +
`source_id` is backward-compatible (desktop rows are `kind=path`). Confirm with
`rusqlite_migration` usage in `db.rs`.

### 6.10 Config / paths

Add an iOS arm to `config.rs:225` and the data-dir logic in `main.rs:301` (the
latter only if you keep any of `main.rs`). On iOS everything lives under the app
container — `dirs` returns container paths, but verify rather than assume.

### 6.11 Concurrency tuning

Keep `BlockingSemaphore`, but `default_max_concurrent_ffmpeg()` =
`available_parallelism()` (`concurrency.rs`) is too aggressive for a phone under
thermal pressure. Lower the default and/or react to `ProcessInfo`
thermal state. The semaphore now bounds native decode/encode sessions.

---

## 7. How the app embeds the core

### 7.1 Recommended: keep gRPC, run the server in-process

Run `ReelVaultService::into_server()` on an **in-process** listener inside the
app — a `127.0.0.1` TCP port or a Unix-domain socket in the app container — and
have the SwiftUI client connect to it exactly as it connects to a remote daemon.

Why this is the strong default:

- **`service.rs` and the entire proto/RPC surface are reused unchanged.** No new
  API layer.
- **The SwiftUI client is written once.** Remote vs. on-device is just a
  different host/endpoint. Use case 1 and use case 2 share the client.
- The existing macOS client's generated gRPC stubs and `grpc-swift` stack carry
  over (note the project's pinned `grpc-swift-protobuf` plugin requirement when
  regenerating Swift stubs).

Costs: you run a tokio runtime and a loopback server inside the app. That's
cheap and well-trodden. tokio + tonic over loopback/UDS work on iOS.

### 7.2 Alternative: direct FFI / UniFFI

Expose `ReelVaultService` methods to Swift via **UniFFI** (or a hand-written C
ABI), bypassing gRPC entirely for the on-device case.

- ➕ No in-process socket; slightly lower overhead; "feels" native.
- ➖ You define and maintain a *second* API surface parallel to the proto, and
  the client diverges between remote (gRPC) and local (FFI). More code, more
  drift, more chance of parity bugs.

Recommendation: **§7.1 for v1.** Consider UniFFI later only if loopback overhead
or streaming ergonomics prove to be a real problem. Either way you need a small
FFI entry point (`start_embedded(db_path, …) -> handle`) to boot the runtime and
server from Swift; the tokio runtime must be owned by the library and survive
across the app lifecycle, parked when suspended.

### 7.3 Lifecycle

- Boot the runtime + server lazily on first foreground; keep the SQLite catalog
  in the app container (WAL mode is fine on a single-process embed).
- Cooperate with suspension: pause scans, flush WAL, stop the watcher's
  PhotoKit observer registration as appropriate.
- Use `BGProcessingTaskRequest` for opportunistic indexing/thumbnailing of large
  imports; rely on `post_index.rs` idempotent catch-up if killed mid-scan.

---

## 8. Build & packaging

### 8.1 Targets and artifact

```bash
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios-sim
# Build a static lib per target, then assemble an XCFramework
# (cargo-xcframework or a manual xcodebuild -create-xcframework step).
```

Add an iOS-friendly crate type for the embed (`staticlib`/`cdylib`) alongside the
existing `lib`/`bin` targets in `core/Cargo.toml`. Keep the two `[[bin]]`s
desktop-only.

### 8.2 build.rs on cross-compile

`tonic-build::compile_protos` and `emit_sensor_table` run on the host and are
fine. `emit_frameshot` already guards on `CARGO_CFG_TARGET_OS == "macos"`
(`build.rs:31`), so an iOS build emits `FRAMESHOT_BIN = None` and compiles. No
change required there, but confirm `protoc` is available in the iOS CI build
environment.

### 8.3 Dependency audit for iOS

- `nix` is `#[cfg(unix)]` only and used in `main.rs` (rlimit) — not compiled into
  the library path you embed. Confirm nothing in the lib path pulls a
  `nix`/`winapi` feature that fails to build for iOS.
- `which` (`Cargo.toml`) is only meaningful for CLI resolution; the native
  backend won't use it.
- `image`, `rayon`, `rusqlite` (bundled), `tokio`, `tonic`, `prost`, `chrono`,
  `regex`, `walkdir`, `uuid`, `serde*` — all known-good on iOS.
- If Option A/C: add the `libav` FFI crate behind a feature flag and link the iOS
  `libav*` + `-framework VideoToolbox -framework CoreMedia -framework AudioToolbox`.

### 8.4 Licensing: GPL‑3 vs. the App Store **[DECISION NEEDED]**

`core/Cargo.toml` declares `license = "GPL-3.0-or-later"`. Distributing a GPL
binary through the App Store has a well-known, unresolved conflict with Apple's
Terms of Service (the usage/DRM restrictions vs. GPL §6/anti-Tivoization; the
canonical precedent is VLC's removal). Linking a **GPL** FFmpeg build (Option
A/C) makes the combined work GPL too. This is **not** a coding problem and can
block release. Resolve one of:

1. Distribute outside the App Store (enterprise/ad-hoc/AltStore) — sidesteps the
   ToS conflict but limits reach.
2. Have the copyright holders grant an explicit App Store distribution exception
   (only they can).
3. Relicense the parts that ship in the iOS binary, and use **Apple frameworks
   only** (Option B) so no GPL media lib is linked.

Option B + a licensing exception (or a permissive relicense of the shipped code)
is the cleanest App-Store path. Flag this to the owner early — it may *decide*
the Option A/B/C choice for you.

---

## 9. Use case 1 (remote mode) ships independently

This needs **no media-pipeline work** and almost no core change. Build the
SwiftUI client against the existing proto and point it at the daemon. Core-side
hardening to expose the daemon safely on a LAN:

- Bind beyond loopback: the `--host` flag exists (`main.rs:45`); allow `0.0.0.0`.
- **Add authentication + TLS.** Today there is none — `main.rs:44` literally
  warns "there's no auth!". Do not expose an unauthenticated catalog on a
  network. Add a token (tonic interceptor) and TLS (rustls) at minimum.
- Add **mDNS/Bonjour** advertisement so the phone discovers the Mac.
- Mind thumbnail/video streaming bandwidth over Wi‑Fi (the `GetThumbnail` stream
  already chunks; large originals should stream/range-request).

Ship this first. It also gives you the SwiftUI gRPC client you'll reuse for the
embedded case ([§7.1](#71-recommended-keep-grpc-run-the-server-in-process)).

---

## 10. Phased plan

| Phase | Deliverable | Acceptance |
|---|---|---|
| **0. Remote client** | SwiftUI iOS client vs. remote daemon; daemon auth+TLS+mDNS | Phone browses a desktop catalog over Wi‑Fi with auth. |
| **1. Build green** | Core library cross-compiles to iOS XCFramework; embed boots an in-process gRPC server; SwiftUI talks to it | App opens an (empty) on-device catalog via the same client as Phase 0. |
| **2. Media abstraction** | `MediaBackend` trait; `CliMediaBackend` extracted; desktop unchanged & green | All desktop tests pass against the trait; no behavior change. |
| **3. Native backend (Option B)** | AVFoundation/VideoToolbox impl: probe, frame extract, proxy encode | On-device: metadata + still + scrub thumbnails + proxy for H.264/HEVC/ProRes assets. |
| **4. Ingest/data model** | PhotoKit + Files ingest; `source_kind`/`source_id`; PhotoKit change observer | Import the Photos video library; grid populates; live updates work. |
| **5. Parity polish** | XMP decision, tag-write decision, thermal tuning, background tasks, sensor-cache `reqwest` | Feature matrix vs. desktop documented; gaps are deliberate. |
| **6. (If required) full-codec parity** | Linked-decoder fallback (Option A/C) behind a feature flag | Formats AVFoundation can't open still index/thumbnail. Requires §8.4 resolved. |

A realistic estimate: Phase 0 is days; Phases 1–5 are the bulk (multiple weeks)
with Phase 3 + Phase 4 the riskiest; Phase 6 is a separate, licensing-gated
project.

---

## 11. Open questions / decisions needed

1. **[§6.2 / §8.4]** Native backend strategy: Apple-frameworks-only (B), linked
   FFmpeg (A), or hybrid (C)? This is gated by the licensing decision.
2. **[§8.4]** App Store distribution given GPL‑3 — relicense, exception, or
   distribute off-store?
3. **[§6.2]** ProRes RAW decode on the target iOS versions/devices — verify
   before promising parity (macOS `rv-frameshot` does not prove iOS).
4. **[§6.5]** Write metadata back to source files on iOS, or catalog-only?
5. **[§6.6]** XMP sidecars in iOS v1, or deferred?
6. **[§6.9]** Schema: add `source_kind`/`source_id`; confirm migration path in
   `db.rs`.
7. **[§7]** Embedding transport: in-process gRPC (recommended) vs. UniFFI.
8. Scope of "videos already on the device": Photos library only, or also
   Files/external drives, for v1?

---

## 12. Appendix — subprocess call sites to replace (verify before editing)

| File:line | What it runs | Replace with |
|---|---|---|
| `core/src/ffmpeg.rs:79`,`:84` | resolves & builds `ffmpeg`/`ffprobe` `Command` | `MediaBackend` trait; CLI impl keeps this for desktop |
| `core/src/metadata.rs:48` | `ffprobe -print_format json` | `MediaBackend::probe` |
| `core/src/metadata.rs:279` | ffprobe availability probe | backend capability check |
| `core/src/metadata.rs:335`,`:418`,`:457` | `ffmpeg -metadata` tag writes | `write_location` / `write_creation_time` (maybe no-op on iOS) |
| `core/src/thumbnails.rs:106`,`:142`,`:166` | still frame + resize | `MediaBackend::extract_frame` |
| `core/src/thumbnails.rs:297` | scrub frames (×10) | `extract_frame` loop |
| `core/src/thumbnails.rs:421` | on-demand frame at width | `extract_frame` |
| `core/src/thumbnails.rs:640` | `rv-frameshot` (staged Mach-O exec) | native AVFoundation frame (no exec) |
| `core/src/thumbnails.rs:675` | `qlmanage -t` | drop on iOS |
| `core/src/thumbnails.rs:693` | `ffmpeg` PNG→JPEG | native backend writes JPEG directly |
| `core/src/proxies.rs:766` | `ffmpeg … libx264 … aac` | `transcode_proxy` (VideoToolbox/`libav`) |
| `core/src/xmp.rs:645`,`:681` | `exiftool` | Rust EXIF/XMP crate or defer |
| `core/src/sensor_cache.rs:245` | `curl` | `reqwest`/`hyper` |
| `core/build.rs:19` (`emit_frameshot`) | `swiftc` compile of helper | already no-op for non-macOS targets |

Also revisit, though not subprocesses:
`core/src/watcher.rs:202` (`notify` → PhotoKit observer),
`core/src/main.rs:156`–`184` (TCP daemon → in-process embed),
`core/src/main.rs:201` (`nix` rlimit → drop),
`core/src/config.rs:225` (add iOS path arm),
`core/src/indexing.rs` (`walkdir` enumeration → Photos/Files enumeration).
