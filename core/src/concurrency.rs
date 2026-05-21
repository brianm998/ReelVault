// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

//! Concurrency primitives used to bound how many heavyweight external
//! processes (ffmpeg / ffprobe) we run at once.
//!
//! These wrap a global semaphore; the limit is configurable via
//! [`Config::max_concurrent_ffmpeg`] and applied at service start via
//! [`set_ffmpeg_concurrency_limit`].

use std::sync::{Arc, Condvar, Mutex, OnceLock};

/// A simple blocking counting semaphore. Backed by `Mutex<usize>` + `Condvar`,
/// so it works from synchronous code (where the ffmpeg/ffprobe invocations
/// live) without needing a tokio runtime.
pub struct BlockingSemaphore {
    available: Mutex<usize>,
    cv: Condvar,
}

impl BlockingSemaphore {
    pub fn new(permits: usize) -> Self {
        // Treat 0 as "no limit" — disabling the throttle is occasionally useful
        // for diagnostics; we represent that by using usize::MAX permits.
        let initial = if permits == 0 { usize::MAX } else { permits };
        BlockingSemaphore {
            available: Mutex::new(initial),
            cv: Condvar::new(),
        }
    }

    /// Block until a permit is available, then take one. The returned guard
    /// releases the permit when dropped.
    pub fn acquire(self: &Arc<Self>) -> Permit {
        let mut guard = self.available.lock().expect("ffmpeg sem poisoned");
        while *guard == 0 {
            guard = self.cv.wait(guard).expect("ffmpeg sem poisoned");
        }
        *guard -= 1;
        Permit {
            sem: Arc::clone(self),
        }
    }
}

pub struct Permit {
    sem: Arc<BlockingSemaphore>,
}

impl Drop for Permit {
    fn drop(&mut self) {
        let mut guard = self.sem.available.lock().expect("ffmpeg sem poisoned");
        *guard = guard.saturating_add(1);
        self.sem.cv.notify_one();
    }
}

static FFMPEG_SEMAPHORE: OnceLock<Arc<BlockingSemaphore>> = OnceLock::new();

/// The recommended default concurrency for ffmpeg/ffprobe: the number of
/// available CPU cores. This matches what an unthrottled ffmpeg would happily
/// saturate without piling unrelated work on top of it, and scales naturally
/// from a small laptop to a many-core workstation.
///
/// Falls back to 4 if `available_parallelism()` returns an error (very rare —
/// only in unusual sandboxed environments).
pub fn default_max_concurrent_ffmpeg() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
}

/// Set the maximum number of concurrent ffmpeg/ffprobe invocations. Should be
/// called once at startup; later calls are ignored (`OnceLock`).
///
/// A value of 0 disables the throttle entirely.
pub fn set_ffmpeg_concurrency_limit(n: usize) {
    let _ = FFMPEG_SEMAPHORE.set(Arc::new(BlockingSemaphore::new(n)));
}

/// Acquire a permit; if the semaphore wasn't configured yet we lazily install
/// the CPU-count default.
pub fn acquire_ffmpeg_permit() -> Permit {
    FFMPEG_SEMAPHORE
        .get_or_init(|| Arc::new(BlockingSemaphore::new(default_max_concurrent_ffmpeg())))
        .acquire()
}
