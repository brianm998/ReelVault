> [Англійський оригінал](SETUP.md)

# Налаштування середовища розробки ReelVault

## Передумови

### Системні вимоги

- **macOS 11+**, **Linux (Ubuntu 20.04+)** або **Windows 10+**
- **Rust 1.70+** ([Встановити через rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (для аналізу відео та генерації мініатюр)

### Встановлення FFmpeg

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
Завантажте з https://ffmpeg.org/download.html або використовуйте:
```bash
choco install ffmpeg
```

### Встановлення Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Перевірка:
```bash
rustc --version
cargo --version
```

## Збірка Core

```bash
cd core
cargo build --release
```

Скомпільований двійковий файл буде розташований у `core/target/release/reelvault-core`.

## Запуск основного демона

```bash
./core/target/release/reelvault-core
```

Демон:
- Створить базу даних у `~/.reelvault/catalog.db`
- Створить директорію кешу мініатюр
- Буде слухати на `127.0.0.1:50051` для gRPC-з'єднань

## Запуск тестів

```bash
cd core
cargo test
```

## Команди розробки

**Перевірити код без компіляції:**
```bash
cargo check
```

**Відформатувати код:**
```bash
cargo fmt
```

**Перевірити код на помилки:**
```bash
cargo clippy
```

**Зібрати з символами налагодження:**
```bash
cargo build
```

## Підключення фронтенду

Коли основний демон запущений, фронтенди можуть підключатися через gRPC за адресою `http://127.0.0.1:50051`.

Визначення proto знаходяться в `core/proto/reelvault.proto` та мають використовуватися для генерації клієнтського коду для кожної мови фронтенду.

## Усунення неполадок

**"ffmpeg не знайдено"**
- Переконайтеся, що FFmpeg встановлений та знаходиться у вашому PATH
- Перевірте: `which ffmpeg && which ffprobe`

**База даних заблокована**
- В один момент має працювати лише один екземпляр демона
- Завершіть існуючі процеси: `pkill reelvault-core`

**Порт вже використовується**
- Змініть порт у `core/src/main.rs`, якщо 50051 зайнятий
- Або завершіть процес: `lsof -ti:50051 | xargs kill`

**Проблеми зі збіркою на Linux**
- Встановіть додаткові dev-залежності: `sudo apt-get install build-essential libssl-dev`

## Наступні кроки

1. **macOS-клієнт**: Реалізувати фронтенд SwiftUI (після MVP)
2. **Desktop-клієнт**: Реалізувати фронтенд Kotlin Compose (пріоритет MVP)
3. **Тестування**: Додати інтеграційні тести для gRPC API
4. **CI/CD**: Налаштувати GitHub Actions для збірок та релізів
