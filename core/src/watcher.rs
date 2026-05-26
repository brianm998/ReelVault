// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

//! Real-time library watcher.
//!
//! Wraps `notify::RecommendedWatcher` so the daemon picks up new and changed
//! files in registered library locations without the user explicitly running
//! `ScanLibrary`. Two things make this less trivial than "scan on every
//! event":
//!
//! 1. Write-settle gating. Camera apps, OBS, ffmpeg, network uploads — all
//!    of them open a file, write growing bytes for many seconds, and then
//!    close it. notify happily fires `Modify(Data)` events the whole time.
//!    Indexing a half-written file means ffprobe sees a truncated moov atom
//!    and returns garbage metadata. Solution: queue events in a pending
//!    map and only run the scan once the file's size has remained stable
//!    for `settle_ms` ms. Configurable via `Config::watch_write_settle_ms`.
//!
//! 2. Poll fallback. FSEvents and inotify don't see anything on NFS / SMB /
//!    a SAN mount that crosses kernel boundaries. The user's "I just dropped
//!    new footage into the SAN" workflow has to work or this feature is
//!    useless on the very case it was added for. So for every watched path
//!    we also run a shallow readdir on a configurable interval and fold any
//!    new entries into the same pending map. Same settle logic catches them.
//!
//! The watcher runs on a dedicated OS thread (not a tokio task) so it can use
//! `notify`'s synchronous API directly and isn't perturbed by the tokio
//! reactor's other work. It communicates with the gRPC service via a
//! `tokio::sync::broadcast` channel of [`CatalogChange`]s, which is also what
//! the `SubscribeCatalogEvents` RPC streams to clients (after a per-event
//! protobuf conversion in `service.rs`).

use crate::db::Database;
use crate::error::Result;
use crate::indexing::{is_supported_video_extension, IndexingEngine, ScanFileOutcome};
use notify::{EventKind, RecommendedWatcher, RecursiveMode, Watcher};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::mpsc as std_mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::sync::broadcast;

/// Files that have failed indexing (e.g. "moov atom not found"), keyed by
/// path with the file size at the time of failure.
///
/// While a path is in this map *with the same size*, the poll fallback will
/// not re-queue it — avoiding the WARN-every-30-seconds spam for in-progress
/// exports. When the file's size changes (the write completes), the entry is
/// evicted and normal retry resumes. Notify-driven events (genuine
/// OS-level Modify) also clear the entry unconditionally.
type FailedFiles = Arc<Mutex<HashMap<PathBuf, u64>>>;

/// Domain-level change event emitted by the watcher. The gRPC service
/// converts this to the proto `CatalogEvent` before sending to clients.
///
/// `path` is always populated — even for `VideoRemoved`, where it lets the
/// client display "lost track of X.mov" without round-tripping the DB.
#[derive(Debug, Clone)]
pub enum CatalogChange {
    VideoAdded { video_id: String, path: PathBuf },
    VideoModified { video_id: String, path: PathBuf },
    /// The file disappeared. `video_id` is None when the path wasn't in the
    /// catalog (we observed a delete for something we never indexed —
    /// nothing to do).
    VideoRemoved { video_id: Option<String>, path: PathBuf },
    /// A user-driven `ScanLibrary` RPC just started. Clients use this to
    /// show a "scanning…" banner that doesn't depend on the per-file
    /// progress stream (so it survives a reconnect mid-scan).
    ScanStarted { path: String },
    /// The matching companion to `ScanStarted` — emitted from the service
    /// after the scan's spawn_blocking task returns.
    ScanCompleted { path: String },
}

/// Per-path settle state. The watcher only triggers a scan once
/// `(now - last_event_at) >= settle_ms` AND the file size hasn't moved
/// since `last_seen_size_at`.
#[derive(Debug, Clone)]
struct PendingFile {
    /// Size as of `last_seen_size_at`. Sentinel value `u64::MAX` means
    /// we haven't been able to stat the file yet (e.g. permission error
    /// during the burst-of-events phase).
    last_seen_size: u64,
    last_seen_size_at: Instant,
    /// Most recent event timestamp. Used to suppress scanning a file
    /// that's currently being written to: every Modify(Data) bumps this,
    /// pushing the settle deadline forward.
    last_event_at: Instant,
}

/// Commands sent from gRPC handlers to the watcher's background loop.
#[derive(Debug)]
enum WatcherCmd {
    AddPath { path: PathBuf, recursive: bool },
    RemovePath { path: PathBuf },
    UpdateSettings { settle_ms: u64, poll_ms: u64 },
    Shutdown,
}

/// Handle to the running watcher. Drop = shutdown. Cloning the handle is
/// intentionally not supported — there should be exactly one watcher per
/// catalog, owned by the service.
pub struct LibraryWatcher {
    cmd_tx: std_mpsc::Sender<WatcherCmd>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl LibraryWatcher {
    /// Spin up the watcher. The thread monitors `initial_paths` immediately
    /// and starts publishing `CatalogChange`s to `events`. Returns Err only
    /// if the notify::Watcher itself fails to construct (rare).
    pub fn start(
        db: Arc<Database>,
        thumbnail_cache: PathBuf,
        events: broadcast::Sender<CatalogChange>,
        settle_ms: u64,
        poll_ms: u64,
        initial_paths: Vec<(PathBuf, bool)>,
    ) -> Result<Self> {
        let (cmd_tx, cmd_rx) = std_mpsc::channel::<WatcherCmd>();

        let thread = std::thread::Builder::new()
            .name("videoroom-watcher".to_string())
            .spawn(move || {
                run_watcher(db, thumbnail_cache, events, settle_ms, poll_ms, initial_paths, cmd_rx);
            })
            .map_err(crate::error::VideoRoomError::IoError)?;

        Ok(LibraryWatcher {
            cmd_tx,
            thread: Some(thread),
        })
    }

    pub fn add_path(&self, path: PathBuf, recursive: bool) {
        let _ = self.cmd_tx.send(WatcherCmd::AddPath { path, recursive });
    }

    pub fn remove_path(&self, path: PathBuf) {
        let _ = self.cmd_tx.send(WatcherCmd::RemovePath { path });
    }

    pub fn update_settings(&self, settle_ms: u64, poll_ms: u64) {
        let _ = self.cmd_tx.send(WatcherCmd::UpdateSettings { settle_ms, poll_ms });
    }
}

impl Drop for LibraryWatcher {
    fn drop(&mut self) {
        let _ = self.cmd_tx.send(WatcherCmd::Shutdown);
        if let Some(t) = self.thread.take() {
            // Best-effort join — we don't want to block the gRPC server's
            // shutdown for more than a moment if the watcher is stuck.
            let _ = t.join();
        }
    }
}

/// Watcher main loop. Owns the notify::Watcher and the pending map. Wakes
/// every ~250 ms to drain notify events, sweep settled files, run the poll
/// fallback if due, and check for new commands.
fn run_watcher(
    db: Arc<Database>,
    thumbnail_cache: PathBuf,
    events: broadcast::Sender<CatalogChange>,
    mut settle_ms: u64,
    mut poll_ms: u64,
    initial_paths: Vec<(PathBuf, bool)>,
    cmd_rx: std_mpsc::Receiver<WatcherCmd>,
) {
    let pending: Arc<Mutex<HashMap<PathBuf, PendingFile>>> = Arc::new(Mutex::new(HashMap::new()));
    let pending_clone = Arc::clone(&pending);

    // Tracks files whose last index attempt failed. While the file's size is
    // unchanged the poll fallback will not re-queue it, suppressing the
    // repeated WARN for in-progress or corrupt files.
    let failed_files: FailedFiles = Arc::new(Mutex::new(HashMap::new()));

    // Channel between the notify callback thread and the main loop.
    let (notify_tx, notify_rx) = std_mpsc::channel::<notify::Result<notify::Event>>();

    let mut watcher: RecommendedWatcher = match notify::recommended_watcher(
        move |res: notify::Result<notify::Event>| {
            // The callback is invoked on notify's internal thread.
            // Forward to the main loop's channel; drop on error.
            let _ = notify_tx.send(res);
        },
    ) {
        Ok(w) => w,
        Err(e) => {
            tracing::error!("Could not initialize notify watcher: {}. Live updates disabled.", e);
            return;
        }
    };

    // The set of paths we asked notify to watch. Used both to unwatch on
    // RemovePath and to drive the poll fallback.
    let mut watched_paths: Vec<(PathBuf, bool)> = Vec::new();

    for (p, recursive) in initial_paths {
        watch_path(&mut watcher, &p, recursive);
        watched_paths.push((p, recursive));
    }

    let mut last_poll = Instant::now();
    tracing::info!(
        "Library watcher running ({} initial path(s), settle={}ms, poll={}ms)",
        watched_paths.len(),
        settle_ms,
        poll_ms,
    );

    loop {
        // 1) Drain commands first so a quick AddPath/RemovePath followed by
        //    a Shutdown doesn't get reordered.
        loop {
            match cmd_rx.try_recv() {
                Ok(WatcherCmd::Shutdown) => return,
                Ok(WatcherCmd::AddPath { path, recursive }) => {
                    watch_path(&mut watcher, &path, recursive);
                    // De-dupe — if the same path is re-added with a different
                    // `recursive` flag we keep the most recent.
                    watched_paths.retain(|(p, _)| p != &path);
                    watched_paths.push((path, recursive));
                }
                Ok(WatcherCmd::RemovePath { path }) => {
                    let _ = watcher.unwatch(&path);
                    watched_paths.retain(|(p, _)| p != &path);
                }
                Ok(WatcherCmd::UpdateSettings {
                    settle_ms: new_settle,
                    poll_ms: new_poll,
                }) => {
                    settle_ms = new_settle;
                    poll_ms = new_poll;
                    tracing::info!(
                        "Watcher settings updated: settle={}ms, poll={}ms",
                        settle_ms,
                        poll_ms,
                    );
                }
                Err(std_mpsc::TryRecvError::Empty) => break,
                Err(std_mpsc::TryRecvError::Disconnected) => return,
            }
        }

        // 2) Drain notify events into the pending map.
        loop {
            match notify_rx.try_recv() {
                Ok(Ok(event)) => {
                    handle_notify_event(event, &pending_clone, &db, &events, &failed_files);
                }
                Ok(Err(e)) => {
                    tracing::debug!("notify error: {}", e);
                }
                Err(std_mpsc::TryRecvError::Empty) => break,
                Err(std_mpsc::TryRecvError::Disconnected) => break,
            }
        }

        // 3) Sweep the pending map for settled files.
        sweep_pending(&pending_clone, settle_ms, &db, &thumbnail_cache, &events, &failed_files);

        // 4) Poll fallback (NFS / SMB / SAN that doesn't deliver events).
        if poll_ms > 0 && last_poll.elapsed().as_millis() as u64 >= poll_ms {
            poll_paths(&watched_paths, &pending_clone, &db, &failed_files);
            last_poll = Instant::now();
        }

        // 5) Sleep a bit before the next tick. 250 ms keeps latency between
        //    "writer closes file" and "video appears in grid" under a second
        //    while still being cheap on CPU.
        std::thread::sleep(Duration::from_millis(250));
    }
}

fn watch_path(watcher: &mut RecommendedWatcher, path: &Path, recursive: bool) {
    let mode = if recursive {
        RecursiveMode::Recursive
    } else {
        RecursiveMode::NonRecursive
    };
    match watcher.watch(path, mode) {
        Ok(()) => tracing::info!(
            "Watching {} ({})",
            path.display(),
            if recursive { "recursive" } else { "shallow" }
        ),
        Err(e) => tracing::warn!(
            "Could not watch {} ({}). Will rely on poll fallback if enabled.",
            path.display(),
            e,
        ),
    }
}

/// Map a notify event onto the pending map.
///
/// `Create` / `Modify` on a supported video extension → upsert with the
/// current size and bump `last_event_at` so the settle deadline pushes
/// forward.
///
/// `Remove` → publish `VideoRemoved` immediately (no settle needed) and
/// soft-delete in the DB (`is_online = 0`).
fn handle_notify_event(
    event: notify::Event,
    pending: &Arc<Mutex<HashMap<PathBuf, PendingFile>>>,
    db: &Arc<Database>,
    events: &broadcast::Sender<CatalogChange>,
    failed_files: &FailedFiles,
) {
    for path in event.paths {
        // Quick reject: we don't care about directories or non-video files.
        if !is_supported_video_extension(&path) {
            continue;
        }

        match event.kind {
            EventKind::Create(_) | EventKind::Modify(_) => {
                // A real OS event means the file changed — clear any previous
                // failure record so the settle+index cycle runs fresh.
                failed_files.lock().ok().map(|mut m| m.remove(&path));
                queue_pending(pending, &path);
            }
            EventKind::Remove(_) => {
                // Pull from pending if it's there — no point scanning a
                // file that just vanished.
                pending.lock().ok().map(|mut p| p.remove(&path));
                let result = IndexingEngine::mark_offline(db.as_ref(), &path).ok().flatten();
                let _ = events.send(CatalogChange::VideoRemoved {
                    video_id: result,
                    path,
                });
            }
            // Access/AnyOther events don't tell us anything actionable.
            _ => {}
        }
    }
}

/// Insert-or-update a path in the pending map with a fresh `last_event_at`.
/// `last_seen_size` is read from disk; on failure we fall back to a sentinel
/// that prevents the file from ever settling — the sweep will pick it up
/// once stat succeeds.
fn queue_pending(pending: &Arc<Mutex<HashMap<PathBuf, PendingFile>>>, path: &Path) {
    let now = Instant::now();
    let size = current_file_size(path);
    if let Ok(mut map) = pending.lock() {
        let entry = map.entry(path.to_path_buf()).or_insert(PendingFile {
            last_seen_size: size.unwrap_or(u64::MAX),
            last_seen_size_at: now,
            last_event_at: now,
        });
        // If the size changed since last time we observed it, reset the
        // size-stability clock. last_event_at always bumps.
        match size {
            Some(s) if s != entry.last_seen_size => {
                entry.last_seen_size = s;
                entry.last_seen_size_at = now;
            }
            None => {
                // stat failed — keep prior value, but advance last_event_at.
            }
            Some(_) => { /* same size — leave last_seen_size_at alone */ }
        }
        entry.last_event_at = now;
    }
}

fn current_file_size(path: &Path) -> Option<u64> {
    std::fs::metadata(path).ok().map(|m| m.len())
}

/// Walk the pending map and (re-)scan any file whose size has been stable
/// for at least `settle_ms` ms.
fn sweep_pending(
    pending: &Arc<Mutex<HashMap<PathBuf, PendingFile>>>,
    settle_ms: u64,
    db: &Arc<Database>,
    thumbnail_cache: &Path,
    events: &broadcast::Sender<CatalogChange>,
    failed_files: &FailedFiles,
) {
    if settle_ms == 0 {
        // settle disabled — treat every entry as ready immediately.
    }

    let settle = Duration::from_millis(settle_ms.max(1));
    let now = Instant::now();

    // Pull out the settled entries under the lock, then run the (slow)
    // ffprobe / db work without holding it. The pending map is small
    // (~handful of entries at a time) so this is cheap.
    let mut ready: Vec<(PathBuf, PendingFile)> = Vec::new();
    if let Ok(mut map) = pending.lock() {
        let settled: Vec<PathBuf> = map
            .iter()
            .filter_map(|(p, pf)| {
                let stable_long_enough = now.duration_since(pf.last_seen_size_at) >= settle;
                let quiet_long_enough = now.duration_since(pf.last_event_at) >= settle;
                if stable_long_enough && quiet_long_enough && pf.last_seen_size != u64::MAX {
                    Some(p.clone())
                } else {
                    None
                }
            })
            .collect();
        for p in settled {
            if let Some(pf) = map.remove(&p) {
                ready.push((p, pf));
            }
        }
    }

    for (path, _pf) in ready {
        // Final stat check: if the file no longer exists (deleted between
        // settling and now), publish a removal instead of scanning.
        if !path.exists() {
            let video_id = IndexingEngine::mark_offline(db.as_ref(), &path).ok().flatten();
            let _ = events.send(CatalogChange::VideoRemoved { video_id, path });
            continue;
        }

        match IndexingEngine::scan_single_file(db.as_ref(), &path, thumbnail_cache, None) {
            Ok((video_id, ScanFileOutcome::Added)) => {
                tracing::info!("Watcher added: {}", path.display());
                let _ = events.send(CatalogChange::VideoAdded { video_id, path });
            }
            Ok((video_id, ScanFileOutcome::Modified)) => {
                tracing::info!("Watcher refreshed: {}", path.display());
                let _ = events.send(CatalogChange::VideoModified { video_id, path });
            }
            Err(e) => {
                let size_now = current_file_size(&path).unwrap_or(0);
                // Only log WARN the *first* time we fail at this size.
                // Subsequent poll cycles at the same size are silently
                // suppressed (the file is likely an in-progress export).
                let first_failure = failed_files
                    .lock()
                    .map(|mut m| {
                        let prev = m.insert(path.clone(), size_now);
                        prev != Some(size_now)
                    })
                    .unwrap_or(true);
                if first_failure {
                    tracing::warn!(
                        "Watcher failed to index {} (size {}B, will retry when size changes): {}",
                        path.display(),
                        size_now,
                        e,
                    );
                } else {
                    tracing::debug!(
                        "Watcher: skipping retry for {} (same size {}B, still failing)",
                        path.display(),
                        size_now,
                    );
                }
            }
        }
    }
}

/// Poll-fallback for paths where the OS-level watcher can't deliver events
/// (NFS / SMB / SAN). Walks each watched path's tree, looking for files
/// that the catalog doesn't know about or whose size has changed since
/// last index, and folds them into the pending map. Same settle logic
/// catches half-written uploads.
///
/// Conscious tradeoff: this can be expensive on a deep SAN tree. The
/// interval is user-configurable for exactly this reason. Default 30 s
/// gives "noticed within half a minute" UX without thrashing the server.
fn poll_paths(
    watched_paths: &[(PathBuf, bool)],
    pending: &Arc<Mutex<HashMap<PathBuf, PendingFile>>>,
    db: &Arc<Database>,
    failed_files: &FailedFiles,
) {
    for (root, recursive) in watched_paths {
        if !root.exists() {
            continue;
        }

        let walker = walkdir::WalkDir::new(root)
            .follow_links(false)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| *recursive || e.depth() <= 1);

        for entry in walker {
            let p = entry.path();
            if !p.is_file() || !is_supported_video_extension(p) {
                continue;
            }
            let size_now = match current_file_size(p) {
                Some(s) => s,
                None => continue,
            };

            // Only enqueue if either we haven't seen this file (new on
            // disk → not in catalog) OR its size differs from the catalog's
            // record (file was overwritten / appended off-FSEvents path).
            let existing = db.get_video_by_path(p.to_str().unwrap_or("")).ok().flatten();
            let interesting = match &existing {
                None => true, // new file
                Some(v) => v.file_size_bytes.unwrap_or(-1) as u64 != size_now,
            };
            if !interesting {
                continue;
            }

            // Suppress re-queueing files that previously failed at this exact
            // size (e.g. an in-progress export whose moov atom isn't written
            // yet). If the size has grown since the last failure, evict the
            // entry and let the normal retry path fire.
            let skip = failed_files
                .lock()
                .map(|mut m| match m.get(p) {
                    Some(&failed_size) if failed_size == size_now => true,
                    Some(_) => {
                        m.remove(p); // size changed → file may now be healthy
                        false
                    }
                    None => false,
                })
                .unwrap_or(false);
            if skip {
                continue;
            }

            queue_pending(pending, p);
        }
    }
}

/// Server-side helper for stamping events with Unix milliseconds.
#[allow(dead_code)]
pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}
