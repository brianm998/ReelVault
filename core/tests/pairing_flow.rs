//! Pairing, rate limiting and the token → authorisation round trip.
mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use common::Harness;
use http_body_util::BodyExt;
use reelvault_core::media_server::router;
use reelvault_core::pairing::{self, Redeem};
use tonic::Request as GrpcRequest;
use tower::ServiceExt;

use reelvault_core::service::reelvault::reel_vault_server::ReelVault;
use reelvault_core::service::reelvault::StartPairingRequest;

#[tokio::test]
async fn start_pairing_rpc_code_redeems_over_http() {
    let h = Harness::new().await;
    let code = h.svc.start_pairing(GrpcRequest::new(StartPairingRequest {})).await.unwrap().into_inner().code;
    assert_eq!(code.len(), 6);
    let app = router(h.media_state(None));
    let req = Request::builder()
        .method("POST")
        .uri("/pair")
        .header("content-type", "application/json")
        .body(Body::from(format!(r#"{{"pin":"{code}","device_name":"  Phone\u0007  "}}"#)))
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let v: serde_json::Value =
        serde_json::from_slice(&resp.into_body().collect().await.unwrap().to_bytes()).unwrap();
    let hdr = format!("Bearer {}", v["token"].as_str().unwrap());
    assert!(reelvault_core::auth::is_authorized(&h.db, Some(&hdr)));
    // Code is single-use and pairing.txt is removed.
    assert!(h.pairing.lock().await.is_none());
    assert!(!h.dir.path().join("pairing.txt").exists());
}

#[tokio::test]
async fn paired_device_name_is_sanitised_in_catalog() {
    let h = Harness::new().await;
    let (code, _) = pairing::issue_code(&h.pairing, h.dir.path()).await;
    let long = "x".repeat(200);
    let app = router(h.media_state(None));
    let req = Request::builder()
        .method("POST")
        .uri("/pair")
        .header("content-type", "application/json")
        .body(Body::from(format!(r#"{{"pin":"{code}","device_name":"{long}"}}"#)))
        .unwrap();
    assert_eq!(app.oneshot(req).await.unwrap().status(), StatusCode::OK);
    assert_eq!(h.db.list_paired_devices().unwrap().len(), 1);
}

#[tokio::test]
async fn pair_without_pending_code_is_unauthorized() {
    let h = Harness::new().await;
    let app = router(h.media_state(None));
    let req = Request::builder()
        .method("POST")
        .uri("/pair")
        .header("content-type", "application/json")
        .body(Body::from(r#"{"pin":"123456"}"#))
        .unwrap();
    assert_eq!(app.oneshot(req).await.unwrap().status(), StatusCode::UNAUTHORIZED);
    assert!(h.db.list_paired_devices().unwrap().is_empty());
}

#[tokio::test]
async fn pair_start_writes_code_file_and_is_idempotent() {
    let h = Harness::new().await;
    let app = router(h.media_state(None));
    for _ in 0..2 {
        let req = Request::builder().method("POST").uri("/pair/start").body(Body::empty()).unwrap();
        assert_eq!(app.clone().oneshot(req).await.unwrap().status(), StatusCode::OK);
    }
    let first = h.pairing.lock().await.as_ref().unwrap().code.clone();
    let txt = std::fs::read_to_string(h.dir.path().join("pairing.txt")).unwrap();
    assert_eq!(txt.trim(), first);
}

#[cfg(unix)]
#[tokio::test]
async fn pairing_file_is_owner_only() {
    use std::os::unix::fs::PermissionsExt;
    let h = Harness::new().await;
    pairing::issue_code(&h.pairing, h.dir.path()).await;
    let mode = std::fs::metadata(h.dir.path().join("pairing.txt")).unwrap().permissions().mode();
    assert_eq!(mode & 0o777, 0o600);
}

#[tokio::test]
async fn pair_revoke_removes_own_token_and_is_idempotent() {
    let h = Harness::new().await;
    let hdr = h.paired_header("mine");
    let app = router(h.media_state(None));
    for _ in 0..2 {
        let req = Request::builder()
            .method("POST")
            .uri("/pair/revoke")
            .header("authorization", &hdr)
            .body(Body::empty())
            .unwrap();
        assert_eq!(app.clone().oneshot(req).await.unwrap().status(), StatusCode::OK);
    }
    assert!(!reelvault_core::auth::is_authorized(&h.db, Some(&hdr)));
}

#[tokio::test]
async fn pair_revoke_without_token_is_unauthorized() {
    let h = Harness::new().await;
    let app = router(h.media_state(None));
    let req = Request::builder().method("POST").uri("/pair/revoke").body(Body::empty()).unwrap();
    assert_eq!(app.oneshot(req).await.unwrap().status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn pair_request_publishes_event_with_sanitised_name() {
    let h = Harness::new().await;
    let (tx, mut rx) = tokio::sync::broadcast::channel(8);
    let st = reelvault_core::media_server::MediaState::new(
        h.db.clone(),
        "fp".into(),
        h.dir.path().to_path_buf(),
        h.dir.path().to_path_buf(),
        None,
        720,
        tx,
        h.pairing.clone(),
    );
    let req = Request::builder()
        .method("POST")
        .uri("/pair/request")
        .header("content-type", "application/json")
        .body(Body::from(r#"{"device_name":"  iPad\n"}"#))
        .unwrap();
    assert_eq!(router(st).oneshot(req).await.unwrap().status(), StatusCode::OK);
    match rx.try_recv().unwrap() {
        reelvault_core::watcher::CatalogChange::PairingRequested { device_name } => {
            assert_eq!(device_name, "iPad")
        }
        other => panic!("unexpected {other:?}"),
    }
}

#[tokio::test]
async fn redeem_states() {
    let st = pairing::new_state();
    assert_eq!(pairing::redeem(&st, "000000").await, Redeem::NoCode);
    let dir = tempfile::tempdir().unwrap();
    let (code, _) = pairing::issue_code(&st, dir.path()).await;
    let wrong = if code == "111111" { "222222" } else { "111111" };
    assert_eq!(pairing::redeem(&st, wrong).await, Redeem::Wrong { remaining: 4 });
    assert_eq!(pairing::redeem(&st, &code).await, Redeem::Ok);
    assert_eq!(pairing::redeem(&st, &code).await, Redeem::NoCode);
}

#[tokio::test]
async fn expired_code_is_rejected() {
    let st = pairing::new_state();
    *st.lock().await = Some(pairing::PendingPairing {
        code: "123456".into(),
        expires: std::time::Instant::now() - std::time::Duration::from_secs(1),
        failed_attempts: 0,
    });
    assert_eq!(pairing::redeem(&st, "123456").await, Redeem::Expired);
    assert!(st.lock().await.is_none());
}

#[tokio::test]
async fn issue_if_none_keeps_live_code_but_replaces_expired() {
    let st = pairing::new_state();
    let dir = tempfile::tempdir().unwrap();
    assert!(pairing::issue_code_if_none(&st, dir.path()).await.is_some());
    assert!(pairing::issue_code_if_none(&st, dir.path()).await.is_none());
    st.lock().await.as_mut().unwrap().expires = std::time::Instant::now() - std::time::Duration::from_secs(1);
    assert!(pairing::issue_code_if_none(&st, dir.path()).await.is_some());
}

#[test]
fn sanitize_device_name_cases() {
    assert_eq!(pairing::sanitize_device_name(None), None);
    assert_eq!(pairing::sanitize_device_name(Some("   ")), None);
    assert_eq!(pairing::sanitize_device_name(Some("a\u{0}b\n")), Some("ab".into()));
    assert_eq!(pairing::sanitize_device_name(Some(&"y".repeat(500))).unwrap().chars().count(), 64);
}

#[test]
fn gen_code_is_six_digits() {
    for _ in 0..50 {
        let c = pairing::gen_code();
        assert_eq!(c.len(), 6);
        assert!(c.bytes().all(|b| b.is_ascii_digit()));
    }
}

#[test]
fn rate_limiter_blocks_after_max_per_key() {
    let rl = pairing::RateLimiter::new(3, std::time::Duration::from_secs(60));
    assert!(rl.allow("a") && rl.allow("a") && rl.allow("a"));
    assert!(!rl.allow("a"));
    assert!(rl.allow("b"), "limits are per client");
}

#[test]
fn rate_limiter_window_expires() {
    let rl = pairing::RateLimiter::new(1, std::time::Duration::from_millis(30));
    assert!(rl.allow("a"));
    assert!(!rl.allow("a"));
    std::thread::sleep(std::time::Duration::from_millis(60));
    assert!(rl.allow("a"));
}

#[test]
fn auth_check_header_forms() {
    let dir = tempfile::tempdir().unwrap();
    let db = reelvault_core::db::Database::new(&dir.path().join("a.db")).unwrap();
    db.set_path(&dir.path().join("a.db")).unwrap();
    db.add_paired_device(&reelvault_core::auth::token_hash("t"), "d").unwrap();
    use reelvault_core::auth::{check, AuthResult::*};
    assert_eq!(check(&db, None), Unauthorized);
    assert_eq!(check(&db, Some("Bearer ")), Unauthorized);
    assert_eq!(check(&db, Some("t")), Unauthorized);
    assert_eq!(check(&db, Some("Bearer t")), Authorized);
}
