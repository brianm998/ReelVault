use anyhow::Result;
use clap::Parser;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;
use tokio::net::TcpListener;
use tokio_stream::wrappers::TcpListenerStream;
use tonic::transport::Server;

use videoroom_core::config::Config;
use videoroom_core::db::Database;
use videoroom_core::service::VideoRoomService;

/// VideoRoom backend daemon.
///
/// The daemon serves a gRPC API over loopback. By default it binds the
/// well-known port 50051 and opens the platform-default catalog; both can be
/// overridden so a client can spawn its own backend on a free port pointed at
/// a user-picked catalog.
#[derive(Parser, Debug)]
#[command(name = "videoroom-core", about, version)]
struct Args {
    /// SQLite catalog file to open at startup. Use `--no-catalog` to start
    /// without one — the client can call `OpenCatalog` later. If neither is
    /// passed, falls back to the platform-default location.
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
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    let args = Args::parse();

    println!("🎬 VideoRoom Core v{}", env!("CARGO_PKG_VERSION"));

    // Decide which catalog to open at startup. Three paths:
    //   1. `--no-catalog` → start empty; client will call OpenCatalog.
    //   2. `--db-path <p>` → open exactly that catalog.
    //   3. neither → fall back to the platform default for backward compat.
    let db = if args.no_catalog {
        println!("📚 No catalog at startup (waiting for client's OpenCatalog)");
        Arc::new(Database::new_empty())
    } else {
        let path = match args.db_path {
            Some(p) => p,
            None => get_default_db_path()?,
        };
        println!("📚 Database: {}", path.display());
        let db = Arc::new(Database::new(&path)?);
        db.initialize().await?;
        db
    };

    // Load config (uses the db it's given, but config is mostly static).
    let config = Arc::new(Config::load(db.as_ref()).await?);
    println!("⚙️  Cache: {}", config.thumbnail_cache_path.display());

    videoroom_core::concurrency::set_ffmpeg_concurrency_limit(
        config.max_concurrent_ffmpeg.max(0) as usize,
    );
    println!(
        "🎞️  Max concurrent ffmpeg: {}",
        if config.max_concurrent_ffmpeg <= 0 {
            "unlimited".to_string()
        } else {
            config.max_concurrent_ffmpeg.to_string()
        }
    );

    // Build the service + the tonic server wrapper.
    let service = VideoRoomService::new(db, config);
    let server = service.into_server();

    // Bind the requested port, falling back to OS-assigned if the user's
    // preferred port is busy. This is the standard "another instance is
    // already running, just grab any free port" behavior.
    let host: IpAddr = args
        .host
        .parse()
        .unwrap_or(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)));
    let preferred = SocketAddr::new(host, args.port);
    let listener = match TcpListener::bind(preferred).await {
        Ok(l) => l,
        Err(e) => {
            eprintln!(
                "⚠️  Could not bind {}: {}. Falling back to an OS-assigned port…",
                preferred, e
            );
            TcpListener::bind(SocketAddr::new(host, 0)).await?
        }
    };
    let local = listener.local_addr()?;

    // This line is parsed by the desktop clients when they spawn the daemon
    // — keep the prefix stable.
    println!("VIDEOROOM_LISTENING_ON=127.0.0.1:{}", local.port());
    println!("🚀 gRPC server listening on {}", local);
    println!("✓ Backend ready");
    // Force-flush so stdout-capturing clients see the line immediately.
    use std::io::Write;
    let _ = std::io::stdout().flush();

    Server::builder()
        .add_service(server)
        .serve_with_incoming(TcpListenerStream::new(listener))
        .await?;

    Ok(())
}

/// Platform-default location for the catalog file. Used when neither
/// `--db-path` nor `--no-catalog` is passed — preserves existing behavior for
/// people running the daemon directly.
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
