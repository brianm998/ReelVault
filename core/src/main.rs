use anyhow::Result;
use std::path::PathBuf;
use std::sync::Arc;
use tonic::transport::Server;

use videoroom_core::config::Config;
use videoroom_core::db::Database;
use videoroom_core::service::VideoRoomService;

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    println!("🎬 VideoRoom Core v{}", env!("CARGO_PKG_VERSION"));

    // Initialize database
    let db_path = get_db_path()?;
    println!("📚 Database: {}", db_path.display());

    let db = Arc::new(Database::new(&db_path)?);
    db.initialize().await?;

    // Load config
    let config = Arc::new(Config::load(db.as_ref()).await?);
    println!("⚙️  Cache: {}", config.thumbnail_cache_path.display());

    // Build service
    let service = VideoRoomService::new(db, config);
    let server = service.into_server();

    // Start gRPC server
    let addr = "127.0.0.1:50051".parse()?;
    println!("🚀 gRPC server listening on {}", addr);
    println!("✓ Backend ready");

    Server::builder()
        .add_service(server)
        .serve(addr)
        .await?;

    Ok(())
}

fn get_db_path() -> Result<PathBuf> {
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
