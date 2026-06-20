# ReelVault Catalog Sync — Implementation Spec

**Status:** Draft for implementation
**Audience:** The engineer-agent implementing the feature
**Scope:** Rust core (`core/`), the embedded mobile core (iOS FFI / Android JNI), all four clients (SwiftUI macOS, SwiftUI iOS, Kotlin Compose desktop, Kotlin Compose Android), shared `kit/` (ReelVaultKit) and `shared/` Kotlin.

> This spec was written against the code as it exists today. Where it cites
> `file:symbol`, trust the **symbol** over the line number (lines drift). Every
> load-bearing architectural claim below (mutually-exclusive Local/Remote
> AppRouter phases, hard `DELETE` in `delete_video`, non-unique
> `collections.name`, 3-valued `source_kind`, `identity.rs` being TLS-only) was
> verified directly in the tree before writing.

---

## 1. Overview & Goals

### 1.1 What sync is

Catalog sync lets a mobile client that has an **on-device embedded catalog** (the
in-process Rust core, per [`docs/IOS_CORE_PORT.md`](IOS_CORE_PORT.md) and the
Android JNI core) reach a **paired remote daemon** over Wi-Fi/LAN and make the
two catalogs mirror each other — in one direction or both — optionally narrowed
by a library filter.

"Catalog" means both the video **files** and the **catalog data** attached to
them (tags, collections, ratings, color labels, notes, capture-date/GPS
overrides, group/stack relationships, proxy links).

The four product use cases all reduce to one primitive — a **directed, filtered
copy with provenance tracking** — composed two ways:

| # | Use case | Direction(s) | Filter | Deletes |
|---|----------|--------------|--------|---------|
| 1 | **Push** — device videos become new imports on the remote, with their catalog data | to-remote | none | never |
| 2 | **Pull** — remote videos copied to the device *in a device-playable format*, with remote catalog data; re-runs recognize already-synced files even though bytes/size/encoding differ | from-remote | none | never |
| 3 | **Mirror** — both catalogs kept as a copy-only union; mobile copies may be downscaled/re-encoded | to-remote **and** from-remote | none | never (v1) |
| 4 | **Filtered** — any of the above, narrowed by the same filter the grid uses | any | yes | never |

### 1.2 Goals

- **Reuse, not reinvent.**
  - Push reuses `POST /upload` (`core/src/media_server.rs`, the existing
    `upload` handler) and the mobile upload path (`UploadManager` +
    `LocalVideoExport` on iOS, the Android equivalent — see
    [[ios-local-to-remote-upload]]).
  - Pull reuses the rendition/transcode path (`GET /video/{id}?height=H` in
    `media_server.rs`) and the offline-download machinery
    (`OfflineLibrary` — see [[ios-offline-cache]]).
  - Filter selection reuses the existing `ListVideosRequest` filter fields,
    `db.build_filter_clauses`, and `SmartCollectionFilters` JSON.
  - The remote is reached the way upload already reaches it: a **transient**
    pinned-TLS gRPC + HTTPS client built from
    `AppRouter.lastPairedUploadEndpoint()`, *not* a second AppRouter
    `connection` phase (§3).
- **Preserve catalog data, not just bytes.** Close the documented metadata-loss
  gap in today's upload: tags / collections / ratings / color labels / notes /
  keywords must transfer (§5).
- **Idempotent re-sync.** Running the same profile twice must not duplicate
  videos, re-transcode already-synced files, or clobber unchanged catalog data
  (§4, §8).
- **Resumable & cancellable** over flaky Wi-Fi (§8.3).
- **4-client parity where appropriate** (`CLAUDE.md` "UI/UX Consistency"), with
  the legitimate per-platform deviations called out (§10.5).

### 1.3 Non-goals

- **No editing.** Transcode happens **only** to make a file playable on the
  target device (the use-case-2/3 requirement), never for creative reasons.
  ReelVault stays "cataloging, not editing."
- **No delete-propagation in v1.** Mirror is a **copy-only union**. `delete_video`
  is a hard `DELETE` (`db.rs`, verified) and there is **no tombstone table**, so
  deletions cannot even be *detected* across a sync, let alone safely
  propagated. Delete-propagation is deferred to a future phase with a real
  tombstone design (§4.6). Do **not** ship a `propagate_deletes` flag in v1.
- **No automatic/background sync in v1.** Profiles are saved and re-run on a
  button tap. Scheduling is a future phase (§9, §13 Phase 6).
- **No desktop↔remote sync in v1.** macOS and Kotlin-desktop have **no embedded
  local catalog** today, so the local↔remote primitive has no local side. They
  receive the proto/RPC plumbing but their sync buttons are gated off until a
  desktop local-catalog concept exists (§10.5). The two buttons ship first on
  **iOS and Android**, which already embed the full core
  ([[ios-core-port]], [[android-local-rust-library]]).
- **No multi-master merge/CRDT.** Conflicts resolve by a deterministic
  precedence rule, not a merge engine (§4.5).

---

## 2. Concepts & Terminology

- **Local catalog** — the embedded SQLite catalog managed by the in-process core
  on iOS/Android, booted by `LocalCore.start()` (which is documented as
  **idempotent**) and reached over **loopback gRPC** via `VideoRepository`
  connected to `localhost:<port>`.
- **Remote catalog** — a paired daemon's catalog reached over LAN: pinned-TLS
  gRPC + the HTTPS media server, bearer-token authed (`core/src/auth.rs`,
  `is_authorized`). Identified by its TLS cert fingerprint (`GET /fingerprint`).
- **Home base** — sync always runs with the **local core as home base** (loopback
  `VideoRepository` live) and reaches the remote through a **transient** client
  built from the persisted paired endpoint. This mirrors how upload works today
  and sidesteps AppRouter's mutually-exclusive Local/Remote phases (§3).
- **Sync direction** — `TO_REMOTE` (local→remote), `FROM_REMOTE` (remote→local),
  or `MIRROR` (a from-remote pass then a to-remote pass).
- **Sync filter** — a `SmartCollectionFilters` value (the exact type the grid's
  filter UI produces) applied to the **source** catalog of a direction to select
  the eligible videos.
- **Original** vs **derived copy** — the bytes that first entered any catalog are
  the *original*; a pulled, downscaled/re-encoded device copy is *derived*
  (`videos.is_derived = 1`). This distinction is central to identity (§4).
- **Provenance link** — a `sync_links` row recording that local video X
  corresponds to remote video Y on a specific peer, plus the original's identity
  hash. Identity for derived copies is carried by this link, **not** by
  re-hashing the derived bytes (§4.2).
- **Mirror (precise definition)** — a **copy-only union**: after a mirror run,
  every source-matching video exists on both sides with catalog data reconciled,
  and **nothing is deleted on either side**. This is the only v1 behavior.

---

## 3. Connection Model — running against two catalogs at once

> This section corrects the most load-bearing assumption a naive design makes.
> It must be read before §6–§10.

### 3.1 The constraint

On iOS and Android, `AppRouter` is in **either** Local **or** Remote mode, never
both:

- `startLocal()` boots the embedded core (`LocalCore.start()`), connects
  `VideoRepository` to loopback, and sets `connection = nil`.
- A remote connect sets `connection = ConnectionInfo(...)` and **never boots the
  embedded core** this session.

So "currently connected to a remote *and* the local catalog is live" is **not a
state that exists today**. A design that assumes two simultaneous AppRouter
`connection` phases is wrong.

### 3.2 The seam that already works: transient remote client

Upload already copies a file from the *local* catalog to the *remote* without
being in remote mode: from Local mode it calls `lastPairedUploadEndpoint()`
(UserDefaults address + Keychain bearer token) and drives `UploadManager`
against a transient `MediaClient`. The local core stays the home base;
`connection` stays `nil`.

Sync generalizes exactly this:

```
        SyncManager  (client-side orchestrator, §7/§10)
        ├── localRepo : VideoRepository → loopback gRPC to the embedded core   (home base)
        └── remote    : transient pinned-TLS gRPC client  +  MediaClient (HTTPS media server)
                        built from AppRouter.lastPairedUploadEndpoint()
```

### 3.3 What the implementer must guarantee

1. **Both endpoints live for the duration of a run, regardless of current
   AppRouter phase.**
   - Ensure the embedded local core is booted before `DIFF` and kept alive
     through `RECONCILE`. `LocalCore.start()` is idempotent, so calling it to
     guarantee liveness is safe even if the user is currently in Remote mode.
   - Build the transient remote gRPC client the same way the kit's remote
     `VideoRepository` is built (pinned-TLS via cert fingerprint, bearer
     interceptor, `.ipv4`/`.ipv6` target to avoid the
     `cannotUseIPAddressInSNI` trap — see [[grpc-swift-ip-sni]]). Reuse
     `MediaClient` for bytes.
2. **Gating (corrected).** The two buttons appear when the local core is
   *bootable* **and** a paired remote endpoint is persisted — **not** when
   `connection != nil`. Add to `AppRouter`:
   ```swift
   var canSync: Bool { LocalCore.isAvailable && lastPairedUploadEndpoint() != nil }
   ```
   Android: the equivalent on `AppRouter.kt`.
3. **Resource contention.** Two SQLite files are open (loopback core + nothing
   else local; the remote's DB is the daemon's). The **local** core does **no
   transcoding** during pull (mobile native transcode is deferred — Android
   `transcodeProxy` returns -1; iOS deferred), so there is no local ffmpeg
   contention; all transcoding happens on the **remote daemon** and is bounded
   by its existing `acquire_ffmpeg_permit` pool. Cap concurrent transfers
   (default 2) so a sync run does not starve remote playback.

---

## 4. Cross-Catalog Identity, Provenance & Idempotency (the hard problem)

Two independent catalogs assign their own UUID `video_id`s, and a pulled device
copy is **deliberately a different file** (re-encoded/downscaled) than the remote
original. We must recognize "local X **is** the synced counterpart of remote Y"
even though their bytes, size, codec, and resolution all differ. The existing
filename+size heuristic (`db.find_location_by_filename_size`) is useless here
**because sync changes exactly those properties.**

The key correction over a naive design: **content identity and provenance are two
different things, and a derived copy is matched by provenance, never by
re-hashing its own bytes.**

### 4.1 Original content identity (verifiable, original side only)

- **New column `videos.content_hash TEXT`** — the hash of **this file's own
  bytes**, populated **only for non-derived originals** (`is_derived = 0 AND
  proxy_of IS NULL`). Do **not** repurpose the existing `videos.hash UNIQUE`
  column (it is `UNIQUE` and always NULL today; a non-unique new column avoids a
  collision constraint when, e.g., two catalogs legitimately hold the same
  original). `content_hash` is **non-unique** and **indexed**.
- **Algorithm:** `blake3` over `first 8 MiB ‖ last 8 MiB ‖ total length` — a
  *sparse* hash, to avoid full reads of multi-GB ProRes on a slow NAS
  ([[catalog-reindex-cost]]). **This is a weak identity signal** (two files with
  identical container head/tail/length can collide; any re-mux changes it), so a
  hash hit is treated as a *candidate*, corroborated before adoption (§4.3).
- **Module:** put this in a **new `core/src/content_hash.rs`**. Do **not** add it
  to `core/src/identity.rs` — that module is exclusively the **TLS cert/identity**
  (verified) and is the wrong home.
- **When computed:** lazily on the side holding the original — desktop daemon via
  a post-index pass (`phase = 'hashing'`, only rows where `content_hash IS NULL
  AND is_derived = 0 AND proxy_of IS NULL`); mobile during the export step of a
  push (hash the export stream in one pass). Hashing must be **bounded and run
  before/independently of byte transfer** (§4.4).

### 4.2 Provenance link (carries identity to the derived side)

A pulled file's own bytes will **never** hash to the original's `content_hash`
(it was re-encoded). Identity for derived copies is therefore carried, not
recomputed:

- **New column `videos.origin_hash TEXT`** on the derived row = the **original's**
  `content_hash`, delivered over the wire in the manifest (§6) and stamped at
  ingest. This is a **provenance label**, not a hash of local bytes.
- **New table `sync_links`** records the correspondence and derivation. Created
  via the migrations array in `core/src/db.rs` (same pattern as existing
  migrations):

```sql
CREATE TABLE IF NOT EXISTS sync_links (
  id              TEXT PRIMARY KEY,            -- uuid
  local_video_id  TEXT NOT NULL,              -- this catalog's videos.id
  peer_key        TEXT NOT NULL,              -- remote daemon's TLS cert fingerprint (hex)
  remote_video_id TEXT NOT NULL,              -- the peer catalog's videos.id
  origin_hash     TEXT,                       -- the ORIGINAL's content_hash (same on both sides)
  is_derived      INTEGER NOT NULL DEFAULT 0, -- 1 = this side holds a transcoded/downscaled copy
  derived_height  INTEGER,                    -- actual height of the derived copy (NULL for original)
  last_synced_ms  INTEGER NOT NULL,
  local_rev_at_sync  INTEGER NOT NULL DEFAULT 0,  -- local catalog-data rev seen at last sync (§4.5)
  remote_rev_at_sync INTEGER NOT NULL DEFAULT 0,  -- remote catalog-data rev seen at last sync
  UNIQUE(peer_key, remote_video_id),
  UNIQUE(local_video_id, peer_key)
);
CREATE INDEX IF NOT EXISTS idx_sync_links_origin ON sync_links(origin_hash);
CREATE INDEX IF NOT EXISTS idx_sync_links_peer   ON sync_links(peer_key);
```

`peer_key` is the remote's pinned TLS cert fingerprint — the daemon's stable
identity, already advertised via mDNS / `GET /fingerprint` and already persisted
client-side.

### 4.3 Already-synced detection (resolution order)

For each source video `S` for a direction, decide whether a counterpart exists on
the destination by checking, **in order**:

1. **Direct link** — a `sync_links` row for `(peer_key, S.remote_video_id)`
   (from-remote) or `(local_video_id = S.id, peer_key)` (to-remote). → already
   synced; only reconcile catalog data (§4.5). *This is the primary path for
   derived copies.*
2. **Origin-hash re-adoption (survives reinstall / lost local catalog)** — query
   the destination's `LookupByContentHash([S.origin_hash or S.content_hash])`
   (§6.1). The destination matches only **non-derived originals**
   (`is_derived = 0 AND proxy_of IS NULL`) so a proxy/derived row can never be
   returned as a hit. Corroborate a hit with `duration_ms` **and**
   `creation_date` (and `size_bytes` for byte-identical push) before accepting —
   the sparse hash alone is not authoritative. → adopt: create the `sync_links`
   row, **no file copy**.
3. **Byte-identical hash match (push only)** — when pushing a local *original*,
   the remote may already hold the exact same bytes (e.g. pushed earlier from
   another device); the remote's `content_hash` of its own bytes will match. →
   adopt, no copy.
4. **Filename+size fallback** — only when no hash exists yet (legacy rows). Low
   confidence → record as an **ambiguous** match surfaced to the user, never
   auto-merged.
5. **No match** → `COPY_NEW` (push §7.4 / pull §7.5).

> **Why step 2 is provenance, not content verification:** a reinstalled device
> has lost `sync_links` and holds derived bytes that don't hash to the original.
> But the derived row still carries `origin_hash`, and the *remote still holds
> the original* whose `content_hash == origin_hash`. So the device re-adopts by
> matching its stored `origin_hash` against the remote's `content_hash` — never
> by re-hashing its own derived bytes. Document this explicitly so no one
> "fixes" it into a (broken) re-hash-local-bytes check.

### 4.4 Lazy-hash handling in the plan

Hashing a whole filtered set synchronously inside a run can be slow on a NAS, and
the manifest may emit rows whose `content_hash` is not yet computed. The planner
must **not** classify a not-yet-hashed row as `COPY_NEW` (that risks duplicate
copies). Instead:

- The manifest marks such rows `hash_pending = true`.
- The planner **defers** `hash_pending` items to a follow-up pass and triggers
  hashing (bounded) first; only after a content_hash exists are they classified.
- Hashing runs **before/independently of** the byte-transfer phase so a long
  transfer never blocks behind it.

### 4.5 Catalog-data conflict resolution

Catalog data lives in **separate tables** (`video_tags`, `video_user_marks` for
rating/color, `video_notes`, `collection_members`, group tables), so a single
"video changed" signal must be maintained deliberately:

- **New column `videos.marks_rev INTEGER NOT NULL DEFAULT 0`.** Every mutation
  that changes a video's catalog data bumps the `marks_rev` of **all affected
  video rows**. Implement via a single helper `db.bump_marks_rev(&[video_id])`
  called from each mutation handler, **or** (preferred, lower-risk) via SQLite
  **triggers** on the junction tables so no call site can be forgotten.
  Enumerate and cover **all** of:
  - tag add / remove (`tag_videos`, untag) — bump **every** affected video
  - rating, color label (`video_user_marks`)
  - notes (`video_notes`)
  - capture-date override, GPS override (`metadata` user-edits)
  - group add/remove, preferred change — bump **all member** videos
  - proxy link change
  - manual collection add/remove — bump **all member** videos
  - Smart-collection edits change no video row → tracked at the collection level
    (see below), not via `marks_rev`.
  Add a Rust unit test asserting each handler/trigger bumps as expected.

- **`marks_rev` is per-catalog-local and starts at 0 independently on each
  side.** It is therefore meaningful **only for "did *this* side change since
  last sync"** (`current local marks_rev != sync_links.local_rev_at_sync`), and
  it is **meaningless to compare numerically across sides.** Do not write
  "higher marks_rev wins."

- **Resolution policy:**
  - Only one side changed since last sync → that side wins; apply to the other.
  - Both changed (true conflict):
    - In a **directed** pass (`TO_REMOTE`/`FROM_REMOTE`) → the **source** of that
      direction wins.
    - In **MIRROR** → a **fixed precedence: the remote/archive is canonical**
      (the remote daemon is the long-term library; the device is a
      space-constrained mirror). No wall-clock comparison (clock skew is real
      across device/daemon).
  - Log every conflict; surface a **count** in the run result, not a blocking
    merge UI (v1).

- **Smart-collection change tracking:** track a `collections.updated_at`-style
  rev per collection; reconcile smart collections by name when changed (§5.3).

### 4.6 Deletes are out of scope for v1 (and why)

- `delete_video` is a **hard `DELETE FROM videos`** (verified) — there is no
  soft-delete to reuse.
- There is **no tombstone table**, so a sync cannot distinguish "deleted on
  source" from "never existed" / "filtered out" without a full set-diff against a
  deletion record that doesn't exist.
- A **filtered** profile makes this worse: a video that drops out of the filter
  (e.g. rating lowered below the threshold) is *absent from the manifest* —
  identical-looking to a deletion. Treating absence as deletion would silently
  destroy data.

Therefore **v1 never deletes**, and there is **no `propagate_deletes` flag**.
The future design (Phase 6) requires: a `videos.deleted_at` tombstone written by
`delete_video`, a manifest field marking tombstoned rows, a planner pass diffing
`sync_links` against the live **unfiltered** source set, and soft-delete
(`is_online = 0`) on the destination only when the source row is genuinely
tombstoned — never inferred from a filtered manifest.

### 4.7 Re-sync idempotency (recap)

`UNIQUE(peer_key, remote_video_id)` + `INSERT OR IGNORE` make link creation
idempotent under retries; the file copy is skipped when step 1/2/3 hit;
catalog-data reconcile is skipped when neither side's `marks_rev` changed; a
re-pull at a different height checks the link **before** download/ingest, so it
never creates a second `videos` row.

---

## 5. Catalog-Data Preservation

### 5.1 What transfers

For each synced video, transfer the **user-applied** catalog data (technical
FFprobe metadata is re-extracted on the destination — see §5.4):

| Data | Source tables | Destination write path |
|------|---------------|------------------------|
| Tags / keywords (+ color) | `video_tags` + `tags` | `create_tag` (match by name) + `tag_videos` |
| Manual collection membership | `collection_members` + `collections` | `add_to_collection` (match by name, §5.2) |
| Smart collections | `collections.filter_json` | recreate by name with rewritten tag refs (§5.3) |
| Rating | `video_user_marks.rating` | `UpdateVideoRating` |
| Color label | `video_user_marks.color_label` | `UpdateVideoColorLabel` |
| Notes | `video_notes` | `UpdateVideoNotes` |
| Capture-date override | `metadata.creation_date` | `UpdateVideoCaptureDate` |
| User-edited GPS | `metadata.gps_*` | `UpdateVideoLocation` |
| Group / stack membership | group tables + `videos.group_id`/`position` | `CreateGroup` + `SetGroupPreferred` |
| Proxy links | `proxy_links` | `SetProxyOf` |

The transfer object is `VideoCatalogData` (§6.1). Always insert/match the video
**before** writing its groups/proxies so forward references resolve.

### 5.2 ID mapping across two independent catalogs

UUIDs are catalog-local. Map by **stable natural key**:

- **Tags — safe.** `tags.name` is `UNIQUE` (verified). Build `src_tag_id →
  dst_tag_id` by listing the destination's tags and `create_tag` for missing
  ones (preserve color). Used both for tag membership and smart-collection
  rewriting (§5.3).
- **Collections — match by name, but resolve clashes with the user (no UNIQUE
  migration).** `collections.name` is **not unique** (verified: only a plain
  `idx_collections_name`, plus `create_collection` does a bare `INSERT`).
  Per the product owner, the design does **not** add a `UNIQUE(name)`
  constraint and does **not** silently auto-merge or auto-pick the oldest.
  Instead, name clashes are **surfaced to the user** for a decision (§5.2.1).
- **Videos** — map via `sync_links` (§4).
- **Groups** — match by `(name, base_name)`; if a group's preferred member was
  filtered out of the sync set, let the destination's representative filter pick
  `MIN(id)` and log it.

### 5.2.1 Collection-name clash resolution (user-decided)

Before the catalog-data apply phase, the engine computes the collection-name
correspondences between the two sides (via `ListCollections` on each) and flags a
name as **clashing** when either:
- a source collection's name already exists on the destination (it may be "the
  same" collection conceptually, or a coincidental same name), **or**
- **either side already holds multiple** collections with that name (ambiguous
  target).

Each flagged name is presented in a pre-flight **Collection Conflicts** review
(part of the run's review screen, §10.2) where the user picks one of:

- **Combine** — treat them as one: members are merged into the chosen destination
  collection (no rename, no duplicate created).
- **Rename** — keep them distinct by renaming the source or the destination
  collection; a separate destination collection is then created/used.
- **Skip these videos now** — do **not** sync membership for videos in the
  clashing collection on this run. The **videos themselves still sync**; only
  their membership in that one clashing collection is withheld (re-runnable
  later).

Names with **no** clash map/create silently (this is the common case). Smart
collections clash on name the same way and use the same review. Manual and smart
collections are listed separately in the review so the user knows which is which.

Persist the user's per-name choices on the profile (a `collection_resolutions`
JSON map `name → {action, renameTo?}`) so a re-run does **not** re-prompt for
already-decided names; only a newly-appearing clashing name reopens the review.
Tags are unaffected (auto-mapped by their unique name).

### 5.3 Smart collections — rewrite on the client, treat as opaque in Rust

`filter_json` references **tag UUIDs** that differ across catalogs, and this JSON
has a **history of cross-implementation parser drift** (see
[[smart-collections-client-side-resolution]] and the macOS strArray key-name
leak bug). Do **not** introduce a third (Rust) parser.

- The **client** (which already owns a tested `SmartCollectionFilters` parser in
  Swift, and the Kotlin equivalent) rewrites `tagIds` through the
  `src_tag_id → dst_tag_id` map and re-serializes via `toJson()`.
- The already-rewritten `filter_json` is sent to the destination and stored via
  `CreateCollection(is_smart = true, filter_json = ...)`.
- The **Rust core treats `filter_json` as an opaque blob** (exactly as it does
  today — the core never parses it). No membership rows are written; destination
  clients expand the rewritten JSON themselves.
- If a server-side rewrite is ever unavoidable, ship shared **golden round-trip
  test vectors** exercised in CI across Swift, Kotlin, and Rust.

### 5.4 Derived copies carry their own technical metadata

A pulled, downscaled copy is genuinely a different file, so the destination
**re-extracts** its technical metadata on index. Consequence: the device row may
show `1080p H.264` while the remote original shows `4K ProRes`. This is correct,
but must be **made visible**: surface `is_derived` in `VideoSummary` (a new
field) so clients can badge a "synced copy" and **exclude derived rows from
full-resolution facets** and from being treated as originals. (Android loudness
extraction stays deferred — accept empty there, per [[loudness-graph-ffmpeg-ebur128]].)

---

## 6. Transcode-for-Target-Device (Pull & Mirror's mobile copy)

Use cases 2/3 require the file landing on the device to be **playable there**,
possibly downscaled/re-encoded, while remembering it is derived from the remote
original.

### 6.1 Reuse the daemon's rendition pipeline; never transcode on the device

The daemon already produces device-playable renditions: `GET /video/{id}?height=H`
runs `probe_playable()` and, if needed, `ensure_downscaled()` to H.264 8-bit
`yuv420p`, preferring a streamable proxy via `closest_streamable_proxy()`
(`media_server.rs`). It also **promotes** a durable proxy so a re-pull is a cheap
copy-mux (the `has_streamable_proxy` gate — see [[hls-proxy-promotion-and-visibility]]).
Mobile native transcode is deferred, so:

1. Pull asks the **remote daemon** to prepare the rendition at the device's
   target height (`PrepareRendition`, §7.1), warming the server-side transcode +
   proxy promotion **before** the byte download.
2. Pull then `GET /video/{id}?height=H` (range-capable), **copies the bytes out**
   to a persistent app directory (not the purgeable cache — like
   `OfflineLibrary`'s copy-out), and **ingests the file into the local core**
   (§9.2).

### 6.2 Target-height policy (per-device profiles)

Per the product owner, sync profiles are **per-device**: each device tunes its
own `target_height` (and direction/filter) independently against the same remote.
This is naturally the case because profiles live in the **on-device local
catalog** (§11) — an iPhone profile can target 720p while an iPad targets 1080p
for the same remote + filter, with no shared/global setting to reconcile.

- Per-profile `target_height`, default **1080**. `0` = original (only honored
  when the daemon's `probe_playable()` says the original is directly playable).
- Because profiles are device-local, a remote that is pulled to multiple devices
  ends up with one derived copy *per device* at each device's chosen height; the
  `sync_links` rows are `peer_key`-scoped per device, so the devices don't
  interfere (§4.2). Stamp the profile's `device_label` on the run so the remote's
  logs/UI can attribute a pushed/derived set to the originating device.
- `closest_streamable_proxy` picks the nearest existing proxy ≥ target, else the
  daemon transcodes.
- Record the **actual delivered height** in `sync_links.derived_height` and set
  `is_derived = 1`, so a later mirror-back never treats the downscaled copy as
  the original (§7.6).

### 6.3 The pulled file is `source_kind = path`, marked derived — NOT a new source_kind

`source_kind` has exactly **3 values** today: `0 = path`, `1 = photo-asset`,
`2 = bookmark` (verified in `core/src/embed.rs::source_kind_and_id`). A pulled
file is a genuine **on-device filesystem path**, so it is ingested as
`source_kind = path (0)`. **Do not invent `source_kind = 'synced'`** — that would
ripple into `prune_photos` and every source-kind-scoped sweep.

Synced/derived-ness is recorded by the **new `videos.is_derived`/`origin_hash`
columns + the `sync_links` row**, not by `source_kind`. Confirm no source-kind
sweep touches these rows (they're `path`, like any other on-device file). Idempotency
is enforced by checking `sync_links` **before** download/ingest, never by path
uniqueness.

### 6.4 ProRes RAW pull caveat

`ffmpeg` cannot *develop* ProRes RAW; distinct-frame work needs the macOS-only
`rv-frameshot` helper ([[prores-raw-thumbnails-avfoundation]]). On a **non-macOS
daemon, a ProRes RAW pull may be impossible** (no decode → no H.264 rendition),
not merely thumbnail-degraded. `PrepareRendition` must **detect this and return a
clear `FAILED` ("cannot transcode ProRes RAW on this daemon")** rather than
implying playback always succeeds. On a macOS daemon, pull works.

---

## 7. Protocol / API Design

Two transports already exist and are reused: **gRPC** carries the plan and
catalog data (typed, streaming progress); the **HTTPS media server** carries
bulk bytes (already chunked-streaming + HTTP range). Edit
`core/proto/reelvault.proto` and regenerate stubs (§11.3). `core/build.rs`
already compiles the proto for Rust via `tonic_build`.

### 7.1 New gRPC RPCs (add to `service ReelVault`)

```proto
// --- Catalog Sync ---
rpc GetSyncManifest      (SyncManifestRequest)     returns (stream SyncManifestEntry);
rpc LookupByContentHash  (LookupByContentHashRequest) returns (LookupByContentHashResponse);
rpc GetVideoCatalogData  (VideoCatalogDataRequest)  returns (VideoCatalogData);
rpc ApplyVideoCatalogData(ApplyCatalogDataRequest)  returns (Response);
rpc PrepareRendition     (PrepareRenditionRequest)  returns (stream PrepareRenditionProgress);
```

```proto
message SyncManifestRequest {
  ListVideosRequest filter = 1;     // exactly the grid's filter fields
  string smart_filter_json = 2;     // optional smart-collection JSON (client pre-expands; core opaque)
  string cursor = 3;                // stable pagination (video_id ASC), §8.4
  int32  page_size = 4;
}
message SyncManifestEntry {
  string video_id            = 1;
  string content_hash        = 2;   // empty when hash_pending
  bool   hash_pending        = 3;   // §4.4
  int64  marks_rev           = 4;
  int64  size_bytes          = 5;   // ORIGINAL size (NOT the rendition size — §8.6)
  int64  duration_ms         = 6;   // corroborator for hash adoption (§4.3)
  int64  creation_date_ms    = 7;   // corroborator
  string filename            = 8;
  int32  width               = 9;
  int32  height              = 10;
  string codec_video         = 11;
  bool   is_derived          = 12;  // never offered as an original
  bool   has_streamable_proxy= 13;
  string next_cursor         = 14;  // last entry of a page carries the cursor
}

message LookupByContentHashRequest { repeated string content_hash = 1; }
message LookupByContentHashResponse {
  message Hit {                      // ONLY non-derived originals (proxy_of IS NULL AND is_derived=0)
    string content_hash = 1; string video_id = 2;
    int64  marks_rev = 3; int64 duration_ms = 4; int64 creation_date_ms = 5;
  }
  repeated Hit hits = 1;
}

message VideoCatalogData {          // mirrors §5.1
  repeated string tags        = 1;  // tag names
  repeated string tag_colors  = 2;  // parallel to tags
  repeated string collections = 3;  // manual collection names
  repeated SmartCollectionSpec smart = 4;  // name + already-rewritten filter_json (§5.3)
  int32  rating = 5;
  string color_label = 6;
  string notes = 7;
  int64  creation_date_ms = 8;
  double gps_lat = 9; double gps_lon = 10; bool has_gps = 11;
  int64  marks_rev = 12;
  GroupSpec group = 13;             // name/base_name/preferred(origin_hash)
}
message SmartCollectionSpec { string name = 1; string filter_json = 2; }

message PrepareRenditionRequest { string video_id = 1; int32 target_height = 2; }
message PrepareRenditionProgress {
  enum Phase { QUEUED=0; TRANSCODING=1; PROMOTING=2; READY=3; FAILED=4; }
  Phase  phase = 1;
  float  fraction = 2;
  string detail = 3;
  int32  ready_height = 4;          // actual height available to download
  int64  rendition_bytes = 5;       // ACTUAL post-transcode size for the storage pre-flight (§8.6)
  string download_path = 6;         // "/video/{id}?height=H" to GET
}
```

`GetSyncManifest` and `PrepareRendition` use the existing streaming pattern
(`ScanLibrary → stream ScanProgress`, `SubscribeCatalogEvents`). Note the Swift
`has_*` → `_p` field-name quirk (see [[swift-proto-has-field-p-suffix]]) — name
the bool `has_gps` deliberately and expect `hasGps_p` in Swift, or pick a
non-`has_` name.

### 7.2 Extend the HTTPS media server (`core/src/media_server.rs`)

**Push with catalog data + resumable, integrity-checked upload.** The current
`POST /upload` streams to a random `{uuid}.part` then atomic-renames into the
import dir and indexes immediately — "single-shot, no resume." Replace with:

- **Up-front declaration.** Client opens an upload with `?upload_id=<uuid>
  &content_hash=<hex>&total_size=<bytes>&marks_rev=<n>`. Server records the
  expected `content_hash` + `total_size` for that `upload_id`.
- **`POST /upload/{upload_id}/status`** → returns bytes-received so the client can
  resume from the right offset. Use a **deterministic** `{upload_id}.part` and a
  **per-`upload_id` lock** (reuse the `transcode_locks` pattern) so concurrent
  requests/retries can't interleave writes.
- **Append with validation.** On each `?offset=` append, **reject** if the
  on-disk `.part` length ≠ the client-supplied offset (prevents silent
  interleave/corruption).
- **Integrity on completion.** When `total_size` bytes are present, **verify the
  assembled file's `content_hash` equals the declared hash** before atomic-rename
  + index. Mismatch → reject (client re-uploads). TTL-expire stale `.part`
  files.
- On successful index, persist `content_hash` and return `{video_id,
  content_hash}`.
- **`POST /upload/{video_id}/catalog-data`** (JSON body = `VideoCatalogData`) →
  applies catalog data via the same logic as `ApplyVideoCatalogData`. Kept
  **separate** from byte upload so a metadata-only reconcile needs no transfer
  and partial failures are independently retryable.

**Pull download** already exists (`GET /video/{id}?height=H`, range-served) — no
change beyond ensuring `content_hash` is set on the original before serving so
the client can stamp `origin_hash`.

All new endpoints reuse `is_authorized` (`auth.rs`) and pinned TLS. Bytes stay on
the media server; gRPC stays for the (small) plan + catalog data.

---

## 8. Sync Engine Algorithm

New Rust module **`core/src/sync/`** (`mod.rs`, `planner.rs`, `executor.rs`)
provides the **server-side helpers** each side exposes
(`GetSyncManifest`, `LookupByContentHash`, `Get/ApplyVideoCatalogData`,
`PrepareRendition`) plus the embedded-core ingest entrypoint (§9). The
**orchestration runs client-side** in `SyncManager` (§10.1) — the device drives
its loopback local core and the transient remote client (§3), matching the
existing upload/offline model.

### 8.1 Pipeline (per direction)

```
DIFF  →  PLAN  →  EXECUTE (batched, resumable)  →  RECONCILE LINKS
```

**DIFF**
1. Resolve the filter on the **source** catalog (to-remote: source = local core;
   from-remote: source = remote daemon): `GetSyncManifest(filter, cursor)` →
   paginated stream of `SyncManifestEntry`.
2. Ensure `content_hash` for source entries; `hash_pending` rows are deferred
   (§4.4).
3. Batch hashes → `LookupByContentHash` on the **destination**; combine with the
   device's local `sync_links` rows.

**PLAN** — classify each source video:
- `SKIP_FILE_RECONCILE_DATA` — counterpart exists (link or corroborated hash).
  Compare `marks_rev` vs the `sync_links` snapshot; if changed, queue a
  catalog-data reconcile (§4.5).
- `COPY_NEW` — no counterpart; queue file copy + catalog data.
- `SKIP_DERIVED` — source row is `is_derived = 1` and its original lives on the
  destination peer (don't push a downscaled copy back as an original, §7.6).
- `DEFER_HASH_PENDING` — re-evaluate after hashing.
- `AMBIGUOUS` — only a fuzzy filename+size match; surface to user, default skip.

**Resolve collection-name clashes (before EXECUTE).** After classification, the
planner computes collection-name correspondences between the two sides and
applies the user's saved `collection_resolutions` (§5.2.1). Any newly-clashing
name with no saved decision blocks the run at the **review screen** until the
user chooses Combine / Rename / Skip; the chosen actions are folded into the
catalog-data apply step (a `Skip` simply omits that collection from each video's
`VideoCatalogData.collections`). This runs once per plan, not per video.

Plans persist to the **local** catalog (the device owns the plan) for crash
resume:

```sql
CREATE TABLE IF NOT EXISTS sync_runs (
  id TEXT PRIMARY KEY, profile_id TEXT, peer_key TEXT, direction TEXT,
  started_ms INTEGER, finished_ms INTEGER,
  state TEXT,                       -- planning|running|paused|done|failed|cancelled
  total INTEGER, completed INTEGER, failed INTEGER, conflicts INTEGER
);
CREATE TABLE IF NOT EXISTS sync_run_items (
  run_id TEXT, source_video_id TEXT, action TEXT,
  status TEXT,                      -- pending|inflight|done|failed|skipped
  content_hash TEXT, bytes_done INTEGER, last_error TEXT,
  PRIMARY KEY(run_id, source_video_id)
);
```

**EXECUTE** (concurrency capped, default 2):
- **to-remote (push):** for `COPY_NEW`: export/materialize the local file
  (`LocalVideoExport` on iOS, the Android `MediaStore` equivalent — Photos-sourced
  only in v1, matching today's upload limitation), `POST /upload` (resumable +
  integrity, §7.2), then `POST /upload/{video_id}/catalog-data`. For
  `SKIP_FILE_RECONCILE_DATA`: catalog-data POST only. Record/refresh `sync_links`.
- **from-remote (pull):** for `COPY_NEW`: `PrepareRendition(video_id,
  target_height)` → await `READY` (or surface `FAILED`, §6.4) → free-space
  pre-flight using `rendition_bytes` (§8.6) → `GET /video/{id}?height=H`
  (resumable range) → copy out → ingest into the local core via `ingest_synced`
  (§9.2, stamps `origin_hash`/`is_derived`/`derived_height`) → `GetVideoCatalogData`
  from remote → apply on the local core. Record `sync_links`.
- **mirror:** run a **from-remote pass, then a to-remote pass** (pull first so the
  device holds the union, then push device-only items). Conflict precedence per
  §4.5 (remote canonical).

**RECONCILE LINKS** — update `last_synced_ms`, `local_rev_at_sync`,
`remote_rev_at_sync` on each touched `sync_links` row.

### 8.2 Filter application

The filter is the existing `SmartCollectionFilters` / `ListVideosRequest` set.
The source applies it via `db.build_filter_clauses` inside `GetSyncManifest` —
**identical** semantics to the grid (the representative filter collapses groups
and hides proxies; see [[client-filter-check-mirrors-daemon]]). A profile
"tagged *Vacation*, rated ≥3" therefore syncs exactly the grid's representative
set. When transferring a group, the planner expands its members via
`ListGroupMembers` so members behind the representative come along.
`GetSyncManifest`/`LookupByContentHash` are constrained to representative,
**non-derived** rows (`proxy_of IS NULL AND is_derived = 0`) so a proxy is never
offered/adopted as an original.

### 8.3 Resumability, partial failure, cancellation

- Each `sync_run_item` is independently retryable; bytes resume from `bytes_done`
  via the `?offset=` param after a `POST /upload/{id}/status` check (push) or
  HTTP range (pull).
- A failed item marks `failed` + `last_error`; the run **continues** (partial
  sync) and ends `done` with non-zero `failed`/`conflicts` counts surfaced in the
  result.
- Cancellation flips `state = cancelled`; in-flight transfers honor a
  cancellation token; the `.part` is kept for a future resume, not deleted.
- **401 mid-run** (revoked/expired bearer token): **pause** the run
  (`state = paused`), prompt re-pair, and **resume from checkpoints** afterward —
  do not fail all remaining items. The Keychain token + `peer_key` survive a
  reinstall (see [[pairing-incoming-device-banner]]).

### 8.4 Manifest consistency under churn

The source catalog can change during a long run (the file watcher fires
add/modify/remove; broadcast lag is bounded at 256 — see
[[grid-latency-perf-diagnosis]]). Specify:
- Stable ordering by `video_id ASC` with a `cursor` (handles 100k catalogs).
- The manifest is a **point-in-time snapshot for diffing**; mid-run source
  changes are picked up on the **next** run, not the current one.

### 8.6 Storage pre-flight uses rendition size, not original size

`SyncManifestEntry.size_bytes` is the **original** size; a pulled rendition is
usually much smaller. Do the device free-space check **per item, just-in-time**,
using `PrepareRenditionProgress.rendition_bytes` (actual post-transcode size) —
not the manifest's original sizes. If free space is insufficient, stop with a
clear message (and offer a future "metadata-only pull").

---

## 9. Core (Rust) Implementation

### 9.1 New / edited Rust

- **`core/src/content_hash.rs`** (new) — `sparse_content_hash(path) -> Result<String>`
  (blake3 head+tail+length). **Not** in `identity.rs`.
- **`core/src/sync/`** (new) — `mod.rs`, `planner.rs`, `executor.rs` (server-side
  helpers behind the 5 RPCs). **No `filter_rewrite.rs`** — smart-collection
  rewrite stays client-side (§5.3); the core treats `filter_json` as opaque.
- **`core/src/service.rs`** — implement the 5 RPCs (§7.1) following the existing
  `async fn(Request<T>) -> Result<Response<T>, Status>` pattern with
  `run_blocking` for DB work; stream `GetSyncManifest`/`PrepareRendition` like
  `subscribe_catalog_events`/`scan_library`.
- **`core/src/media_server.rs`** — resumable + integrity-checked `/upload`,
  `/upload/{id}/status`, `/upload/{video_id}/catalog-data` (§7.2); set
  `content_hash` before `GET /video` serves an original.
- **`core/src/db.rs`** — migrations for `videos.content_hash`, `videos.origin_hash`,
  `videos.is_derived`, `videos.marks_rev`, `sync_links`, `sync_profiles`,
  `sync_runs`, `sync_run_items`, and `marks_rev` triggers (§4.5).
  **No `collections.name` UNIQUE migration** — collection-name clashes are
  resolved interactively (§5.2.1), not by a schema constraint. Helpers:
  `add_sync_link`, `find_sync_link`, `lookup_by_content_hash` (constrained to
  non-derived originals), `get_video_catalog_data`, `apply_video_catalog_data`
  (composes existing `create_tag`/`tag_videos`/`add_to_collection`/`update_notes`/
  `update_rating`/`set_proxy_of`). Surface `is_derived` in the `VideoSummary`
  projection (§5.4).
- **`core/build.rs`** — no change (already compiles the proto); just rebuild after
  proto edits.

### 9.2 Embedded-core FFI / JNI additions

Catalog enumeration, manifest, catalog-data read/write, and rendition prep all
flow over **loopback gRPC** to the embedded core (`VideoRepository` →
`localhost`), which is the stable, safe contract — do **not** reach across the
tokio boundary into SQLite directly. So the only new FFI/JNI needed is to **stamp
provenance at pull-ingest** in one call (avoiding a second round-trip):

- iOS: `reelvault_ingest_synced(path, filename, origin_hash, derived_height)` in
  `core/src/ios.rs` + the C header `core/include/reelvault_core.h`.
- Android: `nativeIngestSynced(...)` in `core/src/android.rs` + `ReelVaultCore.kt`.
- Both delegate to a new `embed::ingest_synced` in `core/src/embed.rs` that
  ingests as `source_kind = path (0)` (§6.3) and sets `is_derived = 1`,
  `origin_hash`, `derived_height`.

(Existing `reelvault_ingest_*`, `prune_photos`, `is_video_indexed`,
`start/stop_embedded` are unchanged.)

### 9.3 Build steps (mandatory after FFI/proto changes)

- **iOS:** rebuild `ReelVaultCore.xcframework` (FFI surface changed; output is
  gitignored — see [[ios-core-port]]); regenerate Swift stubs via
  `kit/regen-proto.sh` (needs the pinned `protoc-gen-grpc-swift` v2 — see
  [[macos-proto-regen-pinned-plugin]], [[reelvaultkit-shared-package]]).
- **Android:** rebuild the `.so` via `android/build-core.sh` (cargo-ndk, all
  ABIs; note `-P` not `-p`, and `dirs` is `None` on Android → `XDG_CACHE_HOME`,
  per [[android-local-rust-library]]). **Verify the Kotlin gRPC codegen path
  first** (§11.3).
- **Desktop daemon:** plain `cargo build`, then **restart the running daemon** —
  a stale binary is the #1 "my change doesn't work" cause (see
  [[stale-daemon-vs-build]]).

---

## 10. Client Implementation (per platform)

### 10.1 Shared model & engine

Add to **`kit/`** (serves macOS + iOS):
- `kit/Sources/ReelVaultKit/Sync/SyncManager.swift` — `@MainActor` class modeled
  on `UploadManager`: holds **both** a loopback `VideoRepository` (local core)
  and a transient remote gRPC client + `MediaClient` (§3); `@Published jobs:
  [SyncJob]`; `start(profile:)`, `pause()`, `cancel()`. `SyncJob { id, videoId,
  filename, direction, status, progress }`.
- `kit/Sources/ReelVaultKit/Sync/SyncProfile.swift` — `Codable` profile reusing
  `SmartCollectionFilters` for `filter`.
- Reuse `MediaClient` for pull bytes; extend `UploadManager` for resumable +
  integrity-checked push.

Android equivalents in **`shared/`** + **`android/`**: `shared/.../sync/SyncManager.kt`
(`StateFlow<List<SyncJob>>`), `SyncProfile.kt`, mirroring the Swift API.

> **Sendable note:** new public kit types lose implicit `Sendable` and keep
> `internal` memberwise inits — declare `Sendable` and add `public init` where the
> apps construct them ([[swift-public-loses-sendable]]).

### 10.2 The two buttons + shared filter picker

Both buttons open the **same** filter-picker UI; only the *direction* and the
*catalog the filter resolves against* differ.

- **"Sync to remote"** — filter resolves against the **local** catalog; pushes
  local-only / changed items to the remote.
- **"Sync from remote"** — filter resolves against the **remote** catalog; pulls
  remote-only / changed items to the device.
- Both gated by `canSync` (§3.3): local core bootable **and** a paired endpoint
  persisted.

**Filter picker reuses the existing grid filter UI:**
- Bind a *scoped copy* of the grid filter state (`searchQuery`,
  `filterMinRating`, `filterColorLabel`, `filterHas*`, `metadataColumns`,
  `selectedLocationPaths`, `filterTagId`, `selectedCollectionId`) and serialize
  via `currentSmartFilters` → `SmartCollectionFilters.toJson()` into the profile.
- macOS: embed `LibraryFilterBar` (`macos/ReelVault/Views/LibraryFilterBar.swift`)
  in the sync window.
- iPhone / Android phone: a mobile subset (search + presence toggles +
  rating/color + tag) in a `.medium` sheet / `ModalBottomSheet`; full editor on
  iPad / Android tablet.

After the filter, the flow shows a **review screen**: direction, the resolved
count + estimated bytes, and a **Collection Conflicts** list when any
collection-name clashes need a Combine / Rename / Skip decision (§5.2.1).
Decisions are remembered on the profile so re-runs skip straight to the run.

### 10.3 Where the buttons live

- **iOS:** the multi-select toolbar in `ios/ReelVaultiOS/Views/LibraryGridScreen.swift`
  (next to Share/Download/Upload), plus a library-menu entry when nothing is
  selected (sync the whole filtered set). Filter picker as a `.sheet` on iPhone,
  trailing panel on iPad (`horizontalSizeClass`).
- **Android:** `android/.../ui/screens/LibraryGridScreen.kt` toolbar; filter
  picker as a `ModalBottomSheet` modeled on `UploadToServerSheet.kt` — **not** a
  nav route (avoid polluting nav history, [[android-nav-back-forward-history]]).
- **macOS / Kotlin desktop:** File-menu `Sync to Remote…` / `Sync from Remote…`,
  **disabled** with a tooltip until a desktop local catalog exists (§10.5). Add
  the macOS/iPad keyboard shortcuts (the two clients' shortcut sets must stay in
  lockstep — [[client-keyboard-shortcut-parity]]).

### 10.4 Progress UI — and the iOS layout-loop hazard

Reuse the ingest/scan banner pattern: a foreground modal sheet for the active run
(like `UploadToServerSheet`), a background banner when dismissed.

> **⚠ KNOWN iOS HAZARD — read before touching the banner.** The import-banner
> reposition caused **three** separate SwiftUI layout-loop hangs and was reverted
> ([[ios-rootsplitview-panel-and-banner]]). For the sync banner: apply
> `.safeAreaInset(edge: .top)` **only to the content view *inside* the
> NavigationStack** — never around the NavigationStack and never at whole-window
> level (it floats over the UIKit nav bar and hangs). Do **not** wrap the
> NavigationStack in a `VStack` (breaks the push transition). Avoid
> `.minimumScaleFactor` on `Text` inside a `safeAreaInset` (`ResolvedStyledText`
> loop). Reuse the known-good `IngestBannerInset` shape verbatim and scope the
> banner's animation to the banner alone. See also the card-tap feedback-loop
> lesson ([[ios-custom-layout-cache-hang]]): never feed a geometry/preference read
> into a `.frame` it then re-measures.

- Android: top banner / `LinearProgressIndicator` (as in `UploadToServerSheet.kt`).
- macOS/desktop: toolbar badge or modeless window.
- Any dialog shown over the map view must be a `DialogWindow`, not an in-window
  `AlertDialog` ([[compose-dialog-over-map-needs-dialogwindow]]); use
  `requestFocusSafely()` ([[compose-focus-requestfocus-safely]]).

### 10.5 4-client parity & legitimate deviations

| Concern | iOS | Android | macOS | Kotlin desktop |
|---------|-----|---------|-------|----------------|
| Two buttons + filter picker | ✅ v1 | ✅ v1 | present, gated off | present, gated off |
| Local catalog | embedded core | embedded core | none (deferred) | none (deferred) |
| Push file export | Photos (`LocalVideoExport`) | Photos (`MediaStore`) | n/a | n/a |
| Pull ingest | `ingest_synced` | `nativeIngestSynced` | n/a | n/a |
| Editor hand-off | share sheet | share intent | drag-drop | drag-drop |

Call out in the PR (legitimate per `CLAUDE.md`): iOS/Android are the only v1 sync
clients because only they embed a local core; desktop sync waits on a desktop
local-catalog design; Photos-only export in v1 (Files/bookmark deferred, matching
current upload); sync keyboard shortcuts apply to macOS/iPad only.

---

## 11. Persisted Sync Configuration (Profiles)

A **sync profile** = `{ name, peer_key, direction, filter_json, target_height,
collection_resolutions }` (no `propagate_deletes` in v1 — §4.6). Profiles are
**device-local** (§6.2): they live in the **on-device local catalog**, so each
device owns its own set and target heights with no cross-device sharing.

```sql
CREATE TABLE IF NOT EXISTS sync_profiles (
  id TEXT PRIMARY KEY, name TEXT, peer_key TEXT, direction TEXT,
  filter_json TEXT, target_height INTEGER DEFAULT 1080,
  collection_resolutions TEXT,      -- JSON: name -> {action: combine|rename|skip, renameTo?} (§5.2.1)
  device_label TEXT,                -- attributes a run to this device on the remote (§6.2)
  last_run_ms INTEGER, created_ms INTEGER, updated_ms INTEGER
);
```

Listed/created/edited from the sync UI; **re-run manually** in v1. The peer is
resolved through `lastPairedUploadEndpoint()`. Future scheduling (§13 Phase 6)
adds a background trigger.

### 11.3 Kotlin proto codegen — verify before estimating Phase 0

`kit/regen-proto.sh` regenerates **Swift only**. **Before adding the 5 RPCs,
confirm how `android/`+`shared/` obtain gRPC stubs** (the codegen mechanism was
not confirmed during research; [[android-client]] notes grpc-okhttp). If a
`com.google.protobuf` gradle plugin chain exists, adding RPCs is a build-config
change; **if stubs are hand-written, the new messages/stubs must be authored
manually** — a materially larger Phase-0 task. Resolve this first.

---

## 12. Security & Auth

- **Transport:** reuse pinned-TLS (cert fingerprint via mDNS / `GET /fingerprint`)
  + bearer token in `Authorization: Bearer`. New gRPC RPCs run under the existing
  LAN interceptor; new HTTP endpoints call `is_authorized` (`auth.rs`). Loopback
  (local core) stays auth-exempt.
- **Identity:** `peer_key` = cert fingerprint, already pinned client-side. A
  changed fingerprint = a different/untrusted daemon → sync refuses and prompts
  re-pair; never auto-trust.
- **Consent for bulk copy:** sync is always user-initiated. The run starts with a
  **review screen** (direction, filter, estimated count + bytes, and any
  collection-name conflicts to resolve — §5.2.1). No silent background copy in v1.
- **Token lifecycle:** the Keychain bearer token + `peer_key` survive reinstall;
  a mid-run 401 pauses for re-pair and resumes from checkpoints (§8.3). No new
  credential type (no API keys/OAuth) in v1.

---

## 13. Phased Delivery Plan

Each phase is independently shippable and testable.

- **Phase 0 — Foundations.** Proto edits (§7.1) + **resolve the Kotlin codegen
  path** (§11.3). Schema migrations (`content_hash`, `origin_hash`, `is_derived`,
  `marks_rev`, `sync_links`, `sync_profiles`, `sync_runs`, `sync_run_items` —
  **no `collections.name UNIQUE`**, §5.2.1). `content_hash.rs` + lazy `phase='hashing'`
  pass. `marks_rev` triggers + unit tests. **Dual-connection plumbing** (§3):
  `SyncManager` holding loopback + transient remote; `canSync`. *Test:* migrations
  apply on a populated catalog; hashes populate; `canSync` correct in both modes;
  no behavior change.
- **Phase 1 — Push (use case 1).** Resumable + integrity-checked `/upload`;
  `/upload/{video_id}/catalog-data`; `Get/ApplyVideoCatalogData`; `SyncManager`
  push path; "Sync to remote" button (iOS + Android), no filter (whole local
  catalog). *Test:* push N Photos videos with tags/ratings/notes → all present on
  remote; re-push is a no-op via `sync_links`; killed transfer resumes and
  passes the hash check.
- **Phase 2 — Pull (use case 2).** `PrepareRendition`; pull ingest
  (`ingest_synced` stamping provenance); `LookupByContentHash`; "Sync from
  remote" button. *Test:* pull renders playable 1080p on device; re-pull
  short-circuits via link; reinstall + re-pair re-adopts via `origin_hash`
  without re-copy; ProRes-RAW-on-Linux-daemon returns a clear `FAILED`.
- **Phase 3 — Mirror (use case 3).** Two-pass orchestration + conflict resolution
  (§4.5, remote canonical). Copy-only (no deletes). *Test:* edits on each side
  converge; conflicts resolved per precedence; nothing deleted.
- **Phase 4 — Filtered (use case 4).** Wire the shared filter picker into both
  buttons; `GetSyncManifest(filter)`; client-side smart-collection rewrite. *Test:*
  a tag/rating/location filter syncs exactly the grid's representative set; a
  video that drops out of the filter is **not** deleted.
- **Phase 5 — Saved profiles.** `sync_profiles` CRUD + re-run UI.
- **Phase 6 (post-v1) — Scheduling + deletes + desktop local catalog.** Background
  trigger; tombstone-based delete-propagation (§4.6); desktop embedded core to
  unlock macOS/Kotlin-desktop sync.

---

## 14. Testing Strategy & Open Questions

### 14.1 Testing

- **Rust unit:** `sparse_content_hash` stability; `sync_links` idempotency under
  retry; `apply_video_catalog_data` ID-mapping (tags by name; collections by the
  chosen rule); `marks_rev` trigger coverage (one assertion per mutation kind);
  conflict precedence; `LookupByContentHash` excludes derived/proxy rows.
- **Integration** (loopback + a second in-process daemon as "remote"): full
  push/pull/mirror over the real gRPC + HTTPS surfaces; resume after a killed
  transfer; upload integrity-mismatch rejection; partial-failure run reports
  correct counts; reinstall re-adoption via `origin_hash`.
- **Device:** verified-on-hardware push from iPhone Photos and pull playback (per
  the [[ios-core-port]] real-iPhone precedent); Android emulator end-to-end (per
  [[android-local-rust-library]]).
- **Parity/snapshot:** the filter picker's result set matches the grid's on each
  client.
- **Layout-regression:** exercise the iOS sync banner across push/pop transitions
  to confirm no `safeAreaInset` loop (§10.4).

### 14.2 Resolved decisions (from the product owner)

These were open questions; the product owner's answers (2026-06-20) are now
binding and folded into the spec above.

1. **Pull destination → main local catalog.** Pulled videos land in the main
   on-device catalog, marked `is_derived` (§5.4, §6.3). *(Not a separate "Synced"
   collection.)*
2. **Target height → per-device profiles.** Each device tunes its own
   `target_height` (and direction/filter); profiles are device-local (§6.2, §11).
3. **Conflict UX → no conflict-review screen for now.** Catalog-data conflicts
   resolve by deterministic precedence (remote canonical) with a logged/surfaced
   **count** only — no per-conflict review UI in v1 (§4.5). *(Note: this is
   distinct from the collection-name clash review in #6, which IS interactive.)*
4. **Files/bookmark sources → undecided (deferred).** v1 stays **Photos-only**
   push (matching today's upload limitation, §8.1); Files/bookmark export remains
   an open item to revisit, not committed for v1.
5. **Smart collections → recreate by name.** Recreate smart collections by name
   with client-side tag-UUID rewrite (§5.3); they do **not** degrade to manual
   collections. Name clashes go through the same interactive review as #6.
6. **Collection-name clashes → surface to the user.** No `UNIQUE(name)` migration
   and no silent auto-merge. On a duplicate collection name the user chooses
   **Combine**, **Rename** (one or the other), or **Skip these videos now**;
   choices are remembered on the profile (§5.2.1, §8.1, §10.2).
7. **Deletes → deferred.** Copy-only in v1; delete-propagation deferred to a
   tombstone design (§4.6, Phase 6).
8. **Storage shortfall on pull → stop with a message.** No auto-trim; the run
   stops and tells the user when device free space is insufficient (§8.6).

---

### Files this feature touches (quick index)

**Rust:** `core/proto/reelvault.proto`; `core/src/service.rs`;
`core/src/media_server.rs`; `core/src/db.rs` (migrations + helpers + triggers +
`is_derived` in `VideoSummary`); new `core/src/content_hash.rs`; new
`core/src/sync/{mod,planner,executor}.rs`; `core/src/embed.rs` (+`ingest_synced`);
`core/src/ios.rs`, `core/src/android.rs`, `core/include/reelvault_core.h`;
`core/build.rs` (rebuild only).

**Swift (kit/iOS/macOS):** new
`kit/Sources/ReelVaultKit/Sync/{SyncManager,SyncProfile}.swift`; regenerated
`kit/.../Generated/reelvault.{pb,grpc}.swift` (via `kit/regen-proto.sh`);
`kit/.../Media/UploadManager.swift` (resumable + integrity);
`ios/ReelVaultiOS/Views/LibraryGridScreen.swift`; `ios/ReelVaultiOS/AppRouter.swift`
(`canSync`); a `SyncProgressInset` (reusing the `IngestBannerInset` shape) + the
run sheet; `macos/ReelVault/Views/LibraryFilterBar.swift` (embed in a sync window).

**Kotlin (shared/android):** Kotlin proto codegen (`android`/`shared`
`build.gradle.kts` — §11.3); new `shared/.../sync/{SyncManager,SyncProfile}.kt`;
`android/.../ui/screens/LibraryGridScreen.kt`; `android/.../AppRouter.kt`; a sync
`ModalBottomSheet`; `android/.../core/ReelVaultCore.kt` (+`nativeIngestSynced`).

**Build:** rebuild `ReelVaultCore.xcframework` (iOS) and the core `.so`
(`android/build-core.sh`) after FFI/proto changes; restart the desktop daemon.
