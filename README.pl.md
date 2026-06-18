> [English original](README.md)

# ReelVault

Wieloplatformowa aplikacja do katalogowania wideo, inspirowana Lightroomem — szybkie,
natywne przeglądanie dużych bibliotek wideo.

**ReelVault służy do organizowania, odkrywania i zarządzania wideo. NIE jest edytorem wideo.**

## Przegląd

ReelVault umożliwia:

- **Przeglądanie** tysięcy filmów w responsywnej, zwirtualizowanej siatce z
  regulowanym rozmiarem miniatur.
- **Scrubbowanie** w stylu Lightroom: najedź kursorem na miniaturę i przesuń w lewo↔prawo,
  aby podglądać klatki na osi czasu.
- **Grupowanie** powiązanych wariantów (np. eksportów 4K i 1080p z tego samego źródła)
  w stosy w stylu Lightroom; oznaczanie jednego jako preferowanego w siatce i przy otwieraniu.
- **Odkrywanie** treści przez wyszukiwanie pełnotekstowe, filtrowanie po tagach oraz
  rozwijane filtry pól (kamera, obiektyw, kodek, rok nagrania, słowo kluczowe).
- **Inspekcję** szczegółowych metadanych: kodek, rozdzielczość, FPS, bitrate, przestrzeń barw,
  HDR, EXIF, GPS, model kamery/obiektywu.
- **Organizowanie** za pomocą notatek, słów kluczowych i operacji z wielokrotnym zaznaczeniem.
- **Przekazywanie** klipów do zewnętrznych edytorów metodą przeciągnij i upuść — przeciągnij
  jedną lub więcej kart bezpośrednio z siatki do DaVinci Resolve, Final Cut Pro, Premiere
  Pro lub dowolnej aplikacji akceptującej upuszczanie plików.
- **Praca z wieloma katalogami**: otwieranie / zamykanie / przełączanie katalogów SQLite
  z menu Plik, z listą ostatnio używanych katalogów i tytułami okien per katalog.

## Architektura

```
Desktop / macOS clients          iOS client (iPhone / iPad)
   ↓ gRPC over loopback             ↓ gRPC + HTTPS media over the LAN
   │                                │ (mDNS discovery · pinned TLS · paired)
   └───────────────┬────────────────┘
                   ↓
        Rust Backend Daemon (reelvault-core)
                   ↓
        SQLite Catalog + FFmpeg/FFprobe + Filesystem
```

- **Rust core** (`core/`) — demon gRPC oparty na Tonic. Zarządza katalogiem SQLite,
  ekstrakcją metadanych FFprobe, generowaniem miniatur i klatek scrubowania,
  skanowaniem/indeksowaniem, wyszukiwaniem i agregacją filtrów. Obsługuje dynamiczną
  wymianę aktywnego katalogu w czasie działania przez RPC `OpenCatalog` / `CloseCatalog` /
  `GetCurrentCatalog`, dzięki czemu jeden proces demona może obsługiwać wiele bibliotek
  przez cały czas swojego działania.

- **Kotlin Compose desktop client** (`desktop/`) — UI Compose Multiplatform.
  Automatycznie wykrywa działającego demona na `127.0.0.1:50051`; jeśli żaden nie działa,
  uruchamia dołączonego demona (korzystając z portu przydzielonego przez system, gdy
  50051 jest zajęty).

- **SwiftUI macOS client** (`macos/`) — natywna aplikacja macOS z pełną parytetem funkcji,
  tym samym automatycznym uruchamianiem, prawdziwym menu Plik macOS (grupa Commands) i
  reaktywnym tytułem okna śledzącym otwarty katalog.

- **SwiftUI iOS client** (`ios/`) — aplikacja na iPhone/iPad działająca **wyłącznie zdalnie**.
  Nie ma dostępu do lokalnych plików i nie zawiera wbudowanego demona: wykrywa demona przez
  Wi‑Fi (mDNS), łączy się przez kanał TLS przypięty odciskiem palca po jednorazowym
  parowaniu, przegląda przez gRPC i **streamuje** wideo (skalowane HLS) z serwera mediów
  demona. Zastępuje przeciąganie do edytora arkuszem udostępniania iOS i dodaje
  przesyłanie z Zdjęć / Plików. Zob. [`ios/README.md`](ios/README.md).

- **ReelVaultKit** (`kit/`) — lokalny pakiet SwiftPM ze współdzielonym kodem Swift
  używanym przez **oba** klienty Apple: modele, modele widoku, klient gRPC, wykrywanie,
  przypięty TLS i warstwa buforowania/przesyłania strumieniowego mediów.

- **Katalog SQLite** — baza danych w trybie WAL z FTS5 do wyszukiwania pełnotekstowego.
  Schemat znajduje się w [`core/schema.sql`](core/schema.sql).

> **Miniatury ProRes RAW mają jakość wyłącznie na macOS.** ffmpeg nie może dekodować
> ProRes RAW (Atomos S-Log3 / S-Gamut), więc na **macOS** demon dekoduje go przez
> QuickLook / AVFoundation — poprawne kolory i prawdziwe scrubbowanie klatka po klatce.
> Na **Linux / Windows** nie ma takiego dekodera, więc demon cofa się do ffmpeg:
> płaskie/ciemniejsze klatki i jedna powtarzająca się klatka scrubowania.
> Rust core jest budowany identycznie na wszystkich trzech platformach — AVFoundation
> nigdy nie jest z nim linkowany. Szczegóły: [`core/README.md`](core/README.md)
> (ścieżki budowania i dekodowania) i [`macos/README.md`](macos/README.md) (klient macOS).
> Wszystkie inne kodeki są obsługiwane przez ffmpeg w ten sam sposób wszędzie.

## Status projektu

**MVP działa na macOS i Compose Desktop.** Oba klienty mają ten sam zestaw funkcji;
klient macOS dodaje natywne polecenia paska menu i uruchamianie edytora przez NSWorkspace.
**Klient iOS wyłącznie zdalny** (iPhone / iPad) łączy się z demonem przez LAN i streamuje
wideo — przeglądanie, inspekcja, stosy, udostępnianie i przesyłanie;
zob. [`ios/README.md`](ios/README.md).

### ✅ Zrealizowane

**Core**
- [x] Demon gRPC z pełną powierzchnią RPC (wideo, wyszukiwanie, skanowanie, tagi,
      kolekcje, stosy, filtry, status, konfiguracja, cykl życia katalogu).
- [x] Katalog SQLite z trybem WAL + FTS5; dynamiczna wymiana katalogu w czasie działania przez
      `OpenCatalog` / `CloseCatalog`.
- [x] Ekstrakcja metadanych FFprobe (kodek, rozdzielczość, FPS, bitrate, HDR,
      EXIF, GPS, kamera/obiektyw).
- [x] Generowanie miniatur i klatek scrubowania w stylu Lightroom (10 klatek na
      wideo) z blokadami per wideo, aby unikać powielania pracy.
- [x] Ograniczanie równoległego ffmpeg (domyślnie liczba rdzeni CPU hosta), aby
      skanowanie dużych bibliotek nie przeciążało pamięci SAN.
- [x] Skanowanie biblioteki z opcjonalną rekurencją i automatycznym grupowaniem wariantów.
- [x] Flagi CLI `--db-path`, `--no-catalog`, `--port` (z rezerwowym portem przydzielanym
      przez system) i czytelna linia `REELVAULT_LISTENING_ON=…` na stdout dla
      programów uruchamiających klienty.

**Oba klienty**
- [x] Zwirtualizowany widok siatki z adaptacyjną liczbą kolumn i suwakiem rozmiaru miniatur.
- [x] Podgląd scrubowania przy najechaniu, nakładka odtwarzania przy najechaniu, wielokrotne
      zaznaczenie z zakresem Shift i przełączaniem ⌘/Ctrl.
- [x] UI stosów (grup): odznaki stosów z liczbą elementów, rozwijanie kliknięciem,
      gwiazdka do ustawienia preferowanego, przyciski otwierania per element.
- [x] Panele boczne: lokalizacje bibliotek (lewa), szczegóły + metadane + notatki +
      słowa kluczowe (prawa). Tab przełącza oba, pojedyncze strzałki zwijają każdy.
- [x] Rozwijane filtry na górnym pasku (kamera / obiektyw / słowo kluczowe / kodek / rok) —
      wyświetlane są tylko pola z danymi; łączone z wyszukiwaniem przez AND.
- [x] Menu sortowania ze wszystkimi głównymi polami (nazwa pliku, daty, czas trwania, rozmiar,
      rozdzielczość, fps, kodek, bitrate, kamera, obiektyw, słowo kluczowe); kliknij ponownie,
      aby odwrócić kierunek.
- [x] Zarządzanie tagami/słowami kluczowymi: tworzenie w locie, stosowanie do zaznaczenia
      wielokrotnego, filtrowanie siatki kliknięciem strzałki `>`.
- [x] Menu kontekstowe prawym przyciskiem myszy na każdej karcie: Otwórz domyślnym odtwarzaczem
      i Pokaż w Finderze/Eksploratorze — działa na całym wielokrotnym zaznaczeniu.
- [x] Przekazywanie przez przeciągnij i upuść: przeciągnij zaznaczone karty do dowolnej
      aplikacji akceptującej upuszczanie plików (DaVinci Resolve, Final Cut Pro, Premiere Pro, itp.).
- [x] Przepływ wielokatalogowy: menu Plik z Otwórz / Zamknij / Otwórz ostatnie,
      arkusz „otwórz katalog" przy pierwszym uruchomieniu, trwała lista ostatnich, tytuł
      okna pokazujący nazwę otwartego katalogu.
- [x] Automatyczne uruchamianie dołączonego demona (z rezerwą dla zajętego portu) gdy
      przy starcie nie ma działającego backendu.
- [x] Tekst pomocniczy przy najechaniu (podpowiedzi w Kotlin przez `TooltipArea`; SwiftUI przez
      `.help(_:)`) na każdym interaktywnym elemencie i polu metadanych.
- [x] Domyślny tryb ciemny; przełącznik jasny/ciemny motyw.

### 🚧 Planowane

- [ ] Obserwowanie plików w czasie rzeczywistym (ponowne indeksowanie przy zmianie folderu).
- [ ] Inteligentne kolekcje (zapisane wyszukiwania z regułami filtrowania na żywo).
- [ ] Generowanie wideo proxy dla materiałów 8K+.
- [ ] Automatyczne aktualizacje oparte na GitHub.
- [ ] Potok CI/CD produkujący podpisane instalatory dla każdej platformy.
- [ ] Dołączanie pliku binarnego demona do bundli aplikacji klienckich (obecnie
      program uruchamiający znajduje go przez `REELVAULT_CORE_BIN` lub drzewo deweloperskie cargo).

## Pierwsze kroki

### Wymagania wstępne

- **Rust** (zalecany 1.75+) — do budowania demona core.
- **FFmpeg / FFprobe** — muszą być w `PATH`. Używane do ekstrakcji metadanych
  i generowania miniatur/klatek scrubowania.
- **JDK 17+** + Gradle (dołączone wrapper) — dla klienta desktopowego Kotlin.
- **Swift 5.9+ / Xcode 15+** — dla klienta macOS.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — dla klienta iOS.

Instrukcje instalacji dla konkretnych platform — zob. [`SETUP.md`](SETUP.md).

### Budowanie demona core

```bash
cd core
cargo build --release
```

Plik binarny trafia do `core/target/release/reelvault-core`. Uruchom go bezpośrednio,
jeśli chcesz sterować nim samodzielnie, lub pozwól jednemu z klientów uruchomić go za
ciebie przy pierwszym starcie:

```bash
# Domyślnie — użyj katalogu platformy na domyślnym porcie.
./target/release/reelvault-core

# Uruchom bez katalogu (ten tryb używają klienty); wydrukuj przydzielony port.
./target/release/reelvault-core --no-catalog --port 0

# Otwórz konkretny katalog przy starcie.
./target/release/reelvault-core --db-path /path/to/library.db
```

Demon drukuje stabilną linię `REELVAULT_LISTENING_ON=127.0.0.1:N` na stdout,
którą klienty parsują, aby odkryć przydzielony port.

### Uruchamianie Kotlin Compose desktop client

```bash
cd desktop
./gradlew run
```

Przy pierwszym uruchomieniu klient sonduje `127.0.0.1:50051` i — jeśli nic nie
nasłuchuje — uruchamia dołączonego demona. Kolejność wyszukiwania lokalizacji budowania:

1. `$REELVAULT_CORE_BIN` (bezwzględna ścieżka do pliku wykonywalnego demona)
2. Plik binarny obok jar aplikacji
3. `core/target/release/reelvault-core` lub `core/target/debug/reelvault-core`
   w drzewie deweloperskim
4. `reelvault-core` w `PATH`

Do programowania wystarczy `cargo build` w `core/`, a klient sam podchwyci
plik binarny debug.

### Uruchamianie SwiftUI macOS client

```bash
cd macos
swift run
```

Ten sam automatyczny przepływ uruchamiania, ta sama kolejność wyszukiwania pliku binarnego
(z dodaniem ścieżki `Resources/reelvault-core` wewnątrz bundla używanego przez podpisane
bundle aplikacji). Użyj `⌘O` do otwarcia katalogu i `⇧⌘W` do jego zamknięcia; lista
ostatnich jest pod `File → Open Recent`.

### Uruchamianie SwiftUI iOS client

Klient iOS działa **wyłącznie zdalnie** — łączy się z demonem przez LAN, zamiast samemu
go uruchamiać. Uruchom demona w trybie zdalnym na maszynie w tej samej sieci Wi‑Fi:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Następnie wygeneruj projekt Xcode (`.xcodeproj` nie jest śledzony w repozytorium)
i zbuduj:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

Przy pierwszym uruchomieniu aplikacja odkrywa demona przez mDNS, jednorazowo autoryzujesz
urządzenie 6-cyfrowym kodem parowania (wygeneruj go z **File → Pair a New Device** w
kliencie desktopowym lub z logu demona), a następnie możesz przeglądać i streamować.
Wymaga **iOS 18+** i **Xcode 16+**. Pełne szczegóły, w tym model streamingu i parowania,
są w [`ios/README.md`](ios/README.md).

## Dokumentacja

- [`CLAUDE.md`](CLAUDE.md) — Wizja projektu, szczegóły architektury, schemat bazy danych
  i zasady projektowania.
- [`SETUP.md`](SETUP.md) — Konfiguracja środowiska deweloperskiego.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Szczegółowy opis modułów i powierzchni RPC
  rdzenia Rust.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Porównanie klientów Kotlin i SwiftUI
  obok siebie.
- [`ios/README.md`](ios/README.md) — Klient iOS wyłącznie zdalny (iPhone / iPad):
  wykrywanie, parowanie, przypięty TLS i streaming HLS.
- [`macos/README.md`](macos/README.md) — Natywny klient macOS.

## Współtworzenie

Wytyczne deweloperskie — zob. [`CLAUDE.md`](CLAUDE.md). Zapraszamy do zgłaszania pull
requestów — prosimy o utrzymanie parytetu funkcji między oboma klientami oraz dodawanie
nagłówków SPDX do nowych plików źródłowych (zob. Licencja poniżej).

## Licencja

ReelVault jest wolnym oprogramowaniem, licencjonowanym na warunkach **GNU General Public
License, wersja 3 lub (wedle uznania) dowolna późniejsza wersja**. Pełny tekst licencji
znajduje się w [`LICENSE`](LICENSE); krótka informacja o prawach autorskich — w
[`COPYRIGHT`](COPYRIGHT).

Każdy plik źródłowy zawiera identyfikator SPDX, dzięki czemu narzędzia do skanowania
licencji (REUSE, FOSSology, detektor licensee na GitHub itp.) mogą programowo zidentyfikować
licencję:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

Jeśli dystrybuujesz zmodyfikowaną wersję ReelVault — lub dowolny program linkowany z
rdzeniem Rust jako bibliotekę — GPL wymaga udostępnienia kodu źródłowego na tych samych
warunkach. Pełny zakres zobowiązań — zob. plik LICENSE.
