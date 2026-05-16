mod db;
mod metadata;
mod thumbnails;
mod search;
mod indexing;
mod config;
mod service;
mod error;

use anyhow::Result;
use std::net::SocketAddr;
use std::sync::Arc;
use std::path::PathBuf;
use tonic::transport::Server;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive(
            "videoroom_core=debug,tonic=info".parse().unwrap(),
        ))
        .init();

    tracing::info!("VideoRoom Core starting...");

    // Get or create database
    let db_path = get_db_path()?;
    tracing::info!("Database path: {}", db_path.display());

    let db = Arc::new(db::Database::new(&db_path)?);
    db.initialize().await?;

    tracing::info!("Database initialized successfully");

    // Load configuration
    let config = Arc::new(config::Config::load(&db).await?);
    tracing::debug!("Configuration loaded: {:?}", config);

    // Create service implementation
    let video_room_service = service::VideoRoomService::new(db.clone(), config.clone());

    // Start gRPC server
    let addr = "127.0.0.1:50051".parse::<SocketAddr>()?;
    tracing::info!("Starting gRPC server on {}", addr);

    Server::builder()
        .add_service(videoroom::video_room_server::VideoRoomServer::new(
            video_room_service,
        ))
        .add_service(tonic_reflection::enable(
            videoroom::video_room_server::VideoRoomServer::new(service::VideoRoomService::new(
                db.clone(),
                config.clone(),
            )),
        ))
        .serve(addr)
        .await?;

    Ok(())
}

fn get_db_path() -> Result<PathBuf> {
    let data_dir = if cfg!(target_os = "macos") {
        dirs::library_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine library directory"))?
            .join("Application Support")
            .join("VideoRoom")
    } else if cfg!(target_os = "windows") {
        dirs::data_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine data directory"))?
            .join("VideoRoom")
    } else {
        // Linux and others
        dirs::data_local_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine data directory"))?
            .join("videoroom")
    };

    std::fs::create_dir_all(&data_dir)?;
    Ok(data_dir.join("catalog.db"))
}
