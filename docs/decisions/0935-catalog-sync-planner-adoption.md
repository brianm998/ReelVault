# 0935: Planner adopts on content-hash hit; sync stays flagged off

Done: `sync::Planner` returned `CopyNew` for a content-hash hit, the same as a miss. It now returns `AdoptLocal` for one local match and `Ambiguous` for several, and `SyncExecutor::apply` records the sync link so the next plan skips the transfer.

Not done (task 918 remains open): the planner still has no non-test caller; no push (loopback media route / resumable `/upload`), pull, `ingest_synced` or `ApplyVideoCatalogData` in the kit; Apple clients still keep profiles in UserDefaults; no two-daemon end-to-end test; no real-daemon verification. So `CatalogSyncFeature` (kit and shared) stays OFF per 0916 — flipping it would again show "synced" for transfers that never happen.
No client changes, so no platform deviation.
