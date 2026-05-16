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
- [ ] Rust backend scaffolding
- [ ] SQLite schema & migrations
- [ ] Metadata extraction (FFprobe)
- [ ] Thumbnail generation
- [ ] Kotlin Compose UI (grid + detail + search)
- [ ] Tag and collection management
- [ ] External editor integration
- [ ] GitHub auto-update

## Documentation

- [CLAUDE.md](./CLAUDE.md) — Comprehensive project specification and architecture
- [docs/](./docs/) — Detailed documentation (coming soon)

## Getting Started

(Coming soon)

## Contributing

See CLAUDE.md for development guidelines.

## License

(TBD)
