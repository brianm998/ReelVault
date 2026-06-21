use anyhow::{Context, Result};
use rusqlite::{Connection, params};
use std::path::Path;

pub fn open(path: &Path) -> Result<Connection> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("creating db directory: {}", parent.display()))?;
    }
    let conn = Connection::open(path)
        .with_context(|| format!("opening database: {}", path.display()))?;
    conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;")?;
    Ok(conn)
}

/// Run all migrations in order; safe to re-run (idempotent).
pub fn migrate(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);",
    )?;

    let current: i64 = conn
        .query_row(
            "SELECT COALESCE(MAX(version),0) FROM schema_version",
            [],
            |r| r.get(0),
        )
        .unwrap_or(0);

    let migrations: &[(&str, i64)] = &[
        (MIGRATION_1, 1),
    ];

    for (sql, ver) in migrations {
        if current < *ver {
            conn.execute_batch(sql)
                .with_context(|| format!("running migration {ver}"))?;
            conn.execute("INSERT INTO schema_version (version) VALUES (?1)", params![ver])?;
            log::info!("Applied DB migration {ver}");
        }
    }
    Ok(())
}

const MIGRATION_1: &str = r#"
CREATE TABLE IF NOT EXISTS fetch_runs (
  id            INTEGER PRIMARY KEY,
  source        TEXT NOT NULL,
  started_at    TEXT NOT NULL,
  finished_at   TEXT,
  status        TEXT NOT NULL,
  rows_ingested INTEGER DEFAULT 0,
  detail        TEXT
);

CREATE TABLE IF NOT EXISTS apple_sales_daily (
  report_date   TEXT NOT NULL,
  sku           TEXT NOT NULL,
  title         TEXT,
  version       TEXT,
  platform      TEXT,
  country_code  TEXT NOT NULL,
  product_type  TEXT NOT NULL,
  units         INTEGER NOT NULL,
  fetched_at    TEXT NOT NULL,
  PRIMARY KEY (report_date, sku, version, platform, country_code, product_type)
);

CREATE TABLE IF NOT EXISTS play_installs_daily (
  date                      TEXT NOT NULL,
  package                   TEXT NOT NULL,
  dimension_type            TEXT NOT NULL,
  dimension_value           TEXT NOT NULL,
  daily_device_installs     INTEGER,
  daily_device_uninstalls   INTEGER,
  daily_device_upgrades     INTEGER,
  daily_user_installs       INTEGER,
  active_device_installs    INTEGER,
  total_user_installs       INTEGER,
  fetched_at                TEXT NOT NULL,
  PRIMARY KEY (date, package, dimension_type, dimension_value)
);

CREATE TABLE IF NOT EXISTS github_asset_snapshots (
  snapshot_date             TEXT NOT NULL,
  release_tag               TEXT NOT NULL,
  release_published_at      TEXT,
  asset_name                TEXT NOT NULL,
  platform                  TEXT NOT NULL,
  download_count_cumulative INTEGER NOT NULL,
  size_bytes                INTEGER,
  fetched_at                TEXT NOT NULL,
  PRIMARY KEY (snapshot_date, release_tag, asset_name)
);

CREATE VIEW IF NOT EXISTS github_asset_daily_delta AS
SELECT
  s.snapshot_date,
  s.release_tag,
  s.asset_name,
  s.platform,
  s.download_count_cumulative
    - LAG(s.download_count_cumulative) OVER (
        PARTITION BY s.release_tag, s.asset_name ORDER BY s.snapshot_date
      ) AS downloads_delta
FROM github_asset_snapshots s;

CREATE VIEW IF NOT EXISTS installs_unified AS
  SELECT report_date AS date, platform AS platform, 'appstore' AS channel,
         SUM(units) AS installs
  FROM apple_sales_daily
  WHERE product_type IN ('1', '1F', '1E', '1EP', '1EU')
  GROUP BY report_date, platform
UNION ALL
  SELECT date, 'android' AS platform, 'play' AS channel,
         SUM(daily_device_installs) AS installs
  FROM play_installs_daily
  WHERE dimension_type = 'overview'
  GROUP BY date
UNION ALL
  SELECT snapshot_date AS date, platform, 'github' AS channel,
         SUM(downloads_delta) AS installs
  FROM github_asset_daily_delta
  WHERE downloads_delta IS NOT NULL
  GROUP BY snapshot_date, platform;
"#;

pub struct RunGuard<'a> {
    conn: &'a Connection,
    pub id: i64,
    pub rows: usize,
}

impl<'a> RunGuard<'a> {
    pub fn start(conn: &'a Connection, source: &str) -> Result<Self> {
        let now = chrono::Utc::now().to_rfc3339();
        conn.execute(
            "INSERT INTO fetch_runs (source, started_at, status) VALUES (?1, ?2, 'running')",
            params![source, now],
        )?;
        let id = conn.last_insert_rowid();
        Ok(RunGuard { conn, id, rows: 0 })
    }

    pub fn finish(self, status: &str, detail: Option<&str>) -> Result<()> {
        let now = chrono::Utc::now().to_rfc3339();
        self.conn.execute(
            "UPDATE fetch_runs SET finished_at=?1, status=?2, rows_ingested=?3, detail=?4 WHERE id=?5",
            params![now, status, self.rows as i64, detail, self.id],
        )?;
        Ok(())
    }
}
