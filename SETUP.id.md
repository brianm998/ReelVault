> [Versi asli bahasa Inggris](SETUP.md)

# Penyiapan Pengembangan ReelVault

## Prasyarat

### Persyaratan Sistem

- **macOS 11+**, **Linux (Ubuntu 20.04+)**, atau **Windows 10+**
- **Rust 1.70+** ([Instal melalui rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (untuk analisis video dan pembuatan thumbnail)

### Instal FFmpeg

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
Unduh dari https://ffmpeg.org/download.html atau gunakan:
```bash
choco install ffmpeg
```

### Instal Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Verifikasi:
```bash
rustc --version
cargo --version
```

## Membangun Core

```bash
cd core
cargo build --release
```

Binary yang dikompilasi akan berada di `core/target/release/reelvault-core`.

## Menjalankan Daemon Core

```bash
./core/target/release/reelvault-core
```

Daemon akan:
- Membuat database di `~/.reelvault/catalog.db`
- Membuat direktori cache thumbnail
- Mendengarkan di `127.0.0.1:50051` untuk koneksi gRPC

## Menjalankan Tes

```bash
cd core
cargo test
```

## Perintah Pengembangan

**Periksa kode tanpa mengompilasi:**
```bash
cargo check
```

**Format kode:**
```bash
cargo fmt
```

**Lint kode:**
```bash
cargo clippy
```

**Build dengan simbol debug:**
```bash
cargo build
```

## Menghubungkan Frontend

Setelah daemon core berjalan, frontend dapat terhubung melalui gRPC di `http://127.0.0.1:50051`.

Definisi proto ada di `core/proto/reelvault.proto` dan harus digunakan untuk menghasilkan kode klien untuk setiap bahasa frontend.

## Pemecahan Masalah

**"ffmpeg tidak ditemukan"**
- Pastikan FFmpeg terinstal dan ada di PATH Anda
- Periksa: `which ffmpeg && which ffprobe`

**Database terkunci**
- Hanya satu instance daemon yang boleh berjalan sekaligus
- Matikan proses yang ada: `pkill reelvault-core`

**Port sudah digunakan**
- Ubah port di `core/src/main.rs` jika 50051 sudah terpakai
- Atau matikan prosesnya: `lsof -ti:50051 | xargs kill`

**Masalah build di Linux**
- Instal dependensi dev tambahan: `sudo apt-get install build-essential libssl-dev`

## Langkah Selanjutnya

1. **Klien macOS**: Implementasikan frontend SwiftUI (pasca-MVP)
2. **Klien Desktop**: Implementasikan frontend Kotlin Compose (prioritas MVP)
3. **Pengujian**: Tambahkan tes integrasi untuk gRPC API
4. **CI/CD**: Siapkan GitHub Actions untuk build dan rilis
