> [Anglický originál](SETUP.md)

# Nastavení vývojového prostředí ReelVault

## Předpoklady

### Systémové požadavky

- **macOS 11+**, **Linux (Ubuntu 20.04+)** nebo **Windows 10+**
- **Rust 1.70+** ([Instalace přes rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (pro analýzu videa a generování náhledů)

### Instalace FFmpeg

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
Stáhněte z https://ffmpeg.org/download.html nebo použijte:
```bash
choco install ffmpeg
```

### Instalace Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Ověření:
```bash
rustc --version
cargo --version
```

## Sestavení Core

```bash
cd core
cargo build --release
```

Zkompilovaný binární soubor bude na `core/target/release/reelvault-core`.

## Spuštění démona Core

```bash
./core/target/release/reelvault-core
```

Démon:
- Vytvoří databázi na `~/.reelvault/catalog.db`
- Vytvoří adresář pro cache náhledů
- Bude naslouchat na `127.0.0.1:50051` pro gRPC připojení

## Spuštění testů

```bash
cd core
cargo test
```

## Vývojové příkazy

**Zkontrolovat kód bez kompilace:**
```bash
cargo check
```

**Formátovat kód:**
```bash
cargo fmt
```

**Provést linting kódu:**
```bash
cargo clippy
```

**Sestavit s ladicími symboly:**
```bash
cargo build
```

## Připojení frontendu

Jakmile je démon core spuštěn, frontendy se mohou připojit přes gRPC na `http://127.0.0.1:50051`.

Definice proto jsou v `core/proto/reelvault.proto` a měly by být použity pro generování klientského kódu pro každý frontendový jazyk.

## Řešení problémů

**"ffmpeg nenalezeno"**
- Ujistěte se, že FFmpeg je nainstalován a je ve vašem PATH
- Zkontrolujte: `which ffmpeg && which ffprobe`

**Databáze zamčena**
- V jednu chvíli by měla běžet pouze jedna instance démona
- Ukončete existující procesy: `pkill reelvault-core`

**Port je již obsazen**
- Změňte port v `core/src/main.rs`, pokud je 50051 obsazen
- Nebo ukončete proces: `lsof -ti:50051 | xargs kill`

**Problémy se sestavením na Linuxu**
- Nainstalujte další dev závislosti: `sudo apt-get install build-essential libssl-dev`

## Další kroky

1. **macOS klient**: Implementovat frontend SwiftUI (po MVP)
2. **Desktop klient**: Implementovat frontend Kotlin Compose (priorita MVP)
3. **Testování**: Přidat integrační testy pro gRPC API
4. **CI/CD**: Nastavit GitHub Actions pro sestavení a vydávání verzí
