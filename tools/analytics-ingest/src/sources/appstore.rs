use anyhow::{bail, Context, Result};
use flate2::read::GzDecoder;
use rusqlite::{params, Connection};
use std::io::Read;

use crate::config::AppStoreConfig;
use crate::db::RunGuard;

/// Fetch Sales & Trends daily reports for the trailing window and upsert.
pub fn fetch(conn: &Connection, cfg: &AppStoreConfig, since: Option<&str>) -> Result<()> {
    cfg.validate()?;

    let issuer_id = cfg.issuer_id.as_deref().unwrap();
    let key_id = cfg.key_id.as_deref().unwrap();
    let vendor_number = cfg.vendor_number.as_deref().unwrap();
    let key_path = cfg.private_key_path.as_deref().unwrap();

    let mut guard = RunGuard::start(conn, "appstore_sales")?;

    match do_fetch(conn, issuer_id, key_id, vendor_number, key_path, since, &mut guard) {
        Ok(()) => guard.finish("ok", None)?,
        Err(e) => {
            let msg = format!("{e:#}");
            log::error!("[appstore] fetch failed: {msg}");
            guard.finish("error", Some(&msg))?;
            return Err(e);
        }
    }
    Ok(())
}

fn do_fetch(
    conn: &Connection,
    issuer_id: &str,
    key_id: &str,
    vendor_number: &str,
    key_path: &str,
    since: Option<&str>,
    guard: &mut RunGuard,
) -> Result<()> {
    let jwt = mint_jwt(issuer_id, key_id, key_path)?;
    let client = reqwest::blocking::Client::builder()
        .user_agent("analytics-ingest/0.1")
        .build()?;

    // Determine start date: use --since, or MAX(report_date)-1 day overlap, or 5 days ago.
    let start_date = if let Some(s) = since {
        chrono::NaiveDate::parse_from_str(s, "%Y-%m-%d")
            .context("invalid --since date")?
    } else {
        let stored: Option<String> = conn
            .query_row(
                "SELECT MAX(report_date) FROM apple_sales_daily",
                [],
                |r| r.get(0),
            )
            .ok()
            .flatten();
        if let Some(d) = stored {
            chrono::NaiveDate::parse_from_str(&d, "%Y-%m-%d")
                .map(|d| d - chrono::Duration::days(1))
                .unwrap_or_else(|_| chrono::Utc::now().date_naive() - chrono::Duration::days(5))
        } else {
            chrono::Utc::now().date_naive() - chrono::Duration::days(5)
        }
    };

    // Reports lag 24-48h; fetch up to yesterday.
    let end_date = chrono::Utc::now().date_naive() - chrono::Duration::days(1);
    let mut current = start_date;

    while current <= end_date {
        let date_str = current.format("%Y-%m-%d").to_string();
        log::info!("[appstore] fetching report for {date_str}");

        match fetch_one_day(&client, &jwt, vendor_number, &date_str) {
            Ok(Some(tsv_bytes)) => {
                let rows = parse_and_upsert(conn, &tsv_bytes, &date_str)?;
                guard.rows += rows;
                log::info!("[appstore] {date_str}: {rows} rows upserted");
            }
            Ok(None) => {
                log::info!("[appstore] {date_str}: report not ready yet — skipping");
            }
            Err(e) => {
                log::warn!("[appstore] {date_str}: error — {e:#}");
            }
        }

        current += chrono::Duration::days(1);
    }

    log::info!("[appstore] done — {} total rows", guard.rows);
    Ok(())
}

fn fetch_one_day(
    client: &reqwest::blocking::Client,
    jwt: &str,
    vendor_number: &str,
    date: &str,
) -> Result<Option<Vec<u8>>> {
    let url = "https://api.appstoreconnect.apple.com/v1/salesReports";
    let resp = client
        .get(url)
        .header("Authorization", format!("Bearer {jwt}"))
        .query(&[
            ("filter[frequency]", "DAILY"),
            ("filter[reportType]", "SALES"),
            ("filter[reportSubType]", "SUMMARY"),
            ("filter[vendorNumber]", vendor_number),
            ("filter[reportDate]", date),
            ("filter[version]", "1_1"),
        ])
        .send()
        .context("App Store Connect request failed")?;

    let status = resp.status();
    if status == reqwest::StatusCode::NOT_FOUND {
        return Ok(None); // report not ready
    }
    if !status.is_success() {
        let body = resp.text().unwrap_or_default();
        bail!("App Store Connect HTTP {status}: {body}");
    }

    // Response body is gzip-compressed TSV.
    let bytes = resp.bytes().context("reading App Store response body")?;
    let mut gz = GzDecoder::new(&bytes[..]);
    let mut decompressed = Vec::new();
    gz.read_to_end(&mut decompressed)
        .context("decompressing App Store response")?;
    Ok(Some(decompressed))
}

fn parse_and_upsert(conn: &Connection, tsv_bytes: &[u8], _report_date: &str) -> Result<usize> {
    let text = String::from_utf8_lossy(tsv_bytes);
    let fetched_at = chrono::Utc::now().to_rfc3339();
    let mut rdr = csv::ReaderBuilder::new()
        .delimiter(b'\t')
        .from_reader(text.as_bytes());

    let headers = rdr.headers().context("reading TSV headers")?.clone();
    let col = |name: &str| headers.iter().position(|h| h == name);

    let idx_sku = col("SKU").context("TSV missing 'SKU' column")?;
    let idx_title = col("Title");
    let idx_version = col("Version");
    let idx_units = col("Units").context("TSV missing 'Units' column")?;
    let idx_begin = col("Begin Date").context("TSV missing 'Begin Date'")?;
    let idx_country = col("Country Code").context("TSV missing 'Country Code'")?;
    let idx_product_type = col("Product Type Identifier").context("TSV missing 'Product Type Identifier'")?;
    let idx_device = col("Device");
    let idx_platform = col("Supported Platforms");

    let mut count = 0usize;
    for result in rdr.records() {
        let record = result.context("reading TSV row")?;
        let get = |i: usize| record.get(i).unwrap_or("").trim().to_string();

        let sku = get(idx_sku);
        let title = idx_title.map(get);
        let version = idx_version.map(get);
        let units: i64 = get(idx_units).parse().unwrap_or(0);
        let report_date = normalize_date(&get(idx_begin));
        let country_code = get(idx_country);
        let product_type = get(idx_product_type);
        let device = idx_device.map(get).unwrap_or_default();
        let supported_platforms = idx_platform.map(get).unwrap_or_default();
        let platform = derive_platform(&device, &supported_platforms);

        conn.execute(
            r#"INSERT INTO apple_sales_daily
                 (report_date, sku, title, version, platform, country_code, product_type, units, fetched_at)
               VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)
               ON CONFLICT(report_date, sku, version, platform, country_code, product_type)
               DO UPDATE SET units=excluded.units, fetched_at=excluded.fetched_at"#,
            params![
                report_date, sku, title, version, platform, country_code,
                product_type, units, fetched_at
            ],
        )?;
        count += 1;
    }
    Ok(count)
}

/// Map Device + Supported Platforms columns to 'ios' or 'macos'.
fn derive_platform(device: &str, supported_platforms: &str) -> &'static str {
    let combined = format!("{device} {supported_platforms}").to_ascii_lowercase();
    if combined.contains("mac") || combined.contains("osx") {
        "macos"
    } else {
        "ios"
    }
}

/// Normalize MM/DD/YYYY → YYYY-MM-DD.
fn normalize_date(s: &str) -> String {
    if let Ok(d) = chrono::NaiveDate::parse_from_str(s, "%m/%d/%Y") {
        return d.format("%Y-%m-%d").to_string();
    }
    s.to_string()
}

fn mint_jwt(issuer_id: &str, key_id: &str, key_path: &str) -> Result<String> {
    let pem = std::fs::read_to_string(key_path)
        .with_context(|| format!("reading private key: {key_path}"))?;

    let now = chrono::Utc::now().timestamp();
    let exp = now + 1100; // < 1200s limit

    // Build header + claims manually to avoid pulling in a full JWT crate
    // that may not support ES256 directly without extra features.
    // We use jsonwebtoken which does support ES256.
    use jsonwebtoken::{Algorithm, EncodingKey, Header};

    let mut header = Header::new(Algorithm::ES256);
    header.kid = Some(key_id.to_string());

    #[derive(serde::Serialize)]
    struct Claims<'a> {
        iss: &'a str,
        iat: i64,
        exp: i64,
        aud: &'a str,
    }

    let claims = Claims {
        iss: issuer_id,
        iat: now,
        exp,
        aud: "appstoreconnect-v1",
    };

    let key = EncodingKey::from_ec_pem(pem.as_bytes())
        .context("parsing EC private key (.p8)")?;

    jsonwebtoken::encode(&header, &claims, &key).context("signing JWT")
}

pub fn verify_credentials(cfg: &AppStoreConfig) -> Result<()> {
    cfg.validate()?;
    let issuer_id = cfg.issuer_id.as_deref().unwrap();
    let key_id = cfg.key_id.as_deref().unwrap();
    let key_path = cfg.private_key_path.as_deref().unwrap();
    let jwt = mint_jwt(issuer_id, key_id, key_path)?;
    log::info!("[appstore] JWT minted successfully (kid={key_id})");

    // Quick ping — fetch a recent date to test auth.
    let client = reqwest::blocking::Client::builder()
        .user_agent("analytics-ingest/0.1")
        .build()?;
    let yesterday = (chrono::Utc::now().date_naive() - chrono::Duration::days(2))
        .format("%Y-%m-%d")
        .to_string();
    let resp = client
        .get("https://api.appstoreconnect.apple.com/v1/salesReports")
        .header("Authorization", format!("Bearer {jwt}"))
        .query(&[
            ("filter[frequency]", "DAILY"),
            ("filter[reportType]", "SALES"),
            ("filter[reportSubType]", "SUMMARY"),
            ("filter[vendorNumber]", cfg.vendor_number.as_deref().unwrap()),
            ("filter[reportDate]", yesterday.as_str()),
            ("filter[version]", "1_1"),
        ])
        .send()
        .context("App Store Connect connectivity test failed")?;

    let status = resp.status();
    if status == reqwest::StatusCode::UNAUTHORIZED || status == reqwest::StatusCode::FORBIDDEN {
        bail!("App Store Connect auth failed (HTTP {status}) — check issuer_id/key_id/p8 key");
    }
    log::info!("[appstore] credentials OK (HTTP {status})");
    Ok(())
}
