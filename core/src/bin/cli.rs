// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

use clap::{Parser, Subcommand};
use std::path::PathBuf;
use std::time::Instant;
use humansize::{format_size, BINARY};
use prettytable::{Table, Row, Cell};

use videoroom_core::db::Database;
use videoroom_core::metadata::MetadataExtractor;
use videoroom_core::thumbnails::ThumbnailGenerator;
use videoroom_core::indexing::IndexingEngine;
use videoroom_core::search::SearchEngine;
use videoroom_core::config::Config;

#[derive(Parser)]
#[command(name = "videoroom-cli")]
#[command(about = "VideoRoom CLI testing tool", long_about = "Test and debug the VideoRoom core")]
struct Cli {
    #[command(subcommand)]
    command: Commands,

    #[arg(global = true, long, default_value = "")]
    db_path: String,
}

#[derive(Subcommand)]
enum Commands {
    /// Scan and index a directory of videos
    Scan {
        /// Path to scan
        #[arg(value_name = "PATH")]
        path: PathBuf,

        /// Recursive scan
        #[arg(short, long)]
        recursive: bool,
    },

    /// List all videos in the database
    List {
        /// Number of results
        #[arg(short, long, default_value = "20")]
        limit: i64,

        /// Offset
        #[arg(short, long, default_value = "0")]
        offset: i64,
    },

    /// Get metadata for a video
    Meta {
        /// Video ID
        #[arg(value_name = "VIDEO_ID")]
        video_id: String,
    },

    /// Search for videos
    Search {
        /// Search query
        #[arg(value_name = "QUERY")]
        query: String,

        /// Limit results
        #[arg(short, long, default_value = "20")]
        limit: i64,
    },

    /// Extract metadata from a single video file
    Extract {
        /// Path to video file
        #[arg(value_name = "FILE")]
        file: PathBuf,
    },

    /// Generate thumbnail for a video
    Thumbnail {
        /// Path to video file
        #[arg(value_name = "FILE")]
        file: PathBuf,

        /// Output directory
        #[arg(short, long)]
        output: PathBuf,
    },

    /// Show database statistics
    Stats,

    /// Add a library location
    AddLib {
        /// Path to add
        #[arg(value_name = "PATH")]
        path: PathBuf,

        /// Recursive
        #[arg(short, long)]
        recursive: bool,
    },

    /// List library locations
    ListLib,

    /// Create a test tag
    CreateTag {
        /// Tag name
        #[arg(value_name = "NAME")]
        name: String,

        /// Tag color (hex)
        #[arg(short, long)]
        color: Option<String>,
    },

    /// List all tags
    ListTags,

    /// Tag a video
    Tag {
        /// Video ID
        #[arg(value_name = "VIDEO_ID")]
        video_id: String,

        /// Tag ID
        #[arg(value_name = "TAG_ID")]
        tag_id: String,
    },

    /// Performance benchmark
    Bench {
        /// Path to scan for benchmark
        #[arg(value_name = "PATH")]
        path: PathBuf,
    },
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // Initialize logging
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    let cli = Cli::parse();

    // Get database path
    let db_path = if !cli.db_path.is_empty() {
        PathBuf::from(&cli.db_path)
    } else {
        get_default_db_path()?
    };

    println!("📚 VideoRoom CLI v{}", env!("CARGO_PKG_VERSION"));
    println!("Database: {}\n", db_path.display());

    // Initialize database
    let db = Database::new(&db_path)?;
    db.initialize().await?;

    match cli.command {
        Commands::Scan { path, recursive } => cmd_scan(&db, &path, recursive).await?,
        Commands::List { limit, offset } => cmd_list(&db, limit, offset).await?,
        Commands::Meta { video_id } => cmd_meta(&db, &video_id).await?,
        Commands::Search { query, limit } => cmd_search(&db, &query, limit).await?,
        Commands::Extract { file } => cmd_extract(&file)?,
        Commands::Thumbnail { file, output } => cmd_thumbnail(&file, &output)?,
        Commands::Stats => cmd_stats(&db).await?,
        Commands::AddLib { path, recursive } => cmd_add_lib(&db, &path, recursive).await?,
        Commands::ListLib => cmd_list_lib(&db).await?,
        Commands::CreateTag { name, color } => cmd_create_tag(&db, &name, color.as_deref()).await?,
        Commands::ListTags => cmd_list_tags(&db).await?,
        Commands::Tag { video_id, tag_id } => cmd_tag(&db, &video_id, &tag_id).await?,
        Commands::Bench { path } => cmd_bench(&db, &path).await?,
    }

    Ok(())
}

async fn cmd_scan(
    db: &Database,
    path: &std::path::Path,
    recursive: bool,
) -> anyhow::Result<()> {
    println!("🔍 Scanning: {}", path.display());
    println!("   Recursive: {}\n", recursive);

    let start = Instant::now();
    let cache_path = get_cache_path()?;

    IndexingEngine::scan_directory(
        db,
        path,
        recursive,
        &cache_path,
        |progress| {
            if !progress.current_file.is_empty() {
                println!(
                    "  {} | Found: {} | Indexed: {} | Progress: {:.1}%",
                    progress.status, progress.videos_found, progress.videos_indexed, progress.progress_percent
                );
            }
        },
    )?;

    let elapsed = start.elapsed();
    println!(
        "\n✅ Scan complete in {:.2}s",
        elapsed.as_secs_f64()
    );

    Ok(())
}

async fn cmd_list(db: &Database, limit: i64, offset: i64) -> anyhow::Result<()> {
    let (videos, total) = db.list_videos(limit, offset)?;

    let mut table = Table::new();
    table.add_row(Row::new(vec![
        Cell::new("ID"),
        Cell::new("Filename"),
        Cell::new("Size"),
        Cell::new("Added"),
    ]));

    for video in &videos {
        let size_str = format_size(video.file_size_bytes.unwrap_or(0) as u64, BINARY);
        let date_str = if video.indexed_at > 0 {
            format_date_ms(video.indexed_at)
        } else {
            "Unknown".to_string()
        };

        table.add_row(Row::new(vec![
            Cell::new(&video.id[..8.min(video.id.len())]),
            Cell::new(&video.filename),
            Cell::new(&size_str),
            Cell::new(&date_str),
        ]));
    }

    println!("📹 Videos ({} of {}):\n", videos.len(), total);
    table.printstd();
    println!(
        "\nShowing {}-{} of {} videos",
        offset + 1,
        (offset + videos.len() as i64).min(total),
        total
    );

    Ok(())
}

async fn cmd_meta(db: &Database, video_id: &str) -> anyhow::Result<()> {
    match db.get_video(video_id)? {
        Some(video) => {
            println!("📹 Video: {}\n", video.filename);
            println!("  ID:       {}", video.id);
            println!("  Path:     {}", video.path);
            println!("  Size:     {}", format_size(video.file_size_bytes.unwrap_or(0) as u64, BINARY));
            println!("  Hash:     {}", video.hash.unwrap_or_else(|| "None".to_string()));
            println!("  Online:   {}", video.is_online != 0);
            println!("  Added:    {}\n", format_date_ms(video.indexed_at));

            // Try to get metadata
            let conn = db.get_connection()?;
            match conn.query_row(
                "SELECT duration_ms, width, height, fps, codec_video, codec_audio, bitrate, color_space
                 FROM metadata WHERE video_id = ?",
                [video_id],
                |row| {
                    Ok((
                        row.get::<_, i64>(0)?,
                        row.get::<_, i32>(1)?,
                        row.get::<_, i32>(2)?,
                        row.get::<_, f64>(3)?,
                        row.get::<_, Option<String>>(4)?,
                        row.get::<_, Option<String>>(5)?,
                        row.get::<_, i64>(6)?,
                        row.get::<_, Option<String>>(7)?,
                    ))
                },
            ) {
                Ok((duration, width, height, fps, codec_v, codec_a, bitrate, color)) => {
                    println!("📊 Technical Metadata:");
                    println!("  Duration: {:.2}s", duration as f64 / 1000.0);
                    println!("  Resolution: {}x{}", width, height);
                    println!("  FPS: {:.2}", fps);
                    println!("  Codec (V): {}", codec_v.unwrap_or_else(|| "Unknown".to_string()));
                    println!("  Codec (A): {}", codec_a.unwrap_or_else(|| "Unknown".to_string()));
                    println!("  Bitrate: {} kbps", bitrate / 1000);
                    println!("  Color: {}\n", color.unwrap_or_else(|| "Unknown".to_string()));
                }
                Err(_) => {
                    println!("  (No metadata extracted yet)\n");
                }
            }

            // Get tags
            let tags = db.get_video_tags(video_id)?;
            if !tags.is_empty() {
                println!("🏷️  Tags: {}", tags.join(", "));
            }
        }
        None => {
            println!("❌ Video not found: {}", video_id);
        }
    }

    Ok(())
}

async fn cmd_search(db: &Database, query: &str, limit: i64) -> anyhow::Result<()> {
    println!("🔎 Searching for: \"{}\"\n", query);

    let (results, total) = SearchEngine::search(db, query, limit, 0, &[])?;

    if results.is_empty() {
        println!("No results found.");
        return Ok(());
    }

    let mut table = Table::new();
    table.add_row(Row::new(vec![
        Cell::new("ID"),
        Cell::new("Filename"),
        Cell::new("Path"),
    ]));

    for result in &results {
        table.add_row(Row::new(vec![
            Cell::new(&result.video_id[..8.min(result.video_id.len())]),
            Cell::new(&result.filename),
            Cell::new(&result.path),
        ]));
    }

    println!("{}\n", table);
    println!("Found {} of {} results", results.len(), total);

    Ok(())
}

fn cmd_extract(file: &std::path::Path) -> anyhow::Result<()> {
    println!("🎬 Extracting metadata from: {}\n", file.display());

    let start = Instant::now();
    let probe = MetadataExtractor::extract(file)?;
    let elapsed = start.elapsed();

    if let Some(video_stream) = probe.streams.iter().find(|s| s.codec_type == Some("video".to_string())) {
        println!("✅ Metadata extracted in {:.2}s\n", elapsed.as_secs_f64());

        if let Some(width) = video_stream.width {
            print!("Resolution: {}x{}", width, video_stream.height.unwrap_or(0));
        }
        if let Some(fps) = &video_stream.r_frame_rate {
            print!(" | FPS: {}", fps);
        }
        if let Some(codec) = &video_stream.codec_name {
            print!(" | Codec: {}", codec);
        }
        println!();

        if let Some(duration) = probe.format.duration {
            println!("Duration: {:.2}s", duration);
        }
        if let Some(bitrate) = probe.format.bit_rate {
            println!("Bitrate: {} kbps", bitrate / 1000);
        }

        // EXIF data
        if let Some(tags) = &video_stream.tags {
            println!("\nEXIF/Camera Data:");
            for (key, value) in tags.0.iter() {
                println!("  {}: {}", key, value);
            }
        }
    } else {
        println!("❌ No video stream found");
    }

    Ok(())
}

fn cmd_thumbnail(_file: &std::path::Path, _output: &std::path::Path) -> anyhow::Result<()> {
    println!("🖼️  Thumbnail generation requires database context.");
    println!("Please scan videos into the library and use 'list' command to see thumbnail status.");
    Ok(())
}

async fn cmd_stats(db: &Database) -> anyhow::Result<()> {
    let (videos, total_videos) = db.list_videos(1, 0)?;
    let tags = db.list_tags()?;
    let collections = db.list_collections()?;
    let locations = db.list_library_locations()?;

    println!("📊 Database Statistics:\n");

    let mut stats_table = Table::new();
    stats_table.add_row(Row::new(vec![Cell::new("Metric"), Cell::new("Value")]));
    stats_table.add_row(Row::new(vec![
        Cell::new("Total Videos"),
        Cell::new(&total_videos.to_string()),
    ]));
    stats_table.add_row(Row::new(vec![
        Cell::new("Total Tags"),
        Cell::new(&tags.len().to_string()),
    ]));
    stats_table.add_row(Row::new(vec![
        Cell::new("Total Collections"),
        Cell::new(&collections.len().to_string()),
    ]));
    stats_table.add_row(Row::new(vec![
        Cell::new("Library Locations"),
        Cell::new(&locations.len().to_string()),
    ]));

    stats_table.printstd();

    if total_videos > 0 {
        // Calculate total size
        let conn = db.get_connection()?;
        let total_size: i64 = conn
            .query_row("SELECT COALESCE(SUM(file_size_bytes), 0) FROM videos", [], |row| {
                row.get(0)
            })
            .unwrap_or(0);

        println!("\nLibrary Size: {}", format_size(total_size as u64, BINARY));

        // Get codec distribution
        let mut codec_stmt = conn
            .prepare("SELECT codec_video, COUNT(*) FROM metadata WHERE codec_video IS NOT NULL GROUP BY codec_video")?;

        let codecs: Vec<(String, i64)> = codec_stmt
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?
            .collect::<std::result::Result<_, _>>()?;

        if !codecs.is_empty() {
            println!("\nCodec Distribution:");
            for (codec, count) in codecs {
                println!("  {}: {}", codec, count);
            }
        }

        // Get resolution distribution
        let mut res_stmt = conn
            .prepare(
                "SELECT width || 'x' || height as res, COUNT(*) FROM metadata GROUP BY width, height ORDER BY width DESC LIMIT 10"
            )?;

        let resolutions: Vec<(String, i64)> = res_stmt
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?
            .collect::<std::result::Result<_, _>>()?;

        if !resolutions.is_empty() {
            println!("\nResolution Distribution:");
            for (res, count) in resolutions {
                println!("  {}: {}", res, count);
            }
        }
    }

    Ok(())
}

async fn cmd_add_lib(
    db: &Database,
    path: &std::path::Path,
    recursive: bool,
) -> anyhow::Result<()> {
    println!("➕ Adding library location: {}", path.display());
    println!("   Recursive: {}\n", recursive);

    db.add_library_location(path.to_str().unwrap_or(""), recursive)?;
    println!("✅ Library location added");

    Ok(())
}

async fn cmd_list_lib(db: &Database) -> anyhow::Result<()> {
    let locations = db.list_library_locations()?;

    if locations.is_empty() {
        println!("No library locations configured.");
        return Ok(());
    }

    let mut table = Table::new();
    table.add_row(Row::new(vec![
        Cell::new("Path"),
        Cell::new("Recursive"),
        Cell::new("Enabled"),
        Cell::new("Last Scanned"),
    ]));

    for loc in &locations {
        let last_scanned = if let Some(ts) = loc.last_scanned {
            format_date_ms(ts)
        } else {
            "Never".to_string()
        };

        table.add_row(Row::new(vec![
            Cell::new(&loc.path),
            Cell::new(if loc.recursive { "Yes" } else { "No" }),
            Cell::new(if loc.enabled { "Yes" } else { "No" }),
            Cell::new(&last_scanned),
        ]));
    }

    println!("📁 Library Locations:\n");
    table.printstd();

    Ok(())
}

async fn cmd_create_tag(
    db: &Database,
    name: &str,
    color: Option<&str>,
) -> anyhow::Result<()> {
    let tag_id = db.create_tag(name, color)?;
    println!("✅ Tag created:");
    println!("  ID:    {}", tag_id);
    println!("  Name:  {}", name);
    if let Some(c) = color {
        println!("  Color: {}", c);
    }

    Ok(())
}

async fn cmd_list_tags(db: &Database) -> anyhow::Result<()> {
    let tags = db.list_tags()?;

    if tags.is_empty() {
        println!("No tags created yet.");
        return Ok(());
    }

    let mut table = Table::new();
    table.add_row(Row::new(vec![Cell::new("ID"), Cell::new("Name"), Cell::new("Color")]));

    for tag in &tags {
        table.add_row(Row::new(vec![
            Cell::new(&tag.id[..8.min(tag.id.len())]),
            Cell::new(&tag.name),
            Cell::new(&tag.color.clone().unwrap_or_else(|| "None".to_string())),
        ]));
    }

    println!("🏷️  Tags:\n");
    table.printstd();

    Ok(())
}

async fn cmd_tag(db: &Database, video_id: &str, tag_id: &str) -> anyhow::Result<()> {
    db.tag_video(video_id, tag_id)?;
    println!("✅ Video tagged successfully");

    Ok(())
}

async fn cmd_bench(db: &Database, path: &std::path::Path) -> anyhow::Result<()> {
    println!("⚡ Running benchmark on: {}\n", path.display());

    let cache_path = get_cache_path()?;
    let start = Instant::now();

    IndexingEngine::scan_directory(
        db,
        path,
        true,
        &cache_path,
        |progress| {
            if progress.progress_percent > 0.0 && progress.progress_percent % 10.0 < 1.0 {
                println!("  {:.0}% - {} videos found, {} indexed",
                    progress.progress_percent, progress.videos_found, progress.videos_indexed
                );
            }
        },
    )?;

    let elapsed = start.elapsed();
    let (videos, _) = db.list_videos(1, 0)?;
    let total_videos = videos.first().map(|_| 1).unwrap_or(0);

    println!("\n📈 Benchmark Results:");
    println!("  Total time: {:.2}s", elapsed.as_secs_f64());
    println!("  Videos/sec: {:.2}", total_videos as f64 / elapsed.as_secs_f64());
    println!("  Avg per video: {:.2}ms", elapsed.as_secs_f64() * 1000.0 / total_videos as f64);

    Ok(())
}

fn get_default_db_path() -> anyhow::Result<PathBuf> {
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

fn get_cache_path() -> anyhow::Result<PathBuf> {
    let cache_dir = if cfg!(target_os = "macos") {
        dirs::home_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine home directory"))?
            .join("Library")
            .join("Caches")
            .join("VideoRoom")
    } else if cfg!(target_os = "windows") {
        dirs::cache_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine cache directory"))?
            .join("VideoRoom")
    } else {
        dirs::cache_dir()
            .ok_or_else(|| anyhow::anyhow!("Could not determine cache directory"))?
            .join("videoroom")
    };

    std::fs::create_dir_all(&cache_dir)?;
    Ok(cache_dir)
}

fn format_date_ms(timestamp_ms: i64) -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let duration = std::time::Duration::from_millis(timestamp_ms as u64);
    match SystemTime::UNIX_EPOCH.checked_add(duration) {
        Some(time) => {
            // Format as a simple date string
            format!("{:?}", time)
        }
        None => "Unknown".to_string(),
    }
}

// Add this helper for the thumbnail command
trait ThumbnailGenHelper {
    fn generate(&self, video_id: &str);
}

impl ThumbnailGenHelper for String {
    fn generate(&self, _video_id: &str) {
        // Placeholder for actual implementation
    }
}
