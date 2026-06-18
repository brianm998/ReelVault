> [English original](SETUP.md)

# ReelVault Entwicklungsumgebung einrichten

## Voraussetzungen

### Systemanforderungen

- **macOS 11+**, **Linux (Ubuntu 20.04+)** oder **Windows 10+**
- **Rust 1.70+** ([Installation über rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (für Videoanalyse und Miniaturbildgenerierung)

### FFmpeg installieren

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
Download von https://ffmpeg.org/download.html oder:
```bash
choco install ffmpeg
```

### Rust installieren

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Überprüfen:
```bash
rustc --version
cargo --version
```

## Kern bauen

```bash
cd core
cargo build --release
```

Das kompilierte Binary befindet sich unter `core/target/release/reelvault-core`.

## Kern-Daemon ausführen

```bash
./core/target/release/reelvault-core
```

Der Daemon wird:
- Eine Datenbank unter `~/.reelvault/catalog.db` erstellen
- Ein Miniaturbilder-Cache-Verzeichnis erstellen
- Auf `127.0.0.1:50051` für gRPC-Verbindungen lauschen

## Tests ausführen

```bash
cd core
cargo test
```

## Entwicklungsbefehle

**Code prüfen ohne zu kompilieren:**
```bash
cargo check
```

**Code formatieren:**
```bash
cargo fmt
```

**Code analysieren (Linting):**
```bash
cargo clippy
```

**Mit Debug-Symbolen bauen:**
```bash
cargo build
```

## Ein Frontend verbinden

Sobald der Kern-Daemon läuft, können Frontends per gRPC unter `http://127.0.0.1:50051` eine Verbindung herstellen.

Die Proto-Definitionen befinden sich in `core/proto/reelvault.proto` und sollten zur Generierung von Client-Code für jede Frontend-Sprache verwendet werden.

## Fehlerbehebung

**"ffmpeg not found" (FFmpeg nicht gefunden)**
- Stelle sicher, dass FFmpeg installiert und im PATH ist
- Prüfe mit: `which ffmpeg && which ffprobe`

**Datenbank gesperrt**
- Es sollte immer nur eine Daemon-Instanz laufen
- Bestehende Prozesse beenden: `pkill reelvault-core`

**Port bereits belegt**
- Ändere den Port in `core/src/main.rs`, falls 50051 belegt ist
- Oder beende den Prozess: `lsof -ti:50051 | xargs kill`

**Build-Probleme unter Linux**
- Zusätzliche Entwicklungsabhängigkeiten installieren: `sudo apt-get install build-essential libssl-dev`

## Nächste Schritte

1. **macOS-Client**: SwiftUI-Frontend implementieren (nach MVP)
2. **Desktop-Client**: Kotlin Compose-Frontend implementieren (MVP-Priorität)
3. **Tests**: Integrationstests für die gRPC-API hinzufügen
4. **CI/CD**: GitHub Actions für Builds und Releases einrichten
