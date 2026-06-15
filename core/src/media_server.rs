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
//! - `GET /hls/{id}/{height}/{file}` — on-the-fly HLS stream that begins playing
//!   before the whole file finishes transcoding; `file` is the media playlist
//!   `index.m3u8` or a segment `seg_NNNNN.ts`. Height lives in the path (not a
//!   query) so the playlist's relative segment URIs carry it (A4).
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
    /// Promoted HLS proxies are stored under `<data_dir>/generated-proxies/`.
    pub data_dir: PathBuf,
    /// Where uploaded videos are stored (None = uploads refused).
    pub import_dir: Option<PathBuf>,
    /// The configured proxy height (`config.proxy_target_height`). A live HLS
    /// re-encode at exactly this height is promoted to a durable proxy so it is
    /// only encoded once (subsequent plays copy-mux it) and shows in the inspector.
    pub proxy_target_height: i32,
    /// The catalog-change bus; a promoted proxy publishes `VideoAdded` so clients
    /// refresh the source card's proxy badge / inspector list live.
    pub events: tokio::sync::broadcast::Sender<crate::watcher::CatalogChange>,
    /// The current pending pairing code — shared with the gRPC service so a code
    /// minted by a desktop client (StartPairing) is redeemable here.
    pairing: crate::pairing::PairingState,
}

impl MediaState {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        db: Arc<Database>,
        fingerprint_hex: String,
        cache_dir: PathBuf,
        data_dir: PathBuf,
        import_dir: Option<PathBuf>,
        proxy_target_height: i32,
        events: tokio::sync::broadcast::Sender<crate::watcher::CatalogChange>,
        pairing: crate::pairing::PairingState,
    ) -> Self {
        Self {
            db,
            fingerprint_hex,
            cache_dir,
            transcode_locks: Arc::new(Mutex::new(HashMap::new())),
            data_dir,
            import_dir,
            proxy_target_height,
            events,
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
        .route("/hls/:id/:height/*file", get(hls_file))
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
    let entered = req.pin.trim();
    tracing::info!(
        "pair: attempt from {:?} ({} digit code)",
        req.device_name.as_deref().unwrap_or("device"),
        entered.len()
    );
    let valid = {
        let mut p = state.pairing.lock().await;
        match p.as_ref() {
            Some(pp) if pp.code == entered && pp.expires > Instant::now() => {
                *p = None; // single-use
                true
            }
            // Distinguish the failure modes so a bad pairing is diagnosable.
            Some(pp) if pp.expires <= Instant::now() => {
                tracing::warn!("pair: rejected — code expired (regenerate it on the desktop)");
                false
            }
            Some(_) => {
                tracing::warn!("pair: rejected — code mismatch");
                false
            }
            None => {
                tracing::warn!(
                    "pair: rejected — no pending code (it's single-use; \
                     already consumed by another device, or never generated). \
                     Generate a fresh code per device."
                );
                false
            }
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
    // "Original" (height <= 0): serve the file as-is ONLY when the client can
    // decode it. A mastering codec (ProRes, DNxHD, …) served raw plays as audio
    // with no video on iOS, so transcode it to full-res H.264 (ensure_downscaled
    // treats height 0 as "no downscale"). A positive height always transcodes /
    // serves the closest proxy.
    let needs_transcode = height > 0
        || !original_codec(&state, &id).map(|c| is_streamable_codec(&c)).unwrap_or(false);
    if needs_transcode {
        match ensure_downscaled(&state, &id, &record.path, height).await {
            Some(path) => serve_file_range(&path.to_string_lossy(), &headers).await,
            None => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        }
    } else {
        serve_file_range(&record.path, &headers).await
    }
}

/// The original's stored video codec (from `metadata.codec_video`), used to
/// decide whether "Original" can be served raw or must be transcoded for the
/// client. `None` when unknown — treated as not-streamable (transcode), so we
/// never hand the player a file it can't decode.
fn original_codec(state: &MediaState, id: &str) -> Option<String> {
    let conn = state.db.get_connection().ok()?;
    conn.query_row(
        "SELECT codec_video FROM metadata WHERE video_id = ?",
        [id],
        |row| row.get::<_, Option<String>>(0),
    )
    .ok()
    .flatten()
}

/// `GET /hls/{id}/{height}/{file}` — serve one file of an on-the-fly HLS stream.
///
/// `file` is `master.m3u8`, `index.m3u8`, or `seg_NNNNN.ts`. The first request
/// for an `(id, height)` starts a detached ffmpeg that progressively writes a
/// growing EVENT playlist into `<cache>/hls/{id}_{height}/`; we block that first
/// request only until `master.m3u8` exists (AVPlayer treats a 404 on the master
/// as fatal), then serve files straight off disk via [`serve_file_range`], which
/// already maps the `.m3u8`/`.ts` content types and is range-aware.
async fn hls_file(
    AxPath((id, height, file)): AxPath<(String, i32, String)>,
    State(state): State<MediaState>,
    headers: HeaderMap,
) -> Response {
    let authz = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok());
    if !crate::auth::is_authorized(&state.db, authz) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let height = height.clamp(144, 2160);
    // Progress for the client's readiness gate. This also *starts* the session,
    // so the client can drive everything from status polling and only build the
    // player once enough is buffered.
    if file == "status" {
        return hls_status(&state, &id, height).await;
    }
    if !is_allowed_hls_file(&file) {
        tracing::warn!("hls: rejected filename {file:?} ({id})");
        return StatusCode::NOT_FOUND.into_response();
    }
    let dir = match ensure_hls_session(&state, &id, height).await {
        Some(d) => d,
        None => {
            tracing::warn!("hls: {id}@{height}p {file} -> 500 (no session: transcode failed or timed out)");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let path = dir.join(&file);
    if !path.exists() {
        // The two common cases: a forward-seek past the live transcode head, or a
        // segment that an LRU sweep removed mid-playback.
        tracing::warn!("hls: {id}@{height}p {file} -> 404 (not on disk: ahead of live head, or evicted)");
        return StatusCode::NOT_FOUND.into_response();
    }
    tracing::debug!("hls: {id}@{height}p serving {file}");
    serve_file_range(&path.to_string_lossy(), &headers).await
}

/// `GET /hls/{id}/{height}/status` — JSON progress for the client's readiness
/// gate: how many segments are transcoded, whether the session is complete, and
/// whether the transcode failed. Also starts/joins the session so polling alone
/// drives the transcode. The client knows the video duration, so it can estimate
/// the encode rate (by polling) and decide whether to start now or show an ETA.
async fn hls_status(state: &MediaState, id: &str, height: i32) -> Response {
    // Start (or join) the session; ignore the result — `.failed`/segment counts
    // below report the real state (a slow first segment returns None on timeout
    // but is NOT a failure; the detached transcode keeps going).
    let _ = ensure_hls_session(state, id, height).await;
    let dir = state.cache_dir.join("hls").join(format!("{id}_{height}"));
    let segments = count_ts(&dir);
    let complete = dir.join(".complete").exists();
    let failed = dir.join(".failed").exists();
    let body =
        format!("{{\"segments\":{segments},\"complete\":{complete},\"failed\":{failed},\"segSeconds\":4}}");
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(body))
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

/// Strict allowlist for the HLS `file` segment — defeats path traversal and
/// keeps a half-written `seg_NNNNN.ts.tmp` (ffmpeg's `temp_file`) from ever
/// being served.
fn is_allowed_hls_file(file: &str) -> bool {
    if file == "master.m3u8" || file == "index.m3u8" {
        return true;
    }
    match file.strip_prefix("seg_").and_then(|s| s.strip_suffix(".ts")) {
        Some(mid) => mid.len() == 5 && mid.bytes().all(|b| b.is_ascii_digit()),
        None => false,
    }
}

/// Ensure an HLS session dir for `(id, height)` exists and return it, blocking
/// the *first* request only until the media playlist `index.m3u8` appears (or
/// the transcode fails / times out). We gate on `index.m3u8` (which always has
/// a segment when it exists, and is what the client plays) rather than the
/// `master.m3u8`, which ffmpeg can publish variant-less mid-transcode → an empty
/// 25-byte master that AVPlayer dead-ends on. Mirrors [`ensure_downscaled`] —
/// dedup via `transcode_locks`, ffmpeg under a permit — but returns *before*
/// ffmpeg finishes so the playlist can grow while playback proceeds.
async fn ensure_hls_session(state: &MediaState, id: &str, height: i32) -> Option<PathBuf> {
    let dir = state.cache_dir.join("hls").join(format!("{id}_{height}"));
    let index = dir.join("index.m3u8");
    let complete = dir.join(".complete");
    if complete.exists() && index.exists() {
        return Some(dir);
    }

    // Serialize the start-vs-join decision (NOT the whole transcode). A separate
    // key namespace from the one-shot MP4 path so the two never collide.
    let key = format!("{id}_hls_{height}");
    let lock = {
        let mut map = state.transcode_locks.lock().await;
        map.entry(key)
            .or_insert_with(|| Arc::new(Mutex::new(())))
            .clone()
    };
    let guard = lock.lock().await;
    let failed = dir.join(".failed");
    if complete.exists() && index.exists() {
        drop(guard);
        return Some(dir);
    }
    // Join an in-progress session rather than clobbering it. A dir that exists
    // (or already has a playlist), isn't marked failed, and is making progress
    // (recent mtime) means another request's ffmpeg is running — *including* the
    // few-second window before master.m3u8 first appears, when a second request
    // would otherwise delete the directory out from under the live transcode.
    // Only (re)start when there's no session, a failed one, or a dead/stale one
    // (e.g. the daemon was SIGKILLed mid-transcode).
    let running = (index.exists() || dir.exists()) && !failed.exists() && !dir_is_stale(&dir);
    if !running {
        if dir.exists() {
            let _ = std::fs::remove_dir_all(&dir);
        }
        if let Err(e) = std::fs::create_dir_all(&dir) {
            tracing::warn!("media: hls create dir failed: {e}");
            drop(guard);
            return None;
        }
        // Cheapest source: a streamable proxy already <= the target height is
        // copy-muxed (near-instant, complete VOD); a taller proxy or the original
        // is re-encoded (downscaled) progressively.
        let (src, copy) = match closest_streamable_proxy(state, id, height) {
            // Any streamable (H.264/HEVC) proxy is copy-muxed — instant, complete,
            // no stalls — even if it's taller than the target: a downscaled proxy
            // streams fine over the LAN and beats a slow live re-encode. (Picking
            // the closest one keeps it as small as the catalog allows.)
            Some((path, _)) => (path, true),
            None => {
                // No H.264/HEVC proxy to copy-mux. HLS/MPEG-TS can't carry ProRes,
                // so we must re-encode to H.264 — but re-encode from the smallest
                // proxy that still covers the target (e.g. a 2160p ProRes proxy),
                // NOT the multi-GB 8K original: decoding a proxy is far cheaper, so
                // the live re-encode keeps up and playback doesn't stall.
                match closest_proxy_source(state, id, height) {
                    Some(p) => {
                        tracing::info!("hls: {id}@{height}p no H.264/HEVC proxy — re-encoding from proxy {p}");
                        (p, false)
                    }
                    None => match state.db.get_video(id) {
                        Ok(Some(v)) => {
                            tracing::info!("hls: {id}@{height}p no proxy — re-encoding original");
                            (v.path, false)
                        }
                        _ => {
                            drop(guard);
                            return None;
                        }
                    },
                }
            }
        };
        evict_old_hls_sessions(&state.cache_dir);
        tracing::info!(
            "hls: start {id}@{height}p — {} from {}",
            if copy { "copy-mux" } else { "re-encode" }, src
        );
        spawn_hls_transcode(state.clone(), id.to_string(), dir.clone(), src, height, copy);
    } else {
        // Debug, not info: status polling joins every second and would spam.
        tracing::debug!("hls: join in-progress session {id}@{height}p");
    }
    drop(guard); // release the start/join lock; do NOT hold it during transcode.

    // Wait-for-first-segment: AVPlayer fails the asset on a 404 playlist, so block
    // until index.m3u8 exists, the transcode marks failure, or we time out.
    let started = Instant::now();
    for i in 0..200 {
        if index.exists() {
            tracing::info!("hls: {id}@{height}p playlist ready in {}ms", started.elapsed().as_millis());
            return Some(dir);
        }
        if failed.exists() {
            tracing::warn!("hls: {id}@{height}p transcode failed before first segment");
            return None;
        }
        // Progress every ~5s so a slow-but-working transcode is distinguishable
        // from a stuck one (the common 8K case: re-encode is just slow).
        if i > 0 && i % 50 == 0 {
            tracing::info!("hls: {id}@{height}p still preparing — {}s, {} segment(s)", i / 10, count_ts(&dir));
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    tracing::warn!(
        "hls: {id}@{height}p no playlist within 20s ({} segment(s) so far — slow transcode of a large source; the detached job keeps running, so a retry will join it)",
        count_ts(&dir)
    );
    None
}

/// Count finished `.ts` segments in a session dir (for progress logging).
fn count_ts(dir: &Path) -> usize {
    std::fs::read_dir(dir)
        .map(|rd| {
            rd.flatten()
                .filter(|e| {
                    e.file_name()
                        .to_str()
                        .map(|n| n.starts_with("seg_") && n.ends_with(".ts"))
                        .unwrap_or(false)
                })
                .count()
        })
        .unwrap_or(0)
}

/// Spawn a detached ffmpeg writing a growing HLS session into `dir`. Holds one
/// ffmpeg permit for the *whole* transcode (a streaming transcode is long-lived,
/// unlike the one-shot MP4 cache). Writes `.complete` on success or `.failed` on
/// error — both consulted by [`ensure_hls_session`].
fn spawn_hls_transcode(state: MediaState, id: String, dir: PathBuf, src: String, height: i32, copy: bool) {
    tokio::task::spawn_blocking(move || {
        // Scope the ffmpeg permit to the streaming transcode only. Promotion
        // (below) acquires its own permit for the remux, so the permit must be
        // released first or, with max_concurrent_ffmpeg == 1, promotion would
        // deadlock waiting on a slot this closure still holds.
        let ok = run_hls_transcode(&dir, &src, height, copy);

        // Promote a real re-encode to a durable catalog proxy: encode once,
        // copy-mux forever, and surface it in the inspector. A copy-mux (`copy`)
        // means a streamable proxy already exists, so there's nothing to persist.
        //
        // Promote when the stream is the configured proxy height OR the source
        // has no durable proxy yet. The height-equality gate alone almost never
        // matched a device-driven stream height (the client asks for the screen
        // height, not `proxy_target_height`), so proxies were essentially never
        // created — and so never appeared in the clients. The "no proxy yet"
        // clause guarantees a video the user actually plays ends up with exactly
        // one durable proxy (bounded; once linked, future same-height requests
        // copy-mux and a different height won't re-promote since one now exists).
        if ok && !copy {
            let has_proxy = state
                .db
                .list_proxies(&id)
                .map(|p| !p.is_empty())
                .unwrap_or(false);
            if height == state.proxy_target_height || !has_proxy {
                promote_hls_to_proxy(&state, &id, height, &dir);
            }
        }
    });
}

/// Run the streaming ffmpeg into `dir`, writing `.complete`/`.failed`. Returns
/// whether it succeeded. Holds one ffmpeg permit for the whole transcode (a
/// streaming transcode is long-lived, unlike the one-shot MP4 cache).
fn run_hls_transcode(dir: &Path, src: &str, height: i32, copy: bool) -> bool {
    {
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        let index = dir.join("index.m3u8");
        let seg = dir.join("seg_%05d.ts");
        let mut cmd = crate::ffmpeg::ffmpeg_command();
        cmd.arg("-y").arg("-i").arg(src);
        if copy {
            // Streamable proxy already <= target height: copy video, but always
            // re-encode audio to AAC — ProRes/proxy sources often carry PCM,
            // which mpegts can't deliver to AVPlayer.
            cmd.args(["-c:v", "copy", "-c:a", "aac", "-b:a", "128k", "-ac", "2"]);
        } else {
            // height <= 0 = "Original" → no downscale (encode at source res).
            if height > 0 {
                let vf = format!("scale=-2:min({height}\\,ih)");
                cmd.args(["-vf", &vf]);
            }
            cmd.args([
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-profile:v", "high", "-level", "4.1", "-pix_fmt", "yuv420p",
                // Keyframe every 48 frames so -hls_time cuts on GOP boundaries
                // and independent_segments lets AVPlayer start on any segment.
                "-g", "48", "-keyint_min", "48", "-sc_threshold", "0",
                "-c:a", "aac", "-b:a", "128k", "-ac", "2",
            ]);
        }
        cmd.args([
            "-f", "hls",
            "-hls_time", "4",
            // EVENT = append-only playlist with no ENDLIST until done, so the
            // client starts playing the first segments while the rest transcodes.
            "-hls_playlist_type", "event",
            // temp_file = write seg_NNNNN.ts.tmp then rename, so a reader never
            // sees a half-muxed segment (the playlist is renamed atomically too).
            "-hls_flags", "independent_segments+temp_file",
            "-hls_segment_type", "mpegts",
            "-start_number", "0",
        ]);
        cmd.arg("-hls_segment_filename").arg(&seg).arg(&index);
        cmd.stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::piped());
        match cmd.output() {
            Ok(o) if o.status.success() => {
                ensure_endlist(&index);
                let _ = std::fs::write(dir.join(".complete"), b"");
                tracing::info!(
                    "hls: complete {} ({} segments)",
                    dir.file_name().and_then(|n| n.to_str()).unwrap_or("?"),
                    count_ts(dir)
                );
                true
            }
            Ok(o) => {
                let stderr = String::from_utf8_lossy(&o.stderr);
                let mut lines: Vec<&str> = stderr.lines().collect();
                let start = lines.len().saturating_sub(8);
                let tail = lines.split_off(start).join("\n");
                tracing::warn!(
                    "media: hls transcode failed (exit {:?}) — ffmpeg said:\n{tail}",
                    o.status.code()
                );
                let _ = std::fs::write(dir.join(".failed"), b"");
                false
            }
            Err(e) => {
                tracing::warn!("media: hls ffmpeg could not spawn: {e}");
                let _ = std::fs::write(dir.join(".failed"), b"");
                false
            }
        }
    }
}

/// Promote a completed HLS re-encode into a durable catalog proxy.
///
/// The streaming output is a directory of MPEG-TS segments; a catalog proxy is a
/// single file. We **remux** (not re-encode) the finished playlist into one
/// faststart MP4 (`-c copy`, lossless and cheap since the segments are already
/// H.264/AAC), store it under `<data_dir>/generated-proxies/<id>/` — daemon-owned,
/// never beside the user's source files and never under a library location (so a
/// rescan's offline sweep can't touch it) — then index + link it like
/// [`crate::proxies::create_proxy`] does and publish `VideoAdded`.
///
/// Best-effort: any failure just leaves the ephemeral HLS session in place (the
/// stream already played); the next play re-encodes again. Idempotent in
/// practice — once linked, a future request for this height copy-muxes the proxy
/// (`copy == true`) and never re-enters this path.
fn promote_hls_to_proxy(state: &MediaState, id: &str, height: i32, hls_dir: &Path) {
    let source = match state.db.get_video(id) {
        Ok(Some(v)) => v,
        _ => {
            tracing::warn!("hls: promote {id}@{height}p skipped — source video not found");
            return;
        }
    };
    let stem = Path::new(&source.path)
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("video");
    // One subdir per source id keeps filenames from colliding across videos that
    // happen to share a stem.
    let safe_id: String = id
        .chars()
        .map(|c| if std::path::is_separator(c) { '_' } else { c })
        .collect();
    let out_dir = state.data_dir.join("generated-proxies").join(&safe_id);
    if let Err(e) = std::fs::create_dir_all(&out_dir) {
        tracing::warn!("hls: promote {id}@{height}p — mkdir failed: {e}");
        return;
    }
    let out = out_dir.join(format!("{stem}_{height}p.mp4"));

    {
        let _permit = crate::concurrency::acquire_ffmpeg_permit();
        let index = hls_dir.join("index.m3u8");
        // `-c copy` from MPEG-TS to MP4: lossless remux. Modern ffmpeg auto-
        // applies the aac_adtstoasc bitstream filter the MP4 muxer needs.
        let mut cmd = crate::ffmpeg::ffmpeg_command();
        cmd.arg("-y")
            .arg("-i")
            .arg(&index)
            .args(["-c", "copy", "-movflags", "+faststart"])
            .arg(&out)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::piped());
        match cmd.output() {
            Ok(o) if o.status.success() => {}
            Ok(o) => {
                let stderr = String::from_utf8_lossy(&o.stderr);
                let tail: Vec<&str> = stderr.lines().rev().take(8).collect();
                tracing::warn!(
                    "hls: promote {id}@{height}p — remux failed (exit {:?}): {}",
                    o.status.code(),
                    tail.into_iter().rev().collect::<Vec<_>>().join(" | ")
                );
                let _ = std::fs::remove_file(&out);
                return;
            }
            Err(e) => {
                tracing::warn!("hls: promote {id}@{height}p — remux could not spawn: {e}");
                return;
            }
        }
    }

    // Index the new file (gives it a videos row), then link it as a proxy of the
    // source — exactly the create_proxy tail.
    match crate::indexing::IndexingEngine::scan_single_file(&state.db, &out, &state.cache_dir, None) {
        Ok((proxy_id, _)) => {
            if let Err(e) = state.db.set_proxy_of(&proxy_id, id, 1.0, false) {
                tracing::warn!("hls: promote {id}@{height}p — set_proxy_of failed: {e}");
                return;
            }
            let _ = state.events.send(crate::watcher::CatalogChange::VideoAdded {
                video_id: proxy_id,
                path: out.clone(),
            });
            // The VideoAdded above is for the proxy row, which ListVideos hides
            // (proxy_of IS NULL filter). On its own it wouldn't visibly update
            // the master, so also announce the master changed — that refreshes
            // its proxy-count badge and any open inspector's proxy list.
            let _ = state.events.send(crate::watcher::CatalogChange::VideoModified {
                video_id: id.to_string(),
                path: std::path::PathBuf::from(&source.path),
            });
            tracing::info!("hls: promoted {id}@{height}p to durable proxy {}", out.display());
        }
        Err(e) => {
            tracing::warn!("hls: promote {id}@{height}p — indexing the remux failed: {e}");
        }
    }
}

/// Append `#EXT-X-ENDLIST` if ffmpeg didn't (it normally does on a clean exit,
/// but a killed process leaves an open EVENT playlist that AVPlayer polls
/// forever). Cheap defensive finalize.
fn ensure_endlist(playlist: &Path) {
    if let Ok(s) = std::fs::read_to_string(playlist) {
        if !s.contains("#EXT-X-ENDLIST") {
            use std::io::Write;
            if let Ok(mut f) = std::fs::OpenOptions::new().append(true).open(playlist) {
                let _ = writeln!(f, "#EXT-X-ENDLIST");
            }
        }
    }
}

/// Whether an HLS session dir has made no progress for long enough to be treated
/// as dead (e.g. the daemon was killed mid-transcode). ffmpeg's `temp_file`
/// renames bump the dir mtime on every segment, so an actively transcoding
/// session is never stale; the 90s threshold is well above the per-segment wall
/// time of a downscale transcode.
fn dir_is_stale(dir: &Path) -> bool {
    match std::fs::metadata(dir).and_then(|m| m.modified()) {
        Ok(t) => t.elapsed().map(|e| e.as_secs() > 90).unwrap_or(false),
        Err(_) => false,
    }
}

/// Bound disk use: keep the most-recent completed HLS session dirs and delete
/// older completed ones. Never touches an in-progress dir (no `.complete`), which
/// a client may be tailing.
fn evict_old_hls_sessions(cache_dir: &Path) {
    const KEEP: usize = 24;
    let entries = match std::fs::read_dir(cache_dir.join("hls")) {
        Ok(e) => e,
        Err(_) => return,
    };
    let mut completed: Vec<(std::time::SystemTime, PathBuf)> = Vec::new();
    for ent in entries.flatten() {
        let p = ent.path();
        if !p.is_dir() {
            continue;
        }
        if let Ok(meta) = std::fs::metadata(p.join(".complete")) {
            completed.push((meta.modified().unwrap_or(std::time::UNIX_EPOCH), p));
        }
    }
    if completed.len() <= KEEP {
        return;
    }
    completed.sort_by_key(|(t, _)| *t); // oldest first
    let remove = completed.len() - KEEP;
    for (_, p) in completed.into_iter().take(remove) {
        tracing::info!("hls: evicting old session {}", p.file_name().and_then(|n| n.to_str()).unwrap_or("?"));
        let _ = std::fs::remove_dir_all(&p);
    }
}

/// Resolve a playable rendition of `id` capped near `height` px. Prefers an
/// existing proxy closest to the requested height (so we don't transcode the —
/// possibly huge — original on demand, the whole point for remote tablet/phone
/// playback over Wi-Fi); only transcodes when the video has no usable proxy.
/// Concurrent identical transcode requests share one ffmpeg via a per-key lock;
/// the result is cached under `<cache>/stream/` for reuse.
async fn ensure_downscaled(state: &MediaState, id: &str, src: &str, height: i32) -> Option<PathBuf> {
    if let Some(proxy) = closest_proxy_path(state, id, height) {
        return Some(PathBuf::from(proxy));
    }
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
        let out_str = out_tmp_for_job.to_string_lossy().to_string();
        let vf = format!("scale=-2:min({height}\\,ih)");
        let mut cmd = crate::ffmpeg::ffmpeg_command();
        cmd.args(["-y", "-i", src.as_str()]);
        // height <= 0 = "Original" → transcode at the source resolution (no
        // downscale); a positive height caps it (never upscales).
        if height > 0 {
            cmd.args(["-vf", vf.as_str()]);
        }
        cmd.args([
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
            // Force 8-bit 4:2:0 High profile so Apple hardware decoders can play
            // it. Without -pix_fmt, a ProRes 4:2:2/4:4:4 or 10-bit master yields a
            // High-4:2:2 / 10-bit H.264 that iOS decodes to AUDIO ONLY (the big
            // "Q" with waves) — matching the HLS re-encode path, which already
            // pins yuv420p.
            "-profile:v", "high", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            out_str.as_str(),
        ]);
        cmd
            .stdout(std::process::Stdio::null())
            // Capture stderr so a failed transcode tells us *why* (e.g. ProRes
            // RAW, which ffmpeg can't decode — see rv-frameshot). Without this
            // the only signal is a bare exit code and the client just sees
            // "can't be played on this device".
            .stderr(std::process::Stdio::piped())
            .output()
    })
    .await;

    match res {
        Ok(Ok(output)) if output.status.success() => match std::fs::rename(&out_tmp, &out) {
            Ok(()) => Some(out),
            Err(e) => {
                tracing::warn!("media: rename transcode output failed: {e}");
                let _ = std::fs::remove_file(&out_tmp);
                None
            }
        },
        Ok(Ok(output)) => {
            let stderr = String::from_utf8_lossy(&output.stderr);
            let mut lines: Vec<&str> = stderr.lines().collect();
            let start = lines.len().saturating_sub(8);
            let tail = lines.split_off(start).join("\n");
            tracing::warn!(
                "media: transcode failed for {id} @ {height}p (exit {:?}) — ffmpeg said:\n{tail}",
                output.status.code()
            );
            let _ = std::fs::remove_file(&out_tmp);
            None
        }
        other => {
            tracing::warn!("media: transcode could not spawn for {id} @ {height}p: {other:?}");
            let _ = std::fs::remove_file(&out_tmp);
            None
        }
    }
}

/// The existing proxy whose height best matches `height`: the shortest proxy at
/// least as tall as `height` (least upscaling on the device), or the tallest
/// available if every proxy is shorter than the request. Returns its on-disk
/// path, or `None` when the video has no proxy whose file is present (the caller
/// then transcodes the original). This is what makes remote playback cheap —
/// serving a pre-rendered proxy beats transcoding a multi-GB original per play.
fn closest_proxy_path(state: &MediaState, id: &str, height: i32) -> Option<String> {
    let (path, ph) = closest_streamable_proxy(state, id, height)?;
    tracing::info!("media: serving proxy {path} ({ph}p) for {id} (requested {height}p)");
    Some(path)
}

/// Like [`closest_proxy_path`] but also returns the chosen proxy's height. The
/// HLS path uses the height to decide whether it can copy-mux the proxy (already
/// at or below the target) or must downscale-re-encode it.
///
/// Only proxies the client can actually decode (H.264/HEVC) are considered. A
/// proxy in a mastering codec — common when proxies were made by an external
/// tool (e.g. a ProRes-422 .mov) — would download fine but AVPlayer can't play
/// it, so we skip it and let the caller transcode to H.264 instead.
fn closest_streamable_proxy(state: &MediaState, id: &str, height: i32) -> Option<(String, i32)> {
    let proxies = match state.db.list_proxies(id) {
        Ok(p) if !p.is_empty() => p,
        _ => return None,
    };
    let candidates: Vec<_> = proxies
        .iter()
        .filter(|p| is_streamable_codec(&p.codec_video))
        .collect();
    if candidates.is_empty() {
        return None;
    }
    let chosen = if height <= 0 {
        // "Original" / best quality → the tallest streamable proxy (NOT the
        // smallest, which made Original look lower-res than Auto).
        candidates.iter().max_by_key(|p| p.height)
    } else {
        candidates
            .iter()
            .filter(|p| p.height >= height)
            .min_by_key(|p| p.height)
            .or_else(|| candidates.iter().max_by_key(|p| p.height))
    }?;
    if std::path::Path::new(&chosen.path).exists() {
        Some((chosen.path.clone(), chosen.height))
    } else {
        // Proxy registered but its file is missing (unmounted drive, moved) —
        // fall back to transcoding the original.
        None
    }
}

/// The closest proxy of ANY codec (path), preferring the shortest at least as
/// tall as `height`, else the tallest. Used only as a cheap re-encode *source*
/// — decoding a 2160p ProRes proxy beats decoding a multi-GB 8K original — when
/// no proxy can be copy-muxed into HLS (which can't carry ProRes). The scale
/// filter caps the output at `min(height, source height)`, so a shorter proxy
/// won't be upscaled.
fn closest_proxy_source(state: &MediaState, id: &str, height: i32) -> Option<String> {
    let proxies = state.db.list_proxies(id).ok()?;
    let chosen = if height <= 0 {
        proxies.iter().max_by_key(|p| p.height)
    } else {
        proxies
            .iter()
            .filter(|p| p.height >= height)
            .min_by_key(|p| p.height)
            .or_else(|| proxies.iter().max_by_key(|p| p.height))
    }?;
    let path = chosen.path.clone();
    if std::path::Path::new(&path).exists() {
        Some(path)
    } else {
        None
    }
}

/// Whether a stored codec can be served to an Apple client as-is. Everything
/// outside this set (ProRes, DNxHD, raw, …) is transcoded to H.264 first.
/// Conservative on purpose: an unknown/empty codec is treated as *not*
/// streamable, so we never hand the client a file it can't play.
fn is_streamable_codec(codec: &str) -> bool {
    matches!(
        codec.trim().to_ascii_lowercase().as_str(),
        "h264" | "avc1" | "x264" | "hevc" | "h265" | "hvc1" | "hev1"
    )
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
