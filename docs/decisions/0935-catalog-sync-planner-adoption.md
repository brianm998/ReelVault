# 0935: Planner adopts on content-hash hit; core reconcile pass; sync stays flagged off

Done (core only):
- `sync::Planner` returned `CopyNew` for a content-hash hit, the same as a miss. It now returns `AdoptLocal` for one local match and `Ambiguous` for several.
- `SyncExecutor::apply` takes the peer's catalog data: on `AdoptLocal`, and on `SkipFileReconcileData { marks_changed: true }`, it merges that data into the local video *before* advancing the link's `remote_rev`, so a failed merge is retried and later mark changes are detected.
- `sync::reconcile_catalog` is the production entry point that plans + applies the database half of a sync (adopt, merge marks, tombstones) and returns the ids whose bytes still need transferring.
- Fixed `Database::get_video_catalog_data`: it selected nonexistent `gps_lat`/`gps_lon` columns and read the integer `creation_date` as text, so the `GetVideoCatalogData` RPC failed for every video.
- Test `two_catalogs_adopt_merge_and_report_copies` drives two separate catalog databases (A as peer, B as local) through adopt, marks merge, idempotent re-run and marks-change detection.

Byte-transfer decision (this run):
- **Push reads the local original from the path the local core reports** (`GetMetadata.path`), not through a loopback media route. The HTTP media routes need a paired bearer token even on loopback (only gRPC is exempt), `/video/<id>` may transcode (so its bytes no longer match the content hash), and a new "original" route would be new attack surface. Every client that embeds or shares a core with its daemon can open that path. Non-file paths (e.g. `photos://`) fail with a clear per-video error.
- Push streams the file through the resumable `/upload` protocol (8 MB chunks, deterministic `upload_id` from the content hash so an interrupted push resumes from `/upload/<id>/status`, `content_hash` verified server-side), then copies catalog data onto the new remote video with `ApplyVideoCatalogData`. Implemented in Kotlin `shared/SyncManager.pushVideo`.
- Pull uses `PrepareRendition` -> `/video/<id>?height=`, already in Kotlin.
- Test `core/tests/sync_e2e.rs` drives two in-process daemons (separate catalogs + media routers) through a chunked hash-verified push, a corrupt push (422, nothing left behind), adoption by hash, a `PrepareRendition` + `/video` pull, catalog-data apply, and a convergent re-run. The planner/executor now run in that path through `reconcile_catalog`.
- `embed::ingest_synced` stamped every ingest `is_derived = 1`; it now marks only downscaled copies (`derived_height > 0`), so a full-size pull stays visible to the manifest.
- Apple `SyncProfileStore` now uses the core's `sync_profiles` RPCs (stale TODOs removed); only the device-local `autoSync` flag, which the table has no column for, stays in UserDefaults. iOS auto-sync reads profiles through the store.

Still not done (task 918 remains open), so `CatalogSyncFeature` (kit and shared) stays OFF per 0916:
- `reconcile_catalog` has a test caller but no RPC: clients cannot ask the core to plan/adopt, and nothing records a sync link after a client-driven transfer, so the next run would re-plan the same copy. Needs a proto RPC (e.g. `ReconcileSyncManifest` / `RecordSyncLink`) plus Swift stub regen.
- Pull cannot apply catalog data: ingest (`onIngestSynced` / `reelvault_ingest_synced`) returns a status int, not the new video id, so `ApplyVideoCatalogData` has nothing to target. Needs ingest to return the id (or the RPC above).
- Swift `SyncManager` push/pull are still the `notAvailable` scaffold (a port of the Kotlin push is mechanical but unverified here).
- No real-daemon verification on an Apple and a Kotlin client was possible in this environment (no GUI/device runs allowed); nothing was flipped on on the strength of the in-process test alone.
Platform deviation: none intended; Kotlin got the push fix first and Swift remains scaffolded behind the flag.
