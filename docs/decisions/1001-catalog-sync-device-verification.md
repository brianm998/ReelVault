# 1001: Catalog sync device verification (task 982)

Status: OPEN. `CatalogSyncFeature` (kit `isEnabled`, shared `ENABLED`) stays OFF. Real-device runs have not been done; the owner must run the checklist below and fill in the results table. Only then may the flags be removed (with 0916 and 0935 updated).

## Already proven in-process (not a substitute for devices)

- `core/tests/sync_e2e.rs`: two in-process daemons, full RPC sequence.
- kit `SyncPushTests`; shared `PushFileTest`, `CheckPushableTest`.

## Checklist (owner)

Pick one Apple client (macOS or iOS) and one Kotlin client (desktop or Android). Temporarily enable the flag in a local build only; do not commit it until the table is filled.

1. Record the daemon build: `git rev-parse HEAD` of the daemon and client builds.
2. Push: sync a small video to a remote daemon. Check the video exists remotely with matching hash, and tags/ratings/catalog data arrived.
3. Re-push: run sync again. Check nothing is re-sent and no duplicate video appears.
4. Interrupt a larger push mid-transfer and resume it; check it completes once.
5. Pull: pull a rendition from the peer. Check the file is ingested, the hash is right, and catalog data is applied.
6. Record each outcome below, per client.

## Results

| Client | Daemon commit | Pushed | Pulled | Pass/Fail | Notes |
|--------|---------------|--------|--------|-----------|-------|

## After a pass

Remove the flags in kit and shared, drop the gates, and update 0916 and 0935.
