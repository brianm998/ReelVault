// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! HTTPS media server for remote clients (iOS).
//!
//! Runs on the same self-signed identity certificate as the LAN gRPC bind, so
//! one fingerprint pins both. Endpoints:
//!   - `GET /healthz`          liveness
//!   - `GET /fingerprint`      the cert SHA-256 (unauthenticated, for port-scan
//!                             TOFU clients that discovered us without mDNS)
//!   - `GET /video/{id}`       the video file, range-aware (206 / Accept-Ranges)
//!
//! `?height=` downscaling and HLS land in A4; this step serves the stored file
//! (original or a proxy) with byte-range support so AVPlayer can seek/scrub
//! native-codec content today.

use anyhow::{Context, Result};
use axum::{
    body::Body,
    extract::{Path as AxPath, Query as AxQuery, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use axum_server::tls_rustls::RustlsConfig;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio::sync::Mutex;
use tokio_util::io::ReaderStream;

use crate::db::Database;

/// A pending one-time pairing code (set by /pair/start, consumed by /pair).
struct PendingPairing {
    code: String,
    expires: Instant,
}

/// Shared state for the media handlers.
#[derive(Clone)]
pub struct MediaState {
    pub db: Arc<Database>,
    pub fingerprint_hex: String,
    /// Directory for cached downscaled renditions (a subdir of the thumb cache).
    pub cache_dir: PathBuf,
    /// Per-`{id}_{height}` locks so concurrent identical requests share one
    /// transcode instead of each spawning their own ffmpeg.
    pub transcode_locks: Arc<Mutex<HashMap<String, Arc<Mutex<()>>>>>,
    /// Per-OS data dir; the pending pairing code is written here for headless admins.
    pub data_dir: PathBuf,
    /// The current pending pairing code.
    pairing: Arc<Mutex<Option<PendingPairing>>>,
}

impl MediaState {
    pub fn new(
        db: Arc<Database>,
        fingerprint_hex: String,
        cache_dir: PathBuf,
        data_dir: PathBuf,
    ) -> Self {
        Self {
            db,
            fingerprint_hex,
            cache_dir,
            transcode_locks: Arc::new(Mutex::new(HashMap::new())),
            data_dir,
            pairing: Arc::new(Mutex::new(None)),
        }
    }
}

#[derive(serde::Deserialize)]
struct VideoQuery {
    /// Target max height; 0/absent means "serve the stored file as-is".
    height: Option<i32>,
}

/// Serve the media endpoints on `addr` over TLS using the given PEM cert+key.
/// Runs until the process exits.
pub async fn serve(
    addr: SocketAddr,
    cert_pem: String,
    key_pem: String,
    state: MediaState,
) -> Result<()> {
    let app = Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .route("/fingerprint", get(fingerprint))
        .route("/pair/start", post(pair_start))
        .route("/pair", post(pair))
        .route("/video/:id", get(video))
        .with_state(state);

    let config = RustlsConfig::from_pem(cert_pem.into_bytes(), key_pem.into_bytes())
        .await
        .context("load media-server TLS cert")?;

    axum_server::bind_rustls(addr, config)
        .serve(app.into_make_service())
        .await
        .context("media server failed")?;
    Ok(())
}

async fn fingerprint(State(state): State<MediaState>) -> String {
    state.fingerprint_hex.clone()
}

/// Begin pairing: generate a 6-digit code (5-min TTL) and surface it to the
/// admin (daemon log + `<data_dir>/pairing.txt`) for entry on the new device.
async fn pair_start(State(state): State<MediaState>) -> StatusCode {
    let code = gen_code();
    {
        let mut p = state.pairing.lock().await;
        *p = Some(PendingPairing {
            code: code.clone(),
            expires: Instant::now() + Duration::from_secs(300),
        });
    }
    tracing::info!("PAIRING CODE: {code} — enter on the device within 5 minutes");
    let _ = std::fs::write(state.data_dir.join("pairing.txt"), format!("{code}\n"));
    StatusCode::OK
}

#[derive(serde::Deserialize)]
struct PairRequest {
    pin: String,
    device_name: Option<String>,
}

#[derive(serde::Serialize)]
struct PairResponse {
    token: String,
}

/// Complete pairing: validate the PIN and, on success, mint a long-lived bearer
/// token (only its hash is stored) for the device to use on all future calls.
async fn pair(State(state): State<MediaState>, Json(req): Json<PairRequest>) -> Response {
    let valid = {
        let mut p = state.pairing.lock().await;
        match p.as_ref() {
            Some(pp) if pp.code == req.pin.trim() && pp.expires > Instant::now() => {
                *p = None; // single-use
                true
            }
            _ => false,
        }
    };
    if !valid {
        return (StatusCode::UNAUTHORIZED, "invalid or expired pairing code").into_response();
    }
    let token = format!(
        "{}{}",
        uuid::Uuid::new_v4().simple(),
        uuid::Uuid::new_v4().simple()
    );
    let name = req.device_name.unwrap_or_else(|| "device".to_string());
    if let Err(e) = state.db.add_paired_device(&crate::auth::token_hash(&token), &name) {
        tracing::warn!("pair: add_paired_device failed: {e}");
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    let _ = std::fs::remove_file(state.data_dir.join("pairing.txt"));
    tracing::info!("Paired new device: {name}");
    Json(PairResponse { token }).into_response()
}

/// A 6-digit pairing code derived from random UUID bytes (no extra RNG dep).
fn gen_code() -> String {
    let b = uuid::Uuid::new_v4().into_bytes();
    let n = u32::from_le_bytes([b[0], b[1], b[2], b[3]]) % 1_000_000;
    format!("{n:06}")
}

async fn video(
    AxPath(id): AxPath<String>,
    AxQuery(q): AxQuery<VideoQuery>,
    State(state): State<MediaState>,
    headers: HeaderMap,
) -> Response {
    let authz = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok());
    if !crate::auth::is_authorized(&state.db, authz) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let record = match state.db.get_video(&id) {
        Ok(Some(v)) => v,
        Ok(None) => return StatusCode::NOT_FOUND.into_response(),
        Err(e) => {
            tracing::warn!("media: get_video({id}) failed: {e}");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let height = q.height.unwrap_or(0);
    if height > 0 {
        match ensure_downscaled(&state, &id, &record.path, height).await {
            Some(path) => serve_file_range(&path.to_string_lossy(), &headers).await,
            None => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        }
    } else {
        serve_file_range(&record.path, &headers).await
    }
}

/// Transcode `src` to a cached downscaled MP4 capped at `height` px (never
/// upscaled), returning its path. Concurrent identical requests share one ffmpeg
/// via a per-key lock; the result is cached under `<cache>/stream/` for reuse.
async fn ensure_downscaled(state: &MediaState, id: &str, src: &str, height: i32) -> Option<PathBuf> {
    let dir = state.cache_dir.join("stream");
    let out = dir.join(format!("{id}_{height}.mp4"));
    if out.exists() {
        return Some(out);
    }
    // Serialize concurrent identical requests behind one transcode.
    let key = format!("{id}_{height}");
    let lock = {
        let mut map = state.transcode_locks.lock().await;
        map.entry(key)
            .or_insert_with(|| Arc::new(Mutex::new(())))
            .clone()
    };
    let _guard = lock.lock().await;
    if out.exists() {
        return Some(out); // produced while we waited on the lock
    }
    if let Err(e) = std::fs::create_dir_all(&dir) {
        tracing::warn!("media: create cache dir failed: {e}");
        return None;
    }

    let out_tmp = dir.join(format!(".{id}_{height}.tmp.mp4"));
    let out_tmp_for_job = out_tmp.clone();
    let src = src.to_string();
    let res = tokio::task::spawn_blocking(move || {
        // Same global semaphore that bounds all other ffmpeg work.
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        let vf = format!("scale=-2:min({height}\\,ih)");
        let out_str = out_tmp_for_job.to_string_lossy().to_string();
        crate::ffmpeg::ffmpeg_command()
            .args([
                "-y", "-i", src.as_str(),
                "-vf", vf.as_str(),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart",
                out_str.as_str(),
            ])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
    })
    .await;

    match res {
        Ok(Ok(status)) if status.success() => match std::fs::rename(&out_tmp, &out) {
            Ok(()) => Some(out),
            Err(e) => {
                tracing::warn!("media: rename transcode output failed: {e}");
                let _ = std::fs::remove_file(&out_tmp);
                None
            }
        },
        other => {
            tracing::warn!("media: transcode failed for {id} @ {height}p: {other:?}");
            let _ = std::fs::remove_file(&out_tmp);
            None
        }
    }
}

/// Serve `path` with single-range support (the form AVPlayer issues).
async fn serve_file_range(path: &str, headers: &HeaderMap) -> Response {
    let file = match tokio::fs::File::open(path).await {
        Ok(f) => f,
        Err(_) => return StatusCode::NOT_FOUND.into_response(),
    };
    let total = match file.metadata().await {
        Ok(m) => m.len(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let ctype = content_type_for(path);

    if let Some((start, end)) = parse_range(headers, total) {
        let mut f = file;
        if f.seek(std::io::SeekFrom::Start(start)).await.is_err() {
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
        let len = end - start + 1;
        let body = Body::from_stream(ReaderStream::new(f.take(len)));
        return Response::builder()
            .status(StatusCode::PARTIAL_CONTENT)
            .header(header::CONTENT_TYPE, ctype)
            .header(header::ACCEPT_RANGES, "bytes")
            .header(header::CONTENT_RANGE, format!("bytes {start}-{end}/{total}"))
            .header(header::CONTENT_LENGTH, len.to_string())
            .body(body)
            .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response());
    }

    let body = Body::from_stream(ReaderStream::new(file));
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, ctype)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CONTENT_LENGTH, total.to_string())
        .body(body)
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

/// Parse a single `Range: bytes=start-end` (or `bytes=start-` / `bytes=-N`)
/// into inclusive byte offsets clamped to the file size. None => serve whole.
fn parse_range(headers: &HeaderMap, total: u64) -> Option<(u64, u64)> {
    if total == 0 {
        return None;
    }
    let raw = headers.get(header::RANGE)?.to_str().ok()?;
    let spec = raw.strip_prefix("bytes=")?;
    let first = spec.split(',').next()?.trim();
    let (s, e) = first.split_once('-')?;
    let last = total - 1;
    let (start, end) = if s.is_empty() {
        // suffix range: last N bytes
        let n: u64 = e.parse().ok()?;
        let n = n.min(total);
        (total - n, last)
    } else {
        let start: u64 = s.parse().ok()?;
        let end: u64 = if e.is_empty() { last } else { e.parse::<u64>().ok()?.min(last) };
        (start, end)
    };
    if start > end || start > last {
        return None;
    }
    Some((start, end))
}

fn content_type_for(path: &str) -> &'static str {
    let lower = path.to_ascii_lowercase();
    if lower.ends_with(".mp4") || lower.ends_with(".m4v") {
        "video/mp4"
    } else if lower.ends_with(".mov") {
        "video/quicktime"
    } else if lower.ends_with(".m3u8") {
        "application/vnd.apple.mpegurl"
    } else if lower.ends_with(".ts") {
        "video/mp2t"
    } else {
        "application/octet-stream"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::HeaderValue;

    fn range_header(v: &'static str) -> HeaderMap {
        let mut h = HeaderMap::new();
        h.insert(header::RANGE, HeaderValue::from_static(v));
        h
    }

    #[test]
    fn parse_range_basic() {
        assert_eq!(parse_range(&range_header("bytes=0-99"), 1000), Some((0, 99)));
    }

    #[test]
    fn parse_range_open_ended() {
        assert_eq!(parse_range(&range_header("bytes=500-"), 1000), Some((500, 999)));
    }

    #[test]
    fn parse_range_suffix() {
        assert_eq!(parse_range(&range_header("bytes=-100"), 1000), Some((900, 999)));
    }

    #[test]
    fn parse_range_clamped_to_size() {
        assert_eq!(parse_range(&range_header("bytes=0-99999"), 1000), Some((0, 999)));
    }

    #[test]
    fn parse_range_none_without_header() {
        assert_eq!(parse_range(&HeaderMap::new(), 1000), None);
    }

    #[tokio::test]
    async fn serve_file_range_returns_206() {
        let dir = std::env::temp_dir().join(format!("rv-media-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("clip.mp4");
        std::fs::write(&path, vec![7u8; 1000]).unwrap();

        let resp = serve_file_range(path.to_str().unwrap(), &range_header("bytes=10-19")).await;
        assert_eq!(resp.status(), StatusCode::PARTIAL_CONTENT);
        assert_eq!(
            resp.headers().get(header::CONTENT_RANGE).unwrap(),
            "bytes 10-19/1000"
        );
        assert_eq!(resp.headers().get(header::CONTENT_LENGTH).unwrap(), "10");

        let full = serve_file_range(path.to_str().unwrap(), &HeaderMap::new()).await;
        assert_eq!(full.status(), StatusCode::OK);
        assert_eq!(full.headers().get(header::CONTENT_LENGTH).unwrap(), "1000");

        let _ = std::fs::remove_dir_all(&dir);
    }
}
