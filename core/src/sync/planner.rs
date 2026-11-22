// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Catalog-sync planner: classifies each remote manifest entry into a
//! `SyncAction` that the executor (or the client-side orchestrator) acts on.

use crate::db::{ContentHashHit, Database, SyncManifestRow};
use std::collections::HashMap;
use std::sync::Arc;

/// The decision the planner reaches for one remote video.
#[derive(Debug, Clone, PartialEq)]
pub enum SyncAction {
    /// No local copy — transfer the file from the remote peer.
    CopyNew,
    /// The file already exists locally (matched by sync link or content hash).
    /// The executor should reconcile marks if `marks_changed` is true.
    SkipFileReconcileData {
        local_video_id: String,
        marks_changed: bool,
    },
    /// The remote entry is itself a derived (downscaled) copy — skip it; we
    /// only sync originals and let the local daemon re-derive as needed.
    SkipDerived,
    /// The remote hasn't computed its content hash yet — defer to the next
    /// sync cycle once the peer has hashed its catalog.
    DeferHashPending,
    /// Multiple local candidates match and the planner can't pick one safely.
    Ambiguous { reason: String },
    /// The remote video was deleted (has a `deleted_at` tombstone). If we have
    /// a local copy (found via sync_links), the executor should soft-delete it.
    SoftDelete {
        local_video_id: Option<String>,
    },
}

/// One resolved action for one remote video.
#[derive(Debug, Clone)]
pub struct SyncPlanItem {
    pub source_video_id: String,
    pub action: SyncAction,
    pub content_hash: Option<String>,
    pub remote_marks_rev: i64,
}

/// The full plan returned to the executor.
pub struct SyncPlan {
    pub items: Vec<SyncPlanItem>,
    pub run_id: String,
}

/// Classifies manifest entries from a remote peer into sync actions.
pub struct Planner {
    db: Arc<Database>,
    peer_key: String,
}

impl Planner {
    pub fn new(db: Arc<Database>, peer_key: String) -> Self {
        Self { db, peer_key }
    }

    /// Classify all `manifest_rows` into [`SyncPlanItem`]s.
    ///
    /// `hash_hits` is the result of a prior `lookup_by_content_hash` call that
    /// probed the local catalog for all non-null hashes in the manifest.
    pub fn classify(
        &self,
        manifest_rows: &[SyncManifestRow],
        hash_hits: &[ContentHashHit],
    ) -> Vec<SyncPlanItem> {
        let hash_hit_map: HashMap<&str, &ContentHashHit> =
            hash_hits.iter().map(|h| (h.content_hash.as_str(), h)).collect();

        manifest_rows
            .iter()
            .map(|row| {
                // TOMBSTONE: source deleted this video. Soft-delete our local copy if we have one.
                if row.deleted_at.is_some() {
                    let local_id = self
                        .db
                        .find_sync_link_by_remote(&self.peer_key, &row.video_id)
                        .ok()
                        .flatten()
                        .map(|link| link.local_video_id);
                    return SyncPlanItem {
                        source_video_id: row.video_id.clone(),
                        action: SyncAction::SoftDelete { local_video_id: local_id },
                        content_hash: row.content_hash.clone(),
                        remote_marks_rev: row.marks_rev,
                    };
                }

                // SKIP_DERIVED: source is itself a derived copy — skip.
                if row.is_derived {
                    return SyncPlanItem {
                        source_video_id: row.video_id.clone(),
                        action: SyncAction::SkipDerived,
                        content_hash: row.content_hash.clone(),
                        remote_marks_rev: row.marks_rev,
                    };
                }

                // DEFER: no hash yet on the remote side.
                if row.content_hash.is_none() {
                    return SyncPlanItem {
                        source_video_id: row.video_id.clone(),
                        action: SyncAction::DeferHashPending,
                        content_hash: None,
                        remote_marks_rev: row.marks_rev,
                    };
                }

                // Step 1: direct link — we've previously synced this exact
                // (peer, remote_video_id) pair.
                if let Ok(Some(link)) =
                    self.db.find_sync_link_by_remote(&self.peer_key, &row.video_id)
                {
                    let marks_changed = link.remote_rev_at_sync != row.marks_rev;
                    return SyncPlanItem {
                        source_video_id: row.video_id.clone(),
                        action: SyncAction::SkipFileReconcileData {
                            local_video_id: link.local_video_id.clone(),
                            marks_changed,
                        },
                        content_hash: row.content_hash.clone(),
                        remote_marks_rev: row.marks_rev,
                    };
                }

                // Step 2: hash adoption — a local original shares the same
                // content hash. The executor validates before creating the link.
                if let Some(hash) = &row.content_hash {
                    if hash_hit_map.contains_key(hash.as_str()) {
                        // Return CopyNew so the executor can decide whether to
                        // adopt or genuinely copy (it checks hash_hit_map again).
                        return SyncPlanItem {
                            source_video_id: row.video_id.clone(),
                            action: SyncAction::CopyNew,
                            content_hash: row.content_hash.clone(),
                            remote_marks_rev: row.marks_rev,
                        };
                    }
                }

                // Step 5: no match → transfer the file.
                SyncPlanItem {
                    source_video_id: row.video_id.clone(),
                    action: SyncAction::CopyNew,
                    content_hash: row.content_hash.clone(),
                    remote_marks_rev: row.marks_rev,
                }
            })
            .collect()
    }
}
