> [Originale in inglese](SETUP.md)

# Configurazione dell'Ambiente di Sviluppo ReelVault

## Prerequisiti

### Requisiti di sistema

- **macOS 11+**, **Linux (Ubuntu 20.04+)** o **Windows 10+**
- **Rust 1.70+** ([Installa tramite rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (per l'analisi video e la generazione di miniature)

### Installare FFmpeg

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
Scarica da https://ffmpeg.org/download.html oppure usa:
```bash
choco install ffmpeg
```

### Installare Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Verifica:
```bash
rustc --version
cargo --version
```

## Compilare il Nucleo

```bash
cd core
cargo build --release
```

Il binario compilato si troverà in `core/target/release/reelvault-core`.

## Eseguire il Daemon Core

```bash
./core/target/release/reelvault-core
```

Il daemon:
- Creerà un database in `~/.reelvault/catalog.db`
- Creerà una directory di cache per le miniature
- Ascolterà su `127.0.0.1:50051` le connessioni gRPC

## Eseguire i Test

```bash
cd core
cargo test
```

## Comandi di Sviluppo

**Controllare il codice senza compilare:**
```bash
cargo check
```

**Formattare il codice:**
```bash
cargo fmt
```

**Analizzare il codice (lint):**
```bash
cargo clippy
```

**Compilare con simboli di debug:**
```bash
cargo build
```

## Collegare un Frontend

Una volta che il daemon core è in esecuzione, i frontend possono connettersi tramite gRPC a `http://127.0.0.1:50051`.

Le definizioni proto si trovano in `core/proto/reelvault.proto` e devono essere usate per generare il codice client per ogni linguaggio frontend.

## Risoluzione dei Problemi

**"ffmpeg not found"**
- Assicurati che FFmpeg sia installato e nel tuo PATH
- Verifica: `which ffmpeg && which ffprobe`

**Database bloccato**
- Deve essere in esecuzione una sola istanza del daemon per volta
- Termina i processi esistenti: `pkill reelvault-core`

**Porta già in uso**
- Modifica la porta in `core/src/main.rs` se la 50051 è occupata
- Oppure termina il processo: `lsof -ti:50051 | xargs kill`

**Problemi di compilazione su Linux**
- Installa dipendenze di sviluppo aggiuntive: `sudo apt-get install build-essential libssl-dev`

## Prossimi Passi

1. **Client macOS**: Implementare il frontend SwiftUI (post-MVP)
2. **Client Desktop**: Implementare il frontend Kotlin Compose (priorità MVP)
3. **Testing**: Aggiungere test di integrazione per l'API gRPC
4. **CI/CD**: Configurare GitHub Actions per le build e i rilasci
