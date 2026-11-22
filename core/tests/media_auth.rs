//! Every protected media route must refuse unauthenticated requests, and the
//! upload path must sanitise what it is given.
mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use common::Harness;
use reelvault_core::media_server::router;
use tower::ServiceExt;

async fn call(h: &Harness, method: &str, uri: &str, authz: Option<&str>, body: &str) -> StatusCode {
    let dir = h.dir.path().join("import");
    let app = router(h.media_state(Some(dir)));
    let mut b = Request::builder().method(method).uri(uri);
    if let Some(a) = authz {
        b = b.header("authorization", a);
    }
    app.oneshot(b.body(Body::from(body.to_string())).unwrap()).await.unwrap().status()
}

const PROTECTED: &[(&str, &str)] = &[
    ("POST", "/upload?filename=a.mp4"),
    ("POST", "/upload?filename=a.mp4&upload_id=abc&total_size=3"),
    ("POST", "/upload/abc/status"),
    ("POST", "/upload/vid1/catalog-data"),
    ("GET", "/video/vid1"),
    ("GET", "/video/vid1?height=480"),
    ("GET", "/hls/vid1/720/index.m3u8"),
    ("GET", "/hls/vid1/720/seg0.m4s"),
];

#[tokio::test]
async fn every_protected_route_rejects_missing_token() {
    let h = Harness::new().await;
    for (m, uri) in PROTECTED {
        assert_eq!(call(&h, m, uri, None, "{}").await, StatusCode::UNAUTHORIZED, "{m} {uri}");
    }
}

#[tokio::test]
async fn every_protected_route_rejects_unknown_token() {
    let h = Harness::new().await;
    for (m, uri) in PROTECTED {
        let s = call(&h, m, uri, Some("Bearer not-a-real-token"), "{}").await;
        assert_eq!(s, StatusCode::UNAUTHORIZED, "{m} {uri}");
    }
}

#[tokio::test]
async fn every_protected_route_rejects_malformed_header() {
    let h = Harness::new().await;
    h.paired_header("good");
    for hdr in ["good", "Basic good", "Bearer ", "bearer good"] {
        for (m, uri) in PROTECTED {
            assert_eq!(call(&h, m, uri, Some(hdr), "{}").await, StatusCode::UNAUTHORIZED, "{hdr:?} {m} {uri}");
        }
    }
}

#[tokio::test]
async fn revoked_token_loses_access() {
    let h = Harness::new().await;
    let hdr = h.paired_header("tok1");
    assert_ne!(call(&h, "GET", "/video/nope", Some(&hdr), "").await, StatusCode::UNAUTHORIZED);
    assert!(h.db.revoke_paired_device(&reelvault_core::auth::token_hash("tok1")).unwrap());
    assert_eq!(call(&h, "GET", "/video/nope", Some(&hdr), "").await, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn unreadable_database_is_503_not_401() {
    let h = Harness::new().await;
    let st = {
        let (tx, _) = tokio::sync::broadcast::channel(4);
        reelvault_core::media_server::MediaState::new(
            std::sync::Arc::new(reelvault_core::db::Database::new_empty()),
            "fp".into(),
            h.dir.path().to_path_buf(),
            h.dir.path().to_path_buf(),
            None,
            720,
            tx,
            reelvault_core::pairing::new_state(),
        )
    };
    let req = Request::builder()
        .uri("/video/x")
        .header("authorization", "Bearer abc")
        .body(Body::empty())
        .unwrap();
    assert_eq!(router(st).oneshot(req).await.unwrap().status(), StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn public_routes_need_no_token() {
    let h = Harness::new().await;
    assert_eq!(call(&h, "GET", "/healthz", None, "").await, StatusCode::OK);
    assert_eq!(call(&h, "GET", "/fingerprint", None, "").await, StatusCode::OK);
}

#[tokio::test]
async fn video_unknown_id_is_404_when_authorised() {
    let h = Harness::new().await;
    let hdr = h.paired_header("t");
    assert_eq!(call(&h, "GET", "/video/missing", Some(&hdr), "").await, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn upload_refused_without_import_dir() {
    let h = Harness::new().await;
    let hdr = h.paired_header("t");
    let app = router(h.media_state(None));
    let req = Request::builder()
        .method("POST")
        .uri("/upload?filename=a.mp4")
        .header("authorization", hdr)
        .body(Body::from("x"))
        .unwrap();
    assert_eq!(app.oneshot(req).await.unwrap().status(), StatusCode::CONFLICT);
}

#[tokio::test]
async fn upload_rejects_empty_and_dotdot_filenames() {
    let h = Harness::new().await;
    let hdr = h.paired_header("t");
    for name in ["", "..", "/"] {
        let uri = format!("/upload?filename={}", name.replace('/', "%2F"));
        assert_eq!(call(&h, "POST", &uri, Some(&hdr), "x").await, StatusCode::BAD_REQUEST, "{name:?}");
    }
}

#[tokio::test]
async fn upload_rejects_unsafe_upload_id() {
    let h = Harness::new().await;
    let hdr = h.paired_header("t");
    for id in ["..%2F..%2Fetc", "a%2Fb", "a.b", "x%20y"] {
        let uri = format!("/upload?filename=a.mp4&upload_id={id}&total_size=1");
        assert_eq!(call(&h, "POST", &uri, Some(&hdr), "x").await, StatusCode::BAD_REQUEST, "{id}");
    }
}

#[tokio::test]
async fn resumable_upload_never_writes_outside_import_dir() {
    let h = Harness::new().await;
    let hdr = h.paired_header("t");
    let import = h.dir.path().join("import");
    // Partial chunk (total_size larger than the body) stays in .uploads.
    let s = call(&h, "POST", "/upload?filename=..%2F..%2Fescape.mp4&upload_id=u1&total_size=100", Some(&hdr), "abc").await;
    assert_eq!(s, StatusCode::OK);
    assert!(import.join(".uploads/u1.part").exists());
    assert!(!h.dir.path().join("escape.mp4").exists());
    assert!(!h.dir.path().parent().unwrap().join("escape.mp4").exists());
}

#[tokio::test]
async fn resumable_upload_offset_mismatch_is_conflict() {
    let h = Harness::new().await;
    let hdr = h.paired_header("t");
    call(&h, "POST", "/upload?filename=a.mp4&upload_id=u2&total_size=100", Some(&hdr), "abc").await;
    let s = call(&h, "POST", "/upload?filename=a.mp4&upload_id=u2&total_size=100&offset=50", Some(&hdr), "x").await;
    assert_eq!(s, StatusCode::CONFLICT);
}
