# ReelVault: A Cross-Platform Video Cataloging Application

## Project Vision

ReelVault is a desktop application for organizing, discovering, and managing large video libraries—inspired by Adobe Lightroom, but focused exclusively on **cataloging and discovery rather than editing**.

The core philosophy: **fast, native browsing of massive video collections without bloat**.

## What ReelVault Is
- A video library manager with fast grid browsing
- Metadata extractor and organizer
- Tag, collection, and search engine
- Thumbnail preview generator
- Drag-and-drop hand-off to external editors (no launching from ReelVault)
- Cross-platform (macOS, Linux, Windows)

## What ReelVault Is NOT
- A video editor
- A color grading tool
- A timeline tool
- A transcoding application

---

## Architecture

### Three-Tier Design

```
┌──────────────────────────────────────────────────────────────┐
│                      Frontend Clients                        │
├──────────────┬──────────────────┬──────────────┬────────────┤
│ SwiftUI      │ SwiftUI iOS      │ Kotlin        │ Kotlin     │
│ macOS        │ (iPhone/iPad)    │ Compose       │ Compose    │
│              │                  │ (Linux/Win/   │ Android    │
│              │                  │  macOS)       │            │
└──────┬───────┴────────┬─────────┴──────┬────────┴─────┬──────┘
       │ IPC / gRPC     │                │              │
       └────────────────┴────────┬───────┘──────────────┘
                                 │
                       ┌─────────▼─────────┐
                       │ Rust Core Daemon  │
                       │ ─────────────────  │
                       │ • SQLite Database │
                       │ • Metadata Ext.   │
                       │ • Thumbnails      │
                       │ • File Watching   │
                       │ • Search          │
                       └───────────────────┘
                                 │
                       ┌─────────▼─────────┐
                       │ Filesystem        │
                       │ Video Storage     │
                       └───────────────────┘
```

### Core Components

**Rust Backend (`core/`)**
- Central daemon process managing all catalog state
- SQLite database with WAL mode for safe concurrent access
- FFmpeg/FFprobe integration for metadata extraction
- Thumbnail generation pipeline (frame extraction)
- File system watcher for real-time updates
- IPC/gRPC interface for frontend clients

**macOS Client (`macos/`)**
- SwiftUI-based native application
- QuickLook and Finder integration (post-MVP)
- Premium, highly optimized experience

**Desktop Client (`desktop/`)**
- Kotlin Compose for cross-platform support
- Works on Linux, Windows, and macOS
- MVP priority

**Android Client (`android/`)**
- Kotlin Compose for Android
- Remote-only — connects to a core daemon over the LAN (no embedded daemon)
- ExoPlayer for video playback
- NSD (Network Service Discovery) for daemon discovery
- Android share intent for editor hand-off

---

## Database Schema (SQLite)

### Core Tables

```sql
-- Videos
CREATE TABLE videos (
  id INTEGER PRIMARY KEY,
  path TEXT UNIQUE NOT NULL,
  filename TEXT NOT NULL,
  volume_id INTEGER,  -- Track removable drives
  hash TEXT,          -- For duplicate detection
  indexed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  metadata JSONB      -- Cache extracted technical metadata
);

-- Metadata (technical details from FFprobe)
CREATE TABLE metadata (
  id INTEGER PRIMARY KEY,
  video_id INTEGER UNIQUE,
  duration_ms INTEGER,
  codec_video TEXT,
  codec_audio TEXT,
  width INTEGER,
  height INTEGER,
  fps REAL,
  bitrate INTEGER,
  color_space TEXT,
  hdr BOOLEAN,
  audio_channels INTEGER,
  creation_date TIMESTAMP,
  camera_model TEXT,
  lens_model TEXT,
  gps_lat REAL,
  gps_lon REAL,
  -- ... more fields as needed
  FOREIGN KEY(video_id) REFERENCES videos(id)
);

-- Tags
CREATE TABLE tags (
  id INTEGER PRIMARY KEY,
  name TEXT UNIQUE NOT NULL,
  color TEXT
);

CREATE TABLE video_tags (
  video_id INTEGER,
  tag_id INTEGER,
  PRIMARY KEY(video_id, tag_id),
  FOREIGN KEY(video_id) REFERENCES videos(id),
  FOREIGN KEY(tag_id) REFERENCES tags(id)
);

-- Collections
CREATE TABLE collections (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  is_smart BOOLEAN DEFAULT 0,
  filter_json TEXT  -- Smart collection rules
);

CREATE TABLE collection_members (
  collection_id INTEGER,
  video_id INTEGER,
  PRIMARY KEY(collection_id, video_id),
  FOREIGN KEY(collection_id) REFERENCES collections(id),
  FOREIGN KEY(video_id) REFERENCES videos(id)
);

-- Proxies (lower-res versions for large videos)
CREATE TABLE proxies (
  id INTEGER PRIMARY KEY,
  video_id INTEGER,
  resolution_scale REAL,  -- 0.5 = half resolution
  path TEXT,
  size_bytes INTEGER,
  FOREIGN KEY(video_id) REFERENCES videos(id)
);

-- Library locations
CREATE TABLE library_locations (
  id INTEGER PRIMARY KEY,
  path TEXT UNIQUE NOT NULL,
  recursive BOOLEAN DEFAULT 1,
  enabled BOOLEAN DEFAULT 1
);
```

### Full-Text Search (FTS5)

```sql
CREATE VIRTUAL TABLE video_search USING fts5(
  filename,
  notes,
  tags,
  content=videos,
  content_rowid=id
);
```

---

## Feature Phases

### MVP (Phase 1) — Foundation
- [x] Project scaffolding
- [ ] Rust backend with SQLite
- [ ] Basic metadata extraction (FFprobe)
- [ ] Thumbnail generation pipeline
- [ ] Kotlin Compose desktop client
  - Grid view with virtualized scrolling
  - Detail/preview panel
  - Metadata inspector
  - Tag management
  - Basic search
- [ ] Tagging and collections
- [ ] Auto-update via GitHub releases

### Phase 2 — Polish
- [ ] macOS SwiftUI client (feature parity with desktop)
- [ ] Real-time file watching
- [ ] Smart collections
- [ ] Saved searches
- [ ] Performance optimizations
- [ ] Batch operations

### Phase 3 — Advanced
- [ ] AI auto-tagging (object detection, faces)
- [ ] Proxy video generation (for videos > 4x resolution)
- [ ] Speech transcription & search
- [ ] OCR for text in videos
- [ ] Remote libraries and sync

---

## Development Guidelines

### Code Organization

```
ReelVault/
├── core/                   # Rust daemon
│   ├── Cargo.toml
│   ├── src/
│   │   ├── main.rs
│   │   ├── db.rs
│   │   ├── metadata.rs
│   │   ├── thumbnails.rs
│   │   ├── indexing.rs
│   │   ├── search.rs
│   │   └── ipc.rs
│   └── schema.sql
├── desktop/               # Kotlin Compose client
│   ├── build.gradle.kts
│   ├── src/main/kotlin/
│   │   ├── ui/
│   │   ├── viewmodel/
│   │   ├── data/
│   │   └── util/
│   └── resources/
├── android/               # Kotlin Compose Android client
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/reelvault/android/
├── shared/                # Shared Kotlin library (desktop + android)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/reelvault/
├── macos/                # SwiftUI macOS client (SwiftPM; depends on kit/)
│   ├── Package.swift
│   └── ReelVault/
├── ios/                  # SwiftUI iOS client (XcodeGen; remote-only; depends on kit/)
│   ├── project.yml       #   .xcodeproj is generated, not committed
│   └── ReelVaultiOS/
├── kit/                  # ReelVaultKit — shared Swift used by macOS + iOS:
│   └── Sources/          #   models, view-models, gRPC client, discovery,
│                         #   pinned TLS, media cache/client
├── docs/
│   ├── ARCHITECTURE.md
│   ├── DATABASE.md
│   ├── API.md
│   └── IOS_CORE_PORT.md  # iOS on-device port design (proposal)
├── .github/
│   └── workflows/        # CI/CD
└── CLAUDE.md             # This file
```

### UI/UX Consistency

**Default rule:** Unless explicitly stated otherwise, every feature must be
implemented in **all four clients** — Kotlin Compose desktop, Kotlin Compose
Android, SwiftUI macOS, and SwiftUI iOS (iPhone/iPad) — as part of the same
change, **where appropriate to the platform**. A feature landing in only some
clients is incomplete.

Legitimate per-platform deviations (call them out in the commit/PR so reviewers
don't push back on a "missing" client):
- **iOS replaces drag-and-drop editor hand-off with the share sheet.**
- **iOS is remote-only** — it talks to a core daemon over the LAN, has no local
  filesystem access to originals, and (for now) embeds no daemon; features that
  assume a shared filesystem differ or don't apply.
- **Android is remote-only** — like iOS, it connects to a core daemon over the
  LAN and embeds no daemon; features that assume a shared filesystem differ or
  don't apply.
- **Android replaces drag-and-drop editor hand-off with Android's share intent.**
- **Android uses ExoPlayer** for video playback (not VLCJ).
- **Android uses NSD** (Network Service Discovery) for daemon discovery (not jmdns).
- **Keyboard shortcuts** apply to macOS and iPad-with-a-keyboard, not iPhone or
  Android phones.
- **Layout**: iPhone-portrait and Android-phone use sheets / compact navigation
  instead of the desktop's resizable side panels; iPad and macOS share the
  multi-column layout; Android tablets may use the multi-column layout.
- Platform-only integrations (macOS QuickLook, Linux inotify tuning, …).

The other exception is **client-specific bugs**: a fix for a defect in one
client needs no mirror in the others.

Shared Swift code (macOS + iOS) lives in **`kit/` (ReelVaultKit)** — models,
view-models, the gRPC client, discovery, pinned TLS, and the media cache — so a
change there serves both Apple clients at once.

All clients should share (platform-appropriate):
- Grid layout with thumbnail preview
- Identical metadata inspector
- Same tag/collection management workflows
- Consistent keyboard shortcuts (macOS + iPad)
- Native file pickers / share affordances

### Design Principles

1. **Native First**: Use platform conventions (macOS native, Linux Gtk/Qt-like, Windows Fluent)
2. **Performance**: All blocking operations run in the Rust backend
3. **No UI Lockups**: Frontend stays responsive during scans, searches, thumbnail generation
4. **Keyboard Friendly**: Power users should rarely touch the mouse
5. **Simple First**: Don't design for hypothetical features

---

## IPC/gRPC Interface

The Rust backend exposes a simple gRPC API for frontends:

```proto
service ReelVault {
  // Query
  rpc ListVideos(ListVideosRequest) returns (ListVideosResponse);
  rpc SearchVideos(SearchRequest) returns (SearchResponse);
  rpc GetMetadata(GetMetadataRequest) returns (Metadata);
  
  // Library Management
  rpc ScanLibrary(ScanLibraryRequest) returns (stream ScanProgress);
  rpc AddLibraryLocation(AddLocationRequest) returns (AddLocationResponse);
  
  // Tags & Collections
  rpc AddTag(AddTagRequest) returns (TagResponse);
  rpc TagVideos(TagVideosRequest) returns (TagVideosResponse);
  rpc CreateCollection(CreateCollectionRequest) returns (CollectionResponse);
  rpc AddToCollection(AddToCollectionRequest) returns (Response);
  
  // Thumbnails
  rpc GetThumbnail(GetThumbnailRequest) returns (stream ThumbnailData);
}
```

Editor hand-off is done via drag-and-drop from the grid into the target
application — ReelVault does not launch external editors itself.

---

## Testing Strategy

- **Unit Tests**: Rust core (metadata extraction, search, database operations)
- **Integration Tests**: Backend ↔ Frontend via IPC
- **UI Tests**: Kotlin Compose and SwiftUI snapshot tests
- **Performance Tests**: Large library benchmarks (100k+ videos)

---

## Release & Distribution

- GitHub releases for version management
- Built binaries for macOS, Linux, Windows
- Auto-update mechanism checking GitHub API
- User can configure auto-update frequency

---

## Future Extensibility

Design the core to support:
- Plugin API for custom metadata extractors
- Scriptable metadata operations
- Webhook support for external integrations
- Multi-user shared catalogs (post-MVP)

---

## Key Decisions

1. **SQLite over alternatives**: Single-file database, proven for catalogs (Lightroom, Photos app)
2. **Rust backend**: Type safety, performance, cross-platform
3. **Two frontends**: macOS gets native Swift, others get Compose
4. **No editing**: Clarity of purpose, easier to maintain
5. **Drag-and-drop hand-off**: Reuse existing professional tools by dragging clips out of ReelVault; the app does not launch editors itself
6. **Proxy generation (configurable)**: Performance on 8K+ footage

---

## Success Metrics

- Browse 100,000 videos without lag
- Scan 10TB library in < 5 minutes (incremental)
- Thumbnails generate < 1 second per video on average
- Responsive grid scrolling at 60 FPS
- Search returns results in < 500ms for 100k catalog
