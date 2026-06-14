// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! HTTPS media server for remote clients (iOS).
//!
//! Runs on the same self-signed identity certificate as the LAN gRPC bind, so
//! one fingerprint pins both. Endpoints:
//!
//! - `GET /healthz` — liveness.
//! - `GET /fingerprint` — the cert SHA-256, unauthenticated, for port-scan TOFU
//!   clients that discovered us without mDNS.
//! - `GET /video/{id}` — the video file, range-aware (206 / `Accept-Ranges`).
//! - `GET /video/{id}?height=H` — an on-the-fly downscaled rendition (A4).
//! - `POST /pair/start`, `POST /pair` — one-time device pairing (A5).
//! - `POST /upload?filename=` — authenticated upload into the import dir (A6).
//!
//! Range serving lets AVPlayer seek/scrub native-codec content; everything
//! except `/healthz` and `/fingerprint` requires a paired bearer token on the
//! LAN bind (loopback is exempt).

use anyhow::{Context, Result};
use axum::{
    body::Body,
    extract::{DefaultBodyLimit, Path as AxPath, Query as AxQuery, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use axum_server::tls_rustls::RustlsConfig;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Instant;
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::sync::Mutex;
use tokio_stream::StreamExt;
use tokio_util::io::ReaderStream;

use crate::db::Database;

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
    /// Where uploaded videos are stored (None = uploads refused).
    pub import_dir: Option<PathBuf>,
    /// The current pending pairing code — shared with the gRPC service so a code
    /// minted by a desktop client (StartPairing) is redeemable here.
    pairing: crate::pairing::PairingState,
}

impl MediaState {
    pub fn new(
        db: Arc<Database>,
        fingerprint_hex: String,
        cache_dir: PathBuf,
        data_dir: PathBuf,
        import_dir: Option<PathBuf>,
        pairing: crate::pairing::PairingState,
    ) -> Self {
        Self {
            db,
            fingerprint_hex,
            cache_dir,
            transcode_locks: Arc::new(Mutex::new(HashMap::new())),
            data_dir,
            import_dir,
            pairing,
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
        .route("/upload", post(upload).layer(DefaultBodyLimit::disable()))
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

/// Begin pairing from the device side: mint a 6-digit code (5-min TTL) and
/// surface it (daemon log + `<data_dir>/pairing.txt`). A desktop client can also
/// mint one via the `StartPairing` gRPC RPC — both share the same pending cell.
async fn pair_start(State(state): State<MediaState>) -> StatusCode {
    crate::pairing::issue_code(&state.pairing, &state.data_dir).await;
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

#[derive(serde::Deserialize)]
struct UploadQuery {
    filename: String,
}

/// Upload a full-resolution video (streamed) into the configured import dir,
/// then index it. Single-shot for now; resumable chunking is a follow-up.
async fn upload(
    AxQuery(q): AxQuery<UploadQuery>,
    State(state): State<MediaState>,
    headers: HeaderMap,
    body: Body,
) -> Response {
    let authz = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok());
    if !crate::auth::is_authorized(&state.db, authz) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let import_dir = match &state.import_dir {
        Some(d) => d.clone(),
        None => {
            return (StatusCode::CONFLICT, "import directory not configured on the server")
                .into_response()
        }
    };
    // Sanitize to a bare filename (defeats `..`/path-separator traversal).
    let fname = match Path::new(&q.filename).file_name().and_then(|s| s.to_str()) {
        Some(f) if !f.is_empty() => f.to_string(),
        _ => return (StatusCode::BAD_REQUEST, "invalid filename").into_response(),
    };

    let uploads = import_dir.join(".uploads");
    if let Err(e) = std::fs::create_dir_all(&uploads) {
        tracing::warn!("upload: mkdir failed: {e}");
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    let tmp = uploads.join(format!("{}.part", uuid::Uuid::new_v4().simple()));

    // Stream the request body to the temp file.
    let mut file = match tokio::fs::File::create(&tmp).await {
        Ok(f) => f,
        Err(e) => {
            tracing::warn!("upload: create tmp failed: {e}");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let mut stream = body.into_data_stream();
    while let Some(chunk) = stream.next().await {
        match chunk {
            Ok(bytes) => {
                if let Err(e) = file.write_all(&bytes).await {
                    tracing::warn!("upload: write failed: {e}");
                    let _ = tokio::fs::remove_file(&tmp).await;
                    return StatusCode::INTERNAL_SERVER_ERROR.into_response();
                }
            }
            Err(e) => {
                tracing::warn!("upload: body stream error: {e}");
                let _ = tokio::fs::remove_file(&tmp).await;
                return StatusCode::BAD_REQUEST.into_response();
            }
        }
    }
    let _ = file.flush().await;
    drop(file);

    // Atomic move into the import dir, de-duping filename collisions.
    let final_path = dedup_path(&import_dir, &fname);
    if let Err(e) = std::fs::rename(&tmp, &final_path) {
        tracing::warn!("upload: rename failed: {e}");
        let _ = std::fs::remove_file(&tmp);
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }

    // Index the new file (ffprobe + thumbnails) off the async runtime.
    let db = Arc::clone(&state.db);
    let cache = state.cache_dir.clone();
    let path = final_path.clone();
    let indexed = tokio::task::spawn_blocking(move || {
        crate::indexing::IndexingEngine::scan_single_file(&db, &path, &cache, None)
    })
    .await;
    match indexed {
        Ok(Ok((id, _))) => {
            tracing::info!("Uploaded + indexed {}", final_path.display());
            Json(serde_json::json!({ "video_id": id, "filename": fname })).into_response()
        }
        other => {
            // The file landed; report success so the client doesn't re-upload.
            tracing::warn!("upload: indexing {} failed: {other:?}", final_path.display());
            Json(serde_json::json!({ "video_id": serde_json::Value::Null, "filename": fname }))
                .into_response()
        }
    }
}

/// Pick a non-colliding path in `dir` for `fname`, appending " (2)", " (3)", ….
fn dedup_path(dir: &Path, fname: &str) -> PathBuf {
    let p = dir.join(fname);
    if !p.exists() {
        return p;
    }
    let stem = Path::new(fname).file_stem().and_then(|s| s.to_str()).unwrap_or("file");
    let ext = Path::new(fname).extension().and_then(|s| s.to_str());
    for i in 2..1000 {
        let cand = match ext {
            Some(e) => dir.join(format!("{stem} ({i}).{e}")),
            None => dir.join(format!("{stem} ({i})")),
        };
        if !cand.exists() {
            return cand;
        }
    }
    dir.join(format!("{stem}-{}", uuid::Uuid::new_v4().simple()))
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
