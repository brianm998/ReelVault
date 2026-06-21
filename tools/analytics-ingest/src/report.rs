use anyhow::Result;
use rusqlite::Connection;

pub fn run(conn: &Connection, name: &str) -> Result<()> {
    match name {
        "installs-30d" => installs_30d(conn),
        "installs-daily" => installs_daily(conn),
        "android-installed-base" => android_installed_base(conn),
        "github-by-platform" => github_by_platform(conn),
        "total-installs" => total_installs(conn),
        "fetch-runs" => fetch_runs(conn),
        _ => {
            eprintln!("Unknown report: {name}");
            eprintln!("Available reports:");
            eprintln!("  installs-30d           Installs per platform/channel, last 30 days");
            eprintln!("  installs-daily         Daily install trend, all platforms");
            eprintln!("  android-installed-base Current Android installed base");
            eprintln!("  github-by-platform     GitHub downloads by platform + release");
            eprintln!("  total-installs         Total installs across all sources");
            eprintln!("  fetch-runs             Recent fetch run history");
            Ok(())
        }
    }
}

fn installs_30d(conn: &Connection) -> Result<()> {
    println!("{:<12} {:<12} {:>10}", "platform", "channel", "installs");
    println!("{}", "-".repeat(36));
    let mut stmt = conn.prepare(
        "SELECT platform, channel, SUM(installs) AS installs
         FROM installs_unified
         WHERE date >= date('now','-30 day') AND installs IS NOT NULL
         GROUP BY platform, channel
         ORDER BY installs DESC",
    )?;
    let mut rows = stmt.query([])?;
    while let Some(row) = rows.next()? {
        let platform: String = row.get(0)?;
        let channel: String = row.get(1)?;
        let installs: i64 = row.get(2)?;
        println!("{platform:<12} {channel:<12} {installs:>10}");
    }
    Ok(())
}

fn installs_daily(conn: &Connection) -> Result<()> {
    println!("{:<12} {:>10}", "date", "total");
    println!("{}", "-".repeat(24));
    let mut stmt = conn.prepare(
        "SELECT date, SUM(installs) AS total
         FROM installs_unified
         WHERE installs IS NOT NULL
         GROUP BY date
         ORDER BY date",
    )?;
    let mut rows = stmt.query([])?;
    while let Some(row) = rows.next()? {
        let date: String = row.get(0)?;
        let total: i64 = row.get(1)?;
        println!("{date:<12} {total:>10}");
    }
    Ok(())
}

fn android_installed_base(conn: &Connection) -> Result<()> {
    let result: Option<i64> = conn
        .query_row(
            "SELECT active_device_installs FROM play_installs_daily
             WHERE dimension_type='overview' ORDER BY date DESC LIMIT 1",
            [],
            |r| r.get(0),
        )
        .ok()
        .flatten();
    match result {
        Some(n) => println!("Active Android device installs: {n}"),
        None => println!("No Play data found"),
    }
    Ok(())
}

fn github_by_platform(conn: &Connection) -> Result<()> {
    println!("{:<10} {:<20} {:>10}", "platform", "release", "downloads");
    println!("{}", "-".repeat(42));
    let mut stmt = conn.prepare(
        "SELECT platform, release_tag, SUM(downloads_delta) AS downloads
         FROM github_asset_daily_delta
         WHERE snapshot_date >= date('now','-30 day') AND downloads_delta IS NOT NULL
         GROUP BY platform, release_tag
         ORDER BY downloads DESC",
    )?;
    let mut rows = stmt.query([])?;
    while let Some(row) = rows.next()? {
        let platform: String = row.get(0)?;
        let tag: String = row.get(1)?;
        let downloads: i64 = row.get(2)?;
        println!("{platform:<10} {tag:<20} {downloads:>10}");
    }
    Ok(())
}

fn total_installs(conn: &Connection) -> Result<()> {
    let total: Option<i64> = conn
        .query_row(
            "SELECT SUM(installs) FROM installs_unified WHERE installs IS NOT NULL",
            [],
            |r| r.get(0),
        )
        .ok()
        .flatten();
    match total {
        Some(n) => println!("Total installs (all sources, all time): {n}"),
        None => println!("No install data found"),
    }
    Ok(())
}

fn fetch_runs(conn: &Connection) -> Result<()> {
    println!("{:<6} {:<20} {:<22} {:<8} {:>8}  detail", "id", "source", "started_at", "status", "rows");
    println!("{}", "-".repeat(80));
    let mut stmt = conn.prepare(
        "SELECT id, source, started_at, COALESCE(status,'?'), COALESCE(rows_ingested,0), COALESCE(detail,'')
         FROM fetch_runs ORDER BY id DESC LIMIT 20",
    )?;
    let mut rows = stmt.query([])?;
    while let Some(row) = rows.next()? {
        let id: i64 = row.get(0)?;
        let source: String = row.get(1)?;
        let started: String = row.get(2)?;
        let status: String = row.get(3)?;
        let ingested: i64 = row.get(4)?;
        let detail: String = row.get(5)?;
        let detail_short = if detail.len() > 30 { &detail[..30] } else { &detail };
        println!("{id:<6} {source:<20} {started:<22} {status:<8} {ingested:>8}  {detail_short}");
    }
    Ok(())
}
