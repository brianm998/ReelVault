> [Originale in inglese](README.md)

# ReelVault

Un'applicazione multipiattaforma per la catalogazione di video ispirata a Lightroom — navigazione rapida e nativa di grandi librerie video.

**ReelVault serve a organizzare, scoprire e gestire video. NON è un editor video.**

## Panoramica

ReelVault ti permette di:

- **Sfogliare** migliaia di video in una griglia reattiva e virtualizzata con dimensione delle miniature regolabile.
- **Fare scrubbing** in stile Lightroom: passa il cursore su una miniatura e scorri sinistra↔destra per visualizzare in anteprima i fotogrammi lungo la timeline.
- **Raggruppare** varianti correlate (es. esportazioni 4K e 1080p dalla stessa sorgente) in pile stile Lightroom; imposta una come preferita per la griglia e per l'apertura.
- **Scoprire** contenuti tramite ricerca full-text, filtraggio per tag e menu a tendina di filtro per campo (camera, obiettivo, codec, anno di ripresa, parola chiave).
- **Ispezionare** metadati dettagliati: codec, risoluzione, FPS, bitrate, spazio colore, HDR, EXIF, GPS, modello di camera/obiettivo.
- **Organizzare** con note libere, parole chiave e operazioni di selezione multipla.
- **Trasferire** clip a editor esterni tramite trascinamento — trascina una o più schede direttamente dalla griglia verso DaVinci Resolve, Final Cut Pro, Premiere Pro o qualsiasi app che accetti file trascinati.
- Flussi di lavoro **multi-catalogo**: aprire / chiudere / passare tra cataloghi SQLite dal menu File, con una lista di cataloghi recenti e titoli di finestra per catalogo.

## Architettura

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

- **Nucleo Rust** (`core/`) — Daemon gRPC basato su Tonic. Gestisce il catalogo SQLite, l'estrazione di metadati con FFprobe, la generazione di miniature e fotogrammi di scrub, la scansione/indicizzazione, la ricerca e l'aggregazione dei filtri. Supporta lo scambio a caldo del catalogo attivo a runtime tramite le RPC `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog`, così un singolo processo daemon può servire più librerie nel corso della sua vita.

- **Client desktop Kotlin Compose** (`kotlin-desktop/`) — Interfaccia Compose Multiplatform. Rileva automaticamente un daemon in esecuzione su `127.0.0.1:50051`; se nessuno è attivo, avvia il daemon in bundle (con fallback a una porta assegnata dal SO se la 50051 è occupata).

- **Client macOS SwiftUI** (`macos/`) — App macOS nativa con parità di funzionalità, lo stesso flusso di avvio automatico, un vero menu File di macOS (gruppo Commands) e un titolo di finestra reattivo che segue il catalogo aperto.

- **Client iOS SwiftUI** (`ios/`) — Un'app iPhone / iPad **solo remota**. Non ha accesso a file locali e non incorpora un daemon: scopre un daemon via Wi-Fi (mDNS), si connette tramite un canale TLS con impronta digitale fissata dopo un accoppiamento una tantum, naviga via gRPC e **trasmette in streaming** il video (HLS ridimensionato) dal server media del daemon. Sostituisce il trascinamento verso gli editor con il foglio di condivisione iOS e aggiunge il caricamento da Foto / File. Vedi [`ios/README.md`](ios/README.md).

- **ReelVaultKit** (`kit/`) — Un pacchetto SwiftPM locale di codice Swift condiviso utilizzato da **entrambi** i client Apple: modelli, view-model, client gRPC, discovery, TLS fissato e il livello di cache/streaming media.

- **Catalogo SQLite** — Database in modalità WAL con FTS5 per la ricerca full-text. Lo schema si trova in [`core/schema.sql`](core/schema.sql).

> **Le miniature ProRes RAW sono di qualità esclusiva macOS.** ffmpeg non riesce a sviluppare ProRes RAW (Atomos S-Log3 / S-Gamut), quindi su **macOS** il daemon lo decodifica tramite QuickLook / AVFoundation — colori corretti e vero scrubbing fotogramma per fotogramma. Su **Linux / Windows** non esiste tale decoder, quindi il daemon ricade su ffmpeg: fotogrammi più piatti/scuri e un singolo fotogramma di scrub ripetuto. Il nucleo Rust si compila in modo identico su tutte e tre le piattaforme — AVFoundation non viene mai collegato ad esso. Dettagli: [`core/README.md`](core/README.md) (percorsi di build e decodifica) e [`macos/README.md`](macos/README.md) (il client macOS). Tutti gli altri codec sono gestiti da ffmpeg allo stesso modo ovunque.

## Stato del progetto

**L'MVP è funzionale su macOS e Compose Desktop.** Entrambi i client offrono lo stesso set di funzionalità; il client macOS aggiunge comandi nativi nella barra dei menu e aperture di editor tramite NSWorkspace. Un **client iOS solo remoto** (iPhone / iPad) si connette a un daemon tramite LAN e trasmette video in streaming — sfoglia, ispeziona, impila, condividi e carica; vedi [`ios/README.md`](ios/README.md).

### ✅ Completato

**Nucleo**
- [x] Daemon gRPC con superficie RPC completa (video, ricerca, scansione, tag, collezioni, pile, filtri, stato, configurazione, ciclo di vita del catalogo).
- [x] Catalogo SQLite con modalità WAL + FTS5; scambio a caldo del catalogo a runtime tramite `OpenCatalog` / `CloseCatalog`.
- [x] Estrazione metadati FFprobe (codec, risoluzione, FPS, bitrate, HDR, EXIF, GPS, camera/obiettivo).
- [x] Generazione di miniature e fotogrammi di scrub stile Lightroom (10 fotogrammi per video) con lock per video per deduplicare il lavoro.
- [x] Limitazione del ffmpeg concorrente (default: numero di CPU dell'host) per evitare di sovraccaricare lo storage SAN durante le scansioni di grandi librerie.
- [x] Scansione della libreria con ricorsione opzionale e raggruppamento automatico delle varianti.
- [x] Flag CLI per `--db-path`, `--no-catalog`, `--port` (con fallback a porta assegnata dal SO) e una riga stdout `REELVAULT_LISTENING_ON=…` analizzabile dai launcher client.

**Entrambi i client**
- [x] Vista a griglia virtualizzata con numero di colonne adattivo e cursore per la dimensione delle miniature.
- [x] Anteprima hover-scrub, overlay di riproduzione al passaggio del cursore, selezione multipla con intervallo Shift e toggle ⌘/Ctrl.
- [x] Interfaccia pile (gruppi): badge pile con conteggio dei membri, clic per espandere, stella per impostare il preferito, pulsanti di apertura per membro.
- [x] Pannelli laterali: posizioni della libreria (sinistra), dettagli + metadati + note + parole chiave (destra). Tab per alternare entrambi, frecce individuali per ridurre ciascuno.
- [x] Menu a tendina di filtro nella barra superiore (camera / obiettivo / parola chiave / codec / anno) — solo i campi con dati vengono mostrati; combinati in AND con la ricerca.
- [x] Menu di ordinamento con tutti i campi principali (nome file, date, durata, dimensione, risoluzione, fps, codec, bitrate, camera, obiettivo, parola chiave); clicca di nuovo per invertire la direzione.
- [x] Gestione tag/parole chiave: creazione al volo, applicazione a selezione multipla, filtraggio della griglia cliccando sulla freccia `>`.
- [x] Menu contestuale al clic destro su ogni scheda: Apri con il lettore predefinito e Mostra nel Finder/Esplora Risorse — opera sull'intera selezione multipla.
- [x] Trasferimento tramite trascinamento: trascina le schede selezionate in qualsiasi app che accetti file trascinati (DaVinci Resolve, Final Cut Pro, Premiere Pro, ecc.).
- [x] Flusso multi-catalogo: menu File con Apri / Chiudi / Apri recenti, foglio "apri un catalogo" al primo avvio, lista dei recenti persistente, titolo della finestra che mostra il nome del catalogo aperto.
- [x] Avvio automatico del daemon in bundle (con fallback se la porta è occupata) quando nessun backend è in esecuzione all'avvio.
- [x] Testo di aiuto al passaggio del cursore (tooltip su Kotlin tramite `TooltipArea`; SwiftUI tramite `.help(_:)`) su ogni elemento interattivo e campo di metadati.
- [x] Modalità scura come default; toggle tema chiaro/scuro.

### 🚧 Pianificato

- [ ] Monitoraggio file in tempo reale (reindicizzazione quando la cartella sottostante cambia).
- [ ] Collezioni intelligenti (ricerche salvate con regole di filtro dinamiche).
- [ ] Generazione di video proxy per filmati 8K e oltre.
- [ ] Aggiornamento automatico tramite GitHub.
- [ ] Pipeline di rilascio CI/CD che produce installer firmati per piattaforma.
- [ ] Inclusione del binario daemon all'interno dei bundle delle app client (attualmente il launcher lo trova tramite `REELVAULT_CORE_BIN` o un albero di sviluppo cargo).

## Iniziare

### Prerequisiti

- **Rust** (1.75+ consigliato) — per compilare il daemon core.
- **FFmpeg / FFprobe** — deve essere nel `PATH`. Usato per l'estrazione di metadati e la generazione di miniature/fotogrammi di scrub.
- **JDK 17+** + Gradle (wrapper incluso) — per il client desktop Kotlin.
- **Swift 5.9+ / Xcode 15+** — per il client macOS.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — per il client iOS.

Vedi [`SETUP.md`](SETUP.md) per le istruzioni di installazione specifiche per piattaforma.

### Compilare il daemon core

```bash
cd core
cargo build --release
```

Il binario si trova in `core/target/release/reelvault-core`. Eseguilo direttamente se vuoi gestirlo tu stesso, oppure lascia che uno dei client lo avvii per te al primo lancio:

```bash
# Default — usa il catalogo predefinito della piattaforma sulla porta predefinita.
./target/release/reelvault-core

# Avvia senza catalogo (modalità usata dai client); stampa la porta assegnata.
./target/release/reelvault-core --no-catalog --port 0

# Apri un catalogo specifico all'avvio.
./target/release/reelvault-core --db-path /path/to/library.db
```

Il daemon stampa una riga stabile `REELVAULT_LISTENING_ON=127.0.0.1:N` su stdout che i client analizzano per scoprire la porta assegnata.

### Eseguire il client desktop Kotlin Compose

```bash
cd kotlin-desktop
./gradlew run
```

Al primo lancio il client sonda `127.0.0.1:50051` e — se nulla è in ascolto — avvia il daemon in bundle. Ordine di ricerca del percorso di build:

1. `$REELVAULT_CORE_BIN` (un percorso assoluto all'eseguibile daemon)
2. Un binario accanto al jar dell'applicazione
3. `core/target/release/reelvault-core` o `core/target/debug/reelvault-core` nell'albero di sviluppo
4. `reelvault-core` nel `PATH`

Per lo sviluppo, basta `cargo build` dentro `core/` e il client utilizzerà il binario di debug.

### Eseguire il client macOS SwiftUI

```bash
cd macos
swift run
```

Stesso flusso di avvio automatico, stesso ordine di ricerca del binario (con l'aggiunta di un percorso `Resources/reelvault-core` nel bundle usato dai bundle di app firmati). Usa `⌘O` per aprire un catalogo e `⇧⌘W` per chiuderlo; la lista dei recenti si trova sotto `File → Apri recenti`.

### Eseguire il client iOS SwiftUI

Il client iOS è **solo remoto** — si connette a un daemon tramite LAN invece di avviarne uno. Avvia il daemon in modalità remota su una macchina sulla stessa rete Wi-Fi:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Poi genera il progetto Xcode (il `.xcodeproj` non è versionato) e compila:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

Al primo avvio l'app scopre il daemon tramite mDNS, autorizzi il dispositivo una volta con un codice di accoppiamento a 6 cifre (generalo dal menu **File → Associa nuovo dispositivo** di un client desktop, o dal log del daemon), poi sfoglia e trasmetti in streaming. Richiede **iOS 18+** e **Xcode 16+**. I dettagli completi, incluso il modello di streaming e accoppiamento, si trovano in [`ios/README.md`](ios/README.md).

## Documentazione

- [`CLAUDE.md`](CLAUDE.md) — Visione del progetto, dettagli architetturali, schema del database e principi di design.
- [`SETUP.md`](SETUP.md) — Configurazione dell'ambiente di sviluppo.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Riferimento dettagliato dei moduli del nucleo Rust e della superficie RPC.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Confronto affiancato dei client Kotlin e SwiftUI.
- [`ios/README.md`](ios/README.md) — Il client iOS (iPhone / iPad) solo remoto: discovery, accoppiamento, TLS fissato e streaming HLS.
- [`macos/README.md`](macos/README.md) — Il client macOS nativo.

## Contribuire

Vedi [`CLAUDE.md`](CLAUDE.md) per le linee guida allo sviluppo. Le pull request sono benvenute — mantieni la parità di funzionalità tra i due client e aggiungi intestazioni SPDX a qualsiasi nuovo file sorgente (vedi Licenza di seguito).

## Licenza

ReelVault è software libero, distribuito sotto la **GNU General Public License, versione 3 o (a tua scelta) qualsiasi versione successiva**. Il testo completo della licenza si trova in [`LICENSE`](LICENSE); un breve avviso di copyright è in [`COPYRIGHT`](COPYRIGHT).

Ogni file sorgente porta un identificatore SPDX in modo che gli strumenti di scansione delle licenze (REUSE, FOSSology, il rilevatore licensee di GitHub, ecc.) possano identificare la licenza in modo programmatico:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

Se distribuisci una versione modificata di ReelVault — o qualsiasi programma che colleghi il nucleo Rust come libreria — la GPL richiede che tu renda disponibile il tuo sorgente secondo gli stessi termini. Vedi il file LICENSE per l'insieme completo degli obblighi.
