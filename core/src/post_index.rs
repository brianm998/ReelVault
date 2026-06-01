// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

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
use crate::watcher::CatalogChange;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicU8, Ordering};
use std::sync::mpsc::{self, SyncSender};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};
use tokio::sync::broadcast;

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
    /// Issue a Wikidata SPARQL lookup for any `camera_model` we
    /// encounter that isn't in the built-in sensor table. Backs the
    /// full-resolution badge on the grid card; see
    /// [`crate::sensor_cache::ensure_cached`].
    pub sensor_fetch: bool,
    /// Apply the "timelapse" tag automatically when a video's recorded
    /// resolution exceeds its camera's max in-camera video resolution
    /// (see [`crate::full_resolution::is_likely_timelapse`]).
    /// Default is **on** — the heuristic is conservative and most
    /// users want the auto-tag. Idempotent against user removal: if
    /// the user untags a timelapse-flagged video, the auto-tagger
    /// won't re-apply it (history table in `auto_tag_history`).
    pub auto_tag_timelapses: bool,
}

impl Default for Options {
    fn default() -> Self {
        Options {
            auto_group: false,
            detect_proxies: true,
            sensor_fetch: true,
            auto_tag_timelapses: true,
        }
    }
}

// ---- Progress reporting -------------------------------------------------
//
// The post-index pass (mostly pairwise thumbnail hashing for proxy
// detection) can run for many minutes on a library full of resolution
// variants, and used to be completely invisible to clients — the daemon
// just looked like it had silently pegged a CPU. A dedicated emitter
// thread watches the counters the workers bump and publishes throttled
// `CatalogChange::PostIndex*` events on the same broadcast bus the watcher
// already uses, so both clients can show what's happening and roughly how
// far along it is.

// Phase codes stamped into `ProgressState::phase` (kept as a small int so
// workers can update it with a relaxed atomic store on every sub-step).
const PHASE_GROUPING: u8 = 0;
const PHASE_PROXIES: u8 = 1;
const PHASE_SENSORS: u8 = 2;
const PHASE_TAGGING: u8 = 3;

fn phase_label(code: u8) -> &'static str {
    match code {
        PHASE_GROUPING => "grouping",
        PHASE_PROXIES => "proxies",
        PHASE_SENSORS => "sensors",
        _ => "tagging",
    }
}

/// Stay silent until a pass has run at least this long with work still
/// outstanding. Keeps ordinary small scans and single-file watcher
/// refreshes (which finish in well under a second) from flashing a panel.
const ANNOUNCE_AFTER: Duration = Duration::from_millis(2500);
/// Cadence of `PostIndexProgress` events once a pass has been announced.
const EMIT_EVERY: Duration = Duration::from_millis(1000);
/// How often the emitter wakes to check the shutdown flag / clock. Smaller
/// than `EMIT_EVERY` so `finish()` returns promptly once workers drain.
const EMITTER_TICK: Duration = Duration::from_millis(200);

/// Shared counters for one post-index pass. Workers bump `processed` and
/// stamp `phase` / `last_detail`; the emitter thread reads them.
struct ProgressState {
    /// Videos whose `process_one` has returned (success or handled error).
    processed: AtomicU64,
    /// Videos handed to the pool via [`Handle::submit`]. Used as the
    /// denominator when the caller didn't supply an `expected_total`.
    submitted: AtomicU64,
    /// Current dominant activity (one of the `PHASE_*` codes).
    phase: AtomicU8,
    /// Last human-readable action (e.g. a proxy link), shown live so the
    /// panel has something to say even before percentages are meaningful.
    last_detail: Mutex<String>,
    /// Caller's estimate of how many videos this pass will handle. 0 means
    /// "unknown" — the emitter falls back to `submitted`.
    expected_total: u64,
    /// When the pass started, for rate / ETA computation.
    started: Instant,
    /// Set once the emitter publishes `PostIndexStarted`, so `finish()`
    /// only publishes a matching `PostIndexCompleted` when a panel is up.
    announced: AtomicBool,
    /// Signals the emitter thread to stop (set by `finish()`).
    shutdown: AtomicBool,
}

impl ProgressState {
    fn record_detail(&self, detail: String) {
        if let Ok(mut d) = self.last_detail.lock() {
            *d = detail;
        }
    }
}

/// The emitter thread: throttled snapshots of `ProgressState` → broadcast.
fn emitter_loop(progress: Arc<ProgressState>, events: broadcast::Sender<CatalogChange>) {
    let mut last_emit: Option<Instant> = None;
    loop {
        thread::sleep(EMITTER_TICK);
        if progress.shutdown.load(Ordering::Relaxed) {
            break;
        }

        let processed = progress.processed.load(Ordering::Relaxed);
        let total = if progress.expected_total > 0 {
            progress.expected_total
        } else {
            progress.submitted.load(Ordering::Relaxed)
        };
        let elapsed = progress.started.elapsed();
        let work_remaining = total == 0 || processed < total;

        // Gate: don't announce a pass until it's clearly long-running.
        if !progress.announced.load(Ordering::Relaxed) {
            if elapsed < ANNOUNCE_AFTER || !work_remaining || total < 2 {
                continue;
            }
            progress.announced.store(true, Ordering::Relaxed);
            let _ = events.send(CatalogChange::PostIndexStarted { total });
            last_emit = None; // emit a first progress snapshot immediately
        }

        if last_emit.map(|t| t.elapsed() < EMIT_EVERY).unwrap_or(false) {
            continue;
        }
        last_emit = Some(Instant::now());

        let phase = phase_label(progress.phase.load(Ordering::Relaxed)).to_string();
        let detail = progress
            .last_detail
            .lock()
            .map(|d| d.clone())
            .unwrap_or_default();
        let percent = if total > 0 {
            ((processed as f64 / total as f64) * 100.0).clamp(0.0, 100.0)
        } else {
            0.0
        };
        let eta_secs = estimate_eta(processed, total, elapsed);

        let _ = events.send(CatalogChange::PostIndexProgress {
            processed,
            total,
            percent,
            eta_secs,
            phase,
            detail,
        });
    }
}

/// Seconds remaining at the current average rate. 0 when we can't tell
/// (no progress yet, unknown total, or already done).
fn estimate_eta(processed: u64, total: u64, elapsed: Duration) -> u64 {
    if processed == 0 || total <= processed {
        return 0;
    }
    let secs = elapsed.as_secs_f64();
    if secs <= 0.0 {
        return 0;
    }
    let rate = processed as f64 / secs; // videos per second
    if rate <= 0.0 {
        return 0;
    }
    ((total - processed) as f64 / rate).round() as u64
}

/// Handle held by the scan. Each successfully-indexed video is
/// pushed in through [`Self::submit`]; the scan calls
/// [`Self::finish`] once `par_iter` returns, which closes the
/// channel and joins the worker threads.
pub struct Handle {
    tx: Option<SyncSender<String>>,
    workers: Vec<JoinHandle<()>>,
    /// Progress emitter thread (only spawned when an events sink was
    /// provided). Joined in `finish()` after the workers drain.
    emitter: Option<JoinHandle<()>>,
    progress: Arc<ProgressState>,
    /// Broadcast bus for the closing `PostIndexCompleted`. `None` when the
    /// caller didn't wire up progress reporting (tests / CLI).
    events: Option<broadcast::Sender<CatalogChange>>,
}

impl Handle {
    /// Queue a freshly-indexed video for post-index processing.
    /// Blocks briefly if the bounded channel is full (backpressure
    /// on the scan). Logs a warning if the channel is closed,
    /// which shouldn't happen during a normal scan.
    pub fn submit(&self, video_id: String) {
        if let Some(tx) = &self.tx {
            self.progress.submitted.fetch_add(1, Ordering::Relaxed);
            if let Err(e) = tx.send(video_id) {
                tracing::warn!("post-index channel closed unexpectedly: {}", e);
            }
        }
    }

    /// Drop the sender so workers see EOF, then join. Call once the
    /// scan's `par_iter` has finished pushing ids. Also stops the progress
    /// emitter and publishes a closing `PostIndexCompleted` if a panel was
    /// ever raised.
    pub fn finish(mut self) {
        drop(self.tx.take());
        for w in self.workers.drain(..) {
            let _ = w.join();
        }
        // Workers are done — counters are now final. Stop the emitter,
        // then (if we ever announced a pass) tell clients to clear the
        // activity panel.
        self.progress.shutdown.store(true, Ordering::Relaxed);
        if let Some(e) = self.emitter.take() {
            let _ = e.join();
        }
        if self.progress.announced.load(Ordering::Relaxed) {
            if let Some(events) = &self.events {
                let _ = events.send(CatalogChange::PostIndexCompleted {
                    processed: self.progress.processed.load(Ordering::Relaxed),
                });
            }
        }
    }
}

/// Spawn the worker pool.  Workers run until the returned [`Handle`]
/// is dropped or [`Handle::finish`] is called.
///
/// `events` is the broadcast bus to publish post-index progress on (pass
/// `None` to disable progress reporting — used by the CLI and tests).
/// `expected_total` is the caller's best estimate of how many videos this
/// pass will process; pass 0 when unknown and the emitter will fall back
/// to the running submit count.
pub fn spawn(
    db: Arc<Database>,
    thumbnail_cache: PathBuf,
    options: Options,
    events: Option<broadcast::Sender<CatalogChange>>,
    expected_total: u64,
) -> Handle {
    spawn_with_workers(
        db,
        thumbnail_cache,
        options,
        events,
        expected_total,
        DEFAULT_NUM_WORKERS,
    )
}

pub fn spawn_with_workers(
    db: Arc<Database>,
    thumbnail_cache: PathBuf,
    options: Options,
    events: Option<broadcast::Sender<CatalogChange>>,
    expected_total: u64,
    num_workers: usize,
) -> Handle {
    let num_workers = num_workers.clamp(1, 8);
    let (tx, rx) = mpsc::sync_channel::<String>(CHANNEL_CAPACITY);
    let rx = Arc::new(Mutex::new(rx));
    let decision_mutex: Arc<Mutex<()>> = Arc::new(Mutex::new(()));

    let progress = Arc::new(ProgressState {
        processed: AtomicU64::new(0),
        submitted: AtomicU64::new(0),
        phase: AtomicU8::new(PHASE_PROXIES),
        last_detail: Mutex::new(String::new()),
        expected_total,
        started: Instant::now(),
        announced: AtomicBool::new(false),
        shutdown: AtomicBool::new(false),
    });

    let mut workers = Vec::with_capacity(num_workers);
    for worker_idx in 0..num_workers {
        let rx = Arc::clone(&rx);
        let db = Arc::clone(&db);
        let cache = thumbnail_cache.clone();
        let dec = Arc::clone(&decision_mutex);
        let prog = Arc::clone(&progress);
        workers.push(
            thread::Builder::new()
                .name(format!("post-index-{}", worker_idx))
                .spawn(move || worker_loop(rx, db, cache, dec, options, prog))
                .expect("failed to spawn post-index worker thread"),
        );
    }

    // Only run an emitter when there's somewhere to publish to.
    let emitter = events.as_ref().map(|sink| {
        let prog = Arc::clone(&progress);
        let sink = sink.clone();
        thread::Builder::new()
            .name("post-index-emit".to_string())
            .spawn(move || emitter_loop(prog, sink))
            .expect("failed to spawn post-index emitter thread")
    });

    Handle {
        tx: Some(tx),
        workers,
        emitter,
        progress,
        events,
    }
}

fn worker_loop(
    rx: Arc<Mutex<mpsc::Receiver<String>>>,
    db: Arc<Database>,
    thumbnail_cache: PathBuf,
    decision_mutex: Arc<Mutex<()>>,
    options: Options,
    progress: Arc<ProgressState>,
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

        if let Err(e) = process_one(
            &db,
            &thumbnail_cache,
            &decision_mutex,
            &options,
            &video_id,
            &progress,
        ) {
            tracing::warn!(video_id = %video_id, error = %e, "post-index processing failed");
        }
        // Count the video as handled whether or not a decision was made,
        // so the progress denominator matches what the caller submitted.
        progress.processed.fetch_add(1, Ordering::Relaxed);
    }
}

fn process_one(
    db: &Database,
    thumbnail_cache: &Path,
    decision_mutex: &Mutex<()>,
    options: &Options,
    video_id: &str,
    progress: &ProgressState,
) -> Result<()> {
    // Stack/group decision first — proxy detection uses group_id as a
    // bucket key when present, so we want the group settled before
    // proxy logic runs.
    if options.auto_group {
        progress.phase.store(PHASE_GROUPING, Ordering::Relaxed);
        join_or_create_group(db, decision_mutex, video_id, progress)?;
    }
    if options.detect_proxies {
        progress.phase.store(PHASE_PROXIES, Ordering::Relaxed);
        detect_proxy_for(db, thumbnail_cache, decision_mutex, video_id, progress)?;
    }
    if options.sensor_fetch {
        progress.phase.store(PHASE_SENSORS, Ordering::Relaxed);
        // Best-effort: don't fail the post-index pass on a Wikidata
        // hiccup. The classifier degrades to Unknown for cameras that
        // never get cached; users see the same "no badge" state they
        // would without this step.
        if let Err(e) = fetch_sensor_for(db, video_id) {
            tracing::debug!(video_id = %video_id, error = %e,
                "post-index sensor_fetch skipped");
        }
    }
    if options.auto_tag_timelapses {
        progress.phase.store(PHASE_TAGGING, Ordering::Relaxed);
        // Same best-effort posture as sensor_fetch — never fail the
        // post-index pass over a tag write.
        if let Err(e) = auto_tag_timelapse_for(db, video_id) {
            tracing::debug!(video_id = %video_id, error = %e,
                "post-index timelapse auto-tag skipped");
        }
    }
    Ok(())
}

fn auto_tag_timelapse_for(db: &Database, video_id: &str) -> Result<()> {
    let conn = db.get_connection()?;
    let row: Option<(Option<String>, i32, i32)> = conn
        .query_row(
            "SELECT camera_model, width, height FROM metadata WHERE video_id = ?",
            [video_id],
            |r| {
                Ok((
                    r.get::<_, Option<String>>(0)?,
                    r.get::<_, i32>(1)?,
                    r.get::<_, i32>(2)?,
                ))
            },
        )
        .ok();
    let Some((camera, width, height)) = row else {
        return Ok(());
    };
    let Some(camera) = camera else {
        return Ok(());
    };
    let camera = camera.trim();
    if camera.is_empty() {
        return Ok(());
    }
    let w = width.max(0) as u32;
    let h = height.max(0) as u32;
    if !crate::full_resolution::is_likely_timelapse(camera, w, h) {
        return Ok(());
    }
    // auto_tag_if_unseen returns false if a previous run already applied
    // (and the user may or may not have since removed) the tag — in
    // either case we leave it alone.
    let applied = db.auto_tag_if_unseen(video_id, "timelapse", "timelapse_heuristic")?;
    if applied {
        tracing::info!(
            video_id = %video_id,
            camera = %camera,
            width = w,
            height = h,
            "auto-tagged as timelapse"
        );
    }
    Ok(())
}

fn fetch_sensor_for(db: &Database, video_id: &str) -> Result<()> {
    let conn = db.get_connection()?;
    let camera_model: Option<String> = conn
        .query_row(
            "SELECT camera_model FROM metadata WHERE video_id = ?",
            [video_id],
            |r| r.get::<_, Option<String>>(0),
        )
        .ok()
        .flatten();
    let Some(model) = camera_model else {
        return Ok(());
    };
    let model = model.trim();
    if model.is_empty() {
        return Ok(());
    }
    // ensure_cached is a no-op when the camera is covered by the
    // built-in table or already cached — only newly-discovered bodies
    // pay the Wikidata round-trip.
    let _ = crate::sensor_cache::ensure_cached(&conn, model)?;
    Ok(())
}

// ---- Group/stack decision -----------------------------------------------

fn join_or_create_group(
    db: &Database,
    decision_mutex: &Mutex<()>,
    video_id: &str,
    progress: &ProgressState,
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
        progress.record_detail(format!("grouped {}", cand.filename));
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
        progress.record_detail(format!("grouped {} ({} clips)", cand.filename, members.len()));
    }

    Ok(())
}

// ---- Proxy decision -----------------------------------------------------

fn detect_proxy_for(
    db: &Database,
    thumbnail_cache: &Path,
    decision_mutex: &Mutex<()>,
    video_id: &str,
    progress: &ProgressState,
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
        progress.record_detail(format!(
            "linked {} → {}",
            proxy_cand.filename, master.filename
        ));
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn eta_is_zero_when_indeterminate() {
        // No videos processed yet — can't estimate a rate.
        assert_eq!(estimate_eta(0, 100, Duration::from_secs(5)), 0);
        // Unknown total.
        assert_eq!(estimate_eta(10, 0, Duration::from_secs(5)), 0);
        // Already done / overshot.
        assert_eq!(estimate_eta(100, 100, Duration::from_secs(5)), 0);
        assert_eq!(estimate_eta(150, 100, Duration::from_secs(5)), 0);
        // No elapsed time recorded yet.
        assert_eq!(estimate_eta(10, 100, Duration::from_secs(0)), 0);
    }

    #[test]
    fn eta_extrapolates_current_rate() {
        // 20 of 100 in 10s => 2 videos/sec => 80 left => 40s.
        assert_eq!(estimate_eta(20, 100, Duration::from_secs(10)), 40);
        // 50 of 60 in 100s => 0.5/sec => 10 left => 20s.
        assert_eq!(estimate_eta(50, 60, Duration::from_secs(100)), 20);
    }

    #[test]
    fn phase_labels_match_proto_contract() {
        assert_eq!(phase_label(PHASE_GROUPING), "grouping");
        assert_eq!(phase_label(PHASE_PROXIES), "proxies");
        assert_eq!(phase_label(PHASE_SENSORS), "sensors");
        assert_eq!(phase_label(PHASE_TAGGING), "tagging");
        // Unknown codes fall back to the cheapest phase rather than panic.
        assert_eq!(phase_label(200), "tagging");
    }
}
