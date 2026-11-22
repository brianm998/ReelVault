//! `--exit-with-parent`: closing the daemon's stdin makes it exit.
#![cfg(unix)]
use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

#[test]
fn daemon_exits_when_stdin_closes() {
    let mut child = Command::new(env!("CARGO_BIN_EXE_reelvault-core"))
        .args(["--port", "0", "--no-catalog", "--exit-with-parent"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .expect("spawn daemon");
    let stdin = child.stdin.take().unwrap();
    let mut out = BufReader::new(child.stdout.take().unwrap());
    let mut line = String::new();
    while !line.contains("REELVAULT_LISTENING_ON=") {
        line.clear();
        assert!(out.read_line(&mut line).unwrap() > 0, "daemon exited early");
    }
    assert!(child.try_wait().unwrap().is_none(), "daemon should be running");
    drop(stdin);
    let start = Instant::now();
    loop {
        if child.try_wait().unwrap().is_some() {
            break;
        }
        if start.elapsed() > Duration::from_secs(5) {
            let _ = child.kill();
            panic!("daemon still running 5s after stdin closed");
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}
