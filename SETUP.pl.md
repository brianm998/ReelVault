> [English original](SETUP.md)

# Konfiguracja Środowiska Deweloperskiego ReelVault

## Wymagania wstępne

### Wymagania systemowe

- **macOS 11+**, **Linux (Ubuntu 20.04+)** lub **Windows 10+**
- **Rust 1.70+** ([Instalacja przez rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (do analizy wideo i generowania miniatur)

### Instalacja FFmpeg

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
Pobierz z https://ffmpeg.org/download.html lub użyj:
```bash
choco install ffmpeg
```

### Instalacja Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Weryfikacja:
```bash
rustc --version
cargo --version
```

## Budowanie Rdzenia

```bash
cd core
cargo build --release
```

Skompilowany plik binarny znajdzie się pod ścieżką `core/target/release/reelvault-core`.

## Uruchamianie Demona Core

```bash
./core/target/release/reelvault-core
```

Demon wykona następujące działania:
- Utworzy bazę danych pod ścieżką `~/.reelvault/catalog.db`
- Utworzy katalog pamięci podręcznej miniatur
- Będzie nasłuchiwał połączeń gRPC na `127.0.0.1:50051`

## Uruchamianie Testów

```bash
cd core
cargo test
```

## Polecenia Deweloperskie

**Sprawdzanie kodu bez kompilacji:**
```bash
cargo check
```

**Formatowanie kodu:**
```bash
cargo fmt
```

**Lintowanie kodu:**
```bash
cargo clippy
```

**Budowanie z symbolami debugowania:**
```bash
cargo build
```

## Podłączanie Frontendu

Gdy demon core jest uruchomiony, frontendy mogą łączyć się przez gRPC pod adresem `http://127.0.0.1:50051`.

Definicje proto znajdują się w `core/proto/reelvault.proto` i powinny być używane do generowania kodu klienckiego dla każdego języka frontendowego.

## Rozwiązywanie Problemów

**«ffmpeg not found»**
- Upewnij się, że FFmpeg jest zainstalowany i znajduje się w PATH
- Sprawdź: `which ffmpeg && which ffprobe`

**Baza danych zablokowana**
- W danym momencie powinna działać tylko jedna instancja demona
- Zakończ istniejące procesy: `pkill reelvault-core`

**Port jest już używany**
- Zmień port w `core/src/main.rs`, jeśli 50051 jest zajęty
- Lub zakończ proces: `lsof -ti:50051 | xargs kill`

**Problemy z budowaniem na Linux**
- Zainstaluj dodatkowe zależności deweloperskie: `sudo apt-get install build-essential libssl-dev`

## Następne Kroki

1. **macOS Client**: Zaimplementuj frontend SwiftUI (po MVP)
2. **Desktop Client**: Zaimplementuj frontend Kotlin Compose (priorytet MVP)
3. **Testing**: Dodaj testy integracyjne dla gRPC API
4. **CI/CD**: Skonfiguruj GitHub Actions dla buildów i wydań
