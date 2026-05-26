// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

use anyhow::Result;
use clap::Parser;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::net::TcpListener;
use tokio_stream::wrappers::TcpListenerStream;
use tonic::transport::Server;

use videoroom_core::config::Config;
use videoroom_core::db::Database;
use videoroom_core::service::VideoRoomService;

/// VideoRoom backend daemon.
///
/// Serves a gRPC API over loopback. By default it binds port 50051 and opens
/// the platform-default catalog. Use `--system-daemon` to run as a
/// launchd/systemd/Windows Service that shares one catalog across all users.
#[derive(Parser, Debug)]
#[command(name = "videoroom-core", about, version)]
struct Args {
    /// SQLite catalog file to open at startup. Use `--no-catalog` to start
    /// without one — the client can call `OpenCatalog` later. If neither is
    /// passed, falls back to the platform-default location (system-wide when
    /// `--system-daemon` is set, per-user otherwise).
    #[arg(long, value_name = "PATH")]
    db_path: Option<PathBuf>,

    /// Start with no catalog open. Clients must call `OpenCatalog` before
    /// using any other RPC.
    #[arg(long, default_value_t = false)]
    no_catalog: bool,

    /// gRPC port to bind. `0` means "let the OS pick" — the actual port is
    /// printed to stdout as `VIDEOROOM_LISTENING_ON=127.0.0.1:N` so a parent
    /// process can capture it.
    #[arg(long, default_value_t = 50051u16)]
    port: u16,

    /// Loopback host to bind. Defaults to `127.0.0.1`. Override only if you
    /// have a reason to expose the daemon (note: there's no auth!).
    #[arg(long, default_value = "127.0.0.1")]
    host: String,

    /// Run as a system-level daemon (started by launchd, systemd, or Windows
    /// Service Manager). In this mode the daemon uses the shared system catalog,
    /// logs to the platform log directory, and writes a PID file.
    #[arg(long, default_value_t = false)]
    system_daemon: bool,

    /// File to write structured log output to. When `--system-daemon` is set,
    /// defaults to the platform log directory. Unused in interactive mode
    /// (logs go to stderr).
    ///
    /// macOS default:   /Library/Logs/VideoRoom/videoroom-core.log
    /// Linux default:   /var/log/videoroom/videoroom-core.log
    /// Windows default: C:\ProgramData\VideoRoom\logs\videoroom-core.log
    #[arg(long, value_name = "PATH")]
    log_file: Option<PathBuf>,

    /// PID file written in `--system-daemon` mode so the service manager can
    /// track the process.
    ///
    /// macOS/Linux default: /var/run/videoroom-core.pid
    /// Windows default:     C:\ProgramData\VideoRoom\videoroom-core.pid
    #[arg(long, value_name = "PATH")]
    pid_file: Option<PathBuf>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    // Initialize logging before anything else. In system-daemon mode we write
    // to a log file; interactively we write to stderr. The guard must live for
    // the duration of main() to keep the non-blocking writer flushed.
    let log_path: Option<PathBuf> = if args.system_daemon || args.log_file.is_some() {
        let path = args
            .log_file
            .clone()
            .unwrap_or_else(|| get_log_dir().join("videoroom-core.log"));
        Some(path)
    } else {
        None
    };
    let _log_guard = init_logging(log_path.as_deref());

    tracing::info!("VideoRoom Core v{}", env!("CARGO_PKG_VERSION"));

    // Write a PID file in system-daemon mode.
    let pid_path: Option<PathBuf> = if args.system_daemon {
        let path = args.pid_file.clone().unwrap_or_else(get_pid_file_path);
        match write_pid_file(&path) {
            Ok(()) => {
                tracing::info!("PID file: {}", path.display());
                Some(path)
            }
            Err(e) => {
                tracing::warn!("Could not write PID file {}: {}", path.display(), e);
                None
            }
        }
    } else {
        None
    };

    // Decide which catalog to open at startup.
    let db = if args.no_catalog {
        tracing::info!("No catalog at startup (waiting for client's OpenCatalog)");
        Arc::new(Database::new_empty())
    } else {
        let path = match args.db_path {
            Some(ref p) => p.clone(),
            None => {
                if args.system_daemon {
                    get_system_catalog_path()?
                } else {
                    get_default_db_path()?
                }
            }
        };
        tracing::info!("Database: {}", path.display());
        let db = Arc::new(Database::new(&path)?);
        db.initialize().await?;
        db
    };

    let config = Arc::new(Config::load(db.as_ref()).await?);
    tracing::info!("Cache: {}", config.thumbnail_cache_path.display());

    videoroom_core::concurrency::set_ffmpeg_concurrency_limit(
        config.max_concurrent_ffmpeg.max(0) as usize,
    );
    tracing::info!(
        "Max concurrent ffmpeg: {}",
        if config.max_concurrent_ffmpeg <= 0 {
            "unlimited".to_string()
        } else {
            config.max_concurrent_ffmpeg.to_string()
        }
    );

    let service = VideoRoomService::new(db, config);
    let server = service.into_server();

    let host: IpAddr = args
        .host
        .parse()
        .unwrap_or(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)));
    let preferred = SocketAddr::new(host, args.port);
    let listener = match TcpListener::bind(preferred).await {
        Ok(l) => l,
        Err(e) => {
            tracing::warn!(
                "Could not bind {}: {}. Falling back to an OS-assigned port.",
                preferred,
                e
            );
            TcpListener::bind(SocketAddr::new(host, 0)).await?
        }
    };
    let local = listener.local_addr()?;

    // This sentinel is parsed by desktop clients that spawn the daemon —
    // keep the prefix stable. It is also useful in service logs for confirming
    // the daemon came up correctly.
    println!("VIDEOROOM_LISTENING_ON=127.0.0.1:{}", local.port());
    tracing::info!("gRPC server listening on {}", local);
    if args.system_daemon {
        tracing::info!("Running as system daemon — shared catalog, port {}", local.port());
    }
    tracing::info!("Backend ready");
    // Flush stdout so any parent process that reads the sentinel line sees it.
    use std::io::Write as _;
    let _ = std::io::stdout().flush();

    Server::builder()
        .add_service(server)
        .serve_with_incoming(TcpListenerStream::new(listener))
        .await?;

    if let Some(ref path) = pid_path {
        remove_pid_file(path);
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Logging initialisation
// ---------------------------------------------------------------------------

/// Set up the global tracing subscriber.
///
/// When `log_file` is given, output is written there (non-blocking, appended).
/// Otherwise output goes to stderr. Returns the non-blocking guard; the caller
/// must keep it alive until the process exits.
fn init_logging(log_file: Option<&Path>) -> Option<tracing_appender::non_blocking::WorkerGuard> {
    use tracing_subscriber::fmt;

    if let Some(path) = log_file {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        match std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(path)
        {
            Ok(file) => {
                let (nb, guard) = tracing_appender::non_blocking(file);
                fmt()
                    .with_ansi(false)
                    .with_writer(nb)
                    .with_max_level(tracing::Level::INFO)
                    .init();
                return Some(guard);
            }
            Err(e) => {
                eprintln!(
                    "Warning: cannot open log file {}: {}. Logging to stderr.",
                    path.display(),
                    e
                );
            }
        }
    }

    fmt().with_max_level(tracing::Level::INFO).init();
    None
}

// ---------------------------------------------------------------------------
// Path helpers
// ---------------------------------------------------------------------------

/// Platform log directory for the system daemon.
fn get_log_dir() -> PathBuf {
    if cfg!(target_os = "macos") {
        PathBuf::from("/Library/Logs/VideoRoom")
    } else if cfg!(target_os = "windows") {
        PathBuf::from(r"C:\ProgramData\VideoRoom\logs")
    } else {
        PathBuf::from("/var/log/videoroom")
    }
}

/// System-wide catalog path used when `--system-daemon` is set.
fn get_system_catalog_path() -> Result<PathBuf> {
    let dir = if cfg!(target_os = "macos") {
        PathBuf::from("/Library/Application Support/VideoRoom")
    } else if cfg!(target_os = "windows") {
        PathBuf::from(r"C:\ProgramData\VideoRoom")
    } else {
        PathBuf::from("/var/lib/videoroom")
    };
    std::fs::create_dir_all(&dir)?;
    Ok(dir.join("catalog.db"))
}

/// Per-user catalog path used in interactive (non-daemon) mode.
fn get_default_db_path() -> Result<PathBuf> {
    let data_dir = if cfg!(target_os = "macos") {
        dirs::home_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine home directory"))?
            .join("Library")
            .join("Application Support")
            .join("VideoRoom")
    } else if cfg!(target_os = "windows") {
        dirs::data_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine data directory"))?
            .join("VideoRoom")
    } else {
        dirs::data_local_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine data directory"))?
            .join("videoroom")
    };
    std::fs::create_dir_all(&data_dir)?;
    Ok(data_dir.join("catalog.db"))
}

/// Default PID file path for `--system-daemon` mode.
fn get_pid_file_path() -> PathBuf {
    if cfg!(target_os = "windows") {
        PathBuf::from(r"C:\ProgramData\VideoRoom\videoroom-core.pid")
    } else {
        PathBuf::from("/var/run/videoroom-core.pid")
    }
}

fn write_pid_file(path: &Path) -> Result<()> {
    use std::io::Write as _;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let mut f = std::fs::File::create(path)?;
    writeln!(f, "{}", std::process::id())?;
    Ok(())
}

fn remove_pid_file(path: &Path) {
    let _ = std::fs::remove_file(path);
}
