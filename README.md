# VideoRoom

A cross-platform video cataloging application inspired by Lightroom—fast, native browsing of large video libraries.

**VideoRoom is for organizing, discovering, and managing videos. It is NOT a video editor.**

## Overview

VideoRoom helps you:
- **Browse** thousands of videos in a responsive grid view
- **Discover** content through tags, collections, and full-text search
- **Inspect** detailed metadata (codec, resolution, FPS, EXIF, GPS)
- **Organize** videos with tags and collections
- **Launch** videos in external editing tools (DaVinci Resolve, Premiere, VLC, etc.)

## Architecture

- **Rust Backend**: Central daemon managing catalog, metadata, thumbnails, and file watching
- **Kotlin Compose Client**: Cross-platform desktop UI (MVP priority)
- **SwiftUI macOS Client**: Native macOS experience (post-MVP)
- **SQLite Database**: Local catalog with full-text search

## Project Status

**Phase 1 (MVP)** — In Progress

### ✅ Completed (Rust Core)
- [x] Rust backend daemon scaffolding
- [x] SQLite database with complete schema (videos, metadata, tags, collections, proxies, FTS5)
- [x] gRPC API specification and implementation
- [x] FFprobe integration for metadata extraction (codec, resolution, FPS, EXIF, GPS)
- [x] Thumbnail generation pipeline (multiple resolutions)
- [x] Video library scanning and indexing
- [x] Full-text search with advanced filtering
- [x] Tag and collection management
- [x] Configuration management
- [x] Error handling and logging

### 🚧 In Progress / Planned
- [ ] Kotlin Compose desktop client (UI: grid + detail + search)
- [ ] macOS SwiftUI client (post-MVP)
- [ ] Real-time file watching
- [ ] Proxy video generation (for 4K+ footage)
- [ ] External editor integration
- [ ] GitHub auto-update system
- [ ] CI/CD pipeline

## Documentation

- [CLAUDE.md](./CLAUDE.md) — Complete project vision, architecture, and specifications
- [SETUP.md](./SETUP.md) — Development environment setup and build instructions
- [Architecture Overview](#architecture)

## Getting Started

1. **Setup Development Environment**
   ```bash
   # Install Rust, FFmpeg
   # See SETUP.md for detailed instructions
   ```

2. **Build the Core**
   ```bash
   cd core
   cargo build --release
   ./target/release/videoroom-core
   ```
   The daemon listens on `127.0.0.1:50051` for gRPC connections.

3. **Build Desktop Client** (next phase)
   ```bash
   cd desktop
   # Will be Kotlin Compose project structure
   ```

## Architecture

The system is built in three tiers:

```
Frontend Clients (Kotlin/SwiftUI)
        ↓ (gRPC)
Rust Backend Daemon (core/)
        ↓
SQLite Database + Filesystem
```

**Core** (`core/`): Rust daemon providing:
- Video indexing and metadata extraction
- SQLite persistence with WAL mode
- FFmpeg/FFprobe integration
- Thumbnail generation
- Search and filtering
- gRPC API

**Desktop** (`desktop/`): Kotlin Compose client (MVP priority)
- Grid view with virtualized rendering
- Detail panel with metadata
- Search and filtering
- Tag/collection management

**macOS** (`macos/`): SwiftUI client (post-MVP)
- Native macOS experience
- QuickLook integration
- Feature parity with desktop

## Contributing

See CLAUDE.md for development guidelines.

## License

VideoRoom is free software, licensed under the **GNU General Public License,
version 3 or (at your option) any later version**. The full license text
lives in [`LICENSE`](LICENSE); a short copyright notice is in
[`COPYRIGHT`](COPYRIGHT).

Every source file carries an SPDX identifier so license-scanning tools
(REUSE, FOSSology, GitHub's licensee detector, etc.) can identify the
license programmatically:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors
```

If you distribute a modified version of VideoRoom — or any program that
links against the Rust core as a library — the GPL requires you to make
your source available under the same terms. See the LICENSE file for the
full set of obligations.
