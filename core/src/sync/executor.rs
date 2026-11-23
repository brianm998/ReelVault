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
    ///
    /// `remote_data` is the peer's catalog data (tags, rating, notes, ...) for
    /// the item. When an item adopts a local original, or the peer's marks
    /// revision moved since the last sync, it is merged into the local video
    /// *before* the link's `remote_rev` is advanced, so a failed merge leaves
    /// the link stale and the next plan retries it.
    pub fn apply(
        &self,
        peer_key: &str,
        item: &super::SyncPlanItem,
        remote_data: Option<&crate::db::VideoCatalogData>,
    ) -> Result<()> {
        use super::SyncAction::*;
        let link = |local_video_id: &str| crate::db::SyncLinkParams {
            local_video_id: local_video_id.to_string(),
            peer_key: peer_key.to_string(),
            remote_video_id: item.source_video_id.clone(),
            origin_hash: item.content_hash.clone(),
            is_derived: false,
            derived_height: None,
            local_rev: 0,
            remote_rev: item.remote_marks_rev,
        };
        // Without the peer's data the marks cannot be merged, and advancing
        // the link would mean they never retry (mirrors `reconcile_catalog`).
        if remote_data.is_none()
            && matches!(
                item.action,
                AdoptLocal { .. } | SkipFileReconcileData { marks_changed: true, .. }
            )
        {
            return Err(crate::error::ReelVaultError::VideoNotFound(format!(
                "no catalog data supplied for video {}; link left unchanged",
                item.source_video_id
            )));
        }
        match &item.action {
            AdoptLocal { local_video_id } => {
                // Record the link first at a stale revision (0), then reconcile
                // marks, then advance the revision. A failed merge leaves the
                // link behind but stale, so the next plan reports
                // `SkipFileReconcileData { marks_changed: true }` and retries
                // instead of re-planning a transfer.
                let mut stale = link(local_video_id);
                stale.remote_rev = 0;
                self.update_sync_link_after_reconcile(&stale)?;
                if let Some(d) = remote_data {
                    self.db.apply_video_catalog_data(local_video_id, d)?;
                }
                self.update_sync_link_after_reconcile(&link(local_video_id))
            }
            SkipFileReconcileData { local_video_id, marks_changed: true } => {
                if let Some(d) = remote_data {
                    self.db.apply_video_catalog_data(local_video_id, d)?;
                }
                self.update_sync_link_after_reconcile(&link(local_video_id))
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

        // Without remote data the link must not advance.
        let exec = SyncExecutor::new(db.clone());
        assert!(exec.apply("peer", &plan[0], None).is_err());
        assert!(db.find_sync_link_by_remote("peer", "remote1").unwrap().is_none());

        exec.apply("peer", &plan[0], Some(&data(3))).unwrap();

        let again = planner.classify(&[row], &hits);
        assert_eq!(
            again[0].action,
            SyncAction::SkipFileReconcileData { local_video_id: id, marks_changed: false }
        );
    }

    fn data(rating: i32) -> crate::db::VideoCatalogData {
        crate::db::VideoCatalogData {
            marks_rev: 3,
            tags: vec![],
            tag_colors: vec![],
            collections: vec![],
            smart_collections: vec![],
            rating,
            color_label: String::new(),
            notes: String::new(),
            creation_date_str: String::new(),
            gps_lat: 0.0,
            gps_lon: 0.0,
            has_gps: false,
            group_name: String::new(),
            proxy_of_id: String::new(),
        }
    }

    /// A failed marks merge after adoption leaves a recorded-but-stale link, so
    /// the next plan retries the marks instead of re-copying the file.
    #[test]
    fn adopt_records_link_before_marks_and_failed_merge_is_retried() {
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
        // Data that cannot be applied (rating violates the column's CHECK).
        let bad = data(99);
        let exec = SyncExecutor::new(db.clone());
        assert!(exec.apply("peer", &plan[0], Some(&bad)).is_err());
        {
            let link = db.find_sync_link_by_remote("peer", "remote1").unwrap().expect("link recorded first");
            assert_eq!((link.local_video_id.as_str(), link.remote_rev_at_sync), (id.as_str(), 0));
            let again = planner.classify(&[row.clone()], &hits);
            assert_eq!(
                again[0].action,
                SyncAction::SkipFileReconcileData { local_video_id: id.clone(), marks_changed: true }
            );
        }
        // A good merge then advances the revision.
        let good = data(4);
        exec.apply("peer", &plan[0], Some(&good)).unwrap();
        let link = db.find_sync_link_by_remote("peer", "remote1").unwrap().unwrap();
        assert_eq!(link.remote_rev_at_sync, 3);
        assert_eq!(db.get_video_catalog_data(&id).unwrap().unwrap().rating, 4);
    }
}
