// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

//! Incremental post-index pipeline.
//!
//! Historically the scan ran in two phases: first
//! [`crate::indexing::IndexingEngine::scan_directory`] indexed every
//! video (parallel ffprobe/thumbnail extraction via rayon), then
//! [`crate::grouping::auto_group`] and [`crate::proxies::detect_proxies`]
//! ran sequentially over the full catalog. If the scan was killed
//! before the second phase started, the catalog kept the new
//! thumbnails/metadata but never got proxy or group links — and there
//! was no resume mechanism short of re-running the whole scan.
//!
//! This module folds the grouping and proxy work into the scan itself.
//! As soon as a video finishes indexing (its `videos`/`metadata` rows
//! are committed and its thumbnails generated), the scan worker pushes
//! the new video_id into [`Handle::submit`]. A small background pool
//! of `std::thread` workers drains the queue, and for each id:
//!
//!   1. Picks an auto-group decision — either creates a new group, or
//!      joins an existing one, or leaves the video ungrouped.
//!   2. Picks a proxy-relationship decision — sets the video as a
//!      proxy of an existing anchor, or (when the new video itself is
//!      higher-resolution than the existing anchor) re-promotes the
//!      anchor and re-points all existing proxies of the old anchor.
//!
//! Concurrency model
//! -----------------
//! The CPU-expensive bit is thumbnail loading + dHash comparison,
//! which is fully read-only and parallel-safe. The DB-writing bit
//! (group create/add, proxy link set, repoint) is serialized via a
//! single [`Mutex`] so two workers can't race on group creation or
//! anchor selection. Two to four worker threads gives good
//! end-to-end throughput without producing write contention that
//! would defeat SQLite's WAL serialization.
//!
//! Resume / interruption
//! ---------------------
//! There is no persistent queue. If the scan dies mid-flight, any
//! video ids still in the channel are lost. The pre-existing
//! end-of-scan batch calls in [`crate::service`] act as a catch-up
//! pass on the next successful scan — both `auto_group` and
//! `detect_proxies` are idempotent by construction (they filter on
//! `group_id IS NULL` / `proxy_of IS NULL`).

use crate::db::{AutoGroupCandidate, Database};
use crate::error::Result;
use crate::grouping::{self, AutoGroupOptions};
use crate::proxies::{self, PROXY_SIMILARITY_THRESHOLD};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, SyncSender};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

/// Worker pool size. Above ~4 the DB-write mutex becomes the
/// bottleneck and additional threads just queue without progress.
const DEFAULT_NUM_WORKERS: usize = 3;

/// Channel capacity. Bounded so a wedged worker pool produces
/// backpressure on the scan rather than letting unprocessed ids
/// accumulate in RAM. 1024 is comfortable for a 4-worker pool — even
/// at 100 videos/sec of indexing that's only ~10 seconds of lag.
const CHANNEL_CAPACITY: usize = 1024;

/// Knobs for the post-index worker pool — controls which of the two
/// incremental passes are enabled.  The gating mirrors the existing
/// gRPC contract: proxy detection always runs (today
/// `detect_proxies` runs unconditionally after every scan), while
/// auto-grouping is opt-in (today `auto_group` runs only when
/// `req.auto_group` is true).
#[derive(Debug, Clone, Copy)]
pub struct Options {
    pub auto_group: bool,
    pub detect_proxies: bool,
}

impl Default for Options {
    fn default() -> Self {
        Options { auto_group: false, detect_proxies: true }
    }
}

/// Handle held by the scan. Each successfully-indexed video is
/// pushed in through [`Self::submit`]; the scan calls
/// [`Self::finish`] once `par_iter` returns, which closes the
/// channel and joins the worker threads.
pub struct Handle {
    tx: Option<SyncSender<String>>,
    workers: Vec<JoinHandle<()>>,
}

impl Handle {
    /// Queue a freshly-indexed video for post-index processing.
    /// Blocks briefly if the bounded channel is full (backpressure
    /// on the scan). Logs a warning if the channel is closed,
    /// which shouldn't happen during a normal scan.
    pub fn submit(&self, video_id: String) {
        if let Some(tx) = &self.tx {
            if let Err(e) = tx.send(video_id) {
                tracing::warn!("post-index channel closed unexpectedly: {}", e);
            }
        }
    }

    /// Drop the sender so workers see EOF, then join. Call once the
    /// scan's `par_iter` has finished pushing ids.
    pub fn finish(mut self) {
        drop(self.tx.take());
        for w in self.workers {
            let _ = w.join();
        }
    }
}

/// Spawn the worker pool.  Workers run until the returned [`Handle`]
/// is dropped or [`Handle::finish`] is called.
pub fn spawn(db: Arc<Database>, thumbnail_cache: PathBuf, options: Options) -> Handle {
    spawn_with_workers(db, thumbnail_cache, options, DEFAULT_NUM_WORKERS)
}

pub fn spawn_with_workers(
    db: Arc<Database>,
    thumbnail_cache: PathBuf,
    options: Options,
    num_workers: usize,
) -> Handle {
    let num_workers = num_workers.clamp(1, 8);
    let (tx, rx) = mpsc::sync_channel::<String>(CHANNEL_CAPACITY);
    let rx = Arc::new(Mutex::new(rx));
    let decision_mutex: Arc<Mutex<()>> = Arc::new(Mutex::new(()));

    let mut workers = Vec::with_capacity(num_workers);
    for worker_idx in 0..num_workers {
        let rx = Arc::clone(&rx);
        let db = Arc::clone(&db);
        let cache = thumbnail_cache.clone();
        let dec = Arc::clone(&decision_mutex);
        workers.push(
            thread::Builder::new()
                .name(format!("post-index-{}", worker_idx))
                .spawn(move || worker_loop(rx, db, cache, dec, options))
                .expect("failed to spawn post-index worker thread"),
        );
    }

    Handle { tx: Some(tx), workers }
}

fn worker_loop(
    rx: Arc<Mutex<mpsc::Receiver<String>>>,
    db: Arc<Database>,
    thumbnail_cache: PathBuf,
    decision_mutex: Arc<Mutex<()>>,
    options: Options,
) {
    loop {
        // Hold the receiver lock only long enough to dequeue one id.
        // The actual processing happens lock-free so workers run in
        // parallel.
        let video_id = {
            let guard = match rx.lock() {
                Ok(g) => g,
                Err(_) => break,
            };
            match guard.recv() {
                Ok(id) => id,
                Err(_) => break, // sender dropped — drain complete
            }
        };

        if let Err(e) = process_one(&db, &thumbnail_cache, &decision_mutex, &options, &video_id) {
            tracing::warn!(video_id = %video_id, error = %e, "post-index processing failed");
        }
    }
}

fn process_one(
    db: &Database,
    thumbnail_cache: &Path,
    decision_mutex: &Mutex<()>,
    options: &Options,
    video_id: &str,
) -> Result<()> {
    // Stack/group decision first — proxy detection uses group_id as a
    // bucket key when present, so we want the group settled before
    // proxy logic runs.
    if options.auto_group {
        join_or_create_group(db, decision_mutex, video_id)?;
    }
    if options.detect_proxies {
        detect_proxy_for(db, thumbnail_cache, decision_mutex, video_id)?;
    }
    Ok(())
}

// ---- Group/stack decision -----------------------------------------------

fn join_or_create_group(
    db: &Database,
    decision_mutex: &Mutex<()>,
    video_id: &str,
) -> Result<()> {
    let cand = match db.get_group_candidate(video_id)? {
        Some(c) => c,
        None => return Ok(()), // Marked as proxy or no metadata yet — skip.
    };
    // Skip if already in a group; the worker only makes initial
    // group assignments. Re-grouping happens via the explicit UI
    // flow.
    if cand.group_id.is_some() {
        return Ok(());
    }

    let opts = AutoGroupOptions::default();
    // Pull potential siblings from the catalog. Filtering by
    // parent_dir is the same gate the batch auto-grouper applies via
    // its sort+short-circuit; doing it in SQL keeps this O(bucket)
    // instead of O(N).
    let siblings = db.list_group_candidates_in_dir(&cand.parent_dir, &cand.id)?;

    // Find every existing video that should group with this one.
    let matches: Vec<AutoGroupCandidate> = siblings
        .into_iter()
        .filter(|s| grouping::should_group_together(&cand, s, &opts))
        .collect();

    if matches.is_empty() {
        return Ok(());
    }

    // Critical section: serialize group creation against other
    // workers so we can't double-create a group for the same bucket.
    let _guard = decision_mutex.lock().unwrap_or_else(|p| p.into_inner());

    // Re-fetch under the lock — another worker may have just put
    // this video into a group, or grouped a sibling we depended on.
    let cand = match db.get_group_candidate(video_id)? {
        Some(c) if c.group_id.is_none() => c,
        // Either marked as proxy mid-flight or already grouped.
        _ => return Ok(()),
    };

    // Re-evaluate matches under the lock — some sibling may have
    // just gained a group_id, or another worker may have already
    // formed the group we wanted to create.
    let siblings = db.list_group_candidates_in_dir(&cand.parent_dir, &cand.id)?;
    let matches: Vec<AutoGroupCandidate> = siblings
        .into_iter()
        .filter(|s| grouping::should_group_together(&cand, s, &opts))
        .collect();
    if matches.is_empty() {
        return Ok(());
    }

    // If any match is already in a group, join that group (and add
    // any other matches to it as well — they should belong there
    // too). When matches span multiple groups (unusual but
    // possible), pick the largest by member count.
    let existing_group_id: Option<String> = matches
        .iter()
        .filter_map(|m| m.group_id.clone())
        .next();

    if let Some(group_id) = existing_group_id {
        db.add_video_to_group(&group_id, &cand.id)?;
        for m in &matches {
            if m.group_id.is_none() {
                db.add_video_to_group(&group_id, &m.id)?;
            }
        }

        // Re-evaluate preferred leader: if the new video outranks
        // the current preferred, promote it.
        if let Some(group_row) = db.get_group(&group_id)? {
            let member_ids = db.list_group_member_ids(&group_id)?;
            let mut members: Vec<AutoGroupCandidate> = Vec::with_capacity(member_ids.len());
            for id in &member_ids {
                if let Some(c) = db.get_group_candidate(id)? {
                    members.push(c);
                }
            }
            if !members.is_empty() {
                let new_preferred = &members[grouping::preferred_index(&members)];
                if group_row.preferred_video_id.as_deref() != Some(&new_preferred.id) {
                    db.set_group_preferred(&group_id, &new_preferred.id)?;
                }
            }
        }

        tracing::info!(
            video = %cand.filename,
            group_id = %group_id,
            "post-index: added video to existing group",
        );
    } else {
        // None of the matches is grouped yet — create a fresh group
        // containing the new video plus all matched siblings.
        let mut members: Vec<AutoGroupCandidate> = matches;
        members.push(cand.clone());
        let pref_idx = grouping::preferred_index(&members);
        let preferred_id = members[pref_idx].id.clone();
        let ids: Vec<String> = members.iter().map(|m| m.id.clone()).collect();

        let base_trimmed = grouping::canonical_base(&cand.filename)
            .trim_end_matches(|c: char| !c.is_alphanumeric())
            .to_string();
        let group_name = if base_trimmed.is_empty() { None } else { Some(base_trimmed) };

        let group_id = db.create_group(
            group_name.as_deref(),
            group_name.as_deref(),
            &ids,
            Some(&preferred_id),
        )?;
        tracing::info!(
            video = %cand.filename,
            group_id = %group_id,
            members = members.len(),
            "post-index: created new group",
        );
    }

    Ok(())
}

// ---- Proxy decision -----------------------------------------------------

fn detect_proxy_for(
    db: &Database,
    thumbnail_cache: &Path,
    decision_mutex: &Mutex<()>,
    video_id: &str,
) -> Result<()> {
    let cand = match db.get_proxy_candidate(video_id)? {
        Some(c) => c,
        None => return Ok(()),
    };
    if cand.width == 0 || cand.height == 0 {
        return Ok(()); // Metadata not ready; let the next pass try.
    }

    // Bucket-mates use group_id when set, else (parent_dir, fps_rounded).
    let bucket = if let Some(gid) = &cand.group_id {
        db.list_proxy_candidates_in_group(gid, &cand.id)?
    } else {
        let mut by_dir = db.list_proxy_candidates_in_dir(&cand.parent_dir, &cand.id)?;
        let cand_fps = cand.fps.round() as i64;
        by_dir.retain(|c| c.fps.round() as i64 == cand_fps);
        by_dir
    };
    if bucket.is_empty() {
        return Ok(());
    }

    // Load the new video's thumbnails once — the expensive part of
    // every comparison. Done outside the decision lock so multiple
    // workers can decode JPEGs in parallel.
    let cand_imgs = proxies::thumb_images_for(thumbnail_cache, &cand.id);

    // Examine every bucket-mate as a potential opposite end of a
    // proxy link. The new video might be a proxy of `other` (other
    // has more pixels / heavier file) or `other` might be a proxy of
    // the new video. With many-to-many semantics we don't pick "the
    // anchor"; we just attempt to link wherever the gates + threshold
    // agree.
    for other in &bucket {
        // Determine direction. Skip if neither qualifies as master.
        let (master, proxy_cand, master_imgs, proxy_imgs) =
            if proxies::is_master_of(other, &cand) {
                let m_imgs = proxies::thumb_images_for(thumbnail_cache, &other.id);
                (other.clone(), cand.clone(), m_imgs, cand_imgs.clone())
            } else if proxies::is_master_of(&cand, other) {
                let p_imgs = proxies::thumb_images_for(thumbnail_cache, &other.id);
                (cand.clone(), other.clone(), cand_imgs.clone(), p_imgs)
            } else {
                continue;
            };

        if !proxies::proxy_pair_gates_pass(&master, &proxy_cand) {
            continue;
        }

        let confidence = proxies::compare_thumb_sets(&master_imgs, &proxy_imgs);
        tracing::debug!(
            master = %master.filename,
            proxy = %proxy_cand.filename,
            confidence = %format!("{:.3}", confidence),
            incremental = true,
            "post-index: proxy comparison",
        );
        if confidence < PROXY_SIMILARITY_THRESHOLD {
            continue;
        }

        // Critical section: serialize the actual write so two workers
        // can't double-insert. INSERT OR REPLACE makes the write
        // idempotent, so we don't bother re-checking state here —
        // worst case two workers both write the same row with the
        // same confidence.
        let _guard = decision_mutex.lock().unwrap_or_else(|p| p.into_inner());
        if let Err(e) = db.set_proxy_of(&proxy_cand.id, &master.id, confidence, true) {
            tracing::warn!(
                "Failed to link {} as proxy of {}: {}",
                proxy_cand.filename, master.filename, e,
            );
            continue;
        }
        tracing::info!(
            "post-index: linked {} → {} (confidence {:.3})",
            proxy_cand.filename, master.filename, confidence,
        );
    }

    Ok(())
}
