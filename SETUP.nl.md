> [English original](SETUP.md)

# ReelVault Ontwikkelomgeving Instellen

## Vereisten

### Systeemvereisten

- **macOS 11+**, **Linux (Ubuntu 20.04+)** of **Windows 10+**
- **Rust 1.70+** ([Installeren via rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (voor video-analyse en het genereren van miniaturen)

### FFmpeg installeren

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
Download van https://ffmpeg.org/download.html of gebruik:
```bash
choco install ffmpeg
```

### Rust installeren

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Controleren:
```bash
rustc --version
cargo --version
```

## De Core Bouwen

```bash
cd core
cargo build --release
```

De gecompileerde binary bevindt zich op `core/target/release/reelvault-core`.

## De Core-Daemon Uitvoeren

```bash
./core/target/release/reelvault-core
```

De daemon zal:
- Een database aanmaken op `~/.reelvault/catalog.db`
- Een cache-map voor miniaturen aanmaken
- Luisteren op `127.0.0.1:50051` voor gRPC-verbindingen

## Tests Uitvoeren

```bash
cd core
cargo test
```

## Ontwikkelopdrachten

**Code controleren zonder te compileren:**
```bash
cargo check
```

**Code formatteren:**
```bash
cargo fmt
```

**Code linten:**
```bash
cargo clippy
```

**Bouwen met debug-symbolen:**
```bash
cargo build
```

## Een Frontend Verbinden

Zodra de core-daemon actief is, kunnen frontends via gRPC verbinden op `http://127.0.0.1:50051`.

De proto-definities staan in `core/proto/reelvault.proto` en moeten worden gebruikt om clientcode te genereren voor elke frontend-taal.

## Probleemoplossing

**«ffmpeg not found»**
- Zorg dat FFmpeg is geïnstalleerd en in uw PATH staat
- Controleer: `which ffmpeg && which ffprobe`

**Database vergrendeld**
- Er mag slechts één daemon-instantie tegelijk actief zijn
- Beëindig bestaande processen: `pkill reelvault-core`

**Poort al in gebruik**
- Wijzig de poort in `core/src/main.rs` als 50051 bezet is
- Of beëindig het proces: `lsof -ti:50051 | xargs kill`

**Bouwproblemen op Linux**
- Installeer aanvullende ontwikkelafhankelijkheden: `sudo apt-get install build-essential libssl-dev`

## Volgende Stappen

1. **macOS Client**: SwiftUI-frontend implementeren (na MVP)
2. **Desktop Client**: Kotlin Compose-frontend implementeren (MVP-prioriteit)
3. **Testing**: Integratietests toevoegen voor de gRPC API
4. **CI/CD**: GitHub Actions instellen voor builds en releases
