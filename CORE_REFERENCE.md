# Rust Core Reference

## Overview

The Rust backend (`core/`) is a gRPC daemon that manages the VideoRoom catalog. It handles:
- SQLite database persistence
- Video metadata extraction
- Thumbnail generation
- Searching and filtering
- Library management

## Module Structure

### `main.rs`
Entry point. Initializes:
- Logging via `tracing`
- SQLite database
- Configuration loading
- gRPC server startup on `127.0.0.1:50051`

Uses `dirs` crate to store database in platform-specific locations:
- **macOS**: `~/Library/Application Support/VideoRoom/`
- **Linux**: `~/.local/share/videoroom/`
- **Windows**: `%APPDATA%\VideoRoom\`

### `db.rs` — Database Layer
All SQLite operations. Public methods:
- **Videos**: `add_video()`, `get_video()`, `list_videos()`, `delete_video()`, `get_video_by_path()`
- **Tags**: `create_tag()`, `delete_tag()`, `list_tags()`, `tag_video()`, `untag_video()`
- **Collections**: `create_collection()`, `delete_collection()`, `list_collections()`, `add_to_collection()`
- **Notes**: `update_notes()`, `get_notes()`
- **Library Locations**: `add_library_location()`, `remove_library_location()`, `list_library_locations()`

Uses `rusqlite` for database access. WAL mode enabled for concurrent reads.

### `metadata.rs` — FFprobe Integration
Extracts technical metadata from videos. Entry point:
- `MetadataExtractor::extract(video_path)` → calls FFprobe → returns structured data
- `MetadataExtractor::store_metadata()` → writes to database

Parses FFprobe JSON output for:
- **Video**: codec, width, height, FPS, color space, HDR
- **Audio**: codec, channels, sample rate
- **EXIF/Camera**: model, lens, creation date, GPS coordinates
- **Container**: duration, bitrate, size

### `thumbnails.rs` — Thumbnail & Proxy Generation
`ThumbnailGenerator`:
- `generate()` → extracts middle frame → resizes to small/medium/large
- Frame extracted at 50% duration (avoids black frames at start)
- Three sizes: 200px, 400px, 800px (width, height scaled proportionally)

`ProxyGenerator`:
- `needs_proxy()` → checks if video exceeds threshold (4x by default, configurable)
- `generate_proxy()` → encodes half-resolution copy for easier scrubbing
- Uses H.264 codec for compatibility

### `search.rs` — Search & Filtering
Full-text search on filenames, notes, tags.

Public API:
- `SearchEngine::search(query, limit, offset)` → basic text search
- `SearchEngine::advanced_search(filters)` → detailed filtering by resolution, codec, FPS, duration, tags

Filters struct allows combining:
- Filename (substring match)
- Resolution range (min/max width)
- Codec (video or audio)
- Duration range (ms)
- FPS (exact, ±0.5 tolerance)
- Tag IDs (AND logic)

### `indexing.rs` — Directory Scanning
Recursively scans directories for videos.

`IndexingEngine::scan_directory()`:
1. Walk directory tree
2. Find all supported video extensions
3. Extract metadata for each
4. Generate thumbnails
5. Report progress via callback

Supported formats: MP4, MOV, MKV, AVI, WebM, MXF, MPEG, TS, FLV, WMV, etc.

Progress reporting:
- `scanning` → finding files
- `indexing_metadata` → extracting FFprobe data
- `complete` → finished

### `config.rs` — Configuration
Loads/saves configuration from database.

Config struct contains:
- `proxy_threshold_scale` (default: 4)
- `thumbnail_cache_path` (platform-specific)
- `max_concurrent_jobs` (default: 4)
- `enable_auto_tagging` (future: AI features)

### `service.rs` — gRPC Service Implementation
Implements all gRPC methods defined in `proto/videoroom.proto`.

Main methods (Request → Response):
- **Query**: `list_videos()`, `search_videos()`, `get_metadata()`, `get_thumbnail()`
- **Library**: `add_library_location()`, `list_library_locations()`, `scan_library()`
- **Tags**: `create_tag()`, `list_tags()`, `tag_videos()`, `untag_videos()`
- **Collections**: `create_collection()`, `list_collections()`, `add_to_collection()`
- **Metadata**: `update_video_notes()`, `delete_video()`
- **System**: `get_status()`, `get_config()`, `update_config()`

Streaming responses (scan progress, proxy generation) return `tonic::codec::Streaming`.

### `error.rs` — Error Handling
`VideoRoomError` enum maps application errors to gRPC Status codes:
- `VideoNotFound` → Status::NOT_FOUND
- `DatabaseError` → Status::INTERNAL
- `InvalidRequest` → Status::INVALID_ARGUMENT
- `DuplicateEntry` → Status::ALREADY_EXISTS

## Database Schema

### Core Tables
- `videos` — path, filename, hash, file_size_bytes, indexed_at
- `metadata` — technical details (width, height, fps, codec, etc.)
- `thumbnails` — cached preview images (small, medium, large)
- `tags` — tag definitions (name, color)
- `video_tags` — many-to-many video-tag relationships
- `collections` — user-created collections (manual or smart)
- `collection_members` — videos in collections
- `video_notes` — per-video notes/annotations
- `proxies` — lower-resolution copies
- `library_locations` — configured scan roots
- `config` — key-value configuration
- `scan_jobs` — indexing job tracking
- `video_search` (FTS5) — full-text search index

Indexes on frequently queried fields (filename, path, resolution, fps, etc.) for fast lookups.

## Protocol Buffers

`proto/videoroom.proto` defines:
- **Service**: `VideoRoom` with 30+ RPC methods
- **Messages**: Request/response types for each operation
- **Streaming**: `stream ScanProgress`, `stream ThumbnailChunk`, `stream ProxyGenerationProgress`

Generated Rust code goes to `target/debug/videoroom.rs` (via tonic-build).

## Dependencies

Key crates:
- `tokio` — async runtime
- `tonic` / `prost` — gRPC framework
- `rusqlite` — SQLite access
- `chrono` — date/time handling
- `serde_json` — JSON serialization
- `uuid` — ID generation
- `tracing` — structured logging
- `walkdir` — directory traversal
- `which` — executable detection

## Performance Characteristics

**Scan Speed**: ~1-3 sec per video (depends on FFmpeg)
- FFprobe extraction: ~1s
- Thumbnail generation: ~0.5s (parallel possible)
- Database insert: ~0.01s

**Search**: <500ms for 100k catalog (with indexes)

**Thumbnail Cache**: ~50-100 KB per video (3 sizes)

## Building & Testing

```bash
# Check syntax without compiling
cargo check

# Build debug
cargo build

# Build release (optimized)
cargo build --release

# Run tests
cargo test

# Format
cargo fmt

# Lint
cargo clippy
```

## Extending

### Adding a New gRPC Method
1. Add message types to `proto/videoroom.proto`
2. Run `cargo build` to generate Rust code
3. Implement in `service.rs` under `impl VideoRoomService`
4. Add supporting logic in appropriate module (db, metadata, etc.)

### Adding a New Database Table
1. Add SQL to `schema.sql`
2. Add methods to `db.rs`
3. Update error handling as needed

### Adding FFmpeg Processing
1. Create new function in appropriate module
2. Check `Command::new("ffmpeg")`/`Command::new("ffprobe")`
3. Parse output and handle errors
4. Return Results that convert to gRPC Status

## Known Limitations

- Single-instance only (one daemon per machine)
- No network/remote catalogs yet (post-MVP)
- No real-time file watching yet (post-MVP)
- Proxy generation not yet integrated (post-MVP)
- Auto-tagging (AI) not yet implemented (future)
- Scan progress streaming needs refinement
