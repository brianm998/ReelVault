# 0935: Planner adopts on content-hash hit; core reconcile pass; sync stays flagged off

Done (core only):
- `sync::Planner` returned `CopyNew` for a content-hash hit, the same as a miss. It now returns `AdoptLocal` for one local match and `Ambiguous` for several.
- `SyncExecutor::apply` takes the peer's catalog data: on `AdoptLocal`, and on `SkipFileReconcileData { marks_changed: true }`, `AdoptLocal` first records the sync link at a stale `remote_rev` (0), then reconciles marks, then advances `remote_rev`; a failed merge therefore leaves a link and the next plan retries the marks (`SkipFileReconcileData { marks_changed: true }`) instead of re-copying. Marks-changed items merge then advance. Test `adopt_records_link_before_marks_and_failed_merge_is_retried`.
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

Follow-up (review fixes): legacy `reelvault.syncProfiles` UserDefaults profiles are migrated into the core on first `SyncProfileStore.load()`; a zero-byte Kotlin push now fails with a specific error. The pull leg of `sync_e2e.rs` seeds a byte-identical rendition, so it does not cover transcoded pulls (hash mismatch); noted in the test. Acceptance criteria 2 and 3 remain unmet; this change lands as partial progress and #918 needs follow-ups: reconcile/record-link RPC, ingest returning the video id, Swift SyncManager transfer, real-client verification, then flag removal.

## Round 3 (split decision)

This change lands only part of #918; no owner approval of narrowing is recorded, so the remaining work below keeps #918's acceptance criteria open.

Landed in this round:
- `sync_e2e.rs` pull leg now downloads a *distinct* rendition (different bytes and hash from the original) via `PrepareRendition` + `/video`, ingests it through `sync::ingest_synced_into` (the function behind `embed::ingest_synced`, now returning the new video id), records the link by id and applies catalog data; a re-run transfers nothing.
- Legacy `reelvault.syncProfiles` UserDefaults profiles migrate into the core `sync_profiles` store once (`SyncProfileStore.migrateLegacyProfiles`); the legacy key is cleared only after every upsert succeeds. Swift tests cover migrate-once and failure-retains.
- Zero-byte originals fail with "original is empty (0 bytes)" via `checkPushable` (Kotlin `shared`), with `CheckPushableTest`.
- Executor `AdoptLocal` ordering as above.

Moved to follow-up tasks (not done here; `CatalogSyncFeature` stays OFF in kit and shared):
1. Kit pull with `ingest_synced` and `ApplyVideoCatalogData` (needs ingest id exposure over FFI, which the core function now supports).
2. Apple push/pull byte transfer (Swift `SyncManager` is still the `notAvailable` scaffold).
3. A production caller for `reconcile_catalog`/planner (RPC) or their deletion.
4. Real-device verification of push/pull on one Apple and one Kotlin client, and removal of the O1 flag .
Open acceptance criteria: push/pull verified on real clients; O1 flag removed. The end-to-end test criterion is met in-process.
Platform deviation: Kotlin got push streaming and zero-byte checks; Swift transfer is deferred (scaffold behind the flag).

## Round 4 (review 5845 fixes)

- Kotlin `pushFile` (extracted from `pushVideo`, tested by `PushFileTest` with a fake remote): the remote's content-hash index is the link. Before uploading it calls `LookupByContentHash`; a hit skips the upload and only applies catalog data. So a finished push is not re-sent on a later run, and a retry after a failed `ApplyVideoCatalogData` applies to the already-indexed video instead of restarting from offset 0 (which duplicated it). The "full stale .part, restart from 0" branch now runs only when the remote has no video for the hash. There is no client RPC to record a core `sync_links` row; that remains with the reconcile RPC in #979-#982.
- `core/tests/sync_e2e.rs` pull leg now mirrors exactly what the Kotlin pull does: PrepareRendition, `/video` download, `ingest_synced_into`, and stops. The earlier hand-written link + apply calls and the post-pull convergence assertion are gone, since no client performs them. The test is renamed `push_then_pull_download_and_ingest_between_two_daemons`; its doc comment states what it proves and does not prove.
