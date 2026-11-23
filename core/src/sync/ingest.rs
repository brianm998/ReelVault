// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Ingest of a file pulled from a sync peer, with provenance stamping.

use crate::db::Database;
use crate::error::Result;
use std::path::{Path, PathBuf};

/// Index the downloaded file at `path` into `db` and stamp its provenance:
/// `origin_hash` is the peer original's content hash and `derived_height` the
/// height of a downscaled copy (0 = the original itself). Only a downscaled
/// copy is "derived"; a full-size pull stays visible to the manifest/planner.
/// Returns the new local video id so the caller can record a sync link and
/// `ApplyVideoCatalogData`. Shared by the embedded FFI entry points and tests.
pub fn ingest_synced_into(
    db: &Database,
    cache: &Path,
    events: &tokio::sync::broadcast::Sender<crate::watcher::CatalogChange>,
    path: &str,
    filename: &str,
    origin_hash: &str,
    derived_height: i32,
) -> Result<String> {
    if path.is_empty() {
        return Err(crate::error::ReelVaultError::MetadataExtractionFailed("empty path".into()));
    }
    let filename_owned = if filename.is_empty() {
        Path::new(path).file_name().and_then(|n| n.to_str()).unwrap_or("video").to_string()
    } else {
        filename.to_string()
    };
    let source = crate::media_backend::MediaSource::Path(PathBuf::from(path));
    let video_id = crate::indexing::IndexingEngine::index_media_source(
        db, &source, path, &filename_owned, "path", path, cache,
    )?;
    // The file is already indexed; a stamping failure must not turn that into
    // an error (a retry would hit the same path), so warn and continue.
    // (The old `video_locations` size UPDATE referenced a nonexistent
    // `length_via_stat` column, always failed with its error ignored, and is
    // intentionally not carried over; indexing records the size itself.)
    match db.get_connection() {
        Ok(conn) => {
            if let Err(e) = conn.execute(
                "UPDATE videos SET is_derived = ?3, origin_hash = ?1 WHERE id = ?2",
                rusqlite::params![origin_hash, &video_id, (derived_height > 0) as i32],
            ) {
                tracing::warn!("ingest_synced: could not stamp provenance for {video_id}: {e}");
            }
        }
        Err(e) => tracing::warn!("ingest_synced: no connection to stamp {video_id}: {e}"),
    }
    let _ = events.send(crate::watcher::CatalogChange::VideoAdded {
        video_id: video_id.clone(),
        path: PathBuf::from(path),
    });
    Ok(video_id)
}
