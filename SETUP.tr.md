> [English original](SETUP.md)

# ReelVault Geliştirme Ortamı Kurulumu

## Ön Koşullar

### Sistem Gereksinimleri

- **macOS 11+**, **Linux (Ubuntu 20.04+)** veya **Windows 10+**
- **Rust 1.70+** ([rustup ile kurulum](https://rustup.rs/))
- **FFmpeg & FFprobe** (video analizi ve küçük resim üretimi için)

### FFmpeg Kurulumu

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
https://ffmpeg.org/download.html adresinden indirin veya şunu kullanın:
```bash
choco install ffmpeg
```

### Rust Kurulumu

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Doğrulama:
```bash
rustc --version
cargo --version
```

## Core'u Derleme

```bash
cd core
cargo build --release
```

Derlenmiş ikili dosya `core/target/release/reelvault-core` konumunda olacaktır.

## Core Daemon'ını Çalıştırma

```bash
./core/target/release/reelvault-core
```

Daemon şunları yapacaktır:
- `~/.reelvault/catalog.db` konumunda bir veritabanı oluşturur
- Küçük resim önbellek dizini oluşturur
- gRPC bağlantıları için `127.0.0.1:50051` adresini dinler

## Testleri Çalıştırma

```bash
cd core
cargo test
```

## Geliştirme Komutları

**Derlemeden kodu kontrol etme:**
```bash
cargo check
```

**Kodu biçimlendirme:**
```bash
cargo fmt
```

**Kodu lint etme:**
```bash
cargo clippy
```

**Hata ayıklama sembolleriyle derleme:**
```bash
cargo build
```

## Ön Yüz Bağlama

Core daemon çalışır hale geldiğinde, ön yüzler `http://127.0.0.1:50051` adresinden gRPC aracılığıyla bağlanabilir.

Proto tanımları `core/proto/reelvault.proto` dosyasındadır ve her ön yüz dili için istemci kodu üretmekte kullanılmalıdır.

## Sorun Giderme

**«ffmpeg not found»**
- FFmpeg'in kurulu olduğundan ve PATH'inizde bulunduğundan emin olun
- Kontrol edin: `which ffmpeg && which ffprobe`

**Veritabanı kilitlendi**
- Aynı anda yalnızca bir daemon örneği çalışmalıdır
- Mevcut süreçleri sonlandırın: `pkill reelvault-core`

**Port zaten kullanımda**
- 50051 doluysa `core/src/main.rs` içindeki portu değiştirin
- Veya süreci sonlandırın: `lsof -ti:50051 | xargs kill`

**Linux'ta derleme sorunları**
- Ek geliştirme bağımlılıklarını kurun: `sudo apt-get install build-essential libssl-dev`

## Sonraki Adımlar

1. **macOS Client**: SwiftUI ön yüzünü uygulayın (MVP sonrası)
2. **Desktop Client**: Kotlin Compose ön yüzünü uygulayın (MVP önceliği)
3. **Testing**: gRPC API için entegrasyon testleri ekleyin
4. **CI/CD**: Derlemeler ve sürümler için GitHub Actions kurun
