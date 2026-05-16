-- VideoRoom Database Schema
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
  is_online INTEGER DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_videos_filename ON videos(filename);
CREATE INDEX IF NOT EXISTS idx_videos_path ON videos(path);
CREATE INDEX IF NOT EXISTS idx_videos_hash ON videos(hash);
CREATE INDEX IF NOT EXISTS idx_videos_indexed_at ON videos(indexed_at);

-- Video metadata (technical details from FFprobe)
CREATE TABLE IF NOT EXISTS metadata (
  video_id TEXT PRIMARY KEY,
  duration_ms INTEGER,
  codec_video TEXT,
  codec_audio TEXT,
  width INTEGER,
  height INTEGER,
  fps REAL,
  bitrate INTEGER,
  color_space TEXT,
  hdr INTEGER DEFAULT 0,
  audio_channels INTEGER,
  audio_sample_rate INTEGER,
  creation_date TIMESTAMP,
  camera_model TEXT,
  lens_model TEXT,
  gps_latitude REAL,
  gps_longitude REAL,
  gps_altitude REAL,
  metadata_json TEXT,
  extracted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(video_id) REFERENCES videos(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_metadata_resolution ON metadata(width, height);
CREATE INDEX IF NOT EXISTS idx_metadata_fps ON metadata(fps);
CREATE INDEX IF NOT EXISTS idx_metadata_duration ON metadata(duration_ms);
CREATE INDEX IF NOT EXISTS idx_metadata_codec_video ON metadata(codec_video);

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
