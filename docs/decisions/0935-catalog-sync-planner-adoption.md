# 0935: Planner adopts on content-hash hit; core reconcile pass; sync stays flagged off

Done (core only):
- `sync::Planner` returned `CopyNew` for a content-hash hit, the same as a miss. It now returns `AdoptLocal` for one local match and `Ambiguous` for several.
- `SyncExecutor::apply` takes the peer's catalog data: on `AdoptLocal`, and on `SkipFileReconcileData { marks_changed: true }`, it merges that data into the local video *before* advancing the link's `remote_rev`, so a failed merge is retried and later mark changes are detected.
- `sync::reconcile_catalog` is the production entry point that plans + applies the database half of a sync (adopt, merge marks, tombstones) and returns the ids whose bytes still need transferring.
- Fixed `Database::get_video_catalog_data`: it selected nonexistent `gps_lat`/`gps_lon` columns and read the integer `creation_date` as text, so the `GetVideoCatalogData` RPC failed for every video.
- Test `two_catalogs_adopt_merge_and_report_copies` drives two separate catalog databases (A as peer, B as local) through adopt, marks merge, idempotent re-run and marks-change detection.

Not done (task 918 remains open): `reconcile_catalog` is not yet called from an RPC or client; no byte transfer (loopback media route / resumable `/upload` push, pull) or kit `ingest_synced` / `ApplyVideoCatalogData`; Apple clients still keep profiles in UserDefaults; no real-daemon verification on an Apple and a Kotlin client. So `CatalogSyncFeature` (kit and shared) stays OFF per 0916 — flipping it would again show "synced" for transfers that never happen.
No client changes, so no platform deviation.
