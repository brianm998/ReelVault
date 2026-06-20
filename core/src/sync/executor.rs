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
}
