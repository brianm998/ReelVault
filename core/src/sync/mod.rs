// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

pub mod executor;
pub mod ingest;
pub mod planner;

pub use executor::SyncExecutor;
pub use ingest::ingest_synced_into;
pub use planner::{Planner, SyncAction, SyncPlan, SyncPlanItem};

use crate::db::{Database, SyncManifestRow, VideoCatalogData};
use crate::error::Result;
use std::sync::Arc;

/// Outcome of one [`reconcile_catalog`] pass.
#[derive(Debug, Default, PartialEq)]
pub struct ReconcileSummary {
    pub adopted: usize,
    pub marks_reconciled: usize,
    pub soft_deleted: usize,
    /// Remote video ids whose bytes must be transferred by the client
    /// (the file itself moves through `/upload` / `/video`).
    pub to_copy: Vec<String>,
    pub ambiguous: usize,
    pub deferred: usize,
}

/// Plan and apply the database half of a sync against one peer's manifest:
/// adopt local originals by content hash, merge catalog data, honour
/// tombstones. `fetch_remote_data` returns the peer's catalog data for a
/// remote video id (a `GetVideoCatalogData` call when wired to a peer).
/// The returned `to_copy` ids still need a byte transfer.
pub fn reconcile_catalog(
    db: &Arc<Database>,
    peer_key: &str,
    manifest: &[SyncManifestRow],
    mut fetch_remote_data: impl FnMut(&str) -> Result<Option<VideoCatalogData>>,
) -> Result<ReconcileSummary> {
    let hashes: Vec<String> = manifest.iter().filter_map(|r| r.content_hash.clone()).collect();
    let hits = db.lookup_by_content_hash(&hashes)?;
    let plan = Planner::new(db.clone(), peer_key.to_string()).classify(manifest, &hits);
    let exec = SyncExecutor::new(db.clone());
    let mut out = ReconcileSummary::default();
    for item in &plan {
        let needs_data = matches!(
            item.action,
            SyncAction::AdoptLocal { .. } | SyncAction::SkipFileReconcileData { marks_changed: true, .. }
        );
        let data = if needs_data { fetch_remote_data(&item.source_video_id)? } else { None };
        exec.apply(peer_key, item, data.as_ref())?;
        match &item.action {
            SyncAction::AdoptLocal { .. } => out.adopted += 1,
            SyncAction::SkipFileReconcileData { marks_changed: true, .. } => out.marks_reconciled += 1,
            SyncAction::SoftDelete { local_video_id: Some(_) } => out.soft_deleted += 1,
            SyncAction::CopyNew => out.to_copy.push(item.source_video_id.clone()),
            SyncAction::Ambiguous { .. } => out.ambiguous += 1,
            SyncAction::DeferHashPending => out.deferred += 1,
            _ => {}
        }
    }
    Ok(out)
}

#[cfg(test)]
mod two_catalog_tests {
    use super::*;

    fn open(dir: &std::path::Path, name: &str) -> Arc<Database> {
        let db = Database::new_empty();
        db.set_path(&dir.join(name)).unwrap();
        Arc::new(db)
    }

    /// Two in-process catalogs: B already holds the same bytes as A's first
    /// video (adopted, marks merged) and lacks the second (reported for copy).
    /// A second pass is a no-op until A's marks change.
    #[test]
    fn two_catalogs_adopt_merge_and_report_copies() {
        let tmp = tempfile::tempdir().unwrap();
        let a = open(tmp.path(), "a.db");
        let b = open(tmp.path(), "b.db");

        let a1 = a.add_video("/a/one.mov", "one.mov", None, None, Some(10)).unwrap();
        a.set_content_hash(&a1, "h1").unwrap();
        let a2 = a.add_video("/a/two.mov", "two.mov", None, None, Some(20)).unwrap();
        a.set_content_hash(&a2, "h2").unwrap();
        a.update_video_rating(&a1, 4).unwrap();

        let b1 = b.add_video("/b/one.mov", "one.mov", None, None, Some(10)).unwrap();
        b.set_content_hash(&b1, "h1").unwrap();

        let manifest = a.get_sync_manifest_page(100, "").unwrap();
        assert_eq!(manifest.len(), 2);
        let first = reconcile_catalog(&b, "peerA", &manifest, |id| a.get_video_catalog_data(id)).unwrap();
        assert_eq!(first.adopted, 1);
        assert_eq!(first.to_copy, vec![a2.clone()]);
        assert_eq!(b.get_video_catalog_data(&b1).unwrap().unwrap().rating, 4, "adoption merges the peer's marks");

        let manifest = a.get_sync_manifest_page(100, "").unwrap();
        let second = reconcile_catalog(&b, "peerA", &manifest, |id| a.get_video_catalog_data(id)).unwrap();
        assert_eq!((second.adopted, second.marks_reconciled), (0, 0));

        a.update_video_rating(&a1, 2).unwrap();
        let manifest = a.get_sync_manifest_page(100, "").unwrap();
        let third = reconcile_catalog(&b, "peerA", &manifest, |id| a.get_video_catalog_data(id)).unwrap();
        assert_eq!(third.marks_reconciled, 1);
        assert_eq!(b.get_video_catalog_data(&b1).unwrap().unwrap().rating, 2);
    }
}
