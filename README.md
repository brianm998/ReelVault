# ReelVault

A cross-platform video cataloging application inspired by Lightroom — fast,
native browsing of large video libraries.

**ReelVault is for organizing, discovering, and managing videos. It is NOT a
video editor.**

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
Frontend Clients (Kotlin Compose / SwiftUI)
        ↓ gRPC over loopback
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

- **Kotlin Compose desktop client** (`desktop/`) — Compose Multiplatform UI.
  Auto-detects a running daemon on `127.0.0.1:50051`; if none is running,
  spawns the bundled daemon itself (falling back to an OS-assigned port if
  50051 is busy).

- **SwiftUI macOS client** (`macos/`) — Feature-parity native macOS app with
  the same auto-spawn flow, a real macOS File menu (Commands group), and a
  reactive window title that tracks the open catalog.

- **SQLite catalog** — WAL-mode database with FTS5 for full-text search. The
  schema lives in [`core/schema.sql`](core/schema.sql).

## Project status

**MVP is functional on macOS and Compose Desktop.** Both clients ship the
same feature set; the macOS client adds native menu-bar commands and
NSWorkspace-driven editor launches.

### ✅ Done

**Core**
- [x] gRPC daemon with full RPC surface (videos, search, scan, tags,
      collections, stacks, filters, status, config, catalog lifecycle).
- [x] SQLite catalog with WAL mode + FTS5; runtime catalog hot-swap via
      `OpenCatalog` / `CloseCatalog`.
- [x] FFprobe metadata extraction (codec, resolution, FPS, bitrate, HDR,
      EXIF, GPS, camera/lens).
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
cd desktop
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

## Documentation

- [`CLAUDE.md`](CLAUDE.md) — Project vision, architecture details, database
  schema, and design principles.
- [`SETUP.md`](SETUP.md) — Development environment setup.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Detailed reference for the
  Rust core's modules and RPC surface.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Side-by-side comparison
  of the Kotlin and SwiftUI clients.

## Contributing

See [`CLAUDE.md`](CLAUDE.md) for development guidelines. Pull requests
welcome — please keep the two clients in feature-parity, and add SPDX
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
