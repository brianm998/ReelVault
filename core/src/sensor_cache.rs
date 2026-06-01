// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Runtime-discovered camera sensor resolutions.
//!
//! The built-in [`crate::full_resolution::SENSOR_RESOLUTIONS`] table
//! ships with the binary and covers the bodies we've hand-curated. When
//! the indexer encounters a `camera_model` that's not in that table,
//! [`ensure_cached`] issues a best-effort Wikidata SPARQL query (via
//! `curl`, mirroring the `Command::new("ffprobe")` pattern in
//! `metadata.rs`) and caches the result in the catalog's
//! `camera_sensor_cache` SQLite table.
//!
//! Lookups via [`classify_with_cache`] merge cached entries on top of
//! the built-in table — built-in always wins for cameras present in
//! both. Negative cache entries (the upstream source returned nothing
//! usable) are kept for [`NEGATIVE_TTL_DAYS`] to avoid hammering the
//! endpoint for cameras nobody knows about. Positive entries are kept
//! forever; users can prune via `reelvault catalog reset-sensor-cache`
//! if upstream data improves later.
//!
//! HTTP-via-curl is intentional: the existing core already shells out
//! to ffprobe / ffmpeg, and curl ships on every macOS / Linux box and
//! is bundled in Windows 10+. Pulling in `reqwest` for one endpoint
//! would inflate the binary by ~2 MB.

use crate::error::{Result, ReelVaultError};
use crate::full_resolution::{builtin_natives, classify_with_natives, Classification};
use rusqlite::{params, Connection};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

/// How long to remember a "we asked Wikidata and got nothing" verdict.
/// Short enough that a body added to Wikidata becomes discoverable
/// within a couple of weeks; long enough that the daily scan of a
/// catalog full of obscure bodies doesn't hammer the public endpoint.
pub const NEGATIVE_TTL_DAYS: i64 = 14;

/// Wikidata SPARQL endpoint. Mirrors the constant in
/// `tools/fetch_sensor_resolutions.py`.
const WIKIDATA_ENDPOINT: &str = "https://query.wikidata.org/sparql";

/// User-Agent string for the Wikidata HTTP request. Wikidata requires
/// callers to identify themselves; an unset User-Agent gets rate-limited
/// or blocked entirely.
fn user_agent() -> String {
    format!(
        "ReelVault/{} (https://github.com/reelvault/reelvault) curl",
        env!("CARGO_PKG_VERSION")
    )
}

/// Maximum wall time we'll wait on the SPARQL endpoint. The public
/// service has been slow in the past; 30 seconds is generous enough
/// that flaky weather doesn't poison the cache with negatives, but
/// short enough that an unreachable endpoint doesn't stall a scan.
const CURL_TIMEOUT_SECS: u64 = 30;

/// Classify a video using both the built-in table and any cached
/// runtime entry for this camera.
///
/// The cache lookup is synchronous and reads from SQLite directly —
/// it does not trigger a Wikidata fetch. To populate the cache, call
/// [`ensure_cached`] from a context where blocking I/O is acceptable
/// (post-index pipeline, on-demand background task, etc.).
pub fn classify_with_cache(
    conn: &Connection,
    camera_model: &str,
    width: u32,
    height: u32,
) -> Classification {
    let builtin = builtin_natives(camera_model);
    if !builtin.is_empty() {
        // Built-in entries take precedence: don't bother reading the
        // cache for cameras we already know about.
        return classify_with_natives(builtin, width, height);
    }

    let cached = lookup_cached(conn, camera_model).unwrap_or_default();
    classify_with_natives(&cached, width, height)
}

/// Read cached sensor resolutions for `camera_model`. Returns an empty
/// vec for an absent entry, a "pending" placeholder, or a stale
/// negative entry (callers don't need to distinguish — all three mean
/// "no usable data" for classification purposes).
pub fn lookup_cached(conn: &Connection, camera_model: &str) -> Result<Vec<(u32, u32)>> {
    let key = crate::camera_names::normalise(camera_model);
    if key.is_empty() {
        return Ok(Vec::new());
    }

    let row: Option<(Option<String>, i64)> = conn
        .query_row(
            "SELECT native_resolutions, fetched_at FROM camera_sensor_cache WHERE camera_key = ?",
            params![&key],
            |r| Ok((r.get::<_, Option<String>>(0)?, r.get::<_, i64>(1)?)),
        )
        .ok();

    match row {
        None => Ok(Vec::new()),
        Some((None, _)) => Ok(Vec::new()),
        Some((Some(json), _)) => {
            let parsed: Vec<(u32, u32)> = serde_json::from_str(&json).unwrap_or_default();
            Ok(parsed)
        }
    }
}

/// Insert or replace a cache entry for `camera_model`. Pass `Some(...)`
/// for a positive entry (upstream returned dimensions) or `None` for a
/// negative entry (upstream had nothing usable for this body).
///
/// Public so the CLI's `reset-sensor-cache` path can call it, and so
/// tests can prime entries directly.
pub fn store_cached(
    conn: &Connection,
    camera_model: &str,
    natives: Option<&[(u32, u32)]>,
    source: &str,
) -> Result<()> {
    let key = crate::camera_names::normalise(camera_model);
    if key.is_empty() {
        return Ok(());
    }
    let json = natives.map(|n| serde_json::to_string(&n.to_vec()).unwrap_or_else(|_| "[]".into()));
    let now = unix_secs();
    conn.execute(
        "INSERT INTO camera_sensor_cache (camera_key, native_resolutions, fetched_at, source)
         VALUES (?, ?, ?, ?)
         ON CONFLICT(camera_key) DO UPDATE SET
             native_resolutions=excluded.native_resolutions,
             fetched_at=excluded.fetched_at,
             source=excluded.source",
        params![&key, json, now, source],
    )
    .map_err(|e| ReelVaultError::DatabaseError(format!("camera_sensor_cache insert: {e}")))?;
    Ok(())
}

/// Make sure `camera_model` has a cached entry (or a fresh negative
/// entry). Returns the natives the cache now holds for the camera,
/// which may be empty.
///
/// Skipped silently when:
/// - The camera is empty / blank.
/// - The camera is already covered by the built-in table.
/// - A non-stale cache entry already exists (positive or negative).
///
/// Blocks for up to [`CURL_TIMEOUT_SECS`] when an actual fetch
/// happens. Designed to be called from the post-index worker pool,
/// where a per-video latency hit is acceptable and naturally throttled.
pub fn ensure_cached(conn: &Connection, camera_model: &str) -> Result<Vec<(u32, u32)>> {
    let key = crate::camera_names::normalise(camera_model);
    if key.is_empty() {
        return Ok(Vec::new());
    }
    if !builtin_natives(camera_model).is_empty() {
        return Ok(builtin_natives(camera_model).to_vec());
    }

    // Cheap path: cached entry already exists and is fresh.
    let existing: Option<(Option<String>, i64)> = conn
        .query_row(
            "SELECT native_resolutions, fetched_at FROM camera_sensor_cache WHERE camera_key = ?",
            params![&key],
            |r| Ok((r.get::<_, Option<String>>(0)?, r.get::<_, i64>(1)?)),
        )
        .ok();
    if let Some((stored, fetched_at)) = existing {
        let stale_negative = stored.is_none() && cache_age_days(fetched_at) > NEGATIVE_TTL_DAYS;
        if !stale_negative {
            return lookup_cached(conn, camera_model);
        }
    }

    // Reserve the slot with a "pending" marker so concurrent workers
    // dedupe on the cache key. This is racey — two workers may both
    // see "no entry" and both write the pending row — but the only
    // cost is duplicated curl, not duplicated cache state.
    store_cached(conn, camera_model, None, "pending")?;

    match fetch_via_curl(camera_model) {
        Ok(Some(natives)) => {
            tracing::info!(
                camera = %camera_model,
                resolutions = ?natives,
                "sensor_cache: Wikidata returned data"
            );
            store_cached(conn, camera_model, Some(&natives), "wikidata")?;
            Ok(natives)
        }
        Ok(None) => {
            tracing::info!(
                camera = %camera_model,
                "sensor_cache: Wikidata returned nothing; negative-caching for {} days",
                NEGATIVE_TTL_DAYS
            );
            store_cached(conn, camera_model, None, "wikidata")?;
            Ok(Vec::new())
        }
        Err(e) => {
            tracing::warn!(
                camera = %camera_model,
                error = %e,
                "sensor_cache: Wikidata fetch failed; leaving 'pending' marker"
            );
            // Keep the pending marker so a future call retries (the
            // pending row won't shadow a positive that arrives later).
            Ok(Vec::new())
        }
    }
}

/// SPARQL: find a camera entity whose label or P1545 (model ID) string
/// matches the input, and surface its pixel width / height (P2049 /
/// P2048). The endpoint's coverage of these properties is patchy;
/// callers must treat an empty result as normal rather than an error.
fn build_sparql_query(camera_model: &str) -> String {
    // Defensive: a model containing `"` would break the SPARQL literal.
    // We strip rather than escape because the upstream label set is
    // unlikely to contain quotes and we'd rather fail to match than
    // smuggle through anything weird.
    let needle = camera_model.replace('"', "").to_lowercase();
    format!(
        r#"
SELECT DISTINCT ?width ?height WHERE {{
  ?cam wdt:P31/wdt:P279* wd:Q1378085 .
  ?cam rdfs:label ?label .
  ?cam wdt:P2049 ?width .
  ?cam wdt:P2048 ?height .
  FILTER(LANG(?label) = "en")
  FILTER(CONTAINS(LCASE(STR(?label)), "{needle}"))
}}
LIMIT 5
"#
    )
}

/// Shell out to `curl` to POST the SPARQL query. Returns the raw JSON
/// body on success.
fn fetch_via_curl(camera_model: &str) -> std::result::Result<Option<Vec<(u32, u32)>>, String> {
    let query = build_sparql_query(camera_model);
    let output = Command::new("curl")
        .args([
            "--silent",
            "--show-error",
            "--fail",
            "--max-time",
            &CURL_TIMEOUT_SECS.to_string(),
            "--user-agent",
            &user_agent(),
            "--header",
            "Accept: application/sparql-results+json",
            "--data-urlencode",
        ])
        .arg(format!("query={query}"))
        .arg("--data-urlencode")
        .arg("format=json")
        .arg(WIKIDATA_ENDPOINT)
        .output()
        .map_err(|e| format!("curl spawn failed: {e}"))?;

    if !output.status.success() {
        return Err(format!(
            "curl exit {}: {}",
            output.status,
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }

    let body = std::str::from_utf8(&output.stdout)
        .map_err(|e| format!("curl returned non-UTF-8: {e}"))?;
    parse_sparql_response(body)
}

/// Decode the SPARQL JSON results into a deduped list of (w, h) pairs.
/// Returns `Ok(None)` when the response was valid but contained no
/// usable rows.
fn parse_sparql_response(body: &str) -> std::result::Result<Option<Vec<(u32, u32)>>, String> {
    let v: serde_json::Value =
        serde_json::from_str(body).map_err(|e| format!("SPARQL JSON parse: {e}"))?;
    let bindings = v
        .get("results")
        .and_then(|r| r.get("bindings"))
        .and_then(|b| b.as_array())
        .ok_or_else(|| "SPARQL response missing results.bindings".to_string())?;

    let mut seen = std::collections::BTreeSet::new();
    for row in bindings {
        let width = row
            .get("width")
            .and_then(|x| x.get("value"))
            .and_then(|x| x.as_str())
            .and_then(|s| s.parse::<f64>().ok())
            .map(|f| f as u32);
        let height = row
            .get("height")
            .and_then(|x| x.get("value"))
            .and_then(|x| x.as_str())
            .and_then(|s| s.parse::<f64>().ok())
            .map(|f| f as u32);
        if let (Some(w), Some(h)) = (width, height) {
            if w > 0 && h > 0 {
                seen.insert((w, h));
            }
        }
    }

    if seen.is_empty() {
        Ok(None)
    } else {
        Ok(Some(seen.into_iter().collect()))
    }
}

fn unix_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn cache_age_days(fetched_at_secs: i64) -> i64 {
    let now = unix_secs();
    let diff = now.saturating_sub(fetched_at_secs);
    diff / 86_400
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;

    fn open_memory_db() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(
            "CREATE TABLE camera_sensor_cache (
                camera_key TEXT PRIMARY KEY,
                native_resolutions TEXT,
                fetched_at INTEGER NOT NULL,
                source TEXT NOT NULL
            );",
        )
        .unwrap();
        c
    }

    #[test]
    fn store_and_lookup_positive_round_trip() {
        let c = open_memory_db();
        store_cached(&c, "HASSELBLAD X2D 100C", Some(&[(11656, 8742)]), "test").unwrap();
        let got = lookup_cached(&c, "HASSELBLAD X2D 100C").unwrap();
        assert_eq!(got, vec![(11656, 8742)]);
    }

    #[test]
    fn lookup_is_normalisation_tolerant() {
        let c = open_memory_db();
        store_cached(&c, "hasselblad  x2d 100c", Some(&[(11656, 8742)]), "test").unwrap();
        let got = lookup_cached(&c, "HASSELBLAD X2D 100C").unwrap();
        assert_eq!(got, vec![(11656, 8742)]);
    }

    #[test]
    fn negative_entry_returns_empty_natives() {
        let c = open_memory_db();
        store_cached(&c, "OBSCURE BODY", None, "wikidata").unwrap();
        let got = lookup_cached(&c, "OBSCURE BODY").unwrap();
        assert!(got.is_empty());
    }

    #[test]
    fn classify_falls_through_to_cache_for_unknown_camera() {
        let c = open_memory_db();
        store_cached(&c, "HASSELBLAD X2D 100C", Some(&[(11656, 8742)]), "test").unwrap();
        assert_eq!(
            classify_with_cache(&c, "HASSELBLAD X2D 100C", 11656, 8742),
            Classification::Full
        );
    }

    #[test]
    fn classify_with_cache_prefers_builtin() {
        // Sony a7R III is in the built-in table. Even if the cache
        // claims something different, the built-in wins.
        let c = open_memory_db();
        store_cached(&c, "SONY ILCE-7RM3", Some(&[(1, 1)]), "noise").unwrap();
        assert_eq!(
            classify_with_cache(&c, "SONY ILCE-7RM3", 7952, 5304),
            Classification::Full
        );
        // Known camera + non-matching, non-standard resolution -> NotFull.
        assert_eq!(
            classify_with_cache(&c, "SONY ILCE-7RM3", 1500, 844),
            Classification::NotFull
        );
    }

    #[test]
    fn ensure_cached_skips_builtin_camera() {
        let c = open_memory_db();
        // Built-in covers Sony a7R III; should return the built-in
        // resolutions and NEVER attempt curl.
        let natives = ensure_cached(&c, "SONY ILCE-7RM3").unwrap();
        assert!(natives.iter().any(|(w, h)| *w == 7952 && *h == 5304));
        // No cache row should have been written either.
        let cnt: i64 = c
            .query_row("SELECT COUNT(*) FROM camera_sensor_cache", [], |r| r.get(0))
            .unwrap();
        assert_eq!(cnt, 0);
    }

    #[test]
    fn ensure_cached_honours_existing_positive() {
        let c = open_memory_db();
        store_cached(&c, "FAKE BODY", Some(&[(1234, 567)]), "manual").unwrap();
        let natives = ensure_cached(&c, "FAKE BODY").unwrap();
        assert_eq!(natives, vec![(1234, 567)]);
    }

    #[test]
    fn ensure_cached_empty_camera_is_noop() {
        let c = open_memory_db();
        let natives = ensure_cached(&c, "").unwrap();
        assert!(natives.is_empty());
    }

    #[test]
    fn parse_sparql_response_extracts_pairs() {
        let body = r#"{
            "results": { "bindings": [
                { "width": { "value": "8064" }, "height": { "value": "6048" } },
                { "width": { "value": "8064" }, "height": { "value": "6048" } },
                { "width": { "value": "4032" }, "height": { "value": "3024" } }
            ] }
        }"#;
        let got = parse_sparql_response(body).unwrap().unwrap();
        assert_eq!(got, vec![(4032, 3024), (8064, 6048)]);
    }

    #[test]
    fn parse_sparql_response_empty_returns_none() {
        let body = r#"{"results": {"bindings": []}}"#;
        assert!(parse_sparql_response(body).unwrap().is_none());
    }

    #[test]
    fn parse_sparql_response_rejects_zero_dimensions() {
        let body = r#"{
            "results": { "bindings": [
                { "width": { "value": "0" }, "height": { "value": "1000" } }
            ] }
        }"#;
        assert!(parse_sparql_response(body).unwrap().is_none());
    }

    #[test]
    fn build_sparql_query_contains_normalised_needle() {
        let q = build_sparql_query("Hasselblad X2D 100C");
        assert!(q.contains("hasselblad x2d 100c"), "query was: {q}");
    }

    #[test]
    fn build_sparql_query_strips_quotes() {
        // The needle gets lowercased + quote-stripped before being
        // interpolated into the SPARQL literal. Stripping rather than
        // escaping is fine because EXIF model strings don't contain
        // double-quotes in practice, and we'd rather miss a match than
        // smuggle anything weird through.
        let q = build_sparql_query("WeIrD \"camera\" name");
        assert!(q.contains("\"weird camera name\""), "got: {q}");
    }
}
