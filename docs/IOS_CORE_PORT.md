# Running the ReelVault Core Directly on iOS

> **Audience:** the engineer/agent who will implement on-device iOS support for
> the Rust core (`core/`), reaching **full functional parity** with the desktop
> daemon. This is the design **and** the executable work plan.
>
> **Status:** implementation-ready plan. Nothing here is built yet, but every
> code reference below was **verified against the tree at commit `3c8bf2f`
> (2026-06-14)**. Line numbers drift — re-grep the symbol name before editing,
> but the symbols and signatures are current as of that commit.
>
> **Strategic context (decided by the project owner, 2026-06-14):** there is *no
> rush to ship on the App Store*. The goal is the **long-term answer: full
> desktop parity on-device**, not the minimal App-Review slice. That single
> decision unlocks the licensing path (see [§0](#0-decisions-locked) and
> [§8.4](#84-licensing--distribution)) and makes the linked-decoder hybrid the
> real target rather than a "maybe later" phase. The lighter, pure-Swift
> `LocalVideoRepository` alternative is described in
> [`IOS_REVIEW_LOCAL_MODE.md`](IOS_REVIEW_LOCAL_MODE.md) and is **not** the path
> being taken.

---

## 0. Decisions locked

These were open `[DECISION NEEDED]` items in the prior draft. Given the "full
parity, no App-Store deadline" steer, they are now resolved. They can still be
revisited, but build against them.

| # | Decision | Resolution | Rationale |
|---|---|---|---|
| D1 | Native backend strategy (A: linked FFmpeg / B: Apple frameworks / C: hybrid) | **C — hybrid. Build B first as the foundation, then add the A fallback.** | B gets a working, fast, hardware-accelerated app quickly; the linked-`libav` fallback then closes the codec gap for true parity. |
| D2 | App Store vs. GPL-3 conflict | **Distribute off-store (dev/ad-hoc/TestFlight/enterprise). GPL is fine off-store; the conflict is specifically Apple's App Store ToS, not GPL itself.** | No deadline → no need to neuter the build to be "App-Store-clean." Linking GPL `libav*` is legal when not distributed through the App Store. Revisit only if App Store distribution ever becomes a goal. |
| D3 | Embedding transport (in-process gRPC vs. UniFFI) | **In-process gRPC.** | Reuses `service.rs`, the whole proto surface, and the existing `VideoRepository` client unchanged. See [§7](#7-how-the-app-embeds-the-core). |
| D4 | Write metadata back to source files on iOS | **Catalog-only for v1.** | You generally cannot rewrite a Photos-library original in place; the write-tag RPCs become no-ops for Photos sources. See [§6.5](#65-tag-writing-creation-time--gps). |
| D5 | XMP sidecars in v1 | **Read path: keep (already native). Write path: defer.** | `xmp::read_xmp` is *already* pure-Rust regex (`xmp.rs:233-284`) — iOS-safe today. Only the `exiftool` **write** path (`xmp.rs:681`) is a subprocess, and it's out of scope for v1. |
| D6 | Schema migration for source identity | **Additive `source_kind` + `source_id` columns on `videos`, via the existing inline migration array.** | `db.rs` migrations are an inline `(label, sql)` array that ignores duplicate-column errors — adding two `ALTER TABLE ADD COLUMN`s is clean and backward-compatible. See [§6.9](#69-ingest--data-model-photos--files). |
| D7 | Scope of "videos already on device" for v1 | **Photos library first, Files/bookmarks second** (both behind the same `MediaSource` enum). | Photos is the dominant case and has the cleanest API; Files adds security-scoped-bookmark plumbing that can land in a follow-up. |

Still genuinely open (need empirical answers, not opinions): **ProRes RAW decode
on target iOS devices** (see [§6.2](#62-options-for-the-native-backend) and
[§11](#11-open-questions-that-still-need-answers)).

---

## 1. Goal and the two use cases

ReelVault wants an iOS client that supports:

1. **Remote mode** — the phone is on the same Wi‑Fi as a machine running the
   Rust daemon and browses that catalog over the network. **This already exists**
   (`ios/`, `kit/`): mDNS discovery, pinned TLS, pairing. It is the
   feature/ios-client work and needs no core port.
2. **On-device mode** — the phone catalogs and views videos that already live on
   the device (Photos library / Files), with the core running **inside the app**.

This document is about making use case 2 work *with all existing functionality*
(metadata extraction, still + scrub thumbnails, ProRes RAW frames, proxy
generation, grouping, proxy detection, tags/collections, search, watching).

---

## 2. TL;DR verdict

- **The language/runtime port is not the hard part.** Rust cross-compiles to
  `aarch64-apple-ios` cleanly. `rusqlite` (bundled SQLite), `tokio`, `tonic`,
  `prost`, `image`, `rayon`, `serde`, `chrono`, `regex`, `walkdir`, `uuid` all
  build for iOS. Roughly **half the crate is reused as-is**: `db.rs`, `search.rs`,
  `grouping.rs`, `post_index.rs`, `metadata_keys.rs`, `camera_names.rs`,
  `full_resolution.rs`, `path_templates.rs`, `imagehash.rs`, and most of the
  *business logic* in `service.rs`.
- **The hard part is that the entire media pipeline shells out to external
  command-line binaries, and iOS forbids that.** A sandboxed iOS app cannot
  `fork`/`exec` a separate executable (`ffmpeg`, `ffprobe`, `exiftool`, `curl`,
  `qlmanage`), and cannot stage-and-exec a compiled helper (`rv-frameshot`).
  Every one of those call sites must be replaced with a **linked library or an
  Apple framework**. See [§3.2](#32-the-media-pipeline--external-processes) and
  [§4.1](#41-constraint-1-no-subprocesses-the-big-one).
- **The deployment model also changes.** There is no long-lived background
  daemon on iOS, and the catalog's "filesystem path + recursive watcher" data
  model doesn't match the Photos/Files sandbox. These are adaptations, not
  rewrites.
- **Key architectural lever:** keep the gRPC seam and run the server
  **in-process** inside the app. Then *both* use cases collapse to "connect to a
  gRPC endpoint" — remote vs. local is just a different host — and the existing
  `VideoRepository` client and `service.rs`/`into_server()` are reused unchanged.
  The macOS client already does almost exactly this, except it *spawns a separate
  process* (`macos/.../ServerLauncher.swift`); iOS does the same thing
  **in-process** because it can't spawn. See [§7](#7-how-the-app-embeds-the-core).
- **Distribution:** the crate is **GPL‑3.0‑or‑later** (`core/Cargo.toml:5`).
  Per [D2](#0-decisions-locked) we distribute off the App Store, where GPL is not
  a problem. See [§8.4](#84-licensing--distribution).

**Bottom line:** the core does **not** need a ground-up rewrite. It needs (a) a
media-I/O abstraction with a native iOS implementation, (b) an
ingest/data-model adaptation for the Photos/Files sandbox, and (c) a change from
"spawn a daemon" to "embed a library and run the server in-process." Estimate is
in [§10](#10-phased-plan).

---

## 3. How the core works today (verified)

### 3.1 Process shape and the embed seam already exists

`core` is **already structured as a library plus two binaries** — this is the
single most important fact for the embed:

```toml
# core/Cargo.toml
[lib]                              # line 10
name = "reelvault_core"
path = "src/lib.rs"

[[bin]]                            # line 119
name = "reelvault-core"           # the daemon (src/main.rs)
[[bin]]                            # line 123
name = "reelvault-cli"            # src/bin/cli.rs
```

`core/src/lib.rs` exports `pub mod service` (and the rest). **Anything that can
construct `ReelVaultService` and call `into_server()` gets the full product** —
the daemon binary is just one such caller.

`core/src/main.rs` is the daemon: it parses args (`--port`, default `50051`,
`main.rs:52`; `--host`, default `127.0.0.1`, `main.rs:57`, with the literal
comment "note: there's no auth!" at `main.rs:56`), builds a multi-threaded tokio
runtime, opens a SQLite catalog, raises the fd `rlimit` via `nix`
(`raise_fd_limit()`, `#[cfg(unix)]`, `main.rs:459`, called at `main.rs:180`),
optionally runs as a launchd/systemd/Windows service, chooses per-OS data dirs
(`get_data_dir(system_daemon)`, `main.rs:545-571`), constructs the service
(`main.rs:267`):

```rust
let service = ReelVaultService::new(db, config, pairing, data_dir);
```

…and binds a **TCP** gRPC server (`TcpListener::bind`, `main.rs:277`).

The API lives in `core/src/service.rs`:

```rust
#[derive(Clone)]
pub struct ReelVaultService {            // service.rs:34
    db: Arc<Database>,                   // :35
    config: Arc<Config>,                 // :36
    // opened_at, scrub_locks (:44), …
    catalog_events: broadcast::Sender<CatalogChange>,   // :51  (capacity 256, set at :92)
    watcher: Arc<Mutex<Option<LibraryWatcher>>>,        // :55
    watch_settings: …,                                  // :60
    pairing: crate::pairing::PairingState,              // :63
    data_dir: PathBuf,                                  // :65
}

pub fn new(db: Arc<Database>, config: Arc<Config>,
           pairing: PairingState, data_dir: PathBuf) -> Self  // service.rs:79
pub fn into_server(self) -> ReelVaultServer<Self>              // service.rs:145
```

> **Drift correction vs. prior draft:** `new()` now takes **four** args
> (`db, config, pairing, data_dir`), not two. The watcher is **not** passed in —
> it is booted *inside* `new()` as a `tokio::spawn(restart_watcher())`
> (`service.rs:114-119`) when `config.watch_enabled` and a catalog is open.

The trait impl (`#[tonic::async_trait] impl ReelVaultTrait for ReelVaultService`,
`service.rs:1083`) implements **59 RPCs** (mirror of
`core/proto/reelvault.proto`). Four are streaming, via these associated types
(`service.rs:1084-1088`):

```rust
type ScanLibraryStream            = …Stream<Item=Result<ScanProgress,Status>>;
type GenerateProxyStream          = …Stream<Item=Result<ProxyGenerationProgress,Status>>;
type GetThumbnailStream           = …Stream<Item=Result<ThumbnailChunk,Status>>;
type SubscribeCatalogEventsStream = …Stream<Item=Result<CatalogEvent,Status>>;
```

> **Implication:** anything that can call `ReelVaultService` methods (in-process
> or over gRPC) gets the full product. The transport is incidental.

There is also a **pairing module** (`crate::pairing::PairingState`) and a
**separate media server on its own port** (the client models `mediaPort` in
`DiscoveredServer`). Neither is needed for the on-device case — originals are
local, so there's nothing to stream over a media port — but the embed must
construct a `PairingState` to satisfy `new()` (a throwaway/loopback instance is
fine). See [§7.4](#74-what-to-drop-for-the-embed).

### 3.2 The media pipeline = external processes (the crux)

Every media operation runs a CLI tool. Binary resolution is centralized in
`core/src/ffmpeg.rs` — `pub fn ffmpeg_command() -> Command` (`ffmpeg.rs:78`) and
`pub fn ffprobe_command() -> Command` (`ffmpeg.rs:83`) — and **every ffmpeg/
ffprobe call site goes through them**. That centralization is the natural seam to
abstract ([§6.1](#61-the-media-backend-trait-the-core-of-the-port)).

| Operation | Tool | Call site | Trait method |
|---|---|---|---|
| Metadata extraction | `ffprobe … -of json` | `metadata.rs:48` | `probe` |
| ffprobe availability probe | `ffprobe` | `metadata.rs:279` | backend capability flag |
| **Audio loudness** *(missed in prior draft)* | `ffmpeg` | `metadata.rs:1068` | `extract_loudness` |
| Still thumbnail (frame @ 50%) | `ffmpeg -ss …` | `thumbnails.rs:106`, resize `:142` | `extract_frame` |
| Scrub thumbnails (×10, 5%→95%) | `ffmpeg` | `thumbnails.rs:297` | `extract_frame` loop |
| On-demand frame at width | `ffmpeg` | `thumbnails.rs:421` | `extract_frame` |
| **Color-info probe** *(missed in prior draft)* | `ffprobe` | `thumbnails.rs:480` | `probe_color` |
| ProRes RAW frame (timestamped) | `rv-frameshot` (AVFoundation) | `thumbnails.rs:640` | `extract_frame` (native) |
| Poster fallback | `qlmanage -t` | `thumbnails.rs:675` (macOS only) | drop on iOS |
| QuickLook PNG → JPEG | `ffmpeg` | `thumbnails.rs:693` | native writes JPEG directly |
| Proxy transcode (H.264/AAC) | `ffmpeg … libx264` | `proxies.rs:734` | `transcode_proxy` |
| Write GPS/location tag | `ffmpeg -metadata` | `metadata.rs:335` | `write_location` (no-op on iOS, [D4](#0-decisions-locked)) |
| Write creation-time tag | `ffmpeg -metadata` | `metadata.rs:418` | `write_creation_time` (no-op on iOS) |
| Camera sensor-spec fetch | `curl` | `sensor_cache.rs:245` (`fetch_via_curl`, `:243`) | replace with `reqwest` (desktop too) |
| XMP sidecar **write** | `exiftool` | `xmp.rs:681` | defer ([D5](#0-decisions-locked)) |

> **Correction — XMP read is already native.** `xmp::read_xmp` (the path the
> metadata merge actually uses, `metadata.rs:115`) is a **pure-Rust regex
> parser** (`xmp.rs:233-284`). The only `exiftool` subprocesses are the sidecar
> **write** (`xmp.rs:681`) and a test (`xmp.rs:645`). So the read/merge half is
> iOS-safe today; only the write half is out of scope.

`rv-frameshot` is special: `core/build.rs` (`emit_frameshot`, `build.rs:21`,
guarded by `CARGO_CFG_TARGET_OS == "macos"` at `build.rs:28`) compiles
`core/macos/rv-frameshot.swift` with `swiftc` **only on macOS**, embeds the
Mach-O bytes as `FRAMESHOT_BIN`, and `thumbnails.rs:630-640` (`frameshot_extract`)
stages it to `/tmp/reelvault-rv-frameshot` (`frameshot_path()`, `:601`), `chmod
0755`, and execs it. **None of this works on iOS** (no stage-and-exec), but the
build guard already emits `FRAMESHOT_BIN = None` for non-macOS targets, so an iOS
build compiles — it just has no frame extractor until we add one. Note: the
*logic* of `rv-frameshot.swift` (AVAssetImageGenerator at a timestamp) is exactly
what the iOS native backend re-implements **in-process** instead of via exec.

Concurrency of these external processes is bounded by a blocking semaphore
(`core/src/concurrency.rs`): `acquire_ffmpeg_permit()` (`:83`), default count
from `default_max_concurrent_ffmpeg()` (`:67`, = `available_parallelism()` or 4),
settable via `set_ffmpeg_concurrency_limit(n)` (`:77`). On iOS this same
semaphore throttles native decode/encode sessions instead of subprocesses.

### 3.3 Data model and watching (verified)

- Identity is a **filesystem path**. The real `videos` schema
  (`core/schema.sql:15-28`) is:
  ```sql
  CREATE TABLE IF NOT EXISTS videos (
    id TEXT PRIMARY KEY,          -- UUID string, not the INTEGER in CLAUDE.md's sketch
    path TEXT UNIQUE NOT NULL,
    filename TEXT NOT NULL,
    volume_id TEXT, hash TEXT UNIQUE, file_size_bytes INTEGER,
    indexed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP, modified_at TIMESTAMP,
    is_online INTEGER DEFAULT 1, group_id TEXT, group_position INTEGER NOT NULL DEFAULT 0
  );
  ```
- **Migrations are an inline array, not `rusqlite_migration`.** `db.rs`
  `initialize_conn` (`:310`) runs a `(label, sql)` array (`db.rs:322-438`) with
  `conn.execute` (`:440`); duplicate-column errors are ignored (`:444-446`), so
  re-running is idempotent. Adding columns is a one-line append (see
  [§6.9](#69-ingest--data-model-photos--files)).
- Scanning: `indexing::scan_directory(db, config, on_progress)`
  (`indexing.rs:154`) walks `library_locations` recursively with `walkdir`
  (`:181`), extracts metadata in parallel with rayon (`par_iter`, `:234`), and
  feeds each video to a `post_index` worker pool (`post_index::spawn`, `:207`).
  Single-file path: `scan_single_file` (`indexing.rs:445`).
- The watcher (`watcher.rs`): `notify::recommended_watcher` (`:202`) with a poll
  fallback (`poll_paths`, `:540`) for NFS/SMB; `sweep_pending` (`:396`) settles
  changes and publishes the `CatalogChange` enum (`watcher.rs:61`) into the
  service broadcast.
- `post_index.rs` folds auto-grouping + proxy-detection + sensor-fetch +
  timelapse-tagging into the scan (`process_one`, `:653`; worker pool
  `spawn_with_workers`, `:568`, `DEFAULT_NUM_WORKERS = 3`). It is idempotent
  (filters on `group_id IS NULL`, `proxy_of IS NULL`, etc.), so a killed scan
  catches up on the next run. The CPU-heavy part is JPEG decode + image
  similarity in `imagehash.rs` — **pure Rust** (`image::open` + `resize_exact` +
  `to_luma8` + mean-absolute-difference; note: **MAD, not dHash** — the prior
  draft said dHash). **iOS-safe.**
- Caches/config paths: `config::default_cache_path()` (`config.rs:243-258`) is a
  `cfg!(target_os)` `macos`/`windows`/`else` split using the `dirs` crate. Needs
  an iOS arm ([§6.10](#610-config--paths)).

---

## 4. The four iOS constraints that drive the design

### 4.1 Constraint 1 — no subprocesses (the big one)

App Store / sandboxed iOS apps cannot launch separate executables. `posix_spawn`
/ `NSTask` are unavailable, there is no `/usr/bin`, and you cannot write a binary
to a temp dir and exec it (W^X / code-signing / no JIT entitlement). **Every row
in the table in [§3.2](#32-the-media-pipeline--external-processes) must be
re-implemented against a linked library or Apple framework.** This is the single
largest body of work.

### 4.2 Constraint 2 — no daemon, and the app gets suspended

There is no persistent background process. The app is foregrounded, backgrounded,
and suspended at the OS's discretion. Consequences:

- Don't ship `main.rs` as-is. Embed the core as a **library** via a small FFI
  entry point ([§7.2](#72-the-ffi-entry-point)).
- Long scans must cooperate with the app lifecycle (`BGProcessingTaskRequest` for
  opportunistic indexing; checkpoint so a killed scan resumes — `post_index.rs`
  is already idempotent/catch-up).
- The `notify`-based watcher does not fit (see Constraint 3).

### 4.3 Constraint 3 — sandboxed storage, no stable paths

"Videos already on the device" almost always means:

- **Photos library** — `PHAsset` via PhotoKit. A `PHAsset` has a stable **local
  identifier**, *not* a stable filesystem path. Read frames via
  `PHImageManager`/`AVAsset`, not by opening a path.
- **Files / iCloud Drive / external drives** — document pickers and
  **security-scoped bookmarks**, resolved and
  `startAccessingSecurityScopedResource()`'d each session.

The core's `videos.path TEXT UNIQUE` identity and `walkdir` recursion don't map
to either. This needs the **ingest/identity adaptation** in
[§6.9](#69-ingest--data-model-photos--files). `notify` recursive watching also
doesn't apply — use `PHPhotoLibraryChangeObserver` and/or scan-on-foreground.

### 4.4 Constraint 4 — build, link, and codesign

- Build `staticlib` for `aarch64-apple-ios` (device), `aarch64-apple-ios-sim` +
  `x86_64-apple-ios-sim` (simulator), packaged as an **XCFramework**.
- `build.rs` runs on the **host** during cross-compilation:
  `tonic_build::compile_protos` (`build.rs:7`, needs `protoc`) and the sensor
  codegen (`emit_sensor_table`, `build.rs:76`) are fine; the `swiftc`
  `emit_frameshot` step (`build.rs:21`) is correctly skipped for non-macOS.
- For the Option-A fallback you need iOS-built `libav*` static libraries (e.g.
  `ffmpeg-kit` iOS XCFrameworks or a custom build), plus
  `-framework VideoToolbox/CoreMedia/AudioToolbox`.
- No JIT, no dynamically-loaded code. Everything statically linked and signed.

---

## 5. Module-by-module port classification (verified)

Legend: **Reuse** = compiles and runs on iOS unchanged · **Adapt** = small
platform arm or behavior change · **Replace** = needs a new implementation.

| Module | Verdict | Notes |
|---|---|---|
| `db.rs` | **Reuse** | rusqlite bundled SQLite builds for iOS. Catalog path → app container. Inline migration array makes [§6.9](#69-ingest--data-model-photos--files) trivial. |
| `search.rs` | **Reuse** | FTS5 is in bundled SQLite. |
| `grouping.rs` | **Reuse** | Pure string logic, no IO. |
| `post_index.rs` | **Reuse** | Worker pool + similarity; pure Rust. Retune `DEFAULT_NUM_WORKERS`. |
| `imagehash.rs` | **Reuse** | Pure-Rust decode + MAD similarity (`image` crate). |
| `metadata_keys.rs`, `camera_names.rs`, `full_resolution.rs`, `path_templates.rs` | **Reuse** | Pure logic / generated tables. |
| `concurrency.rs` | **Adapt** | Keep the semaphore; lower the default for mobile thermals; it now throttles native decode/encode, not subprocesses. |
| `config.rs` | **Adapt** | Add an iOS arm at `config.rs:243` (`default_cache_path`). Verify `dirs` returns the app-container Caches. |
| `service.rs` | **Adapt** | Business logic reused. Watcher boot (`:114-119`) and path assumptions need iOS-aware behavior. Media methods call the new backend trait, not `ffmpeg::*`. Construct a loopback `PairingState`. |
| `xmp.rs` | **Adapt** | Read path (`read_xmp`, `:233-284`) is already native → **Reuse**. Write path (`exiftool`, `:681`) → defer ([D5](#0-decisions-locked)). |
| `error.rs` | **Reuse** | — |
| `lib.rs` | **Adapt** | Add the FFI embed entry point ([§7.2](#72-the-ffi-entry-point)) behind `#[cfg(target_os="ios")]` (or a feature). |
| `main.rs` | **Skip on iOS** | Not compiled into the lib. No `nix` rlimit, no service manager, no TCP daemon. |
| `ffmpeg.rs` | **Replace** | Becomes the `CliMediaBackend` impl (desktop only). |
| `metadata.rs` (extraction) | **Replace** (extraction + loudness) | `ffprobe` JSON + loudness → native. `store_metadata` (`:75`) and the XMP merge (`:115`) are **Reuse** once fed an equivalent `FFProbeOutput`. |
| `thumbnails.rs` | **Replace** (extraction) | The 4 frame call sites + color probe + PNG→JPEG → native. Sizing/caching/filename logic (`thumbnail_filename`, `:339`; consts `:14-21`) is reuse. |
| `proxies.rs` | **Replace** (encode path) | `create_proxy` (`:704`, ffmpeg `:734`) → VideoToolbox/`libav`. `detect_proxies` (`:98`, MAD) is reuse. |
| `sensor_cache.rs` | **Replace** (trivial) | `fetch_via_curl` (`:243`) → `reqwest`. Do this on desktop too. |
| `watcher.rs` | **Replace** | `notify` → `PHPhotoLibraryChangeObserver` / foreground rescan; reuse the `CatalogChange` broadcast. |
| `indexing.rs` | **Adapt** | Orchestration reused; the `walkdir` enumeration (`:181`) is replaced by Photos/Files enumeration feeding the same `post_index` pool. |

---

## 6. Work breakdown

### 6.1 The media-backend trait (the core of the port)

Introduce a trait capturing everything the core does by shelling out, and make
`service.rs`/`thumbnails.rs`/`proxies.rs`/`metadata.rs` depend on the trait
instead of `crate::ffmpeg::*` / `crate::concurrency::acquire_ffmpeg_permit`.
Provide two implementations:

- `CliMediaBackend` — wraps the existing `ffmpeg`/`ffprobe`/`curl` calls. Desktop
  keeps working with **zero behavior change**.
- `NativeMediaBackend` (iOS) — see [§6.2](#62-options-for-the-native-backend).

Wire the chosen backend into `ReelVaultService` as `Arc<dyn MediaBackend>` (add a
field; thread it to the call sites). Pick the impl in `new()` by `cfg!` /
constructor argument.

```rust
/// Everything the core used to shell out to a CLI tool for.
/// All methods are blocking and run under `acquire_ffmpeg_permit()`.
pub trait MediaBackend: Send + Sync {
    /// Replaces `ffprobe … -of json` (metadata.rs:48). Returns the SAME
    /// `FFProbeOutput` (metadata.rs:649) that `store_metadata` (metadata.rs:75)
    /// and the XMP merge (metadata.rs:115) consume — so the native backend
    /// SYNTHESIZES an FFProbeOutput from AVAsset and nothing downstream changes.
    fn probe(&self, src: &MediaSource) -> Result<FFProbeOutput>;

    /// Replaces the color-info ffprobe (thumbnails.rs:480).
    fn probe_color(&self, src: &MediaSource) -> Result<ColorInfo>;

    /// Replaces the loudness ffmpeg (metadata.rs:1068).
    fn extract_loudness(&self, src: &MediaSource) -> Result<LoudnessInfo>;

    /// Replaces `ffmpeg -ss <t> -frames:v 1`. Decode one frame at `time_secs`,
    /// longest side <= `max_px`, write JPEG to `out`. This single primitive
    /// covers ALL FOUR extraction call sites (still :106, scrub :297,
    /// on-demand :421, ProRes RAW :640).
    fn extract_frame(&self, src: &MediaSource, time_secs: f64,
                     max_px: i32, out: &Path) -> Result<()>;

    /// Replaces the proxy transcode (proxies.rs:734). H.264/HEVC + AAC,
    /// faststart, target height. Report progress on the same 0–85% band the
    /// GenerateProxy stream expects.
    fn transcode_proxy(&self, src: &MediaSource, out: &Path,
                       target_height: i32, progress: &mut dyn FnMut(f64)) -> Result<()>;

    /// Replaces `ffmpeg -metadata` tag writes (metadata.rs:335, :418).
    /// On iOS these are no-ops for Photos sources ([D4]).
    fn write_creation_time(&self, src: &MediaSource, ts_ms: i64) -> Result<()>;
    fn write_location(&self, src: &MediaSource, lat: f64, lon: f64, alt: f64) -> Result<()>;
}

/// Identity becomes backend-specific.
pub enum MediaSource {
    Path(PathBuf),          // desktop
    PhotoAsset(String),     // PHAsset.localIdentifier (iOS)
    Bookmark(Vec<u8>),      // security-scoped bookmark data (iOS, Files)
}
```

Notes:

- **Return `FFProbeOutput`, don't invent a new struct.** `store_metadata` takes
  `&FFProbeOutput` (`metadata.rs:75`) and the XMP merge keys off its fields. The
  native backend builds an `FFProbeOutput` (`metadata.rs:649`: `streams`,
  `format`) from `AVAssetTrack`/`AVMetadataItem`. Some ffprobe-only fields will
  be absent — fine; the inspector already hides empty rows. This keeps the blast
  radius at the *source* of metadata, not the consumer.
- Keep `extract_frame` as the single primitive (the four call sites differ only
  in time/size). Frame counts, sizes, caching, filenames, and the per-video
  scrub locks (`service.rs`) stay in `thumbnails.rs`.
- The semaphore in `concurrency.rs` still wraps each blocking call.

### 6.2 Options for the native backend

**Per [D1](#0-decisions-locked): hybrid (Option C). Build B first, add A later.**

**Option B — Apple frameworks (AVFoundation + VideoToolbox + CoreMedia).**
A thin Swift/Obj‑C shim, called from Rust over FFI (or vice-versa), implements
the trait. This is the *primary* backend.

- ➕ Native, hardware-accelerated, smaller binary; VideoToolbox is the right iOS
  proxy-encode path. Generalizes the `rv-frameshot` pattern the project already
  trusts (it's literally AVAssetImageGenerator).
- ➖ Codec coverage = what AVFoundation decodes (H.264/HEVC/ProRes, common
  containers). Footage AVFoundation won't open is a gap — closed by Option A.
- ❓ **ProRes RAW decode on iOS** is not guaranteed across OS versions/hardware
  the way it is on macOS. `rv-frameshot` proves *macOS*; verify on target iOS
  devices ([§11](#11-open-questions-that-still-need-answers)).

**Option A — link FFmpeg (`libavformat`/`libavcodec`/`libavutil`/`libswscale`).**
Via `rsmpeg`/`ffmpeg-next` against iOS-built static `libav*`. This is the
**fallback** for sources AVFoundation can't open.

- ➕ True parity, closest to the existing ffprobe field shape.
- ➖ Heavy: cross-compiling/bundling `libav*` for iOS, big binary, longer builds.
- GPL is fine off-store ([D2](#0-decisions-locked)).

**Option C — Hybrid (the plan).** `NativeMediaBackend` tries AVFoundation first
and falls back to the linked decoder when AVFoundation can't open the source —
exactly how `thumbnails.rs` already chains `frameshot_extract` → `quicklook_poster`
today. Generalize that fallback across the whole backend, behind a Cargo feature
(`ios-libav`) so B can ship and be tested before A lands.

### 6.3 Thumbnails

Reimplement the four extraction call sites + the color probe on top of the trait.
Keep: size constants (`thumbnails.rs:14-21`), `generate_default_sizes` (`:37`),
the 10-frame scrub loop (`SCRUB_FRAME_COUNT`, `:20`; `generate_scrub_thumbnails`,
`:206`), on-demand width (`generate_frame_at_width`, `:382`), caching, filenames
(`thumbnail_filename`, `:339`), and the per-video scrub locks in `service.rs`.
The QuickLook PNG→JPEG transcode (`:693`) and `qlmanage` (`:675`) disappear on
iOS — the native backend writes JPEG directly.

### 6.4 Proxy generation

`create_proxy` (`proxies.rs:704`; ffmpeg `:734`) transcodes to H.264/AAC with
`-movflags +faststart` and parses `frame=` progress, mapping to the **0–85%
band**. On iOS, implement `transcode_proxy` with `AVAssetExportSession` or a
VideoToolbox compression session, reporting the same 0–85% band. `needs_proxy`
(`:712`, legacy) / `detect_proxies` (`:98`, MAD, threshold `0.96` at `:77`) are
reused unchanged.

### 6.5 Tag writing (creation time / GPS)

`write_location_tag` (`metadata.rs:296`) and `write_creation_time_tag`
(`metadata.rs:386`) mutate the original via `ffmpeg -metadata`. Per
[D4](#0-decisions-locked), the iOS backend implements these as **no-ops for
Photos sources** (keep the edit in the catalog DB only). Revisit per-source
PhotoKit change requests later if users ask.

### 6.6 XMP sidecars

Read is already native (`read_xmp`, `xmp.rs:233-284`) → keep. The `exiftool`
write (`xmp.rs:681`) is deferred ([D5](#0-decisions-locked)). The metadata merge
(`metadata.rs:115`) degrades gracefully when the sidecar is absent.

### 6.7 Sensor-spec fetch (`curl`)

`fetch_via_curl` (`sensor_cache.rs:243`, curl at `:245`) → `reqwest` (tokio is
already present; add `reqwest` to `Cargo.toml` — currently **not** a dependency).
Do this on desktop too — it removes a subprocess and is a strict improvement.

### 6.8 Watching / live updates

`watcher.rs` (`notify::recommended_watcher`, `:202`) does not map to iOS. Replace
the *event source* with `PHPhotoLibraryChangeObserver` (Photos) and
foreground/`BGProcessingTask` rescans (Files), publishing into the existing
`CatalogChange` broadcast (`watcher.rs:61`) so `SubscribeCatalogEvents` keeps
working unchanged. Only the source changes; the bus and RPC are reused.

### 6.9 Ingest & data model (Photos / Files)

This is the schema-touching part — but small, thanks to the inline migration
array.

- Add **source kind + stable identity** to `videos`. Append two migrations to the
  array in `db.rs` (alongside the existing entries around `db.rs:322-438`):
  ```rust
  ("videos.source_kind", "ALTER TABLE videos ADD COLUMN source_kind TEXT"),
  ("videos.source_id",   "ALTER TABLE videos ADD COLUMN source_id TEXT"),
  ```
  Desktop rows leave them NULL (implicit `kind=path`, identity = `path`). iOS
  rows set `source_kind IN ('photo','bookmark')` and `source_id` =
  `PHAsset.localIdentifier` or base64 bookmark. Keep `path` populated with a
  synthetic stable string (e.g. `photos://<localIdentifier>`) so the `UNIQUE`
  constraint and all path-keyed queries keep functioning.
- Replace the `walkdir` enumeration (`indexing.rs:181`) with a PhotoKit fetch
  (`PHAsset` of media type video) and/or bookmarked-folder enumeration. The rest
  of `scan_directory` orchestration (`indexing.rs:154`) and the `post_index` pool
  are reused — they operate on `video_id`s, not paths.
- Frame/decode access goes through `MediaSource`.

### 6.10 Config / paths

Add an iOS arm to `config::default_cache_path` (`config.rs:243-258`). On iOS
everything lives under the app container — `dirs::cache_dir()` should return the
container Caches, but **verify**, and prefer an explicit path passed in from
Swift via the FFI entry point ([§7.2](#72-the-ffi-entry-point)) rather than
trusting `dirs`.

### 6.11 Concurrency tuning

Keep `BlockingSemaphore`. `default_max_concurrent_ffmpeg()` (`concurrency.rs:67`,
= `available_parallelism()`) is too aggressive for a phone under thermal
pressure. Lower the iOS default (e.g. 2) and/or react to `ProcessInfo`
`thermalState`. The semaphore now bounds native decode/encode sessions.

---

## 7. How the app embeds the core

### 7.1 Keep gRPC, run the server in-process

Run `ReelVaultService::into_server()` on an **in-process** loopback listener
(`127.0.0.1:<ephemeral>`), and have `VideoRepository` connect to it exactly as it
connects to a remote daemon. This is the macOS model
(`macos/.../ServerLauncher.swift` spawns the daemon and the client connects to
`127.0.0.1:50051` plaintext) — minus the subprocess.

Why this is the strong default ([D3](#0-decisions-locked)):

- **`service.rs` and the entire proto/RPC surface are reused unchanged.**
- **`VideoRepository` is reused unchanged.** It already exposes
  `connect(host:port:)` (`VideoRepository.swift:41`) and
  `ServerEndpoint.loopback(port:)` (`ServerEndpoint.swift:42`). Local mode =
  "connect to `127.0.0.1:<embedded port>` with `.plaintext`," skipping mDNS
  discovery, TLS pinning, and pairing entirely (those are LAN concerns).
- The generated gRPC stubs and `grpc-swift` stack carry over (regenerate via
  `kit/regen-proto.sh`; `grpc-swift-protobuf` is pinned `exact "1.3.1"`).

Cost: a tokio runtime + loopback server inside the app. Cheap and well-trodden;
tokio + tonic over loopback work on iOS.

### 7.2 The FFI entry point

Add a tiny C-ABI entry point to `core/src/lib.rs` (or a new `core/src/ios.rs`)
behind `#[cfg(target_os = "ios")]`. It owns the runtime and returns the port:

```rust
/// Boots the embedded server. Swift passes the app-container paths explicitly
/// (don't trust `dirs` on iOS). Returns the bound loopback port, or 0 on error.
/// The runtime is parked in a static so it survives across the app lifecycle.
#[no_mangle]
pub extern "C" fn reelvault_start_embedded(
    db_path: *const c_char,
    data_dir: *const c_char,
    cache_dir: *const c_char,
) -> u16 {
    // 1. build a multi-thread tokio runtime, store in a OnceCell static
    // 2. Database::open(db_path); Config with cache_dir; PairingState::loopback()
    // 3. let svc = ReelVaultService::new(db, config, pairing, data_dir);  // service.rs:79
    // 4. bind 127.0.0.1:0; spawn tonic serve(svc.into_server()); return chosen port
}

#[no_mangle]
pub extern "C" fn reelvault_stop_embedded() { /* park/shutdown for suspension */ }
```

Expose it to Swift via a bridging header in the XCFramework (or a thin
`ReelVaultCore` SwiftPM target wrapping the C symbols). Swift calls
`reelvault_start_embedded(...)`, then `VideoRepository.shared.connect(to:
.loopback(port:))`.

> Note `new()` needs a `PairingState` and a `data_dir` ([§3.1](#31-process-shape-and-the-embed-seam-already-exists)).
> Construct a loopback/no-op `PairingState` (pairing is a LAN concept) and pass
> the container path as `data_dir`.

### 7.3 Lifecycle

- Boot the runtime + server lazily on first foreground; keep the SQLite catalog
  in the app container (WAL mode is fine on a single-process embed).
- Cooperate with suspension: pause scans, flush WAL, deregister the PhotoKit
  observer; `reelvault_stop_embedded()` parks the runtime.
- Use `BGProcessingTaskRequest` for opportunistic indexing/thumbnailing of large
  imports; rely on `post_index.rs` idempotent catch-up if killed mid-scan.

### 7.4 What to drop for the embed

- `main.rs` entirely (TCP daemon, `--host`/`--port`, `nix` rlimit, service
  managers, per-OS data-dir logic).
- The separate **media server / media port** — originals are local; nothing to
  stream over LAN. Thumbnails/proxies still flow through the `GetThumbnail` gRPC
  stream.
- mDNS discovery, pinned TLS, and pairing on the client side **for local mode**
  (they remain for remote mode).

---

## 8. Build & packaging

### 8.1 Targets and artifact

```bash
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios-sim
# staticlib per target, then assemble an XCFramework
# (cargo-xcframework, or manual `xcodebuild -create-xcframework`).
```

Add `staticlib` to the lib's `crate-type` (currently none is declared, so it
defaults to `rlib` only — `core/Cargo.toml:10`):

```toml
[lib]
name = "reelvault_core"
path = "src/lib.rs"
crate-type = ["rlib", "staticlib"]   # add staticlib for the iOS embed
```

Keep the two `[[bin]]`s desktop-only (they won't build for iOS, but `cargo build
-p reelvault_core --lib --target aarch64-apple-ios` builds just the lib).

### 8.2 build.rs on cross-compile

`tonic_build::compile_protos` (`build.rs:7`) and `emit_sensor_table`
(`build.rs:76`) run on the host and are fine. `emit_frameshot` (`build.rs:21`)
already guards on `CARGO_CFG_TARGET_OS == "macos"` (`build.rs:28`) → iOS emits
`FRAMESHOT_BIN = None` and compiles. Confirm `protoc` is on PATH in the iOS CI
build environment.

### 8.3 Dependency audit for iOS

- `nix` is `#[cfg(unix)]` and used only in `main.rs` (rlimit) — not in the lib
  path you embed. Confirm nothing else pulls a `nix`/`winapi` feature.
- `which` (`Cargo.toml`) is only for CLI resolution; the native backend won't use
  it. Gate the `ffmpeg.rs`/CLI backend behind `#[cfg(not(target_os="ios"))]`.
- `image`, `rayon`, `rusqlite` (bundled), `tokio` (full), `tonic` (tls), `prost`,
  `chrono`, `regex`, `walkdir`, `uuid`, `serde*` — all known-good on iOS.
- Add `reqwest` (replaces `curl` in `sensor_cache.rs`).
- Option-A fallback: add the `libav` FFI crate behind a `ios-libav` feature and
  link the iOS `libav*` + `-framework VideoToolbox -framework CoreMedia
  -framework AudioToolbox`.

### 8.4 Licensing / distribution

`core/Cargo.toml:5` declares `license = "GPL-3.0-or-later"`. **Per
[D2](#0-decisions-locked) we distribute off the App Store** (developer/ad-hoc/
TestFlight/enterprise), where there is **no GPL conflict** — GPL governs *how you
distribute source/binaries*, and you can freely distribute GPL binaries; the
well-known conflict is specifically the **App Store's** ToS (usage/DRM
restrictions vs. GPL §6 anti-Tivoization, the VLC precedent). Linking GPL
`libav*` (Option A) is therefore fine here.

If App Store distribution ever becomes a goal, this reopens: you would then need
either an Apple-frameworks-only build (Option B, no GPL media lib linked) plus a
relicense/exception from the copyright holders, or off-store-only. Flag any move
toward the App Store back to the owner.

---

## 9. Remote mode (use case 1) — already shipping

Remote mode is the feature/ios-client work and is largely built: mDNS discovery
(`ServerDiscovery.swift`), pinned TLS / TOFU (`PinnedTLS.swift`), pairing
(`BearerTokenInterceptor.swift`, `pairing` module). The daemon's LAN exposure
(`--host 0.0.0.0`, auth, TLS) is handled there. **This port (use case 2) reuses
that client wholesale** — local mode is just a different endpoint
([§7.1](#71-keep-grpc-run-the-server-in-process)).

---

## 10. Phased plan (agent-executable)

Each phase lists **files**, **do**, **acceptance**, and a **verify** command. Do
them in order; Phases 2→3→4 are the spine.

### Phase 0 — Embed scaffold (build green, empty catalog)
- **Files:** `core/Cargo.toml` (add `staticlib`), `core/src/lib.rs` (FFI entry,
  [§7.2](#72-the-ffi-entry-point)), `ios/` (link the XCFramework, call
  `reelvault_start_embedded`, connect via `ServerEndpoint.loopback`).
- **Do:** cross-compile the lib to iOS; boot an in-process gRPC server over an
  empty app-container catalog; point `VideoRepository` at it.
- **Acceptance:** the iOS app, with no daemon on the network, opens an empty
  on-device catalog through the *same* `VideoRepository` it uses for remote mode.
- **Verify:** `cargo build -p reelvault_core --lib --target aarch64-apple-ios-sim`
  succeeds; app launches in the simulator and `GetStatus` returns over loopback.

### Phase 1 — `MediaBackend` trait + `CliMediaBackend` (desktop unchanged)
- **Files:** new `core/src/media_backend.rs` (trait + `MediaSource`);
  `ffmpeg.rs` → wrapped by `CliMediaBackend`; thread `Arc<dyn MediaBackend>`
  through `service.rs`, `metadata.rs`, `thumbnails.rs`, `proxies.rs`.
- **Do:** extract every call site in
  [§3.2](#32-the-media-pipeline--external-processes) behind the trait;
  `CliMediaBackend` reproduces today's behavior exactly.
- **Acceptance:** desktop behavior is byte-for-byte unchanged.
- **Verify:** `cargo test -p reelvault_core` is green; a manual scan +
  thumbnail + proxy on macOS matches pre-refactor output.

### Phase 2 — `NativeMediaBackend` (Option B: AVFoundation/VideoToolbox)
- **Files:** the Swift/ObjC media shim (in `kit/` or the iOS core wrapper); the
  Rust `NativeMediaBackend` calling it over FFI.
- **Do:** implement `probe` (→ synthesize `FFProbeOutput`), `probe_color`,
  `extract_loudness`, `extract_frame`, `transcode_proxy`. Tags = no-ops ([D4]).
- **Acceptance:** on-device metadata + still + 10 scrub frames + proxy for
  H.264/HEVC/ProRes assets, with the inspector populated from real fields.
- **Verify:** unit-test `extract_frame`/`probe` against bundled sample clips;
  thumbnails render in the grid; a generated proxy plays.

### Phase 3 — Ingest / data model (Photos + Files)
- **Files:** `db.rs` (`source_kind`/`source_id` migrations, [§6.9]);
  `indexing.rs` (enumeration arm); the PhotoKit/Files enumerators (Swift);
  `watcher.rs` (`PHPhotoLibraryChangeObserver` source).
- **Do:** enumerate `PHAsset` videos (then bookmarked folders), index through the
  existing `post_index` pool, wire live updates into the `CatalogChange`
  broadcast.
- **Acceptance:** import the Photos video library; the grid populates; adding a
  video in Photos shows up live via `SubscribeCatalogEvents`.
- **Verify:** add/remove a clip in the simulator's Photos and watch the grid
  update without a manual rescan.

### Phase 4 — Parity polish
- **Files:** `sensor_cache.rs` (`reqwest`), `concurrency.rs` (iOS default +
  thermal), lifecycle (`BGProcessingTask`, suspend/park), `config.rs` (iOS arm).
- **Do:** the small replacements; tune for mobile; document the feature matrix
  vs. desktop with deliberate gaps (XMP write, in-place tag write).
- **Acceptance:** a documented parity matrix; background indexing of a large
  import survives suspension.
- **Verify:** background a long scan, return, confirm it resumed (idempotent
  catch-up).

### Phase 5 — Full-codec parity (Option A fallback, hybrid)
- **Files:** `ios-libav` Cargo feature; the linked-decoder fallback inside
  `NativeMediaBackend`.
- **Do:** route AVFoundation-unsupported sources to the linked `libav*` decoder.
- **Acceptance:** formats AVFoundation can't open still index + thumbnail.
- **Verify:** an exotic-codec sample that fails on Phase 2 now indexes.

Estimate: Phase 0 days; Phases 1–4 the bulk (multiple weeks), Phase 2 + Phase 3
the riskiest; Phase 5 a self-contained follow-on.

---

## 11. Open questions that still need answers

These are empirical (not owner opinions — D1–D7 are settled):

1. **ProRes RAW decode on the target iOS devices/OS versions.** `rv-frameshot`
   proves macOS only. Verify before promising RAW parity in Phase 2; it may be
   the first thing pushed to the Phase 5 `libav` fallback.
2. **`FFProbeOutput` field coverage from AVFoundation.** Enumerate which fields
   `store_metadata`/the grid actually require vs. which AVFoundation can supply;
   confirm the "absent is fine" assumption holds for the inspector and any
   filters/facets (`GetFilterOptions`, `GetMetadataFacets`).
3. **Loudness on iOS.** `metadata.rs:1068` extracts audio loudness via ffmpeg;
   confirm an AVFoundation equivalent (`AVAudioFile`/`AVAssetReader` RMS, or defer
   loudness on iOS).
4. **`dirs` behavior on iOS** for cache/data paths — prefer Swift-supplied
   container paths through the FFI rather than trusting the crate.

---

## 12. Appendix — verified ground-truth reference (commit `3c8bf2f`)

Symbols are current; line numbers drift — re-grep the symbol before editing.

**Service / embed**
- `core/src/lib.rs` — `pub mod service` (+ the rest); lib + 2 bins in
  `Cargo.toml` (`[lib]` :10, `[[bin]]` :119, :123).
- `service.rs:34` `pub struct ReelVaultService` `#[derive(Clone)]`; fields
  `db`(:35) `config`(:36) `catalog_events: broadcast::Sender<CatalogChange>`(:51,
  channel :92 cap 256) `watcher`(:55) `pairing`(:63) `data_dir`(:65).
- `service.rs:79` `pub fn new(db, config, pairing, data_dir) -> Self`.
- `service.rs:114-119` watcher boot (`tokio::spawn(restart_watcher())`).
- `service.rs:145` `pub fn into_server(self) -> ReelVaultServer<Self>`.
- `service.rs:1083` `impl ReelVaultTrait`; 59 RPCs; stream assoc types
  `:1084-1088`.
- `main.rs` — `--port` :52, `--host` :57, "no auth" comment :56, service
  construct :267, `TcpListener::bind` :277, `raise_fd_limit` :459 (called :180),
  `get_data_dir` :545-571.
- `watcher.rs:61` `enum CatalogChange`; `notify::recommended_watcher` :202;
  `poll_paths` :540; `sweep_pending` :396.

**Media pipeline (to replace)**
- `ffmpeg.rs:78` `ffmpeg_command()`, `:83` `ffprobe_command()`.
- `metadata.rs:48` ffprobe; `:649` `struct FFProbeOutput`; `:75`
  `store_metadata(db, video_id, video_path, &FFProbeOutput, file_size)`; `:115`
  XMP merge (`read_xmp`); `:296` `write_location_tag(path, lat, lon, alt)` (ffmpeg
  :335); `:386` `write_creation_time_tag(path, ts_ms)` (ffmpeg :418); `:1068`
  loudness (ffmpeg).
- `thumbnails.rs` — consts :14-21 (`SMALL/MEDIUM/LARGE_WIDTH` 200/400/800,
  `SCRUB_FRAME_COUNT=10`, `SCRUB_WIDTH=320`); `generate_default_sizes` :37;
  `extract_frame` :81 (ffmpeg :106, resize :142); `generate_scrub_thumbnails`
  :206 (ffmpeg :297); `generate_frame_at_width` :382 (ffmpeg :421); color ffprobe
  :480; `thumbnail_filename` :339; `frameshot_path` :601; `frameshot_extract` :630
  (exec :640); `quicklook_poster` :662 (qlmanage :675); PNG→JPEG ffmpeg :693.
- `proxies.rs:98` `detect_proxies(db, thumbnail_cache)`; `:77`
  `PROXY_SIMILARITY_THRESHOLD=0.96`; `:704` `create_proxy` (ffmpeg :734, libx264 /
  aac / +faststart / `-progress pipe:1`; `frame=` parse :772; 0–85% band).
- `sensor_cache.rs:243` `fetch_via_curl` (curl :245, timeout 30s).
- `xmp.rs:233-284` native `read_xmp`; `:681` `exiftool` write (defer); `:645`
  exiftool test.
- `concurrency.rs` — `BlockingSemaphore` :16; `acquire_ffmpeg_permit()` :83;
  `default_max_concurrent_ffmpeg()` :67; `set_ffmpeg_concurrency_limit(n)` :77.
- `build.rs:7` `tonic_build::compile_protos`; `:21` `emit_frameshot` (macOS guard
  :28); `:76` `emit_sensor_table`.

**Data model / reuse**
- `schema.sql:15-28` `videos` (id TEXT PK, path TEXT UNIQUE NOT NULL, …); 16
  tables + FTS `video_search` (`schema.sql:199`).
- `db.rs:310` `initialize_conn`; inline migration array `:322-438` (execute :440,
  duplicate-column ignored :444-446). Migration-only tables: `proxy_links`,
  `camera_sensor_cache`, `auto_tag_history`, `paired_devices`.
- `indexing.rs:154` `scan_directory(db, config, on_progress)`; walkdir :181;
  `post_index::spawn` :207; rayon `par_iter` :234; `scan_single_file` :445.
- `post_index.rs:551` `spawn`, `:568` `spawn_with_workers` (`DEFAULT_NUM_WORKERS=3`),
  `:653` `process_one`.
- `imagehash.rs` — pure-Rust `image::open` + `resize_exact(Lanczos3)` +
  `to_luma8` + MAD similarity (not dHash).
- `config.rs:243-258` `default_cache_path` (macos/windows/else, `dirs`).
- `Cargo.toml:5` `license = "GPL-3.0-or-later"`; rusqlite bundled :46; tokio full
  :16; tonic tls :22; `nix` unix-only :101; `which` :93; **no `reqwest`**.

**Apple client (reused for local mode)**
- `kit/.../VideoRepository.swift:20` `@MainActor public class VideoRepository:
  ObservableObject`; `.shared` :21; `connect(host:port:)` :41; `connect(to:
  ServerEndpoint)` :50.
- `kit/.../ServerEndpoint.swift` — `struct ServerEndpoint { host, port, security,
  bearerToken }`; `EndpointSecurity` `.plaintext`/`.pinnedTLS(...)`;
  `.loopback(port:)` :42.
- `kit/.../Models/Video.swift:27` `VideoSummary` (Sendable), `:252`
  `VideoMetadata` — plain transport-agnostic structs, populated via
  `makeSummary`/`makeMetadata`.
- **No repository protocol seam** — view-models use `VideoRepository.shared`
  directly. The in-process-gRPC embed needs none.
- `kit/regen-proto.sh` — `protoc … Visibility=Public`, grpc-swift v2;
  `grpc-swift-protobuf` pinned `exact "1.3.1"`.
- `ios/` — XcodeGen (`project.yml`, `.xcodeproj` generated), iOS 18 floor,
  remote-only, `AppRouter` discovery→connect; mDNS + pinned TLS + pairing.
- `macos/.../ServerLauncher.swift` — spawns the bundled daemon, connects
  `.plaintext` to `127.0.0.1:50051` (the model the iOS embed mirrors in-process).

---

## 13. Implementation status & parity matrix

Status as of 2026-06-15 (`develop`). Phases 0–4 are complete and were verified on
the iOS Simulator and a real iPhone 16 Pro; Phase 5 is dropped (see §14).

| Phase | State | Notes |
|---|---|---|
| 0 — Embed scaffold | ✅ done, device-verified | in-process gRPC over loopback |
| 1 — `MediaBackend` trait + CLI | ✅ done | process-global backend (not a threaded `Arc`); desktop unchanged |
| 2 — `NativeMediaBackend` | ✅ done | probe/probe_color/extract_frame/transcode_proxy + `extract_loudness` (AVAssetReader RMS envelope); ProRes RAW unverified (no device footage) |
| 3 — Ingest (Photos / Files) | ✅ done | Photos enumerate (parallel `TaskGroup`) + incremental + live observer + foreground catch-up + **removal reconcile** (`reelvault_prune_photos` prunes deleted Photos); **Files (bookmark) ingest** done |
| 4 — Parity polish | ✅ done | reqwest, iOS concurrency cap, thermal backoff, foreground catch-up, parallel ingest + a "keep the app open" ingest banner. **No `BGProcessingTask`** — deliberate: foreground-only ingest + catch-up cover it (a `Task`/`TaskGroup` can't run while suspended; bg processing isn't worth the complexity here) |
| 5 — libav fallback | ⛔ dropped | exotic-codec / ProRes-RAW on-device decode via libav — see §14 |

### Feature parity matrix (deliberate gaps called out)

| Feature | Desktop / macOS | iOS remote | iOS local | Notes |
|---|---|---|---|---|
| Grid / list / detail / map | ✅ | ✅ | ✅ | |
| Metadata inspector | ✅ | ✅ | ✅ | |
| Tags / ratings / color labels / collections / smart collections | ✅ | ✅ | ✅ | catalog-only writes |
| Search / filter / facets | ✅ | ✅ | ✅ | |
| Thumbnails (incl. scrub) | ✅ | ✅ | ✅ | local via AVAssetImageGenerator |
| Playback | native file/proxy | HLS / range stream | direct `AVPlayer` | local: `photos://`→PHImageManager else file URL |
| Proxy generation | ffmpeg | server-side | AVAssetExportSession | |
| Offline download / cache | n/a | ✅ app-private | n/a | download a subset for daemon-free playback |
| Stacking / grouping | ✅ | ✅ | ✅ | |
| Editor hand-off | drag-and-drop | share sheet | share sheet | **deliberate**: iOS replaces D&D with share |
| Upload | n/a | resumable PUT → import dir | n/a | local has no server to upload to |
| Ingest source | filesystem scan | server scans | Photos + Files | parallel `TaskGroup` ingest |
| Live updates | `notify` watcher | server watcher → CatalogEvents | PHPhotoLibraryChangeObserver (add **+ remove**) + foreground reconcile | |
| XMP read | ✅ (native) | ✅ | ✅ | |
| XMP **write** | ✅ (exiftool) | ❌ | ❌ | **deliberate** (D5): defer |
| In-place tag write (creation time / GPS → file) | ✅ (exiftool) | ❌ | ❌ | **deliberate** (D4): catalog-only; can't rewrite a Photos original |
| Audio loudness graph | ✅ (DetailGraphsPanel) | ✅ | ✅ | iOS detail "Visuals" panel; local via native AVAssetReader RMS |
| Keyboard shortcuts | ✅ | iPad+keyboard | iPad+keyboard | **deliberate**: not iPhone |
| ProRes RAW thumbnails | ✅ (macOS AVFoundation helper) | server-side | ⚠ unverified | needs RAW footage on device |
| Exotic codecs (AVFoundation can't open) | ✅ (ffmpeg) | server-side | ❌ (server transcodes) | Phase 5 dropped; remote viewing transcodes on the server |

---

## 14. Phase 5 — libav fallback: DROPPED

**Decision (owner, 2026-06-15): not doing it.** Phase 5 would route
AVFoundation-unsupported sources to a linked `libav*` decoder on-device. We're
dropping it because:

- **Licensing.** Linking FFmpeg (GPL) into the on-device app is a non-starter
  for distribution we'd actually want, and not worth the friction even off-store.
- **Not a priority + unlikely input.** Exotic codecs / ProRes (RAW) sitting in a
  *phone's* Photos library is an edge case; the common path (H.264/HEVC/ProRes
  that AVFoundation opens) is fully covered by Phase 2's native backend.
- **The server already covers it.** When such footage lives on a desktop/NAS
  catalog, the Rust backend transcodes it (HLS / proxy) for remote viewing — so
  iOS sees a playable rendition without any on-device libav.

So an AVFoundation-undecodable source in **Local** mode simply won't thumbnail
(rare), and ProRes RAW on-device stays "⚠ unverified" rather than a planned
fallback. (The original cross-compile recipe is preserved in git history if this
is ever revisited.)
