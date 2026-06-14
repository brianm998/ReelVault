// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

use anyhow::Result;
use clap::Parser;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::net::TcpListener;
use tokio_stream::wrappers::TcpListenerStream;
use tonic::transport::{Identity as TonicIdentity, Server, ServerTlsConfig};

use tonic::service::interceptor::InterceptedService;

use reelvault_core::auth;
use reelvault_core::config::Config;
use reelvault_core::db::Database;
use reelvault_core::discovery;
use reelvault_core::identity;
use reelvault_core::media_server;
use reelvault_core::service::ReelVaultService;

/// ReelVault backend daemon.
///
/// Serves a gRPC API over loopback. By default it binds port 50051 and opens
/// the platform-default catalog. Use `--system-daemon` to run as a
/// launchd/systemd/Windows Service that shares one catalog across all users.
#[derive(Parser, Debug)]
#[command(name = "reelvault-core", about, version)]
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
    /// printed to stdout as `REELVAULT_LISTENING_ON=127.0.0.1:N` so a parent
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
    /// macOS default:   /Library/Logs/ReelVault/reelvault-core.log
    /// Linux default:   /var/log/reelvault/reelvault-core.log
    /// Windows default: C:\ProgramData\ReelVault\logs\reelvault-core.log
    #[arg(long, value_name = "PATH")]
    log_file: Option<PathBuf>,

    /// PID file written in `--system-daemon` mode so the service manager can
    /// track the process.
    ///
    /// macOS/Linux default: /var/run/reelvault-core.pid
    /// Windows default:     C:\ProgramData\ReelVault\reelvault-core.pid
    #[arg(long, value_name = "PATH")]
    pid_file: Option<PathBuf>,

    /// Expose the daemon to LAN clients (e.g. the iOS app): in addition to the
    /// loopback plaintext listener, bind a TLS gRPC listener on the LAN IP using
    /// a persisted self-signed certificate. Off by default, so the desktop
    /// subprocess case is byte-for-byte unchanged.
    #[arg(long, default_value_t = false)]
    remote: bool,

    /// Interface IP for the `--remote` TLS listener. Defaults to the
    /// auto-detected primary non-loopback IPv4. Use `0.0.0.0` for all
    /// interfaces (then pick a `--remote-grpc-port` other than `--port`).
    #[arg(long, value_name = "IP")]
    remote_host: Option<String>,

    /// Port for the `--remote` TLS gRPC listener. Defaults to 50051 (same number
    /// as `--port`, but bound to the LAN IP, so the two don't collide).
    #[arg(long, default_value_t = 50051u16)]
    remote_grpc_port: u16,

    /// Human-friendly server name baked into the TLS certificate and advertised
    /// over mDNS. Defaults to the machine hostname.
    #[arg(long, value_name = "NAME")]
    advertise_name: Option<String>,

    /// Port for the `--remote` HTTPS media server (download/stream + upload).
    /// Advertised over mDNS so clients know where to stream from.
    #[arg(long, default_value_t = 50052u16)]
    media_port: u16,

    /// Directory where uploaded videos are stored on the server and then
    /// indexed. Persisted to the catalog config; required before clients can
    /// upload. Should live inside an enabled library location.
    #[arg(long, value_name = "PATH")]
    import_dir: Option<PathBuf>,
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
            .unwrap_or_else(|| get_log_dir().join("reelvault-core.log"));
        Some(path)
    } else {
        None
    };
    let _log_guard = init_logging(log_path.as_deref());

    tracing::info!("ReelVault Core v{}", env!("CARGO_PKG_VERSION"));

    raise_fd_limit();

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

    let mut config = Config::load(db.as_ref()).await?;
    if let Some(ref dir) = args.import_dir {
        config.import_dir = Some(dir.clone());
        if let Err(e) = config.save(db.as_ref()) {
            tracing::warn!("Could not persist --import-dir: {}", e);
        }
    }
    let config = Arc::new(config);
    tracing::info!("Cache: {}", config.thumbnail_cache_path.display());
    if let Some(ref dir) = config.import_dir {
        tracing::info!("Import directory: {}", dir.display());
    }

    reelvault_core::concurrency::set_ffmpeg_concurrency_limit(
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

    // Catalog display name for the mDNS advertisement (computed before `db` is
    // moved into the service).
    let catalog_name = db
        .current_path()
        .and_then(|p| p.file_stem().map(|s| s.to_string_lossy().into_owned()))
        .unwrap_or_else(|| "ReelVault".to_string());
    // Keep handles for the media server + LAN auth before `db`/`config` move
    // into the service.
    let media_db = Arc::clone(&db);
    let auth_db = Arc::clone(&db);
    let media_cache_dir = config.thumbnail_cache_path.clone();
    let media_import_dir = config.import_dir.clone();

    let service = ReelVaultService::new(db, config);

    // Loopback plaintext listener — desktop clients spawn the daemon and
    // connect here; this path is unchanged. (Port 0 => OS-assigned, reported
    // via the sentinel below.)
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
    println!("REELVAULT_LISTENING_ON=127.0.0.1:{}", local.port());
    tracing::info!("gRPC server listening on {} (loopback, plaintext)", local);
    if args.system_daemon {
        tracing::info!(
            "Running as system daemon — shared catalog, port {}",
            local.port()
        );
    }
    // Flush stdout so any parent process that reads the sentinel line sees it.
    {
        use std::io::Write as _;
        let _ = std::io::stdout().flush();
    }

    let loopback = Server::builder()
        .add_service(service.clone().into_server())
        .serve_with_incoming(TcpListenerStream::new(listener));

    if args.remote {
        // LAN-facing TLS listener for remote clients (iOS). Uses a persisted
        // self-signed certificate; clients pin its fingerprint (advertised over
        // mDNS in a later step). Loopback stays plaintext and auth-exempt.
        let data_dir = get_data_dir(args.system_daemon)?;
        let lan_ip: IpAddr = match args.remote_host.as_deref() {
            Some(s) => s.parse().unwrap_or_else(|_| {
                tracing::warn!("Invalid --remote-host {:?}; binding 0.0.0.0", s);
                IpAddr::V4(Ipv4Addr::UNSPECIFIED)
            }),
            None => primary_lan_ipv4().map(IpAddr::V4).unwrap_or_else(|| {
                tracing::warn!("Could not auto-detect a LAN IPv4; binding 0.0.0.0");
                IpAddr::V4(Ipv4Addr::UNSPECIFIED)
            }),
        };
        let server_name = args
            .advertise_name
            .clone()
            .unwrap_or_else(hostname_or_default);

        // SANs are advisory (clients pin by fingerprint) but include the obvious
        // names so a stricter validator could still succeed.
        let mut sans = vec![
            server_name.clone(),
            format!("{server_name}.local"),
            "localhost".to_string(),
        ];
        if let IpAddr::V4(v4) = lan_ip {
            if !v4.is_unspecified() {
                sans.push(v4.to_string());
            }
        }

        let id = identity::load_or_create(&data_dir, &sans)?;
        let tonic_id = TonicIdentity::from_pem(id.cert_pem.clone(), id.key_pem.clone());
        let lan_addr = SocketAddr::new(lan_ip, args.remote_grpc_port);

        tracing::info!(
            "Remote TLS gRPC listening on {} as \"{}\" (cert sha256={})",
            lan_addr,
            server_name,
            id.fingerprint_hex
        );
        tracing::info!("Backend ready (loopback + remote)");

        // Advertise over mDNS so clients auto-discover us. The guard lives until
        // the process exits (after the servers below run forever), unregistering
        // on drop.
        let adv_ip: Option<Ipv4Addr> = match lan_ip {
            IpAddr::V4(v4) if !v4.is_unspecified() => Some(v4),
            _ => primary_lan_ipv4(),
        };
        let _adv = adv_ip.and_then(|ip| {
            match discovery::Advertisement::start(
                &server_name,
                ip,
                args.remote_grpc_port,
                args.media_port,
                &id.fingerprint_hex,
                &catalog_name,
                env!("CARGO_PKG_VERSION"),
                "pin",   // auth: one-time device pairing required
                "media", // features: range download/stream available
            ) {
                Ok(a) => {
                    tracing::info!("Advertising _reelvault._tcp at {} (mDNS)", ip);
                    Some(a)
                }
                Err(e) => {
                    tracing::warn!("mDNS advertisement failed: {}", e);
                    None
                }
            }
        });
        if adv_ip.is_none() {
            tracing::warn!("No LAN IPv4 available to advertise over mDNS");
        }

        // Gate every LAN gRPC call on a paired-device bearer token. Loopback is
        // never wrapped, so desktop clients stay exempt.
        let interceptor =
            move |req: tonic::Request<()>| -> Result<tonic::Request<()>, tonic::Status> {
                let authz = req.metadata().get("authorization").and_then(|v| v.to_str().ok());
                if auth::is_authorized(&auth_db, authz) {
                    Ok(req)
                } else {
                    Err(tonic::Status::unauthenticated("device not paired"))
                }
            };
        let lan_service = InterceptedService::new(service.clone().into_server(), interceptor);
        let tls = Server::builder()
            .tls_config(ServerTlsConfig::new().identity(tonic_id))?
            .add_service(lan_service)
            .serve(lan_addr);

        // HTTPS media server on the same identity cert.
        let media_addr = SocketAddr::new(lan_ip, args.media_port);
        tracing::info!("Remote HTTPS media server listening on {}", media_addr);
        let media = media_server::serve(
            media_addr,
            id.cert_pem.clone(),
            id.key_pem.clone(),
            media_server::MediaState::new(
                media_db,
                id.fingerprint_hex.clone(),
                media_cache_dir,
                data_dir.clone(),
                media_import_dir,
            ),
        );

        // All three run forever; unify their error types for try_join!.
        let loopback = async move { loopback.await.map_err(anyhow::Error::from) };
        let tls = async move { tls.await.map_err(anyhow::Error::from) };
        tokio::try_join!(loopback, tls, media)?;
    } else {
        tracing::info!("Backend ready");
        loopback.await?;
    }

    if let Some(ref path) = pid_path {
        remove_pid_file(path);
    }

    Ok(())
}

/// Raise the soft fd limit toward the hard limit (capped at 8192). The macOS
/// default for terminal-launched processes is 256, which a busy daemon —
/// concurrent thumbnail reads, SQLite connections, ffmpeg children with their
/// pipes, gRPC sockets from multiple clients — can brush against under a
/// grid-mount storm. Hitting it turns into spurious "file not found"-shaped
/// failures, so claim the headroom up front. Best-effort: a failure is logged
/// and ignored.
#[cfg(unix)]
fn raise_fd_limit() {
    use nix::sys::resource::{getrlimit, setrlimit, Resource};
    match getrlimit(Resource::RLIMIT_NOFILE) {
        Ok((soft, hard)) => {
            let target = hard.min(8192);
            if soft < target {
                match setrlimit(Resource::RLIMIT_NOFILE, target, hard) {
                    Ok(()) => tracing::info!("Raised fd soft limit {} -> {}", soft, target),
                    Err(e) => tracing::warn!(
                        "Could not raise fd soft limit {} -> {}: {}",
                        soft,
                        target,
                        e
                    ),
                }
            }
        }
        Err(e) => tracing::warn!("Could not read fd limit: {}", e),
    }
}

#[cfg(not(unix))]
fn raise_fd_limit() {}

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
        PathBuf::from("/Library/Logs/ReelVault")
    } else if cfg!(target_os = "windows") {
        PathBuf::from(r"C:\ProgramData\ReelVault\logs")
    } else {
        PathBuf::from("/var/log/reelvault")
    }
}

/// Per-OS data directory (without the catalog filename). Shared by the catalog
/// path helpers and the TLS identity store (`identity/`) so they live together.
fn get_data_dir(system_daemon: bool) -> Result<PathBuf> {
    let dir = if system_daemon {
        if cfg!(target_os = "macos") {
            PathBuf::from("/Library/Application Support/ReelVault")
        } else if cfg!(target_os = "windows") {
            PathBuf::from(r"C:\ProgramData\ReelVault")
        } else {
            PathBuf::from("/var/lib/reelvault")
        }
    } else if cfg!(target_os = "macos") {
        dirs::home_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine home directory"))?
            .join("Library")
            .join("Application Support")
            .join("ReelVault")
    } else if cfg!(target_os = "windows") {
        dirs::data_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine data directory"))?
            .join("ReelVault")
    } else {
        dirs::data_local_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine data directory"))?
            .join("reelvault")
    };
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

/// System-wide catalog path used when `--system-daemon` is set.
fn get_system_catalog_path() -> Result<PathBuf> {
    Ok(get_data_dir(true)?.join("catalog.db"))
}

/// Per-user catalog path used in interactive (non-daemon) mode.
fn get_default_db_path() -> Result<PathBuf> {
    Ok(get_data_dir(false)?.join("catalog.db"))
}

/// Best-effort detection of the primary non-loopback IPv4 address. Opens a UDP
/// socket and "connects" it to a public address — no packets are sent; this
/// just asks the OS which local interface serves the default route.
fn primary_lan_ipv4() -> Option<Ipv4Addr> {
    use std::net::UdpSocket;
    let sock = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    sock.connect((Ipv4Addr::new(8, 8, 8, 8), 80)).ok()?;
    match sock.local_addr().ok()? {
        SocketAddr::V4(v4) => {
            let ip = *v4.ip();
            if ip.is_loopback() || ip.is_unspecified() {
                None
            } else {
                Some(ip)
            }
        }
        _ => None,
    }
}

/// The machine hostname for the advertised server name, or a generic fallback.
/// Reads the usual environment variables to avoid a dedicated dependency.
fn hostname_or_default() -> String {
    std::env::var("HOSTNAME")
        .or_else(|_| std::env::var("COMPUTERNAME"))
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "ReelVault".to_string())
}

/// Default PID file path for `--system-daemon` mode.
fn get_pid_file_path() -> PathBuf {
    if cfg!(target_os = "windows") {
        PathBuf::from(r"C:\ProgramData\ReelVault\reelvault-core.pid")
    } else {
        PathBuf::from("/var/run/reelvault-core.pid")
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
