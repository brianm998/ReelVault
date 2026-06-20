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

    /// Record that `local_video_id` on this daemon corresponds to
    /// `remote_video_id` on `peer_key`. Call this after a successful file
    /// transfer or hash-adoption reconcile so subsequent syncs skip the
    /// transfer step.
    pub fn update_sync_link_after_reconcile(
        &self,
        local_video_id: &str,
        peer_key: &str,
        remote_video_id: &str,
        origin_hash: Option<&str>,
        is_derived: bool,
        derived_height: Option<i32>,
        local_rev: i64,
        remote_rev: i64,
    ) -> Result<()> {
        self.db.add_sync_link(
            local_video_id,
            peer_key,
            remote_video_id,
            origin_hash,
            is_derived,
            derived_height,
            local_rev,
            remote_rev,
        )
    }
}
