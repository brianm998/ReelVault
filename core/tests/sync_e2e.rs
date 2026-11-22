//! End-to-end catalog sync between two in-process daemons (A = source,
//! B = destination), each with its own catalog and media router.
//!
//! Push: A's file bytes go to B through the resumable `/upload` protocol
//! (chunked, `total_size` + `content_hash`), B indexes it and receives A's
//! catalog data. Pull: B asks A's `PrepareRendition` for a download path and
//! fetches it from A's `/video/<id>?height=` route, ingests it through
//! `ingest_synced` and links it by id. `reconcile_catalog` then
//! adopts the transferred copies instead of re-transferring them.
mod common;

use std::sync::Arc;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use common::Harness;
use reelvault_core::content_hash::sparse_content_hash;
use reelvault_core::media_backend::{set_backend, MediaBackend, MediaSource};
use reelvault_core::media_server::{router, MEDIA_CACHE_VERSION};
use reelvault_core::metadata::FFProbeOutput;
use reelvault_core::service::reelvault::reel_vault_server::ReelVault;
use reelvault_core::service::reelvault::*;
use reelvault_core::db::SyncLinkParams;
use reelvault_core::sync::{ingest_synced_into, reconcile_catalog, SyncExecutor};
use reelvault_core::thumbnails::ColorInfo;
use tonic::Request as GrpcRequest;
use tower::ServiceExt;

/// Probe succeeds with a fixed stream (so uploads index without ffmpeg);
/// everything that needs real media fails or no-ops.
struct ProbeOnly;

impl MediaBackend for ProbeOnly {
    fn probe(&self, _s: &MediaSource) -> reelvault_core::error::Result<FFProbeOutput> {
        Ok(serde_json::from_str(
            r#"{"streams":[{"codec_type":"video","codec_name":"h264","width":320,"height":240,
                "r_frame_rate":"30/1"}],
                "format":{"duration":"1.0","size":"100","bit_rate":"1000"}}"#,
        )
        .unwrap())
    }
    fn probe_color(&self, _s: &MediaSource) -> ColorInfo {
        ColorInfo::default()
    }
    fn extract_loudness(&self, _s: &MediaSource) -> Vec<f32> {
        Vec::new()
    }
    fn extract_frame(
        &self,
        _s: &MediaSource,
        _c: &ColorInfo,
        _t: f64,
        _m: i32,
        _q: u8,
        _o: &std::path::Path,
    ) -> reelvault_core::error::Result<()> {
        Err(reelvault_core::error::ReelVaultError::ThumbnailGenerationFailed("none".into()))
    }
    fn transcode_proxy(
        &self,
        _s: &MediaSource,
        _o: &std::path::Path,
        _h: i32,
        _f: i64,
        _p: &mut dyn FnMut(f64),
    ) -> reelvault_core::error::Result<()> {
        Err(reelvault_core::error::ReelVaultError::MetadataExtractionFailed("none".into()))
    }
    fn write_creation_time(&self, _s: &MediaSource, _t: i64) -> reelvault_core::error::Result<()> {
        Ok(())
    }
    fn write_location(&self, _s: &MediaSource, _a: f64, _b: f64, _c: f64) -> reelvault_core::error::Result<()> {
        Ok(())
    }
    fn is_available(&self) -> bool {
        false
    }
}

async fn body_bytes(resp: axum::response::Response) -> Vec<u8> {
    axum::body::to_bytes(resp.into_body(), usize::MAX).await.unwrap().to_vec()
}

/// Push `bytes` to a daemon via the resumable protocol in `chunk`-sized pieces,
/// returning the new video id.
async fn push(app: &axum::Router, hdr: &str, name: &str, bytes: &[u8], hash: &str, chunk: usize) -> String {
    let total = bytes.len();
    let mut offset = 0;
    loop {
        let end = (offset + chunk).min(total);
        let uri = format!(
            "/upload?filename={name}&upload_id=sync-1&total_size={total}&content_hash={hash}&offset={offset}"
        );
        let req = Request::builder()
            .method("POST")
            .uri(uri)
            .header("authorization", hdr)
            .body(Body::from(bytes[offset..end].to_vec()))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert!(resp.status().is_success(), "chunk at {offset}: {}", resp.status());
        let v: serde_json::Value = serde_json::from_slice(&body_bytes(resp).await).unwrap();
        offset = end;
        if offset == total {
            return v["video_id"].as_str().expect("indexed video id").to_string();
        }
        assert_eq!(v["bytes_received"].as_u64().unwrap() as usize, offset);
    }
}

#[tokio::test]
async fn push_and_pull_between_two_daemons() {
    set_backend(Arc::new(ProbeOnly));
    let a = Harness::new().await;
    let b = Harness::new().await;
    let b_hdr = b.paired_header("tok-b");
    let b_import = b.dir.path().join("import");
    let app_b = router(b.media_state(Some(b_import.clone())));

    // A holds a small "video" with marks.
    let src = a.dir.path().join("clip.mp4");
    let bytes: Vec<u8> = (0..5000u32).map(|i| (i % 251) as u8).collect();
    std::fs::write(&src, &bytes).unwrap();
    let hash = sparse_content_hash(&src).unwrap();
    let a_id = a.db.add_video(src.to_str().unwrap(), "clip.mp4", None, None, Some(bytes.len() as i64)).unwrap();
    a.db.set_content_hash(&a_id, &hash).unwrap();
    a.db.update_video_rating(&a_id, 5).unwrap();

    // ── Push A -> B: chunked, hash-verified, then indexed on B. ──
    let b_id = push(&app_b, &b_hdr, "clip.mp4", &bytes, &hash, 1700).await;
    assert_eq!(std::fs::read(b_import.join("clip.mp4")).unwrap(), bytes, "bytes arrive intact");
    b.db.set_content_hash(&b_id, &hash).unwrap();

    // A corrupt push is refused and leaves nothing behind.
    let bad = Request::builder()
        .method("POST")
        .uri(format!("/upload?filename=bad.mp4&upload_id=sync-2&total_size=3&content_hash={hash}"))
        .header("authorization", &b_hdr)
        .body(Body::from("abc"))
        .unwrap();
    assert_eq!(app_b.clone().oneshot(bad).await.unwrap().status(), StatusCode::UNPROCESSABLE_ENTITY);
    assert!(!b_import.join("bad.mp4").exists());

    // The pushed copy is recognised by hash: adopt + merge marks, no re-copy.
    let manifest = a.db.get_sync_manifest_page(100, "").unwrap();
    let r = reconcile_catalog(&b.db, "peer-a", &manifest, |id| a.db.get_video_catalog_data(id)).unwrap();
    assert_eq!((r.adopted, r.to_copy.len()), (1, 0));
    assert_eq!(b.db.get_video_catalog_data(&b_id).unwrap().unwrap().rating, 5);

    // ── Pull B -> A: a new video on B that A lacks. ──
    let pull_src = b.dir.path().join("second.mp4");
    let pull_bytes: Vec<u8> = (0..3000u32).map(|i| (i % 13) as u8).collect();
    std::fs::write(&pull_src, &pull_bytes).unwrap();
    let phash = sparse_content_hash(&pull_src).unwrap();
    let b2 = b.db.add_video(pull_src.to_str().unwrap(), "second.mp4", None, None, Some(3000)).unwrap();
    b.db.set_content_hash(&b2, &phash).unwrap();
    b.db.update_video_rating(&b2, 3).unwrap();
    // Seed B's rendition cache (what a transcode would leave; no ffmpeg in CI).
    let stream = b.dir.path().join("thumbs/stream");
    std::fs::create_dir_all(&stream).unwrap();
    // The rendition is a *different* byte stream from the original, as a real
    // downscale would be: its hash must not match the original's.
    let rendition: Vec<u8> = (0..1800u32).map(|i| (i % 7) as u8 ^ 0x5a).collect();
    std::fs::write(stream.join(format!("{b2}_480.{MEDIA_CACHE_VERSION}.mp4")), &rendition).unwrap();

    let manifest = b.db.get_sync_manifest_page(100, "").unwrap();
    let plan = reconcile_catalog(&a.db, "peer-b", &manifest, |id| b.db.get_video_catalog_data(id)).unwrap();
    // Order-independent: `second` must be copied; `clip` (already on A, by hash) is adopted.
    assert_eq!(plan.to_copy, vec![b2.clone()]);

    // PrepareRendition on the source yields the route to download from.
    use tokio_stream::StreamExt;
    let mut prog = b
        .svc
        .prepare_rendition(GrpcRequest::new(PrepareRenditionRequest {
            video_id: b2.clone(),
            target_height: 480,
            ..Default::default()
        }))
        .await
        .unwrap()
        .into_inner();
    let mut download_path = String::new();
    while let Some(p) = prog.next().await {
        let p = p.unwrap();
        if p.phase == prepare_rendition_progress::Phase::Ready as i32 {
            download_path = p.download_path;
        }
    }
    assert_eq!(download_path, format!("/video/{b2}?height=480"));
    let resp = app_b
        .clone()
        .oneshot(Request::builder().uri(&download_path).header("authorization", &b_hdr).body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let got = body_bytes(resp).await;
    assert_eq!(got, rendition);
    assert_ne!(got, pull_bytes);

    // Client side of a pull: write the download to a local file on A, then
    // ingest it through the real `ingest_synced` path (provenance stamped,
    // new local id returned), record the sync link by id (the rendition's
    // hash differs from the original's, so hash adoption cannot find it) and
    // copy B's catalog data onto it.
    let landed = a.dir.path().join("second_480.mp4");
    std::fs::write(&landed, &got).unwrap();
    let (tx, _rx) = tokio::sync::broadcast::channel(16);
    let a_new = ingest_synced_into(
        &a.db,
        &a.dir.path().join("thumbs"),
        &tx,
        landed.to_str().unwrap(),
        "second.mp4",
        &phash,
        480,
    )
    .unwrap();
    let ingested: (bool, Option<String>) = a
        .db
        .get_connection()
        .unwrap()
        .query_row("SELECT is_derived, origin_hash FROM videos WHERE id = ?1", [&a_new], |r| {
            Ok((r.get::<_, i32>(0)? == 1, r.get(1)?))
        })
        .unwrap();
    assert_eq!(ingested, (true, Some(phash.clone())), "derived copy stamped with the origin hash");
    let data = b.db.get_video_catalog_data(&b2).unwrap().unwrap();
    SyncExecutor::new(a.db.clone())
        .update_sync_link_after_reconcile(&SyncLinkParams {
            local_video_id: a_new.clone(),
            peer_key: "peer-b".into(),
            remote_video_id: b2.clone(),
            origin_hash: Some(phash.clone()),
            is_derived: true,
            derived_height: Some(480),
            local_rev: 0,
            remote_rev: data.marks_rev,
        })
        .unwrap();
    a.db.apply_video_catalog_data(&a_new, &data).unwrap();
    assert_eq!(a.db.get_video_catalog_data(&a_new).unwrap().unwrap().rating, 3);

    // Both sides now converge: the link (not a hash hit) stops a re-copy.
    let manifest = b.db.get_sync_manifest_page(100, "").unwrap();
    let again = reconcile_catalog(&a.db, "peer-b", &manifest, |id| b.db.get_video_catalog_data(id)).unwrap();
    assert!(again.to_copy.is_empty(), "nothing left to transfer: {again:?}");
}
