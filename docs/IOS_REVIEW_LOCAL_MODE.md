# iOS: On-Device "Local Library" Mode (App Review path)

> **Audience:** the engineer/agent building the iOS client. This document
> describes a standalone, daemon-free mode whose purpose is twofold: (1) make
> the app **fully functional for Apple's App Review** without access to a Rust
> daemon, and (2) give the shipping app **genuine standalone value**. It is the
> pragmatic, near-term slice of "use case 2" from
> [`IOS_CORE_PORT.md`](IOS_CORE_PORT.md) — it does **not** require embedding the
> Rust core.
>
> **Status:** design proposal / recommendation. Nothing here is built yet.
> Grounded in the code as of this writing; file references included so claims
> can be verified before acting.

---

## 1. The problem

The iOS client connects to the Rust daemon over gRPC
([`VideoRepository.swift`](../kit/Sources/ReelVaultKit/Networking/VideoRepository.swift),
[`ServerEndpoint.swift`](../kit/Sources/ReelVaultKit/Networking/ServerEndpoint.swift)).
The daemon runs on the **user's own Mac / LAN**, not in the cloud.

When Apple reviews the build, the reviewer has no daemon on their network, so a
remote-only app is a blank, non-functional screen. That is the textbook
**App Store Review Guideline 2.1 (App Completeness)** rejection: the reviewer
must be able to fully exercise the app.

A demo account does not solve this — that fix assumes a *cloud* backend the
reviewer can reach. There is no such server here.

## 2. Why on-device beats the alternatives

| Option | Verdict |
|---|---|
| **Public demo daemon + address in review notes** | ❌ The daemon has **no auth today** (`core/src/main.rs:44` warns "there's no auth!"), the media pipeline shells out to ffmpeg so it needs a real host, and reviewers on cellular/VPN often can't reach LAN-style services. Exposes an unauthenticated catalog to the internet. Rejection risk *and* a security problem. |
| **On-device "Local Library" mode** | ✅ Fully functional with zero network. Apple-frameworks-only (**no GPL ffmpeg linked**, side-stepping the §8.4 GPL/App-Store landmine in `IOS_CORE_PORT.md`). Adds real standalone product value. |

## 3. The rule that decides accept vs. reject

**Local Library must be a genuine default mode, not a hidden "demo mode."** Apps
whose only real functionality is a toggle that exists to pass review get
rejected under **2.3.1 (hidden/undocumented features)** and **4.2 (minimum
functionality)**.

- **First launch must land in a working local view** — never a bare
  "enter your server address" prompt. A connection screen the reviewer can't get
  past *is* the 2.1 rejection.
- **"Connect to a ReelVault Server" is an optional power-user add-on**, exactly
  like Plex / Infuse / VLC / Transmission ship remote-server features. Apple
  accepts those *because the app works without the server*.

## 4. Design — a pure-Swift local data source

This does **not** need the embedded-Rust-core port. `IOS_CORE_PORT.md` §7.1
deliberately chooses in-process gRPC to avoid maintaining a second API surface —
that is the right call for *full on-device parity*, but it is heavyweight for the
near-term goal of "give the reviewer a working app and ship a useful baseline." A
pure-Swift local repository is the 80/20.

### 4.1 Repository protocol seam

Introduce a protocol that `GridViewModel` / `DetailViewModel` depend on, with two
implementations:

- **`RemoteVideoRepository`** — the existing gRPC
  [`VideoRepository`](../kit/Sources/ReelVaultKit/Networking/VideoRepository.swift),
  unchanged.
- **`LocalVideoRepository`** — pure Swift, no daemon (below).

The view-model layer should reach for the protocol, not the concrete
`VideoRepository.shared` singleton, so the active source is swappable at runtime
(Local vs. a connected server).

### 4.2 `LocalVideoRepository`

- **Enumerate** videos via **PhotoKit** (`PHAsset`, media type `.video`) and/or
  **Files** (`UIDocumentPicker` + security-scoped bookmarks).
- **Build** `VideoSummary` / `VideoMetadata`
  ([`Video.swift`](../kit/Sources/ReelVaultKit/Models/Video.swift)) from
  `PHAsset` + `AVURLAsset`. These structs are already transport-agnostic (plain
  structs populated from proto today), so the views don't care where rows come
  from.
- **Thumbnails** via `AVAssetImageGenerator` — the still frame plus the 10 scrub
  frames. This is the same AVFoundation pattern the project already trusts for
  `rv-frameshot` (ProRes RAW frames on macOS).
- **Tags / collections / ratings / notes** in a small local store (GRDB/SQLite or
  Core Data). Provide local equivalents of the mutation methods
  (`updateVideoRating`, `tagVideos`, `createCollection`, …).

### 4.3 Field mapping (`PHAsset` / `AVAsset` → model)

| Field | Source | Notes |
|---|---|---|
| `durationMs`, `width`, `height`, `fps` | `AVAssetTrack` | Reliable. |
| `codecVideo`, `codecAudio` | `AVAssetTrack.formatDescriptions` | Reliable. |
| `creationDate` | `PHAsset.creationDate` / `AVAsset` common metadata | Reliable for camera footage. |
| `gpsLatitude/Longitude` | `PHAsset.location` | Present on most phone footage → map view + location slot light up. |
| `sizeBytes` | `PHAssetResource` / file attrs | Reliable. |
| `iso`, `aperture`, `lensModel`, `cameraModel`, `focalLengthMm` | AVMetadata common keys | **Often absent** in consumer video — that's fine; the inspector already hides absent rows. |
| `proxyCount`, `groupId`, `playableNatively`, `fullResolution` | — | Leave at defaults; these are daemon concepts. |

## 5. Review-day checklist

- **`NSPhotoLibraryUsageDescription`** in Info.plist (read-only access; the
  add-usage string is not needed).
- **Bundle 1–3 sample clips** and offer a "Load sample library" entry. App Review
  uses real devices, but don't bet the submission on the reviewer's Photos
  library having videos, or on them granting permission — guarantee content even
  if Photos access is denied. Also wire up "Import from Files."
- **App Review Notes**, roughly:
  > ReelVault is a personal on-device video catalog and works fully standalone —
  > grant Photos access or tap "Load sample library" on first launch. The
  > optional "Connect to Server" feature streams from the user's own self-hosted
  > ReelVault daemon on their local network (similar to Plex); it is not required
  > to use or review the app.
- **GPL note:** this local path links only Apple frameworks, so it does **not**
  trigger the GPL-3 / App-Store conflict from `IOS_CORE_PORT.md` §8.4. Keep any
  linked-ffmpeg work (that doc's Option A/C) out of this build.

## 6. Trade-off to acknowledge

`LocalVideoRepository` is a second, parallel implementation of the data layer —
the exact "drift / parity bugs" risk `IOS_CORE_PORT.md` §7.1 warns about. That is
an acceptable price for shipping a reviewable, useful app now. The later
embedded-core path (in-process gRPC over the native media backend) can replace
`LocalVideoRepository` if/when full feature parity with the desktop daemon is
wanted. Flag this divergence so nobody assumes in-process gRPC is the only
sanctioned on-device route.

## 7. Relationship to the phased plan

This is a lighter-weight predecessor to Phases 3–4 of `IOS_CORE_PORT.md`
(native media backend + Photos/Files ingest), implemented in **Swift instead of
Rust** and scoped to what App Review and a useful standalone baseline need. It
can ship alongside Phase 0 (remote mode) so the first submission has both: a
working local library *and* an optional server connection.
