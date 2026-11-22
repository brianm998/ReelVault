//! Planner, TLS identity, config, search, indexing helpers and the watcher.
mod common;

use std::sync::Arc;

use common::Harness;
use reelvault_core::db::{SyncLinkParams, SyncManifestRow};
use reelvault_core::search::{SearchEngine, SearchFilters};
use reelvault_core::sync::planner::{Planner, SyncAction};

fn row(id: &str, hash: Option<&str>) -> SyncManifestRow {
    SyncManifestRow {
        video_id: id.into(),
        content_hash: hash.map(String::from),
        marks_rev: 1,
        size_bytes: 1,
        duration_ms: 1,
        filename: format!("{id}.mp4"),
        width: 1,
        height: 1,
        codec_video: "h264".into(),
        is_derived: false,
        proxy_of: None,
        has_streamable_proxy: false,
        deleted_at: None,
    }
}

async fn plan(h: &Harness, rows: &[SyncManifestRow]) -> Vec<SyncAction> {
    let hashes: Vec<String> = rows.iter().filter_map(|r| r.content_hash.clone()).collect();
    let hits = h.db.lookup_by_content_hash(&hashes).unwrap();
    Planner::new(Arc::clone(&h.db), "peer".into())
        .classify(rows, &hits)
        .into_iter()
        .map(|i| i.action)
        .collect()
}

#[tokio::test]
async fn planner_new_remote_is_copy_new() {
    let h = Harness::new().await;
    assert_eq!(plan(&h, &[row("r1", Some("h1"))]).await, [SyncAction::CopyNew]);
}

#[tokio::test]
async fn planner_defers_when_hash_missing() {
    let h = Harness::new().await;
    assert_eq!(plan(&h, &[row("r1", None)]).await, [SyncAction::DeferHashPending]);
}

#[tokio::test]
async fn planner_skips_derived() {
    let h = Harness::new().await;
    let mut r = row("r1", Some("h"));
    r.is_derived = true;
    assert_eq!(plan(&h, &[r]).await, [SyncAction::SkipDerived]);
}

#[tokio::test]
async fn planner_tombstone_without_link_has_no_local_id() {
    let h = Harness::new().await;
    let mut r = row("r1", Some("h"));
    r.deleted_at = Some(5);
    assert_eq!(plan(&h, &[r]).await, [SyncAction::SoftDelete { local_video_id: None }]);
}

#[tokio::test]
async fn planner_linked_video_reports_marks_change() {
    let h = Harness::new().await;
    let local = h.add_video("l.mp4", 1);
    h.db.add_sync_link(&SyncLinkParams {
        local_video_id: local.clone(),
        peer_key: "peer".into(),
        remote_video_id: "r1".into(),
        origin_hash: None,
        is_derived: false,
        derived_height: None,
        local_rev: 1,
        remote_rev: 1,
    })
    .unwrap();
    let same = row("r1", Some("h"));
    let mut changed = row("r1", Some("h"));
    changed.marks_rev = 9;
    assert_eq!(
        plan(&h, &[same]).await,
        [SyncAction::SkipFileReconcileData { local_video_id: local.clone(), marks_changed: false }]
    );
    assert_eq!(
        plan(&h, &[changed]).await,
        [SyncAction::SkipFileReconcileData { local_video_id: local.clone(), marks_changed: true }]
    );
    let mut dead = row("r1", Some("h"));
    dead.deleted_at = Some(1);
    assert_eq!(plan(&h, &[dead]).await, [SyncAction::SoftDelete { local_video_id: Some(local) }]);
}

#[tokio::test]
async fn planner_preserves_order_and_length() {
    let h = Harness::new().await;
    let rows = [row("a", Some("1")), row("b", None), row("c", Some("3"))];
    let out = plan(&h, &rows).await;
    assert_eq!(out, [SyncAction::CopyNew, SyncAction::DeferHashPending, SyncAction::CopyNew]);
}

#[test]
fn identity_is_stable_across_loads_and_fingerprint_is_hex() {
    let dir = tempfile::tempdir().unwrap();
    let a = reelvault_core::identity::load_or_create(dir.path(), &["localhost".into()]).unwrap();
    let b = reelvault_core::identity::load_or_create(dir.path(), &[]).unwrap();
    assert_eq!(a.fingerprint_hex, b.fingerprint_hex);
    assert_eq!(a.cert_pem, b.cert_pem);
    assert_eq!(a.fingerprint_hex.len(), 64);
    assert!(a.cert_pem.starts_with("-----BEGIN CERTIFICATE-----"));
}

#[test]
fn identity_fingerprint_matches_cert_der() {
    use base64::Engine as _;
    use sha2::{Digest, Sha256};
    let dir = tempfile::tempdir().unwrap();
    let id = reelvault_core::identity::load_or_create(dir.path(), &[]).unwrap();
    let b64: String = id.cert_pem.lines().filter(|l| !l.starts_with("-----")).collect();
    let der = base64::engine::general_purpose::STANDARD.decode(b64).unwrap();
    assert_eq!(hex::encode(Sha256::digest(&der)), id.fingerprint_hex);
}

#[test]
fn identity_regenerates_on_corrupt_fingerprint() {
    let dir = tempfile::tempdir().unwrap();
    let a = reelvault_core::identity::load_or_create(dir.path(), &[]).unwrap();
    std::fs::write(dir.path().join("identity/fingerprint.txt"), "nothex").unwrap();
    let b = reelvault_core::identity::load_or_create(dir.path(), &[]).unwrap();
    assert_ne!(a.fingerprint_hex, b.fingerprint_hex);
    assert_eq!(b.fingerprint_hex.len(), 64);
}

#[cfg(unix)]
#[test]
fn identity_key_is_owner_only() {
    use std::os::unix::fs::PermissionsExt;
    let dir = tempfile::tempdir().unwrap();
    reelvault_core::identity::load_or_create(dir.path(), &[]).unwrap();
    let m = std::fs::metadata(dir.path().join("identity/key.pem")).unwrap().permissions().mode();
    assert_eq!(m & 0o777, 0o600);
}

#[tokio::test]
async fn config_save_load_roundtrip() {
    let h = Harness::new().await;
    let mut c = reelvault_core::config::Config::load(&h.db).await.unwrap();
    c.proxy_target_height = 540;
    c.watch_write_settle_ms = 1234;
    c.import_dir = Some(h.dir.path().join("imp"));
    c.save(&h.db).unwrap();
    let c2 = reelvault_core::config::Config::load(&h.db).await.unwrap();
    assert_eq!(c2.proxy_target_height, 540);
    assert_eq!(c2.watch_write_settle_ms, 1234);
    assert_eq!(c2.import_dir, c.import_dir);
}

#[tokio::test]
async fn config_watch_settings_defaults_and_clamp() {
    let h = Harness::new().await;
    assert_eq!(reelvault_core::config::Config::load_watch_settings(&h.db).unwrap(), (true, 5000, 30000));
    let mut c = reelvault_core::config::Config::load(&h.db).await.unwrap();
    c.watch_enabled = false;
    c.watch_write_settle_ms = -50;
    c.watch_poll_interval_ms = 0;
    c.save(&h.db).unwrap();
    assert_eq!(reelvault_core::config::Config::load_watch_settings(&h.db).unwrap(), (false, 0, 0));
}

#[tokio::test]
async fn config_without_catalog_returns_defaults() {
    let db = reelvault_core::db::Database::new_empty();
    let c = reelvault_core::config::Config::load(&db).await.unwrap();
    assert_eq!(c.proxy_target_height, 720);
    assert!(c.watch_enabled);
}

#[tokio::test]
async fn search_engine_filename_notes_and_tags() {
    let h = Harness::new().await;
    let a = h.add_video("sunset_beach.mp4", 1);
    h.add_video("city.mp4", 1);
    h.db.update_notes(&a, "golden hour").unwrap();
    let (r, n) = SearchEngine::search(&h.db, "sunset", 10, 0, &[]).unwrap();
    assert_eq!((r.len(), n), (1, 1));
    assert_eq!(SearchEngine::search(&h.db, "golden", 10, 0, &[]).unwrap().0.len(), 1);
    assert_eq!(SearchEngine::search(&h.db, "zzz", 10, 0, &[]).unwrap().0.len(), 0);
    let t = h.db.create_tag("trip", None).unwrap();
    h.db.tag_video(&a, &t).unwrap();
    assert_eq!(SearchEngine::search(&h.db, ".mp4", 10, 0, &[t.clone()]).unwrap().0.len(), 1);
}

#[tokio::test]
async fn search_treats_like_wildcards_and_quotes_safely() {
    let h = Harness::new().await;
    h.add_video("a.mp4", 1);
    // Must not error on quote characters / injection attempts.
    assert!(SearchEngine::search(&h.db, "'; DROP TABLE videos; --", 10, 0, &[]).is_ok());
    assert_eq!(h.db.list_videos(10, 0).unwrap().1, 1);
}

#[tokio::test]
async fn advanced_search_by_filename_and_empty_filters() {
    let h = Harness::new().await;
    h.add_video("x1.mp4", 1);
    h.add_video("y2.mp4", 1);
    let all = SearchEngine::advanced_search(&h.db, &SearchFilters::default(), 10, 0).unwrap();
    assert_eq!(all.0.len(), 2);
    let f = SearchFilters { filename: Some("x1".into()), ..Default::default() };
    assert_eq!(SearchEngine::advanced_search(&h.db, &f, 10, 0).unwrap().0.len(), 1);
}

#[test]
fn supported_extensions_case_insensitive() {
    use reelvault_core::indexing::is_supported_video_extension as ok;
    use std::path::Path;
    assert!(ok(Path::new("a.MP4")));
    assert!(ok(Path::new("a.mov")));
    assert!(!ok(Path::new("a.txt")));
    assert!(!ok(Path::new("noext")));
}

#[test]
fn filename_date_rule_parsing() {
    use reelvault_core::indexing::FilenameDateRule;
    assert!(FilenameDateRule::from_proto("", "").is_none());
    assert!(FilenameDateRule::from_proto("bogus", "end").is_none());
    let r = FilenameDateRule::from_proto("YYYY-MM-DD", "beginning").unwrap();
    assert_eq!(r.parse_filename("2024-03-05_trip"), Some(1709596800000));
    assert_eq!(r.parse_filename("trip_2024-03-05"), None, "position is honoured");
    assert_eq!(r.parse_filename("2024-13-05_x"), None, "month out of range");
    let us = FilenameDateRule::from_proto("MM-DD-YYYY", "anywhere").unwrap();
    assert_eq!(us.parse_filename("clip 03-05-2024 b"), Some(1709596800000));
}

#[tokio::test]
async fn watcher_starts_and_stops_cleanly_and_reports_removals() {
    let h = Harness::new().await;
    let lib = h.dir.path().join("watched");
    std::fs::create_dir_all(&lib).unwrap();
    let (tx, _rx) = tokio::sync::broadcast::channel(16);
    let w = reelvault_core::watcher::LibraryWatcher::start(
        Arc::clone(&h.db),
        h.dir.path().join("thumbs"),
        tx,
        100,
        0,
        vec![(lib.clone(), true)],
    )
    .unwrap();
    w.update_settings(50, 0);
    w.add_path(h.dir.path().to_path_buf(), false);
    w.remove_path(h.dir.path().to_path_buf());
    // Non-video files are ignored: nothing is indexed.
    std::fs::write(lib.join("notes.txt"), b"hi").unwrap();
    std::thread::sleep(std::time::Duration::from_millis(600));
    drop(w);
    assert_eq!(h.db.list_videos(10, 0).unwrap().1, 0);
}
