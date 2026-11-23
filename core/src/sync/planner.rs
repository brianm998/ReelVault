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
    /// No sync link yet, but exactly one local original has the same content
    /// hash: adopt it (record a link, then reconcile marks) instead of
    /// transferring the bytes again.
    AdoptLocal { local_video_id: String },
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
        // hash -> distinct local video ids carrying it.
        let mut hash_hit_map: HashMap<&str, Vec<&str>> = HashMap::new();
        for h in hash_hits {
            let ids = hash_hit_map.entry(h.content_hash.as_str()).or_default();
            if !ids.contains(&h.video_id.as_str()) {
                ids.push(h.video_id.as_str());
            }
        }

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

                // Step 2: hash adoption — local originals share the same
                // content hash. One distinct match is adopted; several are
                // ambiguous and left for the user rather than guessed at.
                if let Some(hash) = &row.content_hash {
                    if let Some(ids) = hash_hit_map.get(hash.as_str()) {
                        let action = match ids.as_slice() {
                            [only] => SyncAction::AdoptLocal { local_video_id: (*only).to_string() },
                            many => SyncAction::Ambiguous {
                                reason: format!("{} local videos share content hash {hash}", many.len()),
                            },
                        };
                        return SyncPlanItem {
                            source_video_id: row.video_id.clone(),
                            action,
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

#[cfg(test)]
mod tests {
    use super::*;

    fn row(id: &str, hash: Option<&str>) -> SyncManifestRow {
        SyncManifestRow {
            video_id: id.into(),
            content_hash: hash.map(String::from),
            marks_rev: 1,
            size_bytes: 10,
            duration_ms: 1000,
            filename: format!("{id}.mov"),
            width: 1920,
            height: 1080,
            codec_video: "h264".into(),
            is_derived: false,
            proxy_of: None,
            has_streamable_proxy: false,
            deleted_at: None,
        }
    }

    fn hit(hash: &str, id: &str) -> ContentHashHit {
        ContentHashHit {
            content_hash: hash.into(),
            video_id: id.into(),
            marks_rev: 0,
            duration_ms: 1000,
            creation_date_ms: 0,
        }
    }

    fn planner() -> (tempfile::TempDir, Planner) {
        let tmp = tempfile::tempdir().unwrap();
        let db = Database::new_empty();
        db.set_path(&tmp.path().join("c.db")).unwrap();
        (tmp, Planner::new(Arc::new(db), "peer".into()))
    }

    #[test]
    fn hash_hit_adopts_and_miss_copies() {
        let (_t, p) = planner();
        let items = p.classify(
            &[row("r1", Some("h1")), row("r2", Some("h2")), row("r3", None)],
            &[hit("h1", "local1")],
        );
        assert_eq!(items[0].action, SyncAction::AdoptLocal { local_video_id: "local1".into() });
        assert_eq!(items[1].action, SyncAction::CopyNew);
        assert_eq!(items[2].action, SyncAction::DeferHashPending);
    }

    #[test]
    fn multiple_local_matches_are_ambiguous() {
        let (_t, p) = planner();
        let items = p.classify(
            &[row("r1", Some("h1"))],
            &[hit("h1", "a"), hit("h1", "b"), hit("h1", "a")],
        );
        assert!(matches!(items[0].action, SyncAction::Ambiguous { .. }));
    }
}
