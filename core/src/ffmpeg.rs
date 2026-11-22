// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Resolves the `ffmpeg` / `ffprobe` executables the daemon shells out to,
//! preferring a copy **bundled alongside the daemon** so ReelVault doesn't
//! depend on a system-wide ffmpeg install.
//!
//! Resolution order (resolved once, then cached):
//!   1. `REELVAULT_FFMPEG` / `REELVAULT_FFPROBE` env var pointing at a file —
//!      an explicit override (used by tests and packagers).
//!   2. A binary bundled next to the running daemon executable:
//!      `<exe_dir>/ffmpeg`, `<exe_dir>/bin/ffmpeg`, or `<exe_dir>/ffmpeg/ffmpeg`
//!      (`.exe` on Windows). This is what a release bundle ships.
//!   3. The bare name `ffmpeg` / `ffprobe`, resolved via `PATH` — the legacy
//!      behaviour, kept as a fallback for dev machines with ffmpeg installed.
//!
//! Every call site uses [`ffmpeg_command`] / [`ffprobe_command`] instead of
//! `Command::new("ffmpeg")`, so the whole daemon honours the bundled binary.

use std::io::{self, Read};
use std::path::PathBuf;
use std::process::{Command, ExitStatus, Output, Stdio};
use std::sync::OnceLock;
use std::thread;
use std::time::{Duration, Instant};

/// Platform-correct executable file name for a tool stem.
fn exe_file_name(stem: &str) -> String {
    if cfg!(windows) {
        format!("{stem}.exe")
    } else {
        stem.to_string()
    }
}

/// Resolve `stem` ("ffmpeg" / "ffprobe") to the binary the daemon should run.
/// See the module docs for the order. The PATH fallback returns the bare stem,
/// which `Command` resolves through `PATH` exactly as `Command::new("ffmpeg")`
/// did before.
fn resolve(stem: &str, env_var: &str) -> PathBuf {
    // 1. Explicit override.
    if let Ok(value) = std::env::var(env_var) {
        if !value.is_empty() {
            let path = PathBuf::from(&value);
            if path.is_file() {
                return path;
            }
        }
    }
    // 2. Bundled next to the daemon.
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let name = exe_file_name(stem);
            let candidates = [
                dir.join(&name),
                dir.join("bin").join(&name),
                dir.join("ffmpeg").join(&name),
            ];
            for candidate in candidates {
                if candidate.is_file() {
                    return candidate;
                }
            }
        }
    }
    // 3. PATH fallback (bare name).
    PathBuf::from(stem)
}

fn ffmpeg_path() -> &'static PathBuf {
    static PATH: OnceLock<PathBuf> = OnceLock::new();
    PATH.get_or_init(|| resolve("ffmpeg", "REELVAULT_FFMPEG"))
}

fn ffprobe_path() -> &'static PathBuf {
    static PATH: OnceLock<PathBuf> = OnceLock::new();
    PATH.get_or_init(|| resolve("ffprobe", "REELVAULT_FFPROBE"))
}

/// A `Command` for the resolved ffmpeg binary. Drop-in for `Command::new("ffmpeg")`.
pub fn ffmpeg_command() -> Command {
    Command::new(ffmpeg_path())
}

/// A `Command` for the resolved ffprobe binary. Drop-in for `Command::new("ffprobe")`.
pub fn ffprobe_command() -> Command {
    Command::new(ffprobe_path())
}

/// Deadline for a metadata probe (`ffprobe`, `-version`, small ffmpeg probes).
pub const PROBE_TIMEOUT: Duration = Duration::from_secs(60);
/// Deadline for extracting/resizing a single frame (ffmpeg, qlmanage, rv-frameshot).
pub const FRAME_TIMEOUT: Duration = Duration::from_secs(120);
/// Deadline for a stream-copy remux / metadata rewrite.
pub const REMUX_TIMEOUT: Duration = Duration::from_secs(30 * 60);
/// Deadline for a full-audio loudness analysis.
pub const LOUDNESS_TIMEOUT: Duration = Duration::from_secs(30 * 60);
/// Deadline for a full transcode / HLS encode / proxy generation.
pub const TRANSCODE_TIMEOUT: Duration = Duration::from_secs(6 * 60 * 60);

/// Deadline for a proxy transcode of `total_frames` frames (0 = unknown):
/// a budget of 0.5 s per frame (a 2 fps worst-case encode), floored at 10
/// minutes and capped at [`TRANSCODE_TIMEOUT`]. Unknown length gets the cap.
pub fn transcode_deadline(total_frames: i64) -> Duration {
    if total_frames <= 0 {
        return TRANSCODE_TIMEOUT;
    }
    let secs = (total_frames as u64 / 2).clamp(10 * 60, TRANSCODE_TIMEOUT.as_secs());
    Duration::from_secs(secs)
}

fn timeout_error(timeout: Duration) -> io::Error {
    io::Error::new(
        io::ErrorKind::TimedOut,
        format!("media subprocess timed out after {}s and was killed", timeout.as_secs()),
    )
}

/// True when `e` was produced by a [`run_with_timeout`] deadline expiry.
pub fn is_timeout(e: &io::Error) -> bool {
    e.kind() == io::ErrorKind::TimedOut
}

/// Spawn `cmd`, capture stdout/stderr, and wait at most `timeout`. On expiry the
/// child is killed and reaped and an `ErrorKind::TimedOut` error is returned.
/// Drop-in for `Command::output()` (stdin is null).
pub fn run_with_timeout(cmd: &mut Command, timeout: Duration) -> io::Result<Output> {
    let mut child = cmd
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;
    fn drain<R: Read + Send + 'static>(r: Option<R>) -> thread::JoinHandle<Vec<u8>> {
        thread::spawn(move || {
            let mut buf = Vec::new();
            if let Some(mut r) = r {
                let _ = r.read_to_end(&mut buf);
            }
            buf
        })
    }
    let out_h = drain(child.stdout.take());
    let err_h = drain(child.stderr.take());
    let deadline = Instant::now() + timeout;
    let status = loop {
        let polled = match child.try_wait() {
            Ok(p) => p,
            Err(e) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(e);
            }
        };
        match polled {
            Some(st) => break st,
            None if Instant::now() >= deadline => {
                let _ = child.kill();
                let _ = child.wait();
                // Reader threads end once the pipe closes; don't block on
                // grandchildren that may still hold it open.
                return Err(timeout_error(timeout));
            }
            None => thread::sleep(Duration::from_millis(10)),
        }
    };
    Ok(Output {
        status,
        stdout: out_h.join().unwrap_or_default(),
        stderr: err_h.join().unwrap_or_default(),
    })
}

/// Like [`run_with_timeout`] but discards output and returns only the exit status
/// (drop-in for `Command::status()`).
pub fn run_status_with_timeout(cmd: &mut Command, timeout: Duration) -> io::Result<ExitStatus> {
    run_with_timeout(cmd, timeout).map(|o| o.status)
}

/// Method-style access: `cmd.output_timeout(PROBE_TIMEOUT)`.
pub trait CommandTimeout {
    fn output_timeout(&mut self, timeout: Duration) -> io::Result<Output>;
    fn status_timeout(&mut self, timeout: Duration) -> io::Result<ExitStatus>;
}

impl CommandTimeout for Command {
    fn output_timeout(&mut self, timeout: Duration) -> io::Result<Output> {
        run_with_timeout(self, timeout)
    }
    fn status_timeout(&mut self, timeout: Duration) -> io::Result<ExitStatus> {
        run_status_with_timeout(self, timeout)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(unix)]
    #[test]
    fn timeout_kills_sleeping_child() {
        let mut c = Command::new("sh");
        c.args(["-c", "echo $$; exec sleep 30"]);
        let start = Instant::now();
        let err = run_with_timeout(&mut c, Duration::from_millis(300)).unwrap_err();
        assert!(is_timeout(&err));
        assert!(start.elapsed() < Duration::from_secs(10));
    }

    #[cfg(unix)]
    #[test]
    fn timeout_reaps_child_process() {
        let dir = std::env::temp_dir().join(format!("rv-timeout-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let pidfile = dir.join("pid");
        let mut c = Command::new("sh");
        c.arg("-c")
            .arg(format!("echo $$ > {}; exec sleep 30", pidfile.display()));
        let err = run_with_timeout(&mut c, Duration::from_millis(500)).unwrap_err();
        assert!(is_timeout(&err));
        let pid: i32 = std::fs::read_to_string(&pidfile).unwrap().trim().parse().unwrap();
        // The child is gone: `kill -0` fails.
        let alive = Command::new("kill").args(["-0", &pid.to_string()]).status().unwrap().success();
        assert!(!alive, "child {pid} still alive after timeout");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[test]
    fn fast_command_returns_output() {
        let mut c = Command::new("sh");
        c.args(["-c", "echo hi; echo err >&2"]);
        let o = run_with_timeout(&mut c, Duration::from_secs(10)).unwrap();
        assert!(o.status.success());
        assert_eq!(String::from_utf8_lossy(&o.stdout).trim(), "hi");
        assert_eq!(String::from_utf8_lossy(&o.stderr).trim(), "err");
    }

    #[test]
    fn path_fallback_is_bare_stem() {
        // With no env override and (almost certainly) no bundled binary next to
        // the test harness, resolution falls through to the bare PATH name.
        std::env::remove_var("REELVAULT_FFMPEG");
        // The resolver returns either a bundled file or the bare stem; in the
        // test environment it must be the bare stem (no ffmpeg ships beside the
        // test binary).
        let resolved = resolve("ffmpeg", "REELVAULT_FFMPEG");
        assert_eq!(resolved, PathBuf::from("ffmpeg"));
    }

    #[test]
    fn env_override_wins_when_file_exists() {
        // Point the override at a file that definitely exists (this source
        // file) and confirm it's chosen over the PATH fallback.
        let here = PathBuf::from(file!());
        if here.is_file() {
            std::env::set_var(
                "REELVAULT_FFPROBE_TEST_ONLY",
                here.to_string_lossy().to_string(),
            );
            let resolved = resolve("ffprobe", "REELVAULT_FFPROBE_TEST_ONLY");
            assert_eq!(resolved, here);
            std::env::remove_var("REELVAULT_FFPROBE_TEST_ONLY");
        }
    }
}
