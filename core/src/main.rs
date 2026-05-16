// gRPC daemon (TODO: Complete implementation)
use anyhow::Result;

#[tokio::main]
async fn main() -> Result<()> {
    println!("🎬 VideoRoom Core v{}", env!("CARGO_PKG_VERSION"));
    println!("Use 'videoroom-cli' tool for testing and database operations");
    println!("gRPC daemon support coming soon...");

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
