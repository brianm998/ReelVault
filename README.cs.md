> [Anglický originál](README.md)

# ReelVault

Multiplatformní aplikace pro katalogizaci videa inspirovaná Lightroom — rychlé, nativní procházení rozsáhlých videotéek.

**ReelVault slouží k organizaci, objevování a správě videí. NENÍ to videoeditor.**

## Přehled

ReelVault vám umožní:

- **Procházet** tisíce videí v responzivní, virtualizované mřížce s nastavitelnou velikostí náhledů.
- **Scrubovat** ve stylu Lightroom: najeďte na náhled a posuňte vlevo↔vpravo pro náhled snímků po časové ose.
- **Seskupovat** příbuzné varianty (například exporty 4K a 1080p ze stejného zdroje) do zásobníků ve stylu Lightroom; označit jeden jako preferovaný pro mřížku a otevření.
- **Objevovat** obsah pomocí fulltextového vyhledávání, filtrování tagů a rozbalovacích nabídek filtrů pro každé pole (kamera, objektiv, kodek, rok záznamu, klíčové slovo).
- **Prohlížet** podrobná metadata: kodek, rozlišení, FPS, bitrate, barevný prostor, HDR, EXIF, GPS, model kamery/objektivu.
- **Organizovat** pomocí volných poznámek, klíčových slov a operací s více výběry.
- **Předávat** klipy externím editorům přetažením — přetáhněte jednu nebo více karet přímo z mřížky do DaVinci Resolve, Final Cut Pro, Premiere Pro nebo jakékoli aplikace přijímající soubory.
- Pracovní postupy s **více katalogy**: otevírání / zavírání / přepínání katalogů SQLite z nabídky Soubor, se seznamem posledních katalogů a názvy oken pro každý katalog.

## Architektura

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

- **Rust core** (`core/`) — gRPC démon postavený na Tonic. Spravuje katalog SQLite, extrakci metadat přes FFprobe, generování náhledů a scrub-snímků, skenování/indexování, vyhledávání a agregaci filtrů. Podporuje za běhu hot-swap aktivního katalogu přes RPC: `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog`, takže jeden proces démona může za svůj životní cyklus obsloužit více knihoven.

- **Kotlin Compose desktop client** (`kotlin-desktop/`) — UI Compose Multiplatform. Automaticky rozpozná běžící démon na `127.0.0.1:50051`; pokud žádný neběží, sám spustí přibalený démon (s přechodem na port přidělený OS, pokud je 50051 obsazen).

- **SwiftUI macOS klient** (`macos/`) — nativní macOS aplikace s paritou funkcí, se stejným tokem automatického spouštění, skutečnou nabídkou Soubor macOS (skupina Commands) a reaktivním názvem okna sledujícím otevřený katalog.

- **SwiftUI iOS klient** (`ios/`) — aplikace pro iPhone / iPad **pouze pro vzdálené připojení**. Nemá přístup k místním souborům a neobsahuje žádný démon: nalézá démon přes Wi‑Fi (mDNS), připojuje se přes TLS kanál s připnutým otiskem prstu po jednorázovém spárování, prochází přes gRPC a **streamuje** video (HLS se sníženou kvalitou) z mediálního serveru démona. Nahrazuje přetažení do editoru sdíleným listem iOS a přidává nahrávání z Fotek / Souborů. Viz [`ios/README.md`](ios/README.md).

- **Kotlin Compose Android client** (`android/`) — A **remote-only**
  Android phone / tablet app. Connects to a daemon over the LAN (NSD
  discovery), streams video via ExoPlayer, and replaces editor drag-out with
  the Android share intent. Also embeds the full Rust core for on-device local
  library access — browse, catalog, and upload footage directly from the
  device. See [`android/README.md`](android/README.md).

- **ReelVaultKit** (`kit/`) — místní SwiftPM balíček se sdíleným Swift kódem používaným **oběma** klienty Apple: modely, view-modely, gRPC klient, objevování, připnutý TLS a vrstva cache/streamování médií.

- **Katalog SQLite** — databáze v režimu WAL s FTS5 pro fulltextové vyhledávání. Schéma se nachází v [`core/schema.sql`](core/schema.sql).

> **Náhledy ProRes RAW mají plnou kvalitu pouze na macOS.** ffmpeg nedokáže dekódovat ProRes RAW (Atomos S-Log3 / S-Gamut), proto na **macOS** démon dekóduje přes QuickLook / AVFoundation — správné barvy a skutečný scrubing po snímcích. Na **Linux / Windows** takový dekodér neexistuje, démon proto přechází na ffmpeg: ploché/tmavší snímky a jeden opakující se scrub-snímek. Rust core se kompiluje identicky na všech třech platformách — AVFoundation se do něj nikdy nelinkuje. Podrobnosti: [`core/README.md`](core/README.md) (cesty sestavení + dekódování) a [`macos/README.md`](macos/README.md) (macOS klient). Všechny ostatní kodeky zpracovává ffmpeg stejně všude.

## Stav projektu

**MVP je funkční na macOS a Compose Desktop.** Oba klienti mají stejnou sadu funkcí; macOS klient přidává nativní příkazy v liště nabídek a spouštění editorů přes NSWorkspace. **iOS klient pouze pro vzdálené připojení** (iPhone / iPad) se připojuje k démonu přes LAN a streamuje video — procházení, prohlídka, zásobníky, sdílení a nahrávání; viz [`ios/README.md`](ios/README.md).

### ✅ Hotovo

**Core**
- [x] gRPC démon s plným povrchem RPC (videa, vyhledávání, skenování, tagy, kolekce, zásobníky, filtry, stav, konfigurace, životní cyklus katalogu).
- [x] Katalog SQLite s režimem WAL + FTS5; hot-swap katalogu za běhu přes `OpenCatalog` / `CloseCatalog`.
- [x] Rich metadata extraction via FFprobe + platform-native helpers: codec,
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera., rozlišení, FPS, bitrate, HDR, EXIF, GPS, kamera/objektiv).
- [x] Generování náhledů a scrub-snímků ve stylu Lightroom (10 snímků na video) s uzamčením pro každé video, aby se předešlo duplicitní práci.
- [x] Omezení souběžného ffmpeg (výchozí nastavení dle počtu jader CPU hostitele), aby skenování velkých knihoven nepřetěžovalo úložiště SAN.
- [x] Skenování knihovny s volitelnou rekurzí a automatickým seskupením variant.
- [x] Parametry CLI pro `--db-path`, `--no-catalog`, `--port` (s přechodem na port přidělený OS) a analyzovatelný řádek stdout `REELVAULT_LISTENING_ON=…` pro spouštěče klientů.

**Oba klienti**
- [x] Virtualizovaný pohled mřížky s adaptivním počtem sloupců a posuvníkem velikosti náhledů.
- [x] Náhled hover-scrub, překryv hover-play, vícenásobný výběr pomocí shift-rozsahu a přepínání ⌘/Ctrl.
- [x] UI zásobníků (skupin): odznaky zásobníků s počtem členů, kliknutí pro rozbalení, hvězdička pro nastavení preferovaného, tlačítka otevření pro každého člena.
- [x] Boční panely: umístění knihoven (vlevo), podrobnosti + metadata + poznámky + klíčová slova (vpravo). Tab přepíná obě, jednotlivé šipky sbalují každý panel.
- [x] Rozbalovací nabídky filtrů v horním pruhu (kamera / objektiv / klíčové slovo / kodek / rok) — zobrazují se pouze pole s daty; kombinovány operátorem AND s vyhledáváním.
- [x] Nabídka řazení se všemi hlavními poli (název souboru, data, délka, velikost, rozlišení, fps, kodek, bitrate, kamera, objektiv, klíčové slovo); kliknutím znovu obrátíte pořadí.
- [x] Správa tagů/klíčových slov: vytváření za chodu, aplikace na vícenásobný výběr, filtrování mřížky kliknutím na šipku `>`.
- [x] Kontextové menu pravým tlačítkem na každé kartě: Open with Default Player a Reveal in Finder/Explorer — pracuje s celým vícenásobným výběrem.
- [x] Předávání přetažením: přetáhněte vybrané karty do jakékoli aplikace přijímající soubory (DaVinci Resolve, Final Cut Pro, Premiere Pro atd.).
- [x] Tok s více katalogy: nabídka Soubor s Open / Close / Open Recent, list „otevřít katalog" při prvním spuštění, trvalý seznam posledních, název okna zobrazující název otevřeného katalogu.
- [x] Automatické spouštění přibaleného démona (s přechodem při obsazeném portu) pokud při startu neběží žádný backend.
- [x] Kontextová nápověda při najetí myší (popisky v Kotlin přes `TooltipArea`; SwiftUI přes `.help(_:)`) na každém interaktivním prvku a poli metadat.
- [x] Tmavý režim jako výchozí; přepínač světlého/tmavého motivu.

### 🚧 Plánováno

- [ ] Sledování souborů v reálném čase (přeindexování při změně sledované složky).
- [ ] Chytré kolekce (uložená hledání s živými pravidly filtrů).
- [ ] Generování proxy videí pro záběry 8K a výše.
- [ ] Automatické aktualizace přes GitHub.
- [ ] Pipeline vydávání CI/CD generující podepsané instalátory pro každou platformu.
- [ ] Zabalení binárního souboru démona do balíčků klientských aplikací (v současnosti ho spouštěč hledá přes `REELVAULT_CORE_BIN` nebo vývojový strom cargo).

## Začínáme

### Předpoklady

- **Rust** (doporučeno 1.75+) — pro sestavení démona core.
- **FFmpeg / FFprobe** — musí být na `PATH`. Používá se pro extrakci metadat a generování náhledů/scrub-snímků.
- **JDK 17+** + Gradle (wrapper je přibalen) — pro Kotlin desktop clienta.
- **Swift 5.9+ / Xcode 15+** — pro macOS klienta.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — pro iOS klienta.

Pokyny k instalaci pro konkrétní platformy najdete v [`SETUP.md`](SETUP.md).

### Sestavení démona core

```bash
cd core
cargo build --release
```

Binární soubor se uloží do `core/target/release/reelvault-core`. Spusťte jej přímo, pokud ho chcete ovládat sami, nebo nechte klienta, ať ho při prvním spuštění spustí za vás:

```bash
# Výchozí — použije výchozí katalog platformy na výchozím portu.
./target/release/reelvault-core

# Spuštění bez katalogu (klienti používají tento režim); vypíše přidělený port.
./target/release/reelvault-core --no-catalog --port 0

# Otevření konkrétního katalogu při spuštění.
./target/release/reelvault-core --db-path /path/to/library.db
```

Démon vypíše na stdout stabilní řádek `REELVAULT_LISTENING_ON=127.0.0.1:N`, který klienti analyzují pro nalezení přiděleného portu.

### Spuštění Kotlin Compose desktop clienta

```bash
cd kotlin-desktop
./gradlew run
```

Při prvním spuštění klient prověří `127.0.0.1:50051` a — pokud nic neposlouchá — spustí přibalený démon. Pořadí hledání umístění sestavení:

1. `$REELVAULT_CORE_BIN` (absolutní cesta ke spustitelnému souboru démona)
2. Binární soubor vedle jar souboru aplikace
3. `core/target/release/reelvault-core` nebo `core/target/debug/reelvault-core` ve vývojovém stromu
4. `reelvault-core` v `PATH`

Pro vývoj stačí `cargo build` uvnitř `core/` a klient si debug binární soubor sám najde.

### Spuštění macOS SwiftUI klienta

```bash
cd macos
swift run
```

Stejný tok automatického spouštění, stejné pořadí hledání binárního souboru (s přidáním cesty `Resources/reelvault-core` uvnitř balíčku, která se používá u podepsaných balíčků aplikací). Stiskněte `⌘O` pro otevření katalogu a `⇧⌘W` pro jeho zavření; seznam posledních je pod `File → Open Recent`.

### Spuštění iOS SwiftUI klienta

iOS klient je **pouze pro vzdálené připojení** — místo spouštění démona se připojuje k démonu přes LAN. Spusťte démona ve vzdáleném režimu na stroji ve stejné síti Wi‑Fi:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Poté vygenerujte projekt Xcode (`.xcodeproj` není commitováno) a sestavte:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

Při prvním spuštění aplikace nalezne démona přes mDNS, zařízení jednou autorizujete 6místným párovacím kódem (vygenerujte ho z **File → Pair a New Device** v desktop clientu nebo z logu démona), a pak procházejte + streamujte. Vyžaduje **iOS 18+** a **Xcode 16+**. Úplné podrobnosti včetně modelu streamování a párování jsou v [`ios/README.md`](ios/README.md).

## Dokumentace

- [`CLAUDE.md`](CLAUDE.md) — Vize projektu, podrobnosti architektury, schéma databáze a principy návrhu.
- [`SETUP.md`](SETUP.md) — Nastavení vývojového prostředí.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Podrobná reference pro moduly Rust core a povrch RPC.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Srovnání Kotlin a SwiftUI klientů vedle sebe.
- [`ios/README.md`](ios/README.md) — iOS klient pouze pro vzdálené připojení (iPhone / iPad): objevování, párování, připnutý TLS a HLS streaming.
- [`macos/README.md`](macos/README.md) — Nativní macOS klient.

## Přispívání

Pokyny pro vývoj najdete v [`CLAUDE.md`](CLAUDE.md). Pull requesty jsou vítány — prosíme zachovejte paritu funkcí napříč všemi čtyřmi klienty, kde to připadá v úvahu
(viz CLAUDE.md pro legitimní odchylky pro danou platformu), a přidejte záhlaví SPDX ke všem novým zdrojovým souborům (viz Licence níže).

## Licence

ReelVault je svobodný software licencovaný pod **GNU General Public License verze 3 nebo (dle vašeho výběru) libovolné pozdější verze**. Úplný text licence se nachází v [`LICENSE`](LICENSE); stručné oznámení o autorských právech v [`COPYRIGHT`](COPYRIGHT).

Každý zdrojový soubor nese identifikátor SPDX, aby jej nástroje pro skenování licencí (REUSE, FOSSology, detektor licensee GitHubu atd.) mohly programově identifikovat:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

Pokud distribuujete upravenou verzi ReelVault — nebo jakýkoli program, který linkuje Rust core jako knihovnu — GPL vyžaduje, abyste zpřístupnili váš zdrojový kód za stejných podmínek. Úplný seznam povinností naleznete v souboru LICENSE.
