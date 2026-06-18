> [English original](SETUP.md)

# Настройка среды разработки ReelVault

## Требования

### Системные требования

- **macOS 11+**, **Linux (Ubuntu 20.04+)** или **Windows 10+**
- **Rust 1.70+** ([Установка через rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (для анализа видео и генерации миниатюр)

### Установка FFmpeg

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
Скачайте с https://ffmpeg.org/download.html или используйте:
```bash
choco install ffmpeg
```

### Установка Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Проверка:
```bash
rustc --version
cargo --version
```

## Сборка ядра

```bash
cd core
cargo build --release
```

Скомпилированный бинарный файл будет находиться по пути `core/target/release/reelvault-core`.

## Запуск основного демона

```bash
./core/target/release/reelvault-core
```

Демон выполнит следующие действия:
- Создаст базу данных по пути `~/.reelvault/catalog.db`
- Создаст каталог кэша миниатюр
- Будет ожидать gRPC-подключений на `127.0.0.1:50051`

## Запуск тестов

```bash
cd core
cargo test
```

## Команды разработки

**Проверка кода без компиляции:**
```bash
cargo check
```

**Форматирование кода:**
```bash
cargo fmt
```

**Линтинг кода:**
```bash
cargo clippy
```

**Сборка с отладочными символами:**
```bash
cargo build
```

## Подключение фронтенда

После запуска основного демона фронтенды могут подключаться через gRPC по адресу `http://127.0.0.1:50051`.

Определения proto находятся в `core/proto/reelvault.proto` и должны использоваться для генерации клиентского кода для каждого языка фронтенда.

## Устранение неполадок

**«ffmpeg not found»**
- Убедитесь, что FFmpeg установлен и находится в PATH
- Проверьте: `which ffmpeg && which ffprobe`

**База данных заблокирована**
- Одновременно должен работать только один экземпляр демона
- Завершите все существующие процессы: `pkill reelvault-core`

**Порт уже используется**
- Измените порт в `core/src/main.rs`, если 50051 занят
- Или завершите процесс: `lsof -ti:50051 | xargs kill`

**Проблемы сборки на Linux**
- Установите дополнительные зависимости для разработки: `sudo apt-get install build-essential libssl-dev`

## Следующие шаги

1. **macOS Client**: Реализовать фронтенд на SwiftUI (после MVP)
2. **Desktop Client**: Реализовать фронтенд на Kotlin Compose (приоритет MVP)
3. **Testing**: Добавить интеграционные тесты для gRPC API
4. **CI/CD**: Настроить GitHub Actions для сборки и релизов
