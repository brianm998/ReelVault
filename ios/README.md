<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Copyright (C) 2026 ReelVault Contributors -->

# ReelVault iOS Client

A native SwiftUI app for **iPhone and iPad** that browses, inspects, and
streams a ReelVault catalog from a `reelvault-core` daemon running elsewhere on
the local network.

Unlike the macOS and Compose Desktop clients, the iOS client is **remote-only**:
it has no local access to the original video files and does not embed the daemon.
It discovers a daemon over Wi‑Fi, connects over a fingerprint-pinned TLS channel,
and streams video downscaled to fit the device.

## How it differs from the desktop clients

These are intentional, platform-appropriate deviations (see [`../CLAUDE.md`](../CLAUDE.md)):

- **Remote-only** — talks to the daemon over the LAN; no shared filesystem, no
  local originals, no embedded core.
- **Streaming, not file access** — video is streamed (HLS) and downscaled for the
  device; thumbnails come over gRPC.
- **Share sheet instead of drag-and-drop** — export selected clips through the
  iOS share sheet; there's no editor drag-out.
- **Upload** — push full-resolution videos from Photos or Files into the catalog's
  import directory.
- **Size-class-adaptive layout** — iPad (regular width) uses a multi-column
  `NavigationSplitView`; iPhone-portrait (compact) uses sheets and a pushed detail
  screen.
- **Keyboard shortcuts apply to iPad-with-a-keyboard**, not iPhone.

## Requirements

- **iOS 18.0+** device or simulator. (grpc-swift v2 annotates its API
  `@available(iOS 18)`, so 18 is a hard floor.)
- **Xcode 16+** (Swift 6 toolchain). The app target builds in Swift 5 language
  mode; `ReelVaultKit` builds in Swift 6.
- **[XcodeGen](https://github.com/yonaskolb/XcodeGen)** (`brew install xcodegen`) —
  the `.xcodeproj` is generated from [`project.yml`](project.yml) and is **not**
  committed.
- A running **`reelvault-core` daemon with `--remote`** reachable on the same
  Wi‑Fi (see [Connecting to a daemon](#connecting-to-a-daemon)).
- Shared Swift lives in **[`ReelVaultKit`](../kit)** (consumed by path), so no
  separate dependency install is needed.

## Building

The Xcode project is generated, so run `make project` after a fresh checkout or
whenever `project.yml` changes.

```bash
cd ios

make project   # xcodegen generate  → ReelVault.xcodeproj
make build     # build for the iOS Simulator (no code signing)
make archive   # Release .xcarchive for distribution
make clean     # remove the generated project + build output
```

To run on a device, open the generated `ReelVault.xcodeproj` in Xcode, pick your
team (the project sets `DEVELOPMENT_TEAM`; override it for your own signing), and
Run. Bundle id: `com.reelvault.ios`; device family: iPhone + iPad.

## Connecting to a daemon

The iOS client never uses loopback — it reaches a daemon over the LAN. Start the
daemon in remote mode on the host machine:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

That binds a TLS gRPC port (default `50051`) and an HTTPS media server (default
`50052`), and advertises `_reelvault._tcp` over mDNS.

Connection flow in the app:

1. **Discovery** — `ServerDiscovery` browses for `_reelvault._tcp` (Bonjour) and
   falls back to a port-scan of the local subnet. Each result carries the daemon's
   cert **fingerprint** (from the mDNS TXT record, or fetched from
   `GET /fingerprint` for port-scan hits).
2. **Pinned TLS** — the client trusts *only* a cert whose SHA-256 matches that
   fingerprint (`PinnedTLS`), so the self-signed daemon cert is secure without a CA.
3. **Pairing** — the first time a device connects it must be authorized with a
   one-time **6-digit code** (5-minute TTL, single-use). Generate the code on a
   desktop client (**File → Pair a New Device**) or from the daemon log, and enter
   it on the device. The device then stores a long-lived bearer token in the
   **Keychain** (`TokenStore`) and sends it on every request thereafter.
4. **Browse + stream** — the catalog loads over gRPC; video streams from the media
   server.

> **One code per device** — the pairing code is single-use. Pairing a second
> device needs a fresh code.

## Architecture

The iOS app is a thin SwiftUI shell over **`ReelVaultKit`** (`../kit`), the
package shared with the macOS client. The kit holds the models, view-models,
gRPC client, discovery, pinned TLS, and the media layer; the app holds the iOS
views and the connection router.

```
ios/
├── project.yml                 # XcodeGen spec (.xcodeproj is generated)
├── Makefile                    # project / build / archive / clean
└── ReelVaultiOS/
    ├── ReelVaultApp.swift      # @main; dark mode + tint
    ├── AppRouter.swift         # discovering → connecting → connected / error
    ├── Info.plist              # ATS local-networking, Bonjour, usage strings
    ├── Assets.xcassets/        # opaque, square AppIcon (iOS rounds corners)
    └── Views/
        ├── ConnectionFlowViews.swift  # discovery / picker / pairing / error
        ├── ConnectedRootView.swift    # loads the catalog once connected
        ├── RootSplitView.swift        # size-class-adaptive container
        ├── LibrarySidebar.swift       # view-mode switcher + sources
        ├── LibraryTopBar.swift        # search / filter sheet / sort / size slider
        ├── LibraryGridScreen.swift    # grid|list + select / share / import toolbar
        ├── VideoGridView.swift        # adaptive grid cards (+ stacking, context menu)
        ├── VideoListView.swift        # list rows (+ stacking)
        ├── CardStatusBadges.swift     # keyword / proxy / resolution / audio badges
        ├── DetailModeView.swift       # player surface + full-screen cover
        ├── VideoDetailView.swift      # compact pushed detail + HLS StreamPlayer
        ├── InspectorPanel.swift       # metadata + proxies + status (iPad)
        ├── LibraryMapView.swift       # MapKit view of geotagged videos
        └── ImportSheet.swift / ShareExport.swift  # upload + share-sheet export
```

Shared in `ReelVaultKit` (used by this app):

- **`GridViewModel` / `DetailViewModel`** — `@Published` state, filters,
  selection, stacking, the load sequence; shared verbatim with macOS.
- **`VideoRepository`** — grpc-swift v2 client (over `GRPCNIOTransportHTTP2`)
  with the bearer-token interceptor and IP/SNI handling.
- **`ServerDiscovery`, `PinnedTLS`, `ServerEndpoint`, `PairingClient`,
  `TokenStore`, `BearerTokenInterceptor`** — discovery + secure connect + pairing.
- **`MediaClient`, `MediaCache`, `LoopbackMediaProxy`, `UploadManager`** — the
  media layer (below).

## Video playback

Large videos (4K/8K originals) are too big to stream raw, so the daemon serves a
**downscaled HLS** rendition and the client plays it progressively.

- **Pinned HLS via a loopback proxy.** `AVPlayer`'s internal networking won't
  call our `URLSession` delegate, so it can't validate the daemon's self-signed
  pinned cert directly. `LoopbackMediaProxy` runs a tiny **127.0.0.1-only**
  HTTP/1.1 reverse proxy that forwards each request to the daemon over the
  existing pinned `URLSession`; `AVPlayer` plays `http://127.0.0.1/…/index.m3u8`
  (ATS exempts loopback), so it stays on its native HTTP path.
- **Server-side rendition choice.** The daemon copy-muxes an existing H.264/HEVC
  proxy when one fits (instant), re-encodes from the closest proxy when only a
  mastering-codec (e.g. ProRes) proxy exists, and only re-encodes the original as
  a last resort. (HLS/MPEG-TS can't carry ProRes, so the *stream* is always
  H.264.) A re-encode at the configured proxy height is **promoted to a durable
  proxy** (remuxed once into an MP4 the catalog tracks), so it shows up in the
  inspector and every later play copy-muxes it instead of re-encoding.
- **Readiness gate.** For a slow (sub-realtime) re-encode, the client polls
  `/hls/{id}/{height}/status` and shows a spinner with a *"Preparing… ready in
  ~N s"* ETA until enough is buffered to play through without stalling.
- **Fallback.** If HLS isn't available, the client falls back to
  download-then-play of a cached rendition (`MediaCache`, LRU-bounded).

## Features

- Grid / List / Detail / Map view modes (mirroring the macOS client).
- Top bar: full-text search, filter sheet (rating / color / attributes /
  metadata facets), sort menu, thumbnail-size slider.
- Cards: configurable top stat band, color label, status badges, tappable rating;
  long-press menu for rating, color, and **stacking** (combine / promote / remove
  / unstack) with inline expansion.
- Inspector (iPad) / detail screens: full metadata, a **proxies** list, and a
  named **status** list.
- Full-screen playback on both iPhone and iPad.
- **Share** selected clips via the iOS share sheet; **upload** from Photos / Files.

## Troubleshooting

- **No daemon found** — confirm the daemon runs with `--remote` on the *same*
  Wi‑Fi, and that the first launch granted the **Local Network** permission
  (the app requests it for mDNS / LAN discovery). You can also enter a host,
  port, and fingerprint manually.
- **"Pairing failed"** — the code is single-use and expires in 5 minutes;
  generate a **fresh** code for each device. The daemon log states the exact
  reject reason (expired / mismatch / already consumed).
- **Video won't play** — check the daemon log for the `hls:` decision line
  (`copy-mux` vs `re-encode`) and the Xcode console for `ReelVault proxy:` /
  `ReelVault player:` lines (the latter logs the failing segment URI + HTTP
  status). A genuinely slow re-encode shows the readiness ETA rather than a dead
  spinner.

## License

ReelVault is free software under the **GNU GPL v3 or later**. Every source file
carries an SPDX identifier; see [`../LICENSE`](../LICENSE).
