-- ReelVault Database Schema
-- SQLite with WAL mode for safe concurrent access

-- Library locations
CREATE TABLE IF NOT EXISTS library_locations (
  id TEXT PRIMARY KEY,
  path TEXT UNIQUE NOT NULL,
  recursive INTEGER NOT NULL DEFAULT 1,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  last_scanned TIMESTAMP
);

-- Videos
CREATE TABLE IF NOT EXISTS videos (
  id TEXT PRIMARY KEY,
  path TEXT UNIQUE NOT NULL,
  filename TEXT NOT NULL,
  volume_id TEXT,
  hash TEXT UNIQUE,
  file_size_bytes INTEGER,
  indexed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP,
  modified_at TIMESTAMP,
  is_online INTEGER DEFAULT 1,
  group_id TEXT,
  group_position INTEGER NOT NULL DEFAULT 0  -- order within the stack (drag-to-reorder)
);

-- Video groups (Lightroom-style "stacks" of related variants)
CREATE TABLE IF NOT EXISTS video_groups (
  id TEXT PRIMARY KEY,
  name TEXT,
  base_name TEXT,
  preferred_video_id TEXT,  -- which member to show / open by default
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(preferred_video_id) REFERENCES videos(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_videos_group_id ON videos(group_id);
CREATE INDEX IF NOT EXISTS idx_video_groups_base_name ON video_groups(base_name);

CREATE INDEX IF NOT EXISTS idx_videos_filename ON videos(filename);
CREATE INDEX IF NOT EXISTS idx_videos_path ON videos(path);
CREATE INDEX IF NOT EXISTS idx_videos_hash ON videos(hash);
CREATE INDEX IF NOT EXISTS idx_videos_indexed_at ON videos(indexed_at);

-- Video metadata (technical details from FFprobe + photo-EXIF from embedded XMP)
CREATE TABLE IF NOT EXISTS metadata (
  video_id TEXT PRIMARY KEY,
  duration_ms INTEGER,
  frame_count INTEGER DEFAULT 0,
  codec_video TEXT,
  codec_audio TEXT,
  width INTEGER,
  height INTEGER,
  fps REAL,
  bitrate INTEGER,
  color_space TEXT,
  color_transfer TEXT,   -- transfer characteristic / EOTF (bt709, smpte2084=PQ, arib-std-b67=HLG)
  color_primaries TEXT,  -- color primaries (bt709, bt2020, …)
  dynamic_range TEXT,    -- derived facet label: "SDR" / "HDR (PQ)" / "HDR (HLG)" / "Log (S-Log3)" / "RAW"
  hdr INTEGER DEFAULT 0,  -- derived: 1 when color_transfer is an HDR EOTF
  bit_depth INTEGER,      -- coded video bit depth (8/10/12/16)
  spatial INTEGER DEFAULT 0,  -- 1 for stereoscopic MV-HEVC (Apple Vision Pro spatial) video
  projection TEXT,        -- spherical/360 projection ("equirectangular", …); NULL = not 360
  capture_fps REAL,       -- sensor capture rate; > fps means slow-motion
  timecode_start TEXT,    -- SMPTE start timecode from the tmcd track ("HH:MM:SS:FF")
  audio_channels INTEGER,
  audio_sample_rate INTEGER,
  audio_bit_depth INTEGER,      -- PCM sample depth (24 for pro recorders); NULL for compressed
  audio_language TEXT,          -- primary audio track language (BCP-47/ISO code); NULL when "und"
  audio_track_count INTEGER,    -- number of audio tracks (iPhone spatial clips have 2)
  creation_date TIMESTAMP,
  camera_model TEXT,
  lens_model TEXT,
  gps_latitude REAL,
  gps_longitude REAL,
  gps_altitude REAL,
  -- Photo-style EXIF, sourced from an embedded XMP packet (read by xmp.rs).
  -- These are populated for videos whose encoder wrote XMP-EXIF into the
  -- MP4/MOV (e.g. via exiftool); NULL otherwise. ffprobe doesn't surface
  -- XMP, so these columns are independent of the ffprobe-derived ones.
  iso INTEGER,
  aperture REAL,
  exposure_time_s REAL,
  focal_length_mm REAL,
  exposure_mode TEXT,
  exposure_program TEXT,
  white_balance TEXT,
  metadata_json TEXT,
  extracted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(video_id) REFERENCES videos(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_metadata_resolution ON metadata(width, height);
CREATE INDEX IF NOT EXISTS idx_metadata_fps ON metadata(fps);
CREATE INDEX IF NOT EXISTS idx_metadata_duration ON metadata(duration_ms);
CREATE INDEX IF NOT EXISTS idx_metadata_codec_video ON metadata(codec_video);
-- NOTE: indexes on XMP/EXIF columns (iso, aperture, exposure_time_s,
-- focal_length_mm) are created via the migrations array in db.rs so that
-- they run AFTER the ALTER TABLE ADD COLUMN steps on existing catalogs.
-- Do NOT add them here — schema.sql runs before migrations and would fail
-- on any catalog that pre-dates those columns.

-- Thumbnails
CREATE TABLE IF NOT EXISTS thumbnails (
  id TEXT PRIMARY KEY,
  video_id TEXT NOT NULL,
  size TEXT NOT NULL,  -- "small", "medium", "large"
  path TEXT NOT NULL,
  generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(video_id) REFERENCES videos(id) ON DELETE CASCADE,
  UNIQUE(video_id, size)
);

CREATE INDEX IF NOT EXISTS idx_thumbnails_video_id ON thumbnails(video_id);

-- Tags
CREATE TABLE IF NOT EXISTS tags (
  id TEXT PRIMARY KEY,
  name TEXT UNIQUE NOT NULL,
  color TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tags_name ON tags(name);

-- Video-Tag relationships
CREATE TABLE IF NOT EXISTS video_tags (
  video_id TEXT NOT NULL,
  tag_id TEXT NOT NULL,
  added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(video_id, tag_id),
  FOREIGN KEY(video_id) REFERENCES videos(id) ON DELETE CASCADE,
  FOREIGN KEY(tag_id) REFERENCES tags(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_video_tags_tag_id ON video_tags(tag_id);

-- Collections (manual or smart)
CREATE TABLE IF NOT EXISTS collections (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  is_smart INTEGER DEFAULT 0,
  filter_json TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_collections_name ON collections(name);

-- Collection members
CREATE TABLE IF NOT EXISTS collection_members (
  collection_id TEXT NOT NULL,
  video_id TEXT NOT NULL,
  added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(collection_id, video_id),
  FOREIGN KEY(collection_id) REFERENCES collections(id) ON DELETE CASCADE,
  FOREIGN KEY(video_id) REFERENCES videos(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_collection_members_collection_id ON collection_members(collection_id);
CREATE INDEX IF NOT EXISTS idx_collection_members_video_id ON collection_members(video_id);

-- Video notes and metadata
CREATE TABLE IF NOT EXISTS video_notes (
  video_id TEXT PRIMARY KEY,
  notes TEXT,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(video_id) REFERENCES videos(id) ON DELETE CASCADE
);

-- Per-video user marks: Lightroom-style star rating (0..5) and color label.
-- Lives separately from `metadata` (which is FFprobe-derived, machine-extracted)
-- to keep human/user data and technical data clearly separated. One row per
-- video; missing row → rating 0, no color label.
CREATE TABLE IF NOT EXISTS video_user_marks (
  video_id     TEXT PRIMARY KEY,
  rating       INTEGER NOT NULL DEFAULT 0 CHECK (rating BETWEEN 0 AND 5),
  color_label  TEXT NOT NULL DEFAULT '',  -- '' | red | yellow | green | blue | purple
  updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(video_id) REFERENCES videos(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_video_user_marks_rating ON video_user_marks(rating);
CREATE INDEX IF NOT EXISTS idx_video_user_marks_color  ON video_user_marks(color_label);

-- Proxies (lower resolution versions for large videos)
CREATE TABLE IF NOT EXISTS proxies (
  id TEXT PRIMARY KEY,
  video_id TEXT NOT NULL,
  resolution_scale REAL NOT NULL,  -- 0.5 = half resolution
  path TEXT NOT NULL UNIQUE,
  size_bytes INTEGER,
  generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(video_id) REFERENCES videos(id) ON DELETE CASCADE,
  UNIQUE(video_id, resolution_scale)
);

CREATE INDEX IF NOT EXISTS idx_proxies_video_id ON proxies(video_id);

-- Configuration
CREATE TABLE IF NOT EXISTS config (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Full-text search virtual table
CREATE VIRTUAL TABLE IF NOT EXISTS video_search USING fts5(
  filename,
  notes,
  tags,
  camera_model,
  content=videos,
  content_rowid=id
);

-- Triggers to keep FTS index in sync
CREATE TRIGGER IF NOT EXISTS video_search_ai AFTER INSERT ON videos BEGIN
  INSERT INTO video_search(rowid, filename) VALUES (new.rowid, new.filename);
END;

CREATE TRIGGER IF NOT EXISTS video_search_ad AFTER DELETE ON videos BEGIN
  INSERT INTO video_search(video_search, rowid, filename) VALUES('delete', old.rowid, old.filename);
END;

CREATE TRIGGER IF NOT EXISTS video_search_au AFTER UPDATE ON videos BEGIN
  INSERT INTO video_search(video_search, rowid, filename) VALUES('delete', old.rowid, old.filename);
  INSERT INTO video_search(rowid, filename) VALUES (new.rowid, new.filename);
END;

-- User-named locations (e.g. "Yosemite Valley Visitor Center" → 37.7459, -119.5936).
-- Independent table — no foreign key to videos — because a single named place
-- typically covers many videos taken nearby, and the lat/lon → name lookup is
-- a proximity search rather than a per-video join. The clients use a 250 m
-- default match radius to resolve a video's GPS into a name.
CREATE TABLE IF NOT EXISTS named_locations (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  -- Stored so future versions can offer larger custom radii ("Yellowstone")
  -- without breaking older clients. Defaults to the protocol-wide 250 m.
  radius_m REAL NOT NULL DEFAULT 250,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Bounding-box indexes — proximity lookups filter on lat ± dlat and
-- lon ± dlon before computing the precise haversine, so these indexes turn
-- the scan into a tight range query even on catalogs with thousands of
-- named places.
CREATE INDEX IF NOT EXISTS idx_named_locations_lat ON named_locations(latitude);
CREATE INDEX IF NOT EXISTS idx_named_locations_lon ON named_locations(longitude);

-- Scan jobs tracking
CREATE TABLE IF NOT EXISTS scan_jobs (
  id TEXT PRIMARY KEY,
  location_path TEXT NOT NULL,
  status TEXT NOT NULL,  -- "pending", "running", "completed", "failed"
  started_at TIMESTAMP,
  completed_at TIMESTAMP,
  videos_found INTEGER DEFAULT 0,
  videos_indexed INTEGER DEFAULT 0,
  error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_scan_jobs_status ON scan_jobs(status);
CREATE INDEX IF NOT EXISTS idx_scan_jobs_started_at ON scan_jobs(started_at);
