> [English original](README.md)

# ReelVault

Een crossplatform applicatie voor videocatalogisering, geïnspireerd door Lightroom — snel,
native browsen door grote videobibliotheken.

**ReelVault is bedoeld voor het organiseren, ontdekken en beheren van video's. Het is GEEN
video-editor.**

## Overzicht

ReelVault helpt u:

- **Bladeren** door duizenden video's in een responsief, gevirtualiseerd raster met
  aanpasbare miniatuurgrootte.
- **Scrubben** op Lightroom-stijl: beweeg de muis over een miniatuur en schuif links↔rechts
  om frames over de tijdlijn te bekijken.
- **Groeperen** van verwante varianten (bijv. 4K- en 1080p-exports van dezelfde bron)
  in Lightroom-stijl stacks; markeer één als favoriet voor het raster en openen.
- **Ontdekken** van content via zoekopdrachten in volledige tekst, tagfiltering en
  dropdowns per veld (camera, lens, codec, opnamejaar, trefwoord).
- **Inspecteren** van gedetailleerde metadata: codec, resolutie, FPS, bitrate, kleurruimte,
  HDR, EXIF, GPS, camera-/lensmodel.
- **Organiseren** met vrije notities, trefwoorden en bewerkingen met meervoudige selectie.
- **Overdragen** van clips naar externe editors via slepen en neerzetten — sleep één of meer
  kaarten rechtstreeks vanuit het raster naar DaVinci Resolve, Final Cut Pro, Premiere
  Pro, of elke app die bestandssleepacties accepteert.
- **Werken met meerdere catalogi**: SQLite-catalogi openen / sluiten / wisselen via een
  Bestand-menu, met een lijst van recente catalogi en venstertitels per catalogus.

## Architectuur

```
Desktop / macOS clients          iOS / Android clients
   ↓ gRPC over loopback             ↓ gRPC + HTTPS media over the LAN
   │                                │ (mDNS/NSD discovery · pinned TLS · paired)
   └───────────────┬────────────────┘
                   ↓
        Rust Backend Daemon (reelvault-core)
                   ↓
        SQLite Catalog + FFmpeg/FFprobe + Filesystem
```

- **Rust core** (`core/`) — op Tonic gebaseerde gRPC-daemon. Beheert de SQLite-catalogus,
  FFprobe-metadata-extractie, het genereren van miniaturen en scrubframes,
  scannen/indexeren, zoeken en filteraggregatie. Ondersteunt het dynamisch wisselen van de
  actieve catalogus via de RPC's `OpenCatalog` / `CloseCatalog` /
  `GetCurrentCatalog`, zodat één daemonproces meerdere bibliotheken kan bedienen gedurende
  zijn levensduur.

- **Kotlin Compose desktop client** (`desktop/`) — Compose Multiplatform UI.
  Detecteert automatisch een actieve daemon op `127.0.0.1:50051`; als er geen actief is,
  start het de meegeleverde daemon zelf (met een door het OS toegewezen poort als
  50051 bezet is).

- **SwiftUI macOS client** (`macos/`) — native macOS-app met volledige functiepariteit,
  dezelfde automatische startflow, een echt macOS Bestand-menu (Commands-groep) en een
  reactieve venstertitel die de geopende catalogus bijhoudt.

- **SwiftUI iOS client** (`ios/`) — Een **alleen-op-afstand** iPhone/iPad-app.
  Heeft geen toegang tot lokale bestanden en bevat geen ingebouwde daemon: ontdekt een
  daemon via Wi‑Fi (mDNS), verbindt via een op vingerafdruk-gepinde TLS-verbinding na
  eenmalige koppeling, bladert via gRPC en **streamt** video (verkleinde HLS) van de
  mediaserver van de daemon. Vervangt het slepen naar de editor door het iOS-deelblad en
  voegt uploaden toe vanuit Foto's / Bestanden. Zie [`ios/README.md`](ios/README.md).

- **Kotlin Compose Android client** (`android/`) — A **remote-only**
  Android phone / tablet app. Connects to a daemon over the LAN (NSD
  discovery), streams video via ExoPlayer, and replaces editor drag-out with
  the Android share intent. Also embeds the full Rust core for on-device local
  library access — browse, catalog, and upload footage directly from the
  device. See [`android/README.md`](android/README.md).

- **ReelVaultKit** (`kit/`) — Een lokaal SwiftPM-pakket met gedeelde Swift-code die door
  **beide** Apple-clients wordt gebruikt: modellen, view-modellen, de gRPC-client, detectie,
  gepinde TLS en de media-cache/streaminglaag.

- **SQLite-catalogus** — WAL-modus database met FTS5 voor zoekopdrachten in volledige tekst.
  Het schema staat in [`core/schema.sql`](core/schema.sql).

> **ProRes RAW-miniaturen zijn alleen van macOS-kwaliteit.** ffmpeg kan ProRes RAW
> (Atomos S-Log3 / S-Gamut) niet verwerken, dus op **macOS** decodeert de daemon dit
> via QuickLook / AVFoundation — correcte kleuren en echte scrubbing per frame.
> Op **Linux / Windows** is er geen dergelijke decoder, dus valt de daemon terug op ffmpeg:
> vlakkere/donkerdere frames en één herhaald scrubframe. De Rust-core wordt op alle drie
> platforms identiek gebouwd — AVFoundation wordt er nooit in gelinkt. Details:
> [`core/README.md`](core/README.md) (build- en decodeerroutes) en
> [`macos/README.md`](macos/README.md) (de macOS-client). Elke andere codec wordt
> overal op dezelfde manier verwerkt door ffmpeg.

## Projectstatus

**MVP is functioneel op macOS en Compose Desktop.** Beide clients leveren dezelfde
functieset; de macOS-client voegt native menubalkcommando's toe en editor-opstarten via
NSWorkspace. Een **iOS-client alleen op afstand** (iPhone / iPad) maakt verbinding met
een daemon via het LAN en streamt video — bladeren, inspecteren, stapelen, delen en
uploaden; zie [`ios/README.md`](ios/README.md).

### ✅ Gereed

**Core**
- [x] gRPC-daemon met volledig RPC-oppervlak (video's, zoeken, scannen, tags,
      collecties, stacks, filters, status, configuratie, levenscyclus van catalogus).
- [x] SQLite-catalogus met WAL-modus + FTS5; dynamisch wisselen van catalogus via
      `OpenCatalog` / `CloseCatalog`.
- [x] Rich metadata extraction via FFprobe + platform-native helpers: codec,
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera.
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera.
- [x] Miniaturen en Lightroom-stijl scrubframe-generatie (10 frames per
      video) met per-video vergrendelingen om dubbel werk te vermijden.
- [x] Gelijktijdige ffmpeg-begrenzing (standaard het aantal CPU-kernen van de host) om te
      voorkomen dat scans van grote bibliotheken SAN-opslag overbelasten.
- [x] Bibliotheekscannen met optionele recursie en automatisch groeperen van varianten.
- [x] CLI-vlaggen voor `--db-path`, `--no-catalog`, `--port` (met door het OS toegewezen
      poort als terugval) en een leesbare `REELVAULT_LISTENING_ON=…` stdout-regel voor
      client-launchers.

**Beide clients**
- [x] Gevirtualiseerde rasterweergave met adaptief kolomaantal en schuifregelaar voor
      miniatuurgrootte.
- [x] Hover-scrubvoorvertoning, hover-afspeeloverlay, meervoudige selectie met
      shift-bereik en ⌘/Ctrl-wisselknop.
- [x] Stack (groep) UI: stackbadges met ledentelling, klikken om uit te vouwen,
      ster om favoriet in te stellen, open-knoppen per lid.
- [x] Zijpanelen: bibliotheeklocaties (links), details + metadata + notities +
      trefwoorden (rechts). Tab schakelt beide, individuele pijlen vouwen elk in.
- [x] Filterdropdowns in de bovenbalk (camera / lens / trefwoord / codec / jaar) —
      alleen velden met data worden getoond; AND-gecombineerd met zoeken.
- [x] Sorteermenu met alle belangrijke velden (bestandsnaam, datums, duur, grootte,
      resolutie, fps, codec, bitrate, camera, lens, trefwoord); klik opnieuw om
      de richting om te keren.
- [x] Tag/trefwoordbeheer: maak direct aan, pas toe op meervoudige selectie,
      filter het raster door op de `>`-pijl te klikken.
- [x] Rechtsklikmenu op elke kaart: Openen met standaardspeler en
      Tonen in Finder/Explorer — werkt op de volledige meervoudige selectie.
- [x] Slepen-en-neerzetten overdracht: sleep geselecteerde kaarten naar elke app die
      bestandssleepacties accepteert (DaVinci Resolve, Final Cut Pro, Premiere Pro, enz.).
- [x] Multi-catalogusstroom: Bestand-menu met Openen / Sluiten / Recent openen,
      "open een catalogus"-blad bij eerste start, permanente recentenlijst, venstertitel
      met de naam van de geopende catalogus.
- [x] Automatisch starten van de meegeleverde daemon (met poort-bezet terugval) als er
      bij het opstarten geen backend actief is.
- [x] Zweeftekst-hulp (tooltips in Kotlin via `TooltipArea`; SwiftUI via
      `.help(_:)`) op elk interactief element en metadataveld.
- [x] Donkere modus standaard; wisselknop licht/donker thema.

### 🚧 Gepland

- [ ] Real-time bestandsbewaking (herindexeren wanneer de onderliggende map wijzigt).
- [ ] Slimme collecties (opgeslagen zoekopdrachten met live filterregels).
- [ ] Proxy-videogeneratie voor 8K+ opnames.
- [ ] Automatische updates via GitHub.
- [ ] CI/CD-releasepijplijn die ondertekende installatieprogramma's per platform produceert.
- [ ] De daemon-binary bundelen in de client-appbundles (nu vindt de launcher deze via
      `REELVAULT_CORE_BIN` of een cargo-ontwikkelaarsboom).

## Aan de slag

### Vereisten

- **Rust** (1.75+ aanbevolen) — voor het bouwen van de core-daemon.
- **FFmpeg / FFprobe** — moet in `PATH` staan. Gebruikt voor metadata-extractie
  en het genereren van miniaturen/scrubframes.
- **JDK 17+** + Gradle (wrapper inbegrepen) — voor de Kotlin-desktopclient.
- **Swift 5.9+ / Xcode 15+** — voor de macOS-client.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — voor de iOS-client.

Zie [`SETUP.md`](SETUP.md) voor platformspecifieke installatie-instructies.

### De core-daemon bouwen

```bash
cd core
cargo build --release
```

De binary belandt op `core/target/release/reelvault-core`. Start hem rechtstreeks als
u hem zelf wilt aansturen, of laat één van de clients hem voor u starten bij de eerste
keer opstarten:

```bash
# Standaard — gebruik de platformstandaard catalogus op de standaardpoort.
./target/release/reelvault-core

# Start zonder catalogus (clients gebruiken deze modus); druk de gebonden poort af.
./target/release/reelvault-core --no-catalog --port 0

# Open een specifieke catalogus bij het opstarten.
./target/release/reelvault-core --db-path /path/to/library.db
```

De daemon drukt een stabiele `REELVAULT_LISTENING_ON=127.0.0.1:N` regel af op
stdout die clients gebruiken om de toegewezen poort te ontdekken.

### De Kotlin Compose desktop client uitvoeren

```bash
cd desktop
./gradlew run
```

Bij de eerste start controleert de client `127.0.0.1:50051` en — als er niets luistert —
start de meegeleverde daemon. Zoekvolgorde voor bouwlocatie:

1. `$REELVAULT_CORE_BIN` (een absoluut pad naar de daemon-executable)
2. Een binary naast de applicatie-jar
3. `core/target/release/reelvault-core` of `core/target/debug/reelvault-core`
   in de ontwikkelaarsboom
4. `reelvault-core` in `PATH`

Voor ontwikkeling voert u gewoon `cargo build` uit in `core/` en pikt de client de
debug-binary automatisch op.

### De SwiftUI macOS client uitvoeren

```bash
cd macos
swift run
```

Dezelfde automatische startflow, dezelfde zoekorde voor binaries (met de toevoeging van
een `Resources/reelvault-core`-pad in de bundle dat door ondertekende app-bundles wordt
gebruikt). Gebruik `⌘O` om een catalogus te openen en `⇧⌘W` om deze te sluiten; de
recentenlijst staat onder `File → Open Recent`.

### De SwiftUI iOS client uitvoeren

De iOS-client is **alleen op afstand** — hij verbindt via het LAN met een daemon in
plaats van er zelf een te starten. Start de daemon in externe modus op een machine in
hetzelfde Wi‑Fi-netwerk:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Genereer dan het Xcode-project (het `.xcodeproj` wordt niet bijgehouden in het archief)
en bouw:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

Bij de eerste start ontdekt de app de daemon via mDNS, autoriseert u het apparaat eenmalig
met een 6-cijferige koppelcode (genereer deze vanuit **File → Pair a New Device** in een
desktopclient, of uit het daemon-logbestand), en kunt u vervolgens bladeren en streamen.
Vereist **iOS 18+** en **Xcode 16+**. Volledige details, inclusief het streaming- en
koppelingsmodel, staan in [`ios/README.md`](ios/README.md).

## Documentatie

- [`CLAUDE.md`](CLAUDE.md) — Projectvisie, architectuurdetails, databaseschema
  en ontwerpprincipes.
- [`SETUP.md`](SETUP.md) — Instellen van de ontwikkelomgeving.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Gedetailleerde referentie voor de
  modules en het RPC-oppervlak van de Rust-core.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Vergelijking naast elkaar van de
  Kotlin- en SwiftUI-clients.
- [`ios/README.md`](ios/README.md) — De alleen-op-afstand iOS-client (iPhone / iPad):
  detectie, koppeling, gepinde TLS en HLS-streaming.
- [`macos/README.md`](macos/README.md) — De native macOS-client.

## Bijdragen

Zie [`CLAUDE.md`](CLAUDE.md) voor ontwikkelingsrichtlijnen. Pull requests zijn welkom — zorg voor functiepariteit in alle vier clients waar van toepassing
(zie CLAUDE.md voor legitieme platformspecifieke afwijkingen), en voeg SPDX-headers toe aan nieuwe bronbestanden (zie Licentie hieronder).

## Licentie

ReelVault is vrije software, gelicentieerd onder de **GNU General Public License,
versie 3 of (naar keuze) een latere versie**. De volledige licentietekst staat in
[`LICENSE`](LICENSE); een korte auteursrechtmelding staat in [`COPYRIGHT`](COPYRIGHT).

Elk bronbestand bevat een SPDX-identifier zodat licentiescanning-tools
(REUSE, FOSSology, GitHub's licensee-detector, enz.) de licentie programmatisch kunnen
identificeren:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

Als u een aangepaste versie van ReelVault distribueert — of een programma dat
de Rust-core als bibliotheek linkt — vereist de GPL dat u uw broncode beschikbaar stelt
onder dezelfde voorwaarden. Zie het LICENSE-bestand voor de volledige set verplichtingen.
