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
    extract::{Path as AxPath, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Router,
};
use axum_server::tls_rustls::RustlsConfig;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio_util::io::ReaderStream;

use crate::db::Database;

/// Shared state for the media handlers.
#[derive(Clone)]
pub struct MediaState {
    pub db: Arc<Database>,
    pub fingerprint_hex: String,
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

async fn video(
    AxPath(id): AxPath<String>,
    State(state): State<MediaState>,
    headers: HeaderMap,
) -> Response {
    let record = match state.db.get_video(&id) {
        Ok(Some(v)) => v,
        Ok(None) => return StatusCode::NOT_FOUND.into_response(),
        Err(e) => {
            tracing::warn!("media: get_video({id}) failed: {e}");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    serve_file_range(&record.path, &headers).await
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
