use anyhow::{bail, Context, Result};
use chrono::Datelike;
use encoding_rs::UTF_16LE;
use rusqlite::{params, Connection};
use serde::Deserialize;

use crate::config::PlayConfig;
use crate::db::RunGuard;

#[derive(Debug, Deserialize)]
struct ServiceAccountKey {
    #[serde(rename = "type")]
    key_type: String,
    project_id: Option<String>,
    private_key_id: String,
    private_key: String,
    client_email: String,
    token_uri: String,
}

pub fn fetch(conn: &Connection, cfg: &PlayConfig, since: Option<&str>) -> Result<()> {
    cfg.validate()?;

    let mut guard = RunGuard::start(conn, "play_installs")?;
    match do_fetch(conn, cfg, since, &mut guard) {
        Ok(()) => guard.finish("ok", None)?,
        Err(e) => {
            let msg = format!("{e:#}");
            log::error!("[play] fetch failed: {msg}");
            guard.finish("error", Some(&msg))?;
            return Err(e);
        }
    }
    Ok(())
}

fn do_fetch(
    conn: &Connection,
    cfg: &PlayConfig,
    _since: Option<&str>,
    guard: &mut RunGuard,
) -> Result<()> {
    let package = cfg.package.as_deref().context("play.package not set")?;
    let bucket = cfg.bucket.as_deref().context("play.bucket not set")?;
    let sa_path = cfg.service_account_json_path.as_deref().context("play.service_account_json_path not set")?;

    let sa_json = std::fs::read_to_string(sa_path)
        .with_context(|| format!("reading service account key: {sa_path}"))?;
    let sa: ServiceAccountKey = serde_json::from_str(&sa_json)
        .context("parsing service account JSON")?;

    if sa.key_type != "service_account" {
        bail!("expected service_account key type, got: {}", sa.key_type);
    }

    let access_token = obtain_access_token(&sa)
        .context("obtaining GCS access token")?;

    let client = reqwest::blocking::Client::builder()
        .user_agent("analytics-ingest/0.1")
        .build()?;

    // Fetch current and previous month.
    let now = chrono::Utc::now().date_naive();
    let months = [
        now.format("%Y%m").to_string(),
        {
            let prev = if now.month() == 1 {
                chrono::NaiveDate::from_ymd_opt(now.year() - 1, 12, 1).unwrap()
            } else {
                chrono::NaiveDate::from_ymd_opt(now.year(), now.month() - 1, 1).unwrap()
            };
            prev.format("%Y%m").to_string()
        },
    ];

    let dimensions = ["overview", "country", "device", "os_version", "app_version"];

    for month in &months {
        for dim in &dimensions {
            let filename = format!(
                "stats/installs/installs_{package}_{month}_{dim}.csv"
            );

            log::info!("[play] fetching gs://{bucket}/{filename}");
            match fetch_gcs_object(&client, bucket, &filename, &access_token) {
                Ok(bytes) => {
                    let rows = parse_and_upsert(conn, &bytes, package, dim)?;
                    guard.rows += rows;
                    log::info!("[play] {filename}: {rows} rows upserted");
                }
                Err(e) => {
                    log::warn!("[play] {filename}: {e:#} — skipping");
                }
            }
        }
    }

    log::info!("[play] done — {} total rows", guard.rows);
    Ok(())
}

fn fetch_gcs_object(
    client: &reqwest::blocking::Client,
    bucket: &str,
    object: &str,
    token: &str,
) -> Result<Vec<u8>> {
    let encoded = percent_encode(object);
    let url = format!(
        "https://storage.googleapis.com/storage/v1/b/{bucket}/o/{encoded}?alt=media"
    );

    let resp = client
        .get(&url)
        .header("Authorization", format!("Bearer {token}"))
        .send()
        .context("GCS request failed")?;

    let status = resp.status();
    if status == reqwest::StatusCode::NOT_FOUND {
        bail!("object not found");
    }
    if !status.is_success() {
        let body = resp.text().unwrap_or_default();
        bail!("GCS HTTP {status}: {body}");
    }

    let bytes = resp.bytes().context("reading GCS response body")?;
    Ok(bytes.to_vec())
}

fn parse_and_upsert(
    conn: &Connection,
    raw: &[u8],
    package: &str,
    dimension_type: &str,
) -> Result<usize> {
    // Google Play CSVs are UTF-16LE with BOM.
    let (decoded, _enc, _had_errors) = UTF_16LE.decode(raw);
    // Strip BOM if present.
    let text = decoded.trim_start_matches('\u{FEFF}');

    let fetched_at = chrono::Utc::now().to_rfc3339();
    let mut rdr = csv::ReaderBuilder::new()
        .from_reader(text.as_bytes());

    let headers = rdr.headers().context("reading Play CSV headers")?.clone();
    let col = |name: &str| {
        headers.iter().position(|h| h.trim() == name)
    };

    let idx_date = col("Date").context("Play CSV missing 'Date'")?;
    let idx_pkg = col("Package Name").or_else(|| col("package_name"));
    let idx_daily_dev_inst = col("Daily Device Installs");
    let idx_daily_dev_uninst = col("Daily Device Uninstalls");
    let idx_daily_dev_upg = col("Daily Device Upgrades");
    let idx_daily_user_inst = col("Daily User Installs");
    let idx_active_dev = col("Active Device Installs");
    let idx_total_user = col("Total User Installs");

    // For breakdown dimensions the dimension value is in a different column per type.
    let dim_col = match dimension_type {
        "country" => col("Country"),
        "device" => col("Device"),
        "os_version" => col("Android OS Version").or_else(|| col("OS Version")),
        "app_version" => col("App Version Code").or_else(|| col("App Version")),
        _ => None, // overview has no breakdown column
    };

    let mut count = 0usize;
    for result in rdr.records() {
        let record = result.context("reading Play CSV row")?;
        let get = |i: usize| record.get(i).unwrap_or("").trim().to_string();
        let get_i64 = |i: Option<usize>| -> Option<i64> {
            i.and_then(|idx| get(idx).replace(',', "").parse().ok())
        };

        let date = normalize_date(&get(idx_date));
        let dimension_value = dim_col.map(get).unwrap_or_default();
        let pkg = idx_pkg.map(get).unwrap_or_else(|| package.to_string());

        conn.execute(
            r#"INSERT INTO play_installs_daily
                 (date, package, dimension_type, dimension_value,
                  daily_device_installs, daily_device_uninstalls, daily_device_upgrades,
                  daily_user_installs, active_device_installs, total_user_installs, fetched_at)
               VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)
               ON CONFLICT(date, package, dimension_type, dimension_value)
               DO UPDATE SET
                 daily_device_installs=excluded.daily_device_installs,
                 daily_device_uninstalls=excluded.daily_device_uninstalls,
                 daily_device_upgrades=excluded.daily_device_upgrades,
                 daily_user_installs=excluded.daily_user_installs,
                 active_device_installs=excluded.active_device_installs,
                 total_user_installs=excluded.total_user_installs,
                 fetched_at=excluded.fetched_at"#,
            params![
                date, pkg, dimension_type, dimension_value,
                get_i64(idx_daily_dev_inst),
                get_i64(idx_daily_dev_uninst),
                get_i64(idx_daily_dev_upg),
                get_i64(idx_daily_user_inst),
                get_i64(idx_active_dev),
                get_i64(idx_total_user),
                fetched_at,
            ],
        )?;
        count += 1;
    }
    Ok(count)
}

fn normalize_date(s: &str) -> String {
    // Try YYYY-MM-DD first.
    if chrono::NaiveDate::parse_from_str(s, "%Y-%m-%d").is_ok() {
        return s.to_string();
    }
    // Try MM/DD/YYYY.
    if let Ok(d) = chrono::NaiveDate::parse_from_str(s, "%m/%d/%Y") {
        return d.format("%Y-%m-%d").to_string();
    }
    // Try YYYYMMDD.
    if let Ok(d) = chrono::NaiveDate::parse_from_str(s, "%Y%m%d") {
        return d.format("%Y-%m-%d").to_string();
    }
    s.to_string()
}

fn percent_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 2);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char);
            }
            b'/' => out.push_str("%2F"),
            _ => {
                out.push_str(&format!("%{b:02X}"));
            }
        }
    }
    out
}

// --- OAuth2 service-account token exchange -----------------------------------

#[derive(Deserialize)]
struct TokenResponse {
    access_token: String,
}

fn obtain_access_token(sa: &ServiceAccountKey) -> Result<String> {
    let now = chrono::Utc::now().timestamp();

    #[derive(serde::Serialize)]
    struct JwtClaims<'a> {
        iss: &'a str,
        scope: &'a str,
        aud: &'a str,
        iat: i64,
        exp: i64,
    }

    let claims = JwtClaims {
        iss: &sa.client_email,
        scope: "https://www.googleapis.com/auth/devstorage.read_only",
        aud: &sa.token_uri,
        iat: now,
        exp: now + 3600,
    };

    use jsonwebtoken::{Algorithm, EncodingKey, Header};
    let header = Header::new(Algorithm::RS256);
    let key = EncodingKey::from_rsa_pem(sa.private_key.as_bytes())
        .context("parsing service account RSA private key")?;
    let assertion = jsonwebtoken::encode(&header, &claims, &key)
        .context("signing service-account JWT")?;

    let client = reqwest::blocking::Client::new();
    let resp: TokenResponse = client
        .post(&sa.token_uri)
        .form(&[
            ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
            ("assertion", &assertion),
        ])
        .send()
        .context("token request failed")?
        .json()
        .context("parsing token response")?;

    Ok(resp.access_token)
}

pub fn verify_credentials(cfg: &PlayConfig) -> Result<()> {
    cfg.validate()?;
    let sa_path = cfg.service_account_json_path.as_deref().unwrap();
    let sa_json = std::fs::read_to_string(sa_path)?;
    let sa: ServiceAccountKey = serde_json::from_str(&sa_json)?;
    let token = obtain_access_token(&sa)?;
    log::info!("[play] access token obtained ({}...)", &token[..8.min(token.len())]);

    // List bucket root to verify permissions.
    let bucket = cfg.bucket.as_deref().unwrap();
    let client = reqwest::blocking::Client::new();
    let url = format!("https://storage.googleapis.com/storage/v1/b/{bucket}/o?maxResults=1");
    let resp = client
        .get(&url)
        .header("Authorization", format!("Bearer {token}"))
        .send()?;
    let status = resp.status();
    if !status.is_success() {
        bail!("GCS bucket access failed (HTTP {status}) — check IAM permissions on {bucket}");
    }
    log::info!("[play] GCS bucket {bucket} accessible — credentials OK");
    Ok(())
}
