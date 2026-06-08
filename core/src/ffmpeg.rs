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

use std::path::PathBuf;
use std::process::Command;
use std::sync::OnceLock;

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

#[cfg(test)]
mod tests {
    use super::*;

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
            std::env::set_var("REELVAULT_FFPROBE_TEST_ONLY", here.to_string_lossy().to_string());
            let resolved = resolve("ffprobe", "REELVAULT_FFPROBE_TEST_ONLY");
            assert_eq!(resolved, here);
            std::env::remove_var("REELVAULT_FFPROBE_TEST_ONLY");
        }
    }
}
