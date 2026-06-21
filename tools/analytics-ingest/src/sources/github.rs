use anyhow::{Context, Result};
use rusqlite::{params, Connection};
use serde::Deserialize;

use crate::config::GitHubConfig;
use crate::db::RunGuard;

#[derive(Debug, Deserialize)]
struct Release {
    tag_name: String,
    published_at: Option<String>,
    draft: bool,
    prerelease: bool,
    assets: Vec<Asset>,
}

#[derive(Debug, Deserialize)]
struct Asset {
    name: String,
    download_count: i64,
    size: i64,
}

pub fn fetch(conn: &Connection, cfg: &GitHubConfig) -> Result<()> {
    let owner = cfg.owner.as_deref().context("github.owner not set")?;
    let repo = cfg.repo.as_deref().context("github.repo not set")?;
    let token = cfg.token();

    let mut guard = RunGuard::start(conn, "github")?;
    let snapshot_date = chrono::Utc::now().format("%Y-%m-%d").to_string();
    let fetched_at = chrono::Utc::now().to_rfc3339();

    match do_fetch(conn, owner, repo, token.as_deref(), &snapshot_date, &fetched_at, &mut guard) {
        Ok(()) => guard.finish("ok", None)?,
        Err(e) => {
            let msg = format!("{e:#}");
            log::error!("[github] fetch failed: {msg}");
            guard.finish("error", Some(&msg))?;
            return Err(e);
        }
    }
    Ok(())
}

fn do_fetch(
    conn: &Connection,
    owner: &str,
    repo: &str,
    token: Option<&str>,
    snapshot_date: &str,
    fetched_at: &str,
    guard: &mut RunGuard,
) -> Result<()> {
    let client = reqwest::blocking::Client::builder()
        .user_agent("analytics-ingest/0.1")
        .build()?;

    let mut page = 1u32;
    loop {
        let url = format!(
            "https://api.github.com/repos/{owner}/{repo}/releases?per_page=100&page={page}"
        );
        log::info!("[github] GET {url}");

        let mut req = client.get(&url);
        if let Some(t) = token {
            req = req.header("Authorization", format!("Bearer {t}"));
        }

        let resp = req.send().context("GitHub API request failed")?;

        // Respect rate-limit headers; 403/429 = slow down
        let status = resp.status();
        if status == reqwest::StatusCode::FORBIDDEN || status == reqwest::StatusCode::TOO_MANY_REQUESTS {
            let reset = resp
                .headers()
                .get("x-ratelimit-reset")
                .and_then(|v| v.to_str().ok())
                .and_then(|s| s.parse::<i64>().ok())
                .unwrap_or(0);
            let now = chrono::Utc::now().timestamp();
            let wait = (reset - now + 5).max(60) as u64;
            log::warn!("[github] rate limited — sleeping {wait}s");
            std::thread::sleep(std::time::Duration::from_secs(wait));
            continue;
        }

        let has_next = resp
            .headers()
            .get("link")
            .and_then(|v| v.to_str().ok())
            .map(|l| l.contains(r#"rel="next""#))
            .unwrap_or(false);

        let releases: Vec<Release> = resp
            .json()
            .context("parsing GitHub releases JSON")?;

        for release in &releases {
            if release.draft {
                continue;
            }
            for asset in &release.assets {
                let platform = derive_platform(&asset.name);
                if platform == "unknown" {
                    log::warn!("[github] unrecognized asset name (platform=unknown): {}", asset.name);
                }
                conn.execute(
                    r#"INSERT INTO github_asset_snapshots
                         (snapshot_date, release_tag, release_published_at, asset_name,
                          platform, download_count_cumulative, size_bytes, fetched_at)
                       VALUES (?1,?2,?3,?4,?5,?6,?7,?8)
                       ON CONFLICT(snapshot_date, release_tag, asset_name)
                       DO UPDATE SET
                         download_count_cumulative=excluded.download_count_cumulative,
                         fetched_at=excluded.fetched_at"#,
                    params![
                        snapshot_date,
                        release.tag_name,
                        release.published_at,
                        asset.name,
                        platform,
                        asset.download_count,
                        asset.size,
                        fetched_at,
                    ],
                )?;
                guard.rows += 1;
            }
        }

        log::info!("[github] page {page}: {} releases processed", releases.len());

        if !has_next || releases.is_empty() {
            break;
        }
        page += 1;
    }

    log::info!("[github] done — {} asset snapshots written", guard.rows);
    Ok(())
}

fn derive_platform(name: &str) -> &'static str {
    let n = name.to_ascii_lowercase();
    if n.ends_with(".dmg") || n.ends_with(".pkg") || n.contains("macos") || n.contains("darwin") {
        "macos"
    } else if n.ends_with(".msi") || n.ends_with(".exe") || n.contains("windows") || n.contains("win") {
        "windows"
    } else if n.ends_with(".appimage")
        || n.ends_with(".deb")
        || n.ends_with(".rpm")
        || n.contains("linux")
        || (n.ends_with(".tar.gz") && n.contains("linux"))
    {
        "linux"
    } else {
        "unknown"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn platform_derivation() {
        assert_eq!(derive_platform("ReelVault-1.0.dmg"), "macos");
        assert_eq!(derive_platform("ReelVault-1.0-linux.tar.gz"), "linux");
        assert_eq!(derive_platform("ReelVault-1.0-setup.msi"), "windows");
        assert_eq!(derive_platform("ReelVault-1.0.AppImage"), "linux");
        assert_eq!(derive_platform("checksums.txt"), "unknown");
    }
}
