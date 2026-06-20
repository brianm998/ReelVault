# ReelVault

**Available in:** [العربية](README.ar.md) · [বাংলা](README.bn.md) · [čeština](README.cs.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [Français](README.fr.md) · [हिन्दी](README.hi.md) · [Bahasa Indonesia](README.id.md) · [Italiano](README.it.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Nederlands](README.nl.md) · [Polski](README.pl.md) · [Português (BR)](README.pt-BR.md) · [Русский](README.ru.md) · [ภาษาไทย](README.th.md) · [Türkçe](README.tr.md) · [Українська](README.uk.md) · [اردو](README.ur.md) · [Tiếng Việt](README.vi.md) · [简体中文](README.zh-Hans.md)

A cross-platform video cataloging application inspired by Lightroom — fast,
native browsing of large video libraries.

**ReelVault is for organizing, discovering, and managing videos. It is NOT a
video editor.**

**[→ Download ReelVault](https://brianm998.github.io/ReelVault/)**

## Overview

ReelVault helps you:

- **Browse** thousands of videos in a responsive, virtualized grid with
  adjustable thumbnail size.
- **Scrub** Lightroom-style: hover a thumbnail and slide left↔right to
  preview frames across the timeline.
- **Group** related variants (e.g. 4K and 1080p exports of the same source)
  into Lightroom-style stacks; mark one as preferred for the grid + open.
- **Discover** content through full-text search, tag filtering, and per-field
  filter dropdowns (camera, lens, codec, capture year, keyword).
- **Inspect** detailed metadata: codec, resolution, FPS, bitrate, color space,
  HDR, EXIF, GPS, camera/lens model.
- **Organize** with free-form notes, keywords, and multi-select operations.
- **Hand off** clips to external editors by drag-and-drop — drag one or more
  cards straight from the grid into DaVinci Resolve, Final Cut Pro, Premiere
  Pro, or any app that accepts file drops.
- **Multi-catalog** workflows: open / close / switch SQLite catalogs from a
  File menu, with a recent-catalogs list and per-catalog window titles.

## Architecture

```
Desktop / macOS clients          iOS / Android clients
   ↓ gRPC over loopback             ↓ gRPC + HTTPS media over the LAN
   │                                │ (mDNS/NSD discovery · pinned TLS · paired)
   └───────────────┬────────────────┘
                   ↓
        Rust Backend Daemon (reelvault-core)
                   ↓
        SQLite Catalog + FFmpeg/FFprobe + Filesystem
```

- **Rust core** (`core/`) — Tonic-based gRPC daemon. Owns the SQLite catalog,
  FFprobe metadata extraction, thumbnail + scrub-frame generation,
  scan/indexing, search, and filter aggregation. Supports hot-swapping the
  active catalog at runtime via `OpenCatalog` / `CloseCatalog` /
  `GetCurrentCatalog` RPCs, so a single daemon process can serve multiple
  libraries over its lifetime.

- **Kotlin Compose desktop client** (`kotlin-desktop/`) — Compose Multiplatform UI.
  Auto-detects a running daemon on `127.0.0.1:50051`; if none is running,
  spawns the bundled daemon itself (falling back to an OS-assigned port if
  50051 is busy).

- **SwiftUI macOS client** (`macos/`) — Feature-parity native macOS app with
  the same auto-spawn flow, a real macOS File menu (Commands group), and a
  reactive window title that tracks the open catalog.

- **SwiftUI iOS client** (`ios/`) — A **remote-only** iPhone / iPad app. It
  has no local file access and embeds no daemon: it discovers a daemon over
  Wi‑Fi (mDNS), connects over a fingerprint-pinned TLS channel after a one-time
  pairing, browses over gRPC, and **streams** video (downscaled HLS) from the
  daemon's media server. Replaces editor drag-out with the iOS share sheet and
  adds upload from Photos / Files. See [`ios/README.md`](ios/README.md).

- **Kotlin Compose Android client** (`android/`) — A **remote-only**
  Android phone / tablet app. Connects to a daemon over the LAN (NSD
  discovery), streams video via ExoPlayer, and replaces editor drag-out with
  the Android share intent. Also embeds the full Rust core for on-device local
  library access — browse, catalog, and upload footage directly from the
  device. See [`android/README.md`](android/README.md).

- **ReelVaultKit** (`kit/`) — A local SwiftPM package of shared Swift used by
  **both** Apple clients: models, view-models, the gRPC client, discovery,
  pinned TLS, and the media cache/streaming layer.

- **SQLite catalog** — WAL-mode database with FTS5 for full-text search. The
  schema lives in [`core/schema.sql`](core/schema.sql).

> **ProRes RAW thumbnails are macOS-only quality.** ffmpeg can't develop
> ProRes RAW (Atomos S-Log3 / S-Gamut), so on **macOS** the daemon decodes it
> through QuickLook / AVFoundation — correct colour and true per-frame
> scrubbing. On **Linux / Windows** there's no such decoder, so the daemon
> falls back to ffmpeg: flatter/darker frames and a single repeated scrub
> frame. The Rust core builds identically on all three platforms — AVFoundation
> is never linked into it. Details: [`core/README.md`](core/README.md)
> (build + decode paths) and [`macos/README.md`](macos/README.md) (the
> macOS client). Every other codec is handled by ffmpeg the same way everywhere.

## Project status

**MVP is functional across all four clients.** The macOS and Kotlin Compose
desktop clients share the full feature set; macOS adds native menu-bar commands
and NSWorkspace-driven editor launches. The **iOS client** (iPhone / iPad) and
the **Android client** each connect to a daemon over the LAN and stream video —
browse, inspect, stack, share, and upload. Both mobile clients also embed the
full Rust core for on-device local library access without a network connection.
See [`ios/README.md`](ios/README.md) and [`android/README.md`](android/README.md).

### ✅ Done

**Core**
- [x] gRPC daemon with full RPC surface (videos, search, scan, tags,
      collections, stacks, filters, status, config, catalog lifecycle).
- [x] SQLite catalog with WAL mode + FTS5; runtime catalog hot-swap via
      `OpenCatalog` / `CloseCatalog`.
- [x] Rich metadata extraction via FFprobe + platform-native helpers: codec,
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera.
- [x] Thumbnail and Lightroom-style scrub-frame generation (10 frames per
      video) with per-video locks to deduplicate work.
- [x] Concurrent-ffmpeg throttle (defaults to host CPU count) to keep
      large-library scans from thrashing SAN-backed storage.
- [x] Library scanning with optional recursion and auto-grouping of variants.
- [x] CLI flags for `--db-path`, `--no-catalog`, `--port` (with
      OS-assigned-port fallback), and a parseable
      `REELVAULT_LISTENING_ON=…` stdout line for client launchers.

**Both clients**
- [x] Virtualized grid view with adaptive column count and a thumbnail-size
      slider.
- [x] Hover-scrub preview, hover-play overlay, multi-select with
      shift-range and ⌘/Ctrl-toggle.
- [x] Stack (group) UI: stack badges with member counts, click-to-expand,
      star to set preferred, per-member open buttons.
- [x] Side panels: library locations (left), details + metadata + notes +
      keywords (right). Tab toggles both, individual chevrons collapse each.
- [x] Top-bar filter dropdowns (camera / lens / keyword / codec / year) —
      only fields with data are shown; AND-combined with search.
- [x] Sort menu with all major fields (filename, dates, duration, size,
      resolution, fps, codec, bitrate, camera, lens, keyword); click again
      to reverse direction.
- [x] Tag/keyword management: create on the fly, apply to multi-selection,
      filter the grid by clicking the `>` chevron.
- [x] Right-click context menu on every card: Open with Default Player and
      Reveal in Finder/Explorer — operates on the full multi-selection.
- [x] Drag-and-drop hand-off: drag selected cards into any app that accepts
      file drops (DaVinci Resolve, Final Cut Pro, Premiere Pro, etc.).
- [x] Multi-catalog flow: File menu with Open / Close / Open Recent,
      first-launch "open a catalog" sheet, persistent recents list, window
      title showing the open catalog's name.
- [x] Auto-spawn of bundled daemon (with port-busy fallback) when no
      backend is running on startup.
- [x] Hover-text help (tooltips on Kotlin via `TooltipArea`; SwiftUI via
      `.help(_:)`) on every interactive element and metadata field.
- [x] Dark mode default; light/dark theme toggle.

**Android client**
- [x] Remote server connection via NSD (Network Service Discovery) and
      gRPC over TLS with certificate pinning and one-time pairing.
- [x] ExoPlayer-based HLS video streaming with quality picker.
- [x] On-device local library via embedded Rust core (JNI / NDK).
- [x] Browse, inspect metadata, tag, stack, rate, and set color labels.
- [x] Share intent for editor hand-off (replaces drag-and-drop).
- [x] Upload device footage to a paired server.
- [x] Offline downloaded video library.
- [x] GPS map view with OSMDroid and GPS track polyline.
- [x] Back/forward navigation history.

### 🚧 Planned

- [ ] Real-time file watching (re-index when the underlying folder changes).
- [ ] Smart collections (saved searches with live filter rules).
- [ ] Proxy video generation for 8K+ footage.
- [ ] GitHub-based auto-update.
- [ ] CI/CD release pipeline producing signed installers per platform.
- [ ] Bundling the daemon binary inside the client app bundles (today the
      launcher finds it via `REELVAULT_CORE_BIN` or a cargo dev tree).

## Getting started

### Prerequisites

- **Rust** (1.75+ recommended) — for building the core daemon.
- **FFmpeg / FFprobe** — must be on `PATH`. Used for metadata extraction
  and thumbnail/scrub-frame generation.
- **JDK 17+** + Gradle (wrapper included) — for the Kotlin desktop client.
- **Swift 5.9+ / Xcode 15+** — for the macOS client.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — for the iOS client.

See [`SETUP.md`](SETUP.md) for platform-specific install instructions.

### Build the core daemon

```bash
cd core
cargo build --release
```

The binary lands at `core/target/release/reelvault-core`. Run it directly if
you want to drive it yourself, or just let one of the clients spawn it for
you on first launch:

```bash
# Default — use the platform-default catalog on the default port.
./target/release/reelvault-core

# Start with no catalog (clients use this mode); print the bound port.
./target/release/reelvault-core --no-catalog --port 0

# Open a specific catalog at startup.
./target/release/reelvault-core --db-path /path/to/library.db
```

The daemon prints a stable `REELVAULT_LISTENING_ON=127.0.0.1:N` line on
stdout that the clients parse to discover the assigned port.

### Run the Kotlin Compose desktop client

```bash
cd kotlin-desktop
./gradlew run
```

On first launch the client probes `127.0.0.1:50051` and — if nothing's
listening — spawns the bundled daemon. Build location lookup order:

1. `$REELVAULT_CORE_BIN` (an absolute path to the daemon executable)
2. A binary next to the application jar
3. `core/target/release/reelvault-core` or `core/target/debug/reelvault-core`
   in the dev tree
4. `reelvault-core` on `PATH`

For development, just `cargo build` inside `core/` and the client will pick
the debug binary up.

### Run the macOS SwiftUI client

```bash
cd macos
swift run
```

Same auto-spawn flow, same binary-lookup order (with the addition of an
in-bundle `Resources/reelvault-core` path that's used by signed app
bundles). Use `⌘O` to open a catalog and `⇧⌘W` to close it; the recent list
lives under `File → Open Recent`.

### Run the iOS SwiftUI client

The iOS client is **remote-only** — it connects to a daemon over the LAN
rather than spawning one. Start the daemon in remote mode on a machine on the
same Wi‑Fi:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Then generate the Xcode project (the `.xcodeproj` isn't committed) and build:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

On first launch the app discovers the daemon over mDNS, you authorize the
device once with a 6-digit pairing code (generate it from a desktop client's
**File → Pair a New Device**, or the daemon log), and then browse + stream.
Requires **iOS 18+** and **Xcode 16+**. Full details, including the streaming
and pairing model, are in [`ios/README.md`](ios/README.md).

### Run the Android client

**Prerequisites:** Android NDK and `cargo-ndk` (for the embedded Rust core).

```bash
cd android
./build-core.sh        # cross-compile the Rust core for Android ABIs
./gradlew assembleDebug
```

Install the APK on a device or emulator. For remote-server access, start the
daemon with `--remote` on a machine on the same Wi‑Fi and pair using the same
6-digit flow as iOS. For on-device local library access, grant the app storage
permission and the embedded daemon will index videos automatically.

For Play Store releases, CI builds a signed APK on `v*` tags via
`.github/workflows/android-release.yml`. See [`android/README.md`](android/README.md)
for full build and signing instructions.

## Documentation

- [`CLAUDE.md`](CLAUDE.md) — Project vision, architecture details, database
  schema, and design principles.
- [`SETUP.md`](SETUP.md) — Development environment setup.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Detailed reference for the
  Rust core's modules and RPC surface.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Side-by-side comparison
  of the Kotlin and SwiftUI clients.
- [`ios/README.md`](ios/README.md) — The iOS (iPhone / iPad) client:
  discovery, pairing, pinned TLS, HLS streaming, and on-device library.
- [`android/README.md`](android/README.md) — The Android client: NSD
  discovery, ExoPlayer streaming, embedded Rust core, and Play Store
  distribution.
- [`macos/README.md`](macos/README.md) — The native macOS client.

## Contributing

See [`CLAUDE.md`](CLAUDE.md) for development guidelines. Pull requests
welcome — please keep all four clients in feature-parity where applicable
(see CLAUDE.md for legitimate per-platform deviations), and add SPDX
headers to any new source files (see License below).

## License

ReelVault is free software, licensed under the **GNU General Public License,
version 3 or (at your option) any later version**. The full license text
lives in [`LICENSE`](LICENSE); a short copyright notice is in
[`COPYRIGHT`](COPYRIGHT).

Every source file carries an SPDX identifier so license-scanning tools
(REUSE, FOSSology, GitHub's licensee detector, etc.) can identify the
license programmatically:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

If you distribute a modified version of ReelVault — or any program that
links against the Rust core as a library — the GPL requires you to make
your source available under the same terms. See the LICENSE file for the
full set of obligations.
