mod config;
mod db;
mod report;
mod sources;

use anyhow::{Context, Result};
use clap::{Parser, Subcommand, ValueEnum};

#[derive(Parser)]
#[command(name = "analytics-ingest", about = "Pull store analytics into a local SQLite database")]
struct Cli {
    /// Path to TOML config file (or set $RV_ANALYTICS_CONFIG)
    #[arg(long, env = "RV_ANALYTICS_CONFIG")]
    config: String,

    #[command(subcommand)]
    cmd: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Create the database and run schema migrations (safe to re-run)
    Init,

    /// Fetch analytics from store APIs
    Fetch {
        #[arg(long, value_enum, default_value = "all")]
        source: Source,

        /// Override the incremental start date (YYYY-MM-DD)
        #[arg(long)]
        since: Option<String>,
    },

    /// Run a canned SQL report
    Report {
        name: String,
    },

    /// Check credentials and connectivity without writing anything
    Verify,
}

#[derive(ValueEnum, Clone, PartialEq)]
enum Source {
    All,
    Appstore,
    Play,
    Github,
}

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let cli = Cli::parse();
    if let Err(e) = run(cli) {
        eprintln!("error: {e:#}");
        std::process::exit(1);
    }
}

fn run(cli: Cli) -> Result<()> {
    let cfg = config::Config::load(&cli.config)
        .with_context(|| format!("loading config: {}", cli.config))?;

    match cli.cmd {
        Command::Init => {
            let conn = db::open(&cfg.db_path())?;
            db::migrate(&conn)?;
            log::info!("Database initialized: {}", cfg.db_path().display());
        }

        Command::Fetch { source, since } => {
            let conn = db::open(&cfg.db_path())?;
            db::migrate(&conn)?;

            let mut errors: Vec<String> = Vec::new();

            let run_appstore = source == Source::All || source == Source::Appstore;
            let run_play = source == Source::All || source == Source::Play;
            let run_github = source == Source::All || source == Source::Github;

            if run_appstore {
                if cfg.appstore.enabled {
                    if let Err(e) = sources::appstore::fetch(&conn, &cfg.appstore, since.as_deref()) {
                        errors.push(format!("appstore: {e:#}"));
                    }
                } else {
                    log::info!("[appstore] disabled in config — skipping");
                }
            }

            if run_play {
                if cfg.play.enabled {
                    if let Err(e) = sources::play::fetch(&conn, &cfg.play, since.as_deref()) {
                        errors.push(format!("play: {e:#}"));
                    }
                } else {
                    log::info!("[play] disabled in config — skipping");
                }
            }

            if run_github {
                if cfg.github.enabled {
                    if let Err(e) = sources::github::fetch(&conn, &cfg.github) {
                        errors.push(format!("github: {e:#}"));
                    }
                } else {
                    log::info!("[github] disabled in config — skipping");
                }
            }

            if !errors.is_empty() {
                eprintln!("\nFetch completed with errors:");
                for e in &errors {
                    eprintln!("  - {e}");
                }
                // Exit non-zero if ALL requested sources failed; partial failure is logged above.
                let total_requested = [run_appstore, run_play, run_github]
                    .into_iter()
                    .filter(|&v| v)
                    .count();
                if errors.len() >= total_requested {
                    std::process::exit(1);
                }
            }
        }

        Command::Report { name } => {
            let conn = db::open(&cfg.db_path())?;
            report::run(&conn, &name)?;
        }

        Command::Verify => {
            let mut ok = true;

            if cfg.appstore.enabled {
                match sources::appstore::verify_credentials(&cfg.appstore) {
                    Ok(()) => log::info!("[appstore] OK"),
                    Err(e) => {
                        eprintln!("[appstore] FAIL: {e:#}");
                        ok = false;
                    }
                }
            } else {
                log::info!("[appstore] disabled — skipping");
            }

            if cfg.play.enabled {
                match sources::play::verify_credentials(&cfg.play) {
                    Ok(()) => log::info!("[play] OK"),
                    Err(e) => {
                        eprintln!("[play] FAIL: {e:#}");
                        ok = false;
                    }
                }
            } else {
                log::info!("[play] disabled — skipping");
            }

            if cfg.github.enabled {
                let owner = cfg.github.owner.as_deref().unwrap_or("");
                let repo = cfg.github.repo.as_deref().unwrap_or("");
                if owner.is_empty() || repo.is_empty() {
                    eprintln!("[github] FAIL: owner and repo must be set");
                    ok = false;
                } else {
                    let token = cfg.github.token();
                    let client = reqwest::blocking::Client::builder()
                        .user_agent("analytics-ingest/0.1")
                        .build()?;
                    let url = format!(
                        "https://api.github.com/repos/{owner}/{repo}/releases?per_page=1"
                    );
                    let mut req = client.get(&url);
                    if let Some(t) = &token {
                        req = req.header("Authorization", format!("Bearer {t}"));
                    }
                    match req.send() {
                        Ok(resp) if resp.status().is_success() => {
                            log::info!("[github] OK (HTTP {})", resp.status());
                        }
                        Ok(resp) => {
                            eprintln!("[github] FAIL: HTTP {}", resp.status());
                            ok = false;
                        }
                        Err(e) => {
                            eprintln!("[github] FAIL: {e:#}");
                            ok = false;
                        }
                    }
                }
            } else {
                log::info!("[github] disabled — skipping");
            }

            if !ok {
                std::process::exit(1);
            }
        }
    }

    Ok(())
}
