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
use std::sync::{Arc, Mutex, OnceLock};
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
// just looked like it had silently pegged a CPU. So a background emitter
// thread watches the counters the workers bump and publishes throttled
// `CatalogChange::PostIndex*` events on the same broadcast bus the watcher
// already uses, and both clients show what's happening.
//
// Crucially the emitter is owned by a *process-wide* [`PostIndexReporter`],
// not by individual passes. A manual rescan and a watcher-triggered wave
// can run at the same time; if each spawned its own emitter the clients
// would see two interleaved progress streams and flicker the activity
// banner between (say) "proxies" and "tagging". Instead every pass
// registers with the one reporter, which merges them into a single
// coalesced session: summed totals, one `PostIndexStarted`, one stream of
// progress with a phase label held steady by hysteresis, and one
// `PostIndexCompleted` when the last overlapping pass drains.

// Phase codes stamped into `SessionState::sampled_phase` (kept as a small
// int so workers can update it with a relaxed atomic store on every
// sub-step; the emitter smooths it into the displayed label).
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
/// Minimum time a newly-dominant phase must persist before the emitter
/// switches the displayed label. Workers stamp the live sub-step on every
/// video they touch, so without this the label would flap between sub-steps
/// (and across overlapping passes) faster than a user can read it. ~2s
/// keeps the banner readable and non-flashing.
const PHASE_HYSTERESIS: Duration = Duration::from_millis(2000);

/// Shared counters for the current *coalesced* post-index session. A
/// session spans every pass that overlaps in time: workers from all of them
/// bump `processed`, stamp `sampled_phase`, and set `last_detail`; the
/// single emitter thread reads them.
struct SessionState {
    /// Videos whose `process_one` has returned, summed across every pass in
    /// this session.
    processed: AtomicU64,
    /// Videos handed to any pool via [`Handle::submit`]. Denominator
    /// fallback when no pass supplied an `expected_total`.
    submitted: AtomicU64,
    /// Sum of every active pass's `expected_total`. Grows when a new pass
    /// joins mid-session (so the percentage can briefly dip — honest: more
    /// work was just queued). 0 means "all unknown" → fall back to
    /// `submitted`.
    total: AtomicU64,
    /// Live dominant sub-step (one of the `PHASE_*` codes), stamped by
    /// workers. The emitter applies hysteresis before displaying it.
    sampled_phase: AtomicU8,
    /// Last human-readable action (e.g. a proxy link), shown live so the
    /// panel has something to say even before percentages are meaningful.
    last_detail: Mutex<String>,
    /// When this session started, for rate / ETA computation.
    started: Instant,
    /// Set once the emitter publishes `PostIndexStarted`, so the closing
    /// `PostIndexCompleted` is only sent when a panel was actually raised.
    announced: AtomicBool,
    /// Signals the emitter thread to stop (set on the active→0 transition).
    shutdown: AtomicBool,
    /// Identifies this session. The emitter captures it at spawn and exits
    /// the instant it observes a different generation, so an emitter is
    /// permanently bound to one session — a reset means a brand-new
    /// `SessionState` (new generation), never mutation of a live one.
    generation: u64,
}

impl SessionState {
    fn record_detail(&self, detail: String) {
        if let Ok(mut d) = self.last_detail.lock() {
            *d = detail;
        }
    }
}

/// Process-wide coalescer. Every progress-reporting post-index pass
/// registers here so overlapping passes share one emitter and one session
/// on the broadcast bus, rather than each flicking the clients between its
/// own snapshots.
///
/// Assumes a single bus per process (one `catalog_events`, created once in
/// `ReelVaultService::new` and never swapped by `OpenCatalog`). Passes that
/// opt out of reporting (`events: None` — the CLI and tests) never touch
/// this reporter at all.
struct PostIndexReporter {
    inner: Mutex<ReporterInner>,
}

struct ReporterInner {
    /// Registered passes that haven't finished yet.
    active_passes: u32,
    /// The current session — `Some` iff `active_passes > 0`.
    session: Option<Arc<SessionState>>,
    /// The one live emitter thread for the current session.
    emitter: Option<JoinHandle<()>>,
}

/// Monotonic session-generation counter; bumped for every new session.
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(0);

fn reporter() -> &'static PostIndexReporter {
    static REPORTER: OnceLock<PostIndexReporter> = OnceLock::new();
    REPORTER.get_or_init(|| PostIndexReporter {
        inner: Mutex::new(ReporterInner {
            active_passes: 0,
            session: None,
            emitter: None,
        }),
    })
}

impl PostIndexReporter {
    /// Register a pass. On the 0→1 transition this starts a fresh session
    /// and spawns the single emitter; otherwise it folds `expected_total`
    /// into the running session. Re-captures `events` every call so the
    /// emitter's sender can't go stale across watcher restarts.
    fn register_pass(
        &self,
        expected_total: u64,
        events: broadcast::Sender<CatalogChange>,
    ) -> Arc<SessionState> {
        let mut inner = self.inner.lock().unwrap_or_else(|p| p.into_inner());
        if inner.active_passes == 0 {
            // Join a previous emitter that already exited (defensive; normal
            // teardown joins in `finish_pass`). Non-blocking in practice.
            if let Some(h) = inner.emitter.take() {
                let _ = h.join();
            }
            let generation = NEXT_GENERATION.fetch_add(1, Ordering::Relaxed);
            let session = Arc::new(SessionState {
                processed: AtomicU64::new(0),
                submitted: AtomicU64::new(0),
                total: AtomicU64::new(expected_total),
                sampled_phase: AtomicU8::new(PHASE_PROXIES),
                last_detail: Mutex::new(String::new()),
                started: Instant::now(),
                announced: AtomicBool::new(false),
                shutdown: AtomicBool::new(false),
                generation,
            });
            let emitter = thread::Builder::new()
                .name("post-index-emit".to_string())
                .spawn({
                    let session = Arc::clone(&session);
                    move || emitter_loop(session, events, generation)
                })
                .expect("failed to spawn post-index emitter thread");
            inner.active_passes = 1;
            inner.session = Some(Arc::clone(&session));
            inner.emitter = Some(emitter);
            session
        } else {
            let session = inner
                .session
                .as_ref()
                .expect("session present while active_passes > 0")
                .clone();
            session.total.fetch_add(expected_total, Ordering::Relaxed);
            inner.active_passes += 1;
            session
        }
    }

    /// Deregister a pass. On the active→0 transition it stops and joins the
    /// emitter and publishes the closing `PostIndexCompleted` (when a panel
    /// was raised). While other passes remain active it just decrements —
    /// the banner stays up and the counters carry over to them.
    fn finish_pass(&self, session: &Arc<SessionState>, events: &broadcast::Sender<CatalogChange>) {
        // Decide under the lock; join the emitter *outside* it so a
        // concurrent `register_pass` isn't stalled for an emitter tick.
        let emitter = {
            let mut inner = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            inner.active_passes = inner.active_passes.saturating_sub(1);
            if inner.active_passes == 0 {
                session.shutdown.store(true, Ordering::Relaxed);
                inner.session = None;
                inner.emitter.take()
            } else {
                None
            }
        };
        let Some(emitter) = emitter else {
            return; // Other passes still running — nothing to tear down.
        };
        let _ = emitter.join();
        // The emitter has stopped, so no `PostIndexProgress` can race the
        // close below.
        if session.announced.load(Ordering::Relaxed) {
            let _ = events.send(CatalogChange::PostIndexCompleted {
                processed: session.processed.load(Ordering::Relaxed),
            });
        }
    }
}

/// Per-pass ticket. Workers report progress through it; the owning
/// [`Handle`] finishes it. `session: None` means progress reporting is
/// disabled (CLI / tests): every method is a cheap no-op, so the worker
/// hot path is identical with and without a bus.
#[derive(Clone)]
struct PassToken {
    session: Option<Arc<SessionState>>,
}

impl PassToken {
    #[inline]
    fn add_processed(&self) {
        if let Some(s) = &self.session {
            s.processed.fetch_add(1, Ordering::Relaxed);
        }
    }

    #[inline]
    fn note_submitted(&self) {
        if let Some(s) = &self.session {
            s.submitted.fetch_add(1, Ordering::Relaxed);
        }
    }

    #[inline]
    fn set_phase(&self, code: u8) {
        if let Some(s) = &self.session {
            s.sampled_phase.store(code, Ordering::Relaxed);
        }
    }

    fn record_detail(&self, detail: String) {
        if let Some(s) = &self.session {
            s.record_detail(detail);
        }
    }
}

/// Progress denominator: the summed expected total, or the running submit
/// count when no pass supplied an estimate.
fn effective_total(total: u64, submitted: u64) -> u64 {
    if total > 0 {
        total
    } else {
        submitted
    }
}

/// Advance the displayed phase with hysteresis: only switch once a
/// *different* sampled phase has persisted for `hold`. `candidate` carries
/// the pending (phase, first-seen) across calls. Pure, so the anti-flash
/// guarantee can be unit tested without threads.
fn next_displayed_phase(
    displayed: u8,
    sampled: u8,
    candidate: &mut Option<(u8, Instant)>,
    now: Instant,
    hold: Duration,
) -> u8 {
    if sampled == displayed {
        *candidate = None;
        return displayed;
    }
    match *candidate {
        Some((c, since)) if c == sampled => {
            if now.duration_since(since) >= hold {
                *candidate = None;
                sampled
            } else {
                displayed
            }
        }
        // A new (or different) challenger restarts the dwell timer, so
        // flapping between sub-steps never accumulates enough to switch.
        _ => {
            *candidate = Some((sampled, now));
            displayed
        }
    }
}

/// The one emitter thread for a session: throttled snapshots → broadcast.
/// Bound to `generation`; exits when the session is torn down (`shutdown`)
/// or replaced (a newer generation took over).
fn emitter_loop(
    session: Arc<SessionState>,
    events: broadcast::Sender<CatalogChange>,
    generation: u64,
) {
    let mut last_emit: Option<Instant> = None;
    let mut displayed_phase = session.sampled_phase.load(Ordering::Relaxed);
    let mut candidate: Option<(u8, Instant)> = None;

    loop {
        thread::sleep(EMITTER_TICK);
        if session.shutdown.load(Ordering::Relaxed) || session.generation != generation {
            break;
        }

        let processed = session.processed.load(Ordering::Relaxed);
        let total = effective_total(
            session.total.load(Ordering::Relaxed),
            session.submitted.load(Ordering::Relaxed),
        );
        let elapsed = session.started.elapsed();
        let work_remaining = total == 0 || processed < total;

        // Smooth the phase label every tick — even on ticks we don't emit —
        // so the candidate's dwell timer keeps advancing.
        displayed_phase = next_displayed_phase(
            displayed_phase,
            session.sampled_phase.load(Ordering::Relaxed),
            &mut candidate,
            Instant::now(),
            PHASE_HYSTERESIS,
        );

        // Gate: don't announce a session until it's clearly long-running.
        if !session.announced.load(Ordering::Relaxed) {
            if elapsed < ANNOUNCE_AFTER || !work_remaining || total < 2 {
                continue;
            }
            session.announced.store(true, Ordering::Relaxed);
            let _ = events.send(CatalogChange::PostIndexStarted { total });
            last_emit = None; // emit a first progress snapshot immediately
        }

        if last_emit.map(|t| t.elapsed() < EMIT_EVERY).unwrap_or(false) {
            continue;
        }
        last_emit = Some(Instant::now());

        let detail = session
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
            phase: phase_label(displayed_phase).to_string(),
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
    /// This pass's ticket into the process-wide reporter. Workers report
    /// through clones of it. `session: None` when reporting is disabled.
    token: PassToken,
    /// Broadcast bus, kept only so `finish()` can forward the closing
    /// `PostIndexCompleted` via [`PostIndexReporter::finish_pass`]. `None`
    /// when the caller didn't wire up progress reporting (tests / CLI).
    events: Option<broadcast::Sender<CatalogChange>>,
}

impl Handle {
    /// Queue a freshly-indexed video for post-index processing.
    /// Blocks briefly if the bounded channel is full (backpressure
    /// on the scan). Logs a warning if the channel is closed,
    /// which shouldn't happen during a normal scan.
    pub fn submit(&self, video_id: String) {
        if let Some(tx) = &self.tx {
            self.token.note_submitted();
            if let Err(e) = tx.send(video_id) {
                tracing::warn!("post-index channel closed unexpectedly: {}", e);
            }
        }
    }

    /// Drop the sender so workers see EOF, then join. Call once the
    /// scan's `par_iter` has finished pushing ids. Then deregister this pass
    /// from the shared reporter, which stops the emitter and publishes the
    /// closing `PostIndexCompleted` only when this was the last overlapping
    /// pass (a concurrent rescan/watcher wave keeps the banner up).
    pub fn finish(mut self) {
        drop(self.tx.take());
        for w in self.workers.drain(..) {
            let _ = w.join();
        }
        // Workers are done — this pass's contribution to `processed` is now
        // final. Nothing to do when progress reporting is disabled.
        if let (Some(session), Some(events)) = (&self.token.session, &self.events) {
            reporter().finish_pass(session, events);
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

    // Register with the process-wide reporter only when a bus was supplied.
    // Overlapping passes coalesce into one session there; the CLI/tests pass
    // `None` and run with progress reporting fully disabled (no global init,
    // no emitter, no atomics on the worker path).
    let token = PassToken {
        session: events
            .as_ref()
            .map(|sink| reporter().register_pass(expected_total, sink.clone())),
    };

    let mut workers = Vec::with_capacity(num_workers);
    for worker_idx in 0..num_workers {
        let rx = Arc::clone(&rx);
        let db = Arc::clone(&db);
        let cache = thumbnail_cache.clone();
        let dec = Arc::clone(&decision_mutex);
        let tok = token.clone();
        workers.push(
            thread::Builder::new()
                .name(format!("post-index-{}", worker_idx))
                .spawn(move || worker_loop(rx, db, cache, dec, options, tok))
                .expect("failed to spawn post-index worker thread"),
        );
    }

    Handle {
        tx: Some(tx),
        workers,
        token,
        events,
    }
}

fn worker_loop(
    rx: Arc<Mutex<mpsc::Receiver<String>>>,
    db: Arc<Database>,
    thumbnail_cache: PathBuf,
    decision_mutex: Arc<Mutex<()>>,
    options: Options,
    token: PassToken,
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
            &token,
        ) {
            tracing::warn!(video_id = %video_id, error = %e, "post-index processing failed");
        }
        // Count the video as handled whether or not a decision was made,
        // so the progress denominator matches what the caller submitted.
        token.add_processed();
    }
}

fn process_one(
    db: &Database,
    thumbnail_cache: &Path,
    decision_mutex: &Mutex<()>,
    options: &Options,
    video_id: &str,
    token: &PassToken,
) -> Result<()> {
    // Stack/group decision first — proxy detection uses group_id as a
    // bucket key when present, so we want the group settled before
    // proxy logic runs.
    if options.auto_group {
        token.set_phase(PHASE_GROUPING);
        join_or_create_group(db, decision_mutex, video_id, token)?;
    }
    if options.detect_proxies {
        token.set_phase(PHASE_PROXIES);
        detect_proxy_for(db, thumbnail_cache, decision_mutex, video_id, token)?;
    }
    if options.sensor_fetch {
        token.set_phase(PHASE_SENSORS);
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
        token.set_phase(PHASE_TAGGING);
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
    token: &PassToken,
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
        token.record_detail(format!("grouped {}", cand.filename));
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
        token.record_detail(format!("grouped {} ({} clips)", cand.filename, members.len()));
    }

    Ok(())
}

// ---- Proxy decision -----------------------------------------------------

fn detect_proxy_for(
    db: &Database,
    thumbnail_cache: &Path,
    decision_mutex: &Mutex<()>,
    video_id: &str,
    token: &PassToken,
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
        token.record_detail(format!(
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

    #[test]
    fn effective_total_prefers_estimate_then_submitted() {
        // Known estimate wins, even if submit count differs.
        assert_eq!(effective_total(120, 7), 120);
        // Unknown estimate (0) falls back to the running submit count.
        assert_eq!(effective_total(0, 7), 7);
        // Nothing known yet.
        assert_eq!(effective_total(0, 0), 0);
    }

    #[test]
    fn hysteresis_holds_steady_while_phase_flaps() {
        // This is the anti-flash guarantee: when workers from overlapping
        // passes rapidly stamp different sub-steps, the displayed label must
        // not move.
        let hold = Duration::from_millis(2000);
        let t0 = Instant::now();
        let mut displayed = PHASE_PROXIES;
        let mut candidate: Option<(u8, Instant)> = None;

        // Sampled jumps to TAGGING: candidate starts, label unchanged.
        displayed = next_displayed_phase(displayed, PHASE_TAGGING, &mut candidate, t0, hold);
        assert_eq!(displayed, PHASE_PROXIES);
        assert!(candidate.is_some());

        // Flaps back to the displayed phase well within the hold: the
        // candidate is cleared, so its dwell never accumulates.
        displayed = next_displayed_phase(
            displayed,
            PHASE_PROXIES,
            &mut candidate,
            t0 + Duration::from_millis(300),
            hold,
        );
        assert_eq!(displayed, PHASE_PROXIES);
        assert!(candidate.is_none());

        // TAGGING again, then GROUPING — each different challenger resets
        // the timer, so no single phase dominates long enough to switch.
        displayed = next_displayed_phase(
            displayed,
            PHASE_TAGGING,
            &mut candidate,
            t0 + Duration::from_millis(600),
            hold,
        );
        displayed = next_displayed_phase(
            displayed,
            PHASE_GROUPING,
            &mut candidate,
            t0 + Duration::from_millis(900),
            hold,
        );
        assert_eq!(displayed, PHASE_PROXIES);
    }

    #[test]
    fn hysteresis_switches_after_sustained_phase() {
        let hold = Duration::from_millis(2000);
        let t0 = Instant::now();
        let mut displayed = PHASE_PROXIES;
        let mut candidate: Option<(u8, Instant)> = None;

        // TAGGING appears and stays put.
        displayed = next_displayed_phase(displayed, PHASE_TAGGING, &mut candidate, t0, hold);
        assert_eq!(displayed, PHASE_PROXIES); // not yet — dwell not met

        // Still short of the hold: no switch.
        displayed = next_displayed_phase(
            displayed,
            PHASE_TAGGING,
            &mut candidate,
            t0 + Duration::from_millis(1999),
            hold,
        );
        assert_eq!(displayed, PHASE_PROXIES);

        // Crosses the hold: switches exactly once, candidate cleared.
        displayed = next_displayed_phase(
            displayed,
            PHASE_TAGGING,
            &mut candidate,
            t0 + Duration::from_millis(2000),
            hold,
        );
        assert_eq!(displayed, PHASE_TAGGING);
        assert!(candidate.is_none());
    }
}
