# ReelVault Beta Testing Guide

Thanks for helping test **ReelVault** — a fast, native manager for large video
libraries. ReelVault is for **cataloging and discovery** (browsing, tagging,
searching, organizing), not editing. When you want to edit a clip, ReelVault
hands it off to the editor of your choice.

This guide explains the different ways ReelVault can be used, what's available
to test, and how to get each piece — with extra detail on the **mobile apps**,
which are distributed through TestFlight (iOS) and Google Play (Android).

> **This is beta software.** Expect rough edges, and please point your test
> libraries at *copies* of footage you care about, not your only originals.

---

## The big picture: three ways to use ReelVault

Everything in ReelVault is organized around a **catalog** (your library — the
"vault"). There are three ways to run it, and they mix and match freely.

```
  ┌─────────────────────────┐        ┌─────────────────────────┐
  │  1. STANDALONE           │        │  3. ON YOUR PHONE        │
  │  Mac / Linux / Windows   │        │  iPhone / iPad / Android │
  │  app + its own catalog   │        │  local catalog + browse  │
  └────────────┬────────────┘         └────────────┬────────────┘
               │                                    │
               │            catalog sync            │
               │        (videos flow both ways)     │
               └─────────────────┬──────────────────┘
                                 │   Wi-Fi (LAN)
                      ┌──────────▼───────────┐
                      │  2. SERVER / VAULT    │
                      │  ReelVault daemon on  │
                      │  a Mac/Linux/Win/NAS  │
                      │  serves over Wi-Fi    │
                      └───────────────────────┘
```

### 1. Standalone on one computer
Install the desktop app on a **Mac, Linux, or Windows** machine and point it at
folders of video. It catalogs everything locally — thumbnails, metadata, tags,
collections, search — with **no network required**. This is the simplest way to
try ReelVault.

### 2. A shared Vault over Wi-Fi (server mode)
Run the **ReelVault server** (the core daemon) on an always-on machine or NAS.
It serves your library over your **local Wi-Fi**, so other laptops, desktops,
and phones on the same network can browse the whole archive — thousands of clips
with instant thumbnails and rich metadata — while the original files stay put on
the server. Devices pair once over an encrypted (TLS) connection.

### 3. On your phone (local catalog + sync)
The **iOS and Android** apps can:
- **Browse a server** over Wi-Fi (path 2), and/or
- Keep their **own on-device catalog** of the videos on your phone — fully
  usable with no network at all.

### Catalog sync — videos move in either direction
Catalogs sync with each other over your network. Sync is **bidirectional**: a
clip added on your phone can flow to your desktop or server catalog, and clips
from the server can come down to your phone. Crucially, **each catalog stays
fully usable on its own afterward** — once a video has synced, you can browse and
play it on that device even when the other device is offline or gone entirely.
This means you can curate on the go, sync when you're home, and have your library
available everywhere without depending on any single machine being online.

---

## What's available to test

| Component | Platforms | How to get it |
|---|---|---|
| **Native desktop app** | macOS 15+ (Apple Silicon & Intel) | GitHub release — `.pkg` |
| **Cross-platform desktop app** | Windows 10/11, Linux (x64 & arm64), macOS (Apple Silicon) | GitHub release — `.exe` / `.deb` / `.pkg` |
| **ReelVault Server** (core daemon) | macOS, Linux (x64 & arm64), Windows | GitHub release — "Server" installers |
| **iOS app** | iPhone & iPad, **iOS 18+** | App Store (TestFlight for early builds) |
| **Android app** | Android **8.0+** (phone & tablet) | Google Play testing |

All four clients share the same look and workflows where the platform allows:
grid browsing, an identical metadata inspector, tags, manual and smart
collections, search, and a map view of geotagged clips. Editor hand-off differs
by platform — **drag-and-drop** on desktop/macOS, the **share sheet/intent** on
iOS/Android.

---

## Desktop & server (GitHub releases)

**Download page:** **https://brianm998.github.io/ReelVault/** — it auto-detects
your OS and points you at the right installer. (Raw files are also on the
[GitHub Releases page](https://github.com/brianm998/ReelVault/releases).)

You'll see two kinds of installer per platform:

- **Standalone** (e.g. `ReelVault-<version>-macOS.pkg`, `-Windows.exe`,
  `-Linux.deb`) — the app for everyday use on one computer (path 1). On Mac,
  prefer the **native macOS app**; it's the most polished.
- **Server** (e.g. `ReelVault-<version>-macOS-Server.pkg`,
  `-Windows-Server.exe`, `-Linux-x64-Server.deb`) — the core daemon you install
  on the machine that will host your shared Vault (path 2).

> macOS/Windows installers are signed; on first launch you may still need to
> approve the app (right-click → Open on macOS, "More info → Run anyway" on
> Windows SmartScreen).

---

## iOS app (App Store + TestFlight)

> **ReelVault for iOS is now on the App Store:**
> <https://apps.apple.com/app/id6781445679>
> Most people should just install it from there. The TestFlight steps below are
> only for testers who want early access to in-development builds.

**Requirements:** iPhone or iPad on **iOS 18 or later**.

1. Install **TestFlight** from the App Store.
2. Open the ReelVault TestFlight invitation: **`<TESTFLIGHT_PUBLIC_LINK>`**
   *(or accept the email invite sent to your Apple ID).*
3. Tap **Install** (or Update) for ReelVault in TestFlight.
4. Launch ReelVault and choose how to start:
   - **Connect to a server** — pick your ReelVault server when it's discovered
     on Wi-Fi and pair, or
   - **Start a local library** — import videos from your Photos library and
     browse them on-device.

TestFlight builds expire after 90 days; you'll get a new one each release.

---

## Android app (Google Play testing)

**Requirements:** Android phone or tablet on **8.0 (Oreo) or later**.

1. Make sure the Google account on your device has been **added as a tester**
   (ask Brian to add your Gmail address if you haven't been).
2. Open the tester opt-in link on that device: **`<PLAY_OPT_IN_LINK>`** and tap
   **Become a tester**.
3. Follow the link to **Google Play** and install ReelVault.
4. Launch it and choose how to start — exactly like iOS: **connect to a server**
   over Wi-Fi, or **keep a local catalog** of the videos on your device.

> Opt-in must use the **same Google account** signed into the device. Right after
> being added you may need to wait a few minutes for Play to show the test build.

---

## Suggested things to try

Pick whichever paths fit how you'd actually use it:

- **Standalone:** add a folder of clips, scroll the grid, open the metadata
  inspector, add a few tags, build a manual collection, then a **smart
  collection** (e.g. "4K HEVC from this month"), and try search and the map.
- **Server over Wi-Fi:** install the Server build on one machine, then connect a
  laptop and your phone to it. Confirm pairing, browsing, thumbnails, and
  playback of a large clip.
- **Local catalog on your phone:** import some phone videos, then put the device
  in airplane mode and confirm the local catalog still browses and plays.
- **Catalog sync:** add/tag a clip on your phone, sync, and confirm it appears in
  your desktop/server catalog (and vice versa). Then take one device offline and
  confirm the other catalog still has everything it synced.
- **Editor hand-off:** drag a clip out of the desktop grid into an editor; on
  mobile, use the share sheet/intent to send a clip to another app.

---

## Reporting feedback

Please file issues at **https://github.com/brianm998/ReelVault/issues** and
include:
- Which client and version (shown in the app's About/Settings).
- Your OS / device and OS version.
- Which path you were using (standalone, server over Wi-Fi, local, or sync).
- Steps to reproduce, and a screenshot or screen recording if you can.

Thanks for testing ReelVault! 🎬
