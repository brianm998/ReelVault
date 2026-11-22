// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Catalog-sync executor: applies decisions produced by the [`Planner`] to
//! the local database. Full orchestration (file transfer, progress reporting)
//! lives in the client-side sync engine; this module handles only the
//! database mutations that must happen on the daemon side.

use crate::db::Database;
use crate::error::Result;
use std::sync::Arc;

/// Executes sync plan decisions against the local [`Database`].
pub struct SyncExecutor {
    pub db: Arc<Database>,
}

impl SyncExecutor {
    pub fn new(db: Arc<Database>) -> Self {
        Self { db }
    }

    /// Soft-delete a video on behalf of a tombstone received from the sync source.
    /// Sets `is_online = 0`; the catalog row stays intact.
    pub fn soft_delete(&self, local_video_id: &str) -> crate::error::Result<()> {
        self.db.soft_delete_synced_video(local_video_id)
    }

    /// Record that a local video corresponds to a remote peer video.
    /// Call this after a successful file transfer or hash-adoption reconcile
    /// so subsequent syncs skip the transfer step.
    pub fn update_sync_link_after_reconcile(&self, params: &crate::db::SyncLinkParams) -> Result<()> {
        self.db.add_sync_link(params)
    }

    /// Apply the database-side effect of one planned item. Transfers
    /// (`CopyNew`) are the client's job and are a no-op here.
    pub fn apply(&self, peer_key: &str, item: &super::SyncPlanItem) -> Result<()> {
        use super::SyncAction::*;
        match &item.action {
            AdoptLocal { local_video_id } => {
                self.update_sync_link_after_reconcile(&crate::db::SyncLinkParams {
                    local_video_id: local_video_id.clone(),
                    peer_key: peer_key.to_string(),
                    remote_video_id: item.source_video_id.clone(),
                    origin_hash: item.content_hash.clone(),
                    is_derived: false,
                    derived_height: None,
                    local_rev: 0,
                    remote_rev: item.remote_marks_rev,
                })
            }
            SoftDelete { local_video_id: Some(id) } => self.soft_delete(id),
            _ => Ok(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sync::{Planner, SyncAction};

    #[test]
    fn adoption_links_then_next_plan_skips_transfer() {
        let tmp = tempfile::tempdir().unwrap();
        let db = Database::new_empty();
        db.set_path(&tmp.path().join("c.db")).unwrap();
        let db = Arc::new(db);
        let id = db.add_video("/lib/a.mov", "a.mov", None, None, Some(10)).unwrap();
        db.set_content_hash(&id, "h1").unwrap();

        let row = crate::db::SyncManifestRow {
            video_id: "remote1".into(),
            content_hash: Some("h1".into()),
            marks_rev: 3,
            size_bytes: 10,
            duration_ms: 0,
            filename: "a.mov".into(),
            width: 0,
            height: 0,
            codec_video: String::new(),
            is_derived: false,
            proxy_of: None,
            has_streamable_proxy: false,
            deleted_at: None,
        };
        let planner = Planner::new(db.clone(), "peer".into());
        let hits = db.lookup_by_content_hash(&["h1".to_string()]).unwrap();
        let plan = planner.classify(&[row.clone()], &hits);
        assert_eq!(plan[0].action, SyncAction::AdoptLocal { local_video_id: id.clone() });

        SyncExecutor::new(db.clone()).apply("peer", &plan[0]).unwrap();

        let again = planner.classify(&[row], &hits);
        assert_eq!(
            again[0].action,
            SyncAction::SkipFileReconcileData { local_video_id: id, marks_changed: false }
        );
    }
}
