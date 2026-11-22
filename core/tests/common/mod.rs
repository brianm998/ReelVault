//! Shared harness for the core integration tests: a temp catalog, a fake
//! `MediaBackend` (no ffmpeg), an in-process gRPC service and the media router.
#![allow(dead_code)]

use std::path::Path;
use std::sync::{Arc, Once};

use reelvault_core::config::Config;
use reelvault_core::db::Database;
use reelvault_core::error::{ReelVaultError, Result};
use reelvault_core::media_backend::{set_backend, MediaBackend, MediaSource};
use reelvault_core::media_server::MediaState;
use reelvault_core::metadata::FFProbeOutput;
use reelvault_core::service::ReelVaultService;
use reelvault_core::thumbnails::ColorInfo;

/// A backend that never touches ffmpeg: media work fails, writes are no-ops.
pub struct FakeBackend;

impl MediaBackend for FakeBackend {
    fn probe(&self, _src: &MediaSource) -> Result<FFProbeOutput> {
        Err(ReelVaultError::MetadataExtractionFailed("fake backend".into()))
    }
    fn probe_color(&self, _src: &MediaSource) -> ColorInfo {
        ColorInfo::default()
    }
    fn extract_loudness(&self, _src: &MediaSource) -> Vec<f32> {
        Vec::new()
    }
    fn extract_frame(
        &self,
        _src: &MediaSource,
        _color: &ColorInfo,
        _t: f64,
        _max_px: i32,
        _q: u8,
        _out: &Path,
    ) -> Result<()> {
        Err(ReelVaultError::ThumbnailGenerationFailed("fake backend".into()))
    }
    fn transcode_proxy(
        &self,
        _src: &MediaSource,
        _out: &Path,
        _h: i32,
        _frames: i64,
        _p: &mut dyn FnMut(f64),
    ) -> Result<()> {
        Err(ReelVaultError::MetadataExtractionFailed("fake backend".into()))
    }
    fn write_creation_time(&self, _src: &MediaSource, _ts: i64) -> Result<()> {
        Ok(())
    }
    fn write_location(&self, _src: &MediaSource, _la: f64, _lo: f64, _al: f64) -> Result<()> {
        Ok(())
    }
    fn is_available(&self) -> bool {
        false
    }
}

pub fn install_fake_backend() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| set_backend(Arc::new(FakeBackend)));
}

/// A temp catalog plus the services built on it. Keep it alive for the test.
pub struct Harness {
    pub dir: tempfile::TempDir,
    pub db: Arc<Database>,
    pub svc: ReelVaultService,
    pub pairing: reelvault_core::pairing::PairingState,
}

impl Harness {
    pub async fn new() -> Self {
        install_fake_backend();
        let dir = tempfile::tempdir().unwrap();
        let db = Arc::new(Database::new(&dir.path().join("cat.db")).unwrap());
        db.initialize().await.unwrap();
        let mut config = Config::load(&db).await.unwrap();
        config.watch_enabled = false;
        config.thumbnail_cache_path = dir.path().join("thumbs");
        std::fs::create_dir_all(&config.thumbnail_cache_path).unwrap();
        let pairing = reelvault_core::pairing::new_state();
        let svc = ReelVaultService::new(
            Arc::clone(&db),
            Arc::new(config),
            Arc::clone(&pairing),
            dir.path().to_path_buf(),
        );
        Harness { dir, db, svc, pairing }
    }

    /// Insert a video row (no file needed) and return its id.
    pub fn add_video(&self, name: &str, size: i64) -> String {
        self.db
            .add_video(&format!("/lib/{name}"), name, None, None, Some(size))
            .unwrap()
    }

    pub fn media_state(&self, import_dir: Option<std::path::PathBuf>) -> MediaState {
        let (tx, _) = tokio::sync::broadcast::channel(16);
        MediaState::new(
            Arc::clone(&self.db),
            "fp".into(),
            self.dir.path().join("thumbs"),
            self.dir.path().to_path_buf(),
            import_dir,
            720,
            tx,
            Arc::clone(&self.pairing),
        )
    }

    /// Pair a device directly in the DB and return its `Authorization` header.
    pub fn paired_header(&self, token: &str) -> String {
        self.db
            .add_paired_device(&reelvault_core::auth::token_hash(token), "test")
            .unwrap();
        format!("Bearer {token}")
    }
}
