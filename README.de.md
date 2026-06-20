> [English original](README.md)

# ReelVault

Eine plattformübergreifende Videokatalogisierungsanwendung, inspiriert von Lightroom — schnelles, natives Durchsuchen großer Videobibliotheken.

**ReelVault dient zum Organisieren, Entdecken und Verwalten von Videos. Es ist KEIN Videoeditor.**

## Überblick

ReelVault hilft dir dabei:

- **Videos durchsuchen** — Tausende von Videos in einem responsiven, virtualisierten Raster mit einstellbarer Miniaturbildgröße anzuzeigen.
- **Scrubbing** im Lightroom-Stil: Bewege den Mauszeiger über ein Miniaturbild und schiebe ihn links↔rechts, um Frames entlang der Zeitleiste in der Vorschau zu sehen.
- **Gruppieren** verwandter Varianten (z. B. 4K- und 1080p-Exporte derselben Quelle) in Lightroom-artige Stapel; markiere eine als bevorzugte für das Raster und das Öffnen.
- **Inhalte entdecken** durch Volltextsuche, Tag-Filterung und feldspezifische Filter-Dropdowns (Kamera, Objektiv, Codec, Aufnahmejahr, Stichwort).
- **Detaillierte Metadaten inspizieren**: Codec, Auflösung, FPS, Bitrate, Farbraum, HDR, EXIF, GPS, Kamera-/Objektivmodell.
- **Organisieren** mit Freitext-Notizen, Stichwörtern und Mehrfachauswahl-Operationen.
- **Clips übergeben** an externe Editoren per Drag-and-Drop — ziehe eine oder mehrere Karten direkt aus dem Raster in DaVinci Resolve, Final Cut Pro, Premiere Pro oder jede andere App, die Datei-Drops akzeptiert.
- **Mehrkatalog-Workflows**: SQLite-Kataloge über ein Datei-Menü öffnen / schließen / wechseln, mit einer Liste zuletzt geöffneter Kataloge und katalogspezifischen Fenstertiteln.

## Architektur

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

- **Rust-Kern** (`core/`) — Tonic-basierter gRPC-Daemon. Verwaltet den SQLite-Katalog, FFprobe-Metadatenextraktion, Miniaturbilder- und Scrub-Frame-Generierung, Scan/Indizierung, Suche und Filteraggregation. Unterstützt den Heiß-Tausch des aktiven Katalogs zur Laufzeit über die RPCs `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog`, sodass ein einzelner Daemon-Prozess während seiner Lebensdauer mehrere Bibliotheken bedienen kann.

- **Kotlin Compose Desktop-Client** (`kotlin-desktop/`) — Compose Multiplatform-Benutzeroberfläche. Erkennt automatisch einen laufenden Daemon auf `127.0.0.1:50051`; falls keiner läuft, startet der Client den mitgelieferten Daemon selbst (mit Rückfall auf einen vom Betriebssystem zugewiesenen Port, falls 50051 belegt ist).

- **SwiftUI macOS-Client** (`macos/`) — Native macOS-App mit identischem Funktionsumfang, demselben Auto-Start-Ablauf, einem echten macOS-Datei-Menü (Commands-Gruppe) und einem reaktiven Fenstertitel, der den geöffneten Katalog anzeigt.

- **SwiftUI iOS-Client** (`ios/`) — Eine **ausschließlich remote** betriebene iPhone-/iPad-App. Sie hat keinen lokalen Dateizugriff und enthält keinen Daemon: Sie entdeckt einen Daemon über Wi‑Fi (mDNS), verbindet sich nach einmaliger Kopplung über einen per Fingerabdruck gepinnten TLS-Kanal, durchsucht über gRPC und **streamt** Video (herunterskaliertes HLS) vom Medienserver des Daemons. Ersetzt das Editor-Drag-out durch das iOS-Teilen-Menü und ergänzt den Upload aus Fotos/Dateien. Siehe [`ios/README.md`](ios/README.md).

- **Kotlin Compose Android client** (`android/`) — A **remote-only**
  Android phone / tablet app. Connects to a daemon over the LAN (NSD
  discovery), streams video via ExoPlayer, and replaces editor drag-out with
  the Android share intent. Also embeds the full Rust core for on-device local
  library access — browse, catalog, and upload footage directly from the
  device. See [`android/README.md`](android/README.md).

- **ReelVaultKit** (`kit/`) — Ein lokales SwiftPM-Paket mit gemeinsamem Swift-Code für **beide** Apple-Clients: Modelle, View-Models, den gRPC-Client, Discovery, gepinntes TLS und die Medien-Cache-/Streaming-Schicht.

- **SQLite-Katalog** — WAL-Modus-Datenbank mit FTS5 für die Volltextsuche. Das Schema befindet sich in [`core/schema.sql`](core/schema.sql).

> **ProRes RAW-Miniaturbilder haben nur auf macOS volle Qualität.** ffmpeg kann ProRes RAW (Atomos S-Log3 / S-Gamut) nicht dekodieren, daher dekodiert der Daemon es auf **macOS** über QuickLook / AVFoundation — korrekte Farbe und echtes Frame-genaues Scrubbing. Auf **Linux / Windows** gibt es keinen solchen Decoder, sodass der Daemon auf ffmpeg zurückfällt: flachere/dunklere Frames und ein einziger wiederholter Scrub-Frame. Der Rust-Kern wird auf allen drei Plattformen identisch gebaut — AVFoundation wird nie damit verlinkt. Details: [`core/README.md`](core/README.md) (Build- und Decode-Pfade) und [`macos/README.md`](macos/README.md) (der macOS-Client). Alle anderen Codecs werden von ffmpeg überall auf dieselbe Weise verarbeitet.

## Projektstatus

**Das MVP ist auf macOS und Compose Desktop funktionsfähig.** Beide Clients bieten denselben Funktionsumfang; der macOS-Client ergänzt native Menüleistenbefehle und NSWorkspace-gesteuerte Editor-Starts. Ein **ausschließlich remote** betriebener iOS-Client (iPhone / iPad) verbindet sich über das LAN mit einem Daemon und streamt Video — durchsuchen, inspizieren, stapeln, teilen und hochladen; siehe [`ios/README.md`](ios/README.md).

### ✅ Fertiggestellt

**Kern**
- [x] gRPC-Daemon mit vollständiger RPC-Oberfläche (Videos, Suche, Scan, Tags, Kollektionen, Stapel, Filter, Status, Konfiguration, Katalog-Lebenszyklus).
- [x] SQLite-Katalog mit WAL-Modus + FTS5; Laufzeit-Katalog-Heiß-Tausch über `OpenCatalog` / `CloseCatalog`.
- [x] Rich metadata extraction via FFprobe + platform-native helpers: codec,
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera.
- [x] Miniaturbilder und Lightroom-artige Scrub-Frame-Generierung (10 Frames pro Video) mit Pro-Video-Sperren zur Deduplizierung von Arbeit.
- [x] Gleichzeitige ffmpeg-Drosselung (standardmäßig auf die CPU-Anzahl des Hosts) um zu verhindern, dass Bibliotheksscans SAN-gesichertem Speicher überlasten.
- [x] Bibliotheks-Scan mit optionaler Rekursion und automatischer Variantengruppierung.
- [x] CLI-Flags für `--db-path`, `--no-catalog`, `--port` (mit OS-Port-Zuweisung als Fallback) und eine parsierbare `REELVAULT_LISTENING_ON=…`-Ausgabezeile für Client-Launcher.

**Beide Clients**
- [x] Virtualisierte Rasteransicht mit adaptiver Spaltenanzahl und einem Schieberegler für die Miniaturbildgröße.
- [x] Hover-Scrubbing-Vorschau, Hover-Abspiel-Overlay, Mehrfachauswahl mit Shift-Bereich und ⌘/Strg-Umschalten.
- [x] Stapel-UI (Gruppe): Stapelabzeichen mit Mitgliederzahl, Klick zum Aufklappen, Stern zum Festlegen als bevorzugt, Öffnen-Schaltflächen pro Mitglied.
- [x] Seitenpanels: Bibliotheksstandorte (links), Details + Metadaten + Notizen + Stichwörter (rechts). Tab schaltet beide um, einzelne Pfeile klappen jedes ein.
- [x] Filter-Dropdowns in der oberen Leiste (Kamera / Objektiv / Stichwort / Codec / Jahr) — nur Felder mit Daten werden angezeigt; UND-verknüpft mit der Suche.
- [x] Sortiermenü mit allen wichtigen Feldern (Dateiname, Datum, Dauer, Größe, Auflösung, fps, Codec, Bitrate, Kamera, Objektiv, Stichwort); erneuter Klick kehrt die Reihenfolge um.
- [x] Tag-/Stichwortverwaltung: spontan erstellen, auf Mehrfachauswahl anwenden, Raster durch Klick auf das `>`-Pfeilsymbol filtern.
- [x] Rechtsklick-Kontextmenü auf jeder Karte: Mit Standard-Player öffnen und Im Finder/Explorer anzeigen — wirkt auf die gesamte Mehrfachauswahl.
- [x] Drag-and-Drop-Übergabe: Ausgewählte Karten in eine beliebige App ziehen, die Datei-Drops akzeptiert (DaVinci Resolve, Final Cut Pro, Premiere Pro usw.).
- [x] Mehrkatalog-Ablauf: Datei-Menü mit Öffnen / Schließen / Zuletzt geöffnet, „Katalog öffnen"-Sheet beim ersten Start, persistente Liste zuletzt geöffneter Kataloge, Fenstertitel zeigt den Namen des geöffneten Katalogs.
- [x] Automatischer Start des mitgelieferten Daemons (mit Port-Belegung-Fallback), wenn beim Start kein Backend läuft.
- [x] Hover-Text-Hilfe (Tooltips in Kotlin über `TooltipArea`; SwiftUI über `.help(_:)`) bei jedem interaktiven Element und Metadatenfeld.
- [x] Standardmäßiger Dunkelmodus; Umschalter für helles/dunkles Design.

### 🚧 Geplant

- [ ] Echtzeit-Dateiüberwachung (Re-Indizierung, wenn sich der zugrunde liegende Ordner ändert).
- [ ] Smarte Kollektionen (gespeicherte Suchen mit Live-Filterregeln).
- [ ] Proxy-Videogenerierung für 8K+-Material.
- [ ] GitHub-basierte automatische Aktualisierung.
- [ ] CI/CD-Veröffentlichungspipeline, die signierte Installer pro Plattform erzeugt.
- [ ] Einbindung des Daemon-Binaries in die Client-App-Bundles (derzeit findet der Launcher es über `REELVAULT_CORE_BIN` oder einen Cargo-Entwicklungsbaum).

## Erste Schritte

### Voraussetzungen

- **Rust** (1.75+ empfohlen) — zum Bauen des Kern-Daemons.
- **FFmpeg / FFprobe** — muss im `PATH` vorhanden sein. Wird für die Metadatenextraktion und die Miniaturbilder-/Scrub-Frame-Generierung verwendet.
- **JDK 17+** + Gradle (Wrapper enthalten) — für den Kotlin Desktop-Client.
- **Swift 5.9+ / Xcode 15+** — für den macOS-Client.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — für den iOS-Client.

Plattformspezifische Installationsanweisungen findest du in [`SETUP.md`](SETUP.md).

### Kern-Daemon bauen

```bash
cd core
cargo build --release
```

Das Binary landet in `core/target/release/reelvault-core`. Führe es direkt aus, wenn du es selbst steuern möchtest, oder lass einen der Clients es beim ersten Start automatisch starten:

```bash
# Standard — verwendet den plattformvorgegebenen Katalog auf dem Standardport.
./target/release/reelvault-core

# Ohne Katalog starten (dieser Modus wird von den Clients genutzt); gibt den gebundenen Port aus.
./target/release/reelvault-core --no-catalog --port 0

# Beim Start einen bestimmten Katalog öffnen.
./target/release/reelvault-core --db-path /path/to/library.db
```

Der Daemon gibt eine stabile `REELVAULT_LISTENING_ON=127.0.0.1:N`-Zeile auf der Standardausgabe aus, die die Clients parsen, um den zugewiesenen Port zu ermitteln.

### Kotlin Compose Desktop-Client ausführen

```bash
cd kotlin-desktop
./gradlew run
```

Beim ersten Start prüft der Client `127.0.0.1:50051` und — falls nichts hört — startet den mitgelieferten Daemon. Suchreihenfolge für den Build-Speicherort:

1. `$REELVAULT_CORE_BIN` (absoluter Pfad zum Daemon-Executable)
2. Ein Binary neben der Anwendungs-JAR-Datei
3. `core/target/release/reelvault-core` oder `core/target/debug/reelvault-core` im Entwicklungsbaum
4. `reelvault-core` im `PATH`

Für die Entwicklung reicht ein `cargo build` innerhalb von `core/`, und der Client findet das Debug-Binary automatisch.

### macOS SwiftUI-Client ausführen

```bash
cd macos
swift run
```

Derselbe Auto-Start-Ablauf, dieselbe Binary-Suchreihenfolge (mit der Ergänzung eines `Resources/reelvault-core`-Pfads im Bundle, der von signierten App-Bundles genutzt wird). Verwende `⌘O` zum Öffnen eines Katalogs und `⇧⌘W` zum Schließen; die Liste zuletzt geöffneter Kataloge findet sich unter `File → Open Recent`.

### iOS SwiftUI-Client ausführen

Der iOS-Client ist **ausschließlich remote** — er verbindet sich mit einem Daemon über das LAN, anstatt selbst einen zu starten. Starte den Daemon im Remote-Modus auf einem Rechner im selben Wi‑Fi:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Generiere dann das Xcode-Projekt (das `.xcodeproj` wird nicht in die Versionsverwaltung eingecheckt) und baue es:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

Beim ersten Start entdeckt die App den Daemon über mDNS; du autorisierst das Gerät einmalig mit einem 6-stelligen Kopplungscode (generiert aus dem **File → Pair a New Device**-Menü eines Desktop-Clients oder dem Daemon-Log) und kannst dann durchsuchen und streamen. Erfordert **iOS 18+** und **Xcode 16+**. Vollständige Details, einschließlich des Streaming- und Kopplungsmodells, findest du in [`ios/README.md`](ios/README.md).

## Dokumentation

- [`CLAUDE.md`](CLAUDE.md) — Projektvision, Architekturdetails, Datenbankschema und Designprinzipien.
- [`SETUP.md`](SETUP.md) — Einrichtung der Entwicklungsumgebung.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Detaillierte Referenz für die Module des Rust-Kerns und die RPC-Oberfläche.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Vergleich der Kotlin- und SwiftUI-Clients nebeneinander.
- [`ios/README.md`](ios/README.md) — Der ausschließlich remote betriebene iOS-Client (iPhone / iPad): Discovery, Kopplung, gepinntes TLS und HLS-Streaming.
- [`macos/README.md`](macos/README.md) — Der native macOS-Client.

## Mitwirken

Entwicklungsrichtlinien findest du in [`CLAUDE.md`](CLAUDE.md). Pull Requests sind willkommen — bitte die Funktionsparität aller vier Clients aufrechterhalten, wo es angemessen ist
(zulässige plattformspezifische Abweichungen findest du in CLAUDE.md), und SPDX-Header zu neuen Quelldateien hinzufügen (siehe Lizenz unten).

## Lizenz

ReelVault ist freie Software, lizenziert unter der **GNU General Public License, Version 3 oder (nach deiner Wahl) einer neueren Version**. Der vollständige Lizenztext befindet sich in [`LICENSE`](LICENSE); ein kurzer Urheberrechtshinweis in [`COPYRIGHT`](COPYRIGHT).

Jede Quelldatei trägt eine SPDX-Kennung, damit Lizenz-Scan-Tools (REUSE, FOSSology, GitHubs licensee-Detektor usw.) die Lizenz programmatisch identifizieren können:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

Wenn du eine modifizierte Version von ReelVault verteilst — oder ein Programm, das den Rust-Kern als Bibliothek einbindet — verlangt die GPL, dass du deinen Quellcode unter denselben Bedingungen verfügbar machst. Den vollständigen Satz an Verpflichtungen findest du in der LICENSE-Datei.
