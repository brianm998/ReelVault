> [Versi asli bahasa Inggris](README.md)

# ReelVault

Aplikasi katalogisasi video lintas platform yang terinspirasi dari Lightroom — penelusuran perpustakaan video besar yang cepat dan native.

**ReelVault dibuat untuk mengorganisasi, menemukan, dan mengelola video. Ini BUKAN editor video.**

## Ikhtisar

ReelVault membantu Anda:

- **Menelusuri** ribuan video dalam grid yang responsif dan tervirtualisasi dengan ukuran thumbnail yang dapat disesuaikan.
- **Scrub** gaya Lightroom: arahkan kursor ke thumbnail dan geser kiri↔kanan untuk melihat pratinjau frame di sepanjang timeline.
- **Mengelompokkan** varian terkait (misalnya ekspor 4K dan 1080p dari sumber yang sama) ke dalam tumpukan gaya Lightroom; tandai satu sebagai yang diutamakan untuk grid + buka.
- **Menemukan** konten melalui pencarian teks lengkap, pemfilteran tag, dan dropdown filter per-field (kamera, lensa, codec, tahun rekaman, kata kunci).
- **Memeriksa** metadata terperinci: codec, resolusi, FPS, bitrate, ruang warna, HDR, EXIF, GPS, model kamera/lensa.
- **Mengorganisasi** dengan catatan bebas, kata kunci, dan operasi multi-pilih.
- **Menyerahkan** klip ke editor eksternal melalui seret-dan-lepas — seret satu atau lebih kartu langsung dari grid ke DaVinci Resolve, Final Cut Pro, Premiere Pro, atau aplikasi apa pun yang menerima peletakan file.
- Alur kerja **multi-katalog**: buka / tutup / alihkan katalog SQLite dari menu File, dengan daftar katalog-terbaru dan judul jendela per-katalog.

## Arsitektur

```
Desktop / macOS clients          iOS / Android clients
   ↓ gRPC over loopback             ↓ gRPC + HTTPS media over the LAN
   │                                │ (mDNS/NSD discovery · pinned TLS · paired)
   └───────────────┬────────────────┘
                   ↓
        Rust Backend Daemon (reelvault-core)
                   ↓
        SQLite Catalog + FFmpeg/FFprobe + Filesystem
```

- **Rust core** (`core/`) — Daemon gRPC berbasis Tonic. Mengelola katalog SQLite, ekstraksi metadata FFprobe, pembuatan thumbnail + scrub-frame, pemindaian/pengindeksan, pencarian, dan agregasi filter. Mendukung hot-swap katalog aktif saat runtime melalui RPC `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog`, sehingga satu proses daemon dapat melayani beberapa perpustakaan selama masa pakainya.

- **Klien desktop Kotlin Compose** (`kotlin-desktop/`) — UI Compose Multiplatform. Mendeteksi daemon yang berjalan di `127.0.0.1:50051` secara otomatis; jika tidak ada yang berjalan, daemon bundel dijalankan sendiri (dengan fallback ke port yang ditetapkan OS jika 50051 sibuk).

- **Klien SwiftUI macOS** (`macos/`) — Aplikasi macOS native dengan paritas fitur, alur auto-spawn yang sama, menu File macOS asli (grup Commands), dan judul jendela reaktif yang melacak katalog yang terbuka.

- **Klien SwiftUI iOS** (`ios/`) — Aplikasi iPhone / iPad **hanya-remote**. Tidak memiliki akses file lokal dan tidak menyematkan daemon: menemukan daemon melalui Wi‑Fi (mDNS), terhubung melalui saluran TLS yang disematkan sidik jari setelah pemasangan sekali, menelusuri melalui gRPC, dan **streaming** video (HLS yang diturunkan skalanya) dari server media daemon. Menggantikan drag-out editor dengan lembar berbagi iOS dan menambahkan unggahan dari Foto / File. Lihat [`ios/README.md`](ios/README.md).

- **Kotlin Compose Android client** (`android/`) — A **remote-only**
  Android phone / tablet app. Connects to a daemon over the LAN (NSD
  discovery), streams video via ExoPlayer, and replaces editor drag-out with
  the Android share intent. Also embeds the full Rust core for on-device local
  library access — browse, catalog, and upload footage directly from the
  device. See [`android/README.md`](android/README.md).

- **ReelVaultKit** (`kit/`) — Paket SwiftPM lokal dari Swift bersama yang digunakan oleh **kedua** klien Apple: model, view-model, klien gRPC, discovery, TLS yang disematkan, dan lapisan cache/streaming media.

- **Katalog SQLite** — Database mode WAL dengan FTS5 untuk pencarian teks lengkap. Skema ada di [`core/schema.sql`](core/schema.sql).

> **Thumbnail ProRes RAW memiliki kualitas eksklusif macOS.** ffmpeg tidak dapat mengembangkan ProRes RAW (Atomos S-Log3 / S-Gamut), sehingga di **macOS** daemon mendekodenya melalui QuickLook / AVFoundation — warna yang benar dan scrubbing per-frame yang sesungguhnya. Di **Linux / Windows** tidak ada dekoder seperti itu, sehingga daemon jatuh ke ffmpeg: frame yang lebih datar/gelap dan satu frame scrub yang diulang. Rust core dibangun identik di ketiga platform — AVFoundation tidak pernah ditautkan ke dalamnya. Detail: [`core/README.md`](core/README.md) (jalur build + dekode) dan [`macos/README.md`](macos/README.md) (klien macOS). Setiap codec lain ditangani oleh ffmpeg dengan cara yang sama di mana saja.

## Status proyek

**MVP berfungsi di macOS dan Compose Desktop.** Kedua klien memiliki fitur yang sama; klien macOS menambahkan perintah menu-bar native dan peluncuran editor berbasis NSWorkspace. **Klien iOS hanya-remote** (iPhone / iPad) terhubung ke daemon melalui LAN dan streaming video — telusuri, periksa, tumpuk, bagikan, dan unggah; lihat [`ios/README.md`](ios/README.md).

### ✅ Selesai

**Core**
- [x] Daemon gRPC dengan permukaan RPC lengkap (video, pencarian, pemindaian, tag, koleksi, tumpukan, filter, status, konfigurasi, siklus hidup katalog).
- [x] Katalog SQLite dengan mode WAL + FTS5; hot-swap katalog runtime melalui `OpenCatalog` / `CloseCatalog`.
- [x] Rich metadata extraction via FFprobe + platform-native helpers: codec,
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera., resolusi, FPS, bitrate, HDR, EXIF, GPS, kamera/lensa).
- [x] Pembuatan thumbnail dan scrub-frame gaya Lightroom (10 frame per video) dengan kunci per-video untuk mendeduplikasi pekerjaan.
- [x] Pembatasan ffmpeg bersamaan (default ke jumlah CPU host) agar pemindaian perpustakaan besar tidak mengacaukan penyimpanan berbasis SAN.
- [x] Pemindaian perpustakaan dengan rekursi opsional dan pengelompokan varian otomatis.
- [x] Flag CLI untuk `--db-path`, `--no-catalog`, `--port` (dengan fallback port yang ditetapkan OS), dan baris stdout `REELVAULT_LISTENING_ON=…` yang dapat diurai untuk peluncur klien.

**Kedua klien**
- [x] Tampilan grid tervirtualisasi dengan jumlah kolom adaptif dan slider ukuran thumbnail.
- [x] Pratinjau hover-scrub, overlay hover-play, multi-pilih dengan shift-range dan toggle ⌘/Ctrl.
- [x] UI tumpukan (grup): lencana tumpukan dengan jumlah anggota, klik-untuk-perluas, bintang untuk mengatur yang diutamakan, tombol buka per-anggota.
- [x] Panel samping: lokasi perpustakaan (kiri), detail + metadata + catatan + kata kunci (kanan). Tab mengalihkan keduanya, chevron individual menciutkan masing-masing.
- [x] Dropdown filter bilah atas (kamera / lensa / kata kunci / codec / tahun) — hanya field dengan data yang ditampilkan; dikombinasikan AND dengan pencarian.
- [x] Menu urutkan dengan semua field utama (nama file, tanggal, durasi, ukuran, resolusi, fps, codec, bitrate, kamera, lensa, kata kunci); klik lagi untuk membalik arah.
- [x] Manajemen tag/kata kunci: buat saat berjalan, terapkan ke multi-pilihan, filter grid dengan mengklik chevron `>`.
- [x] Menu konteks klik kanan pada setiap kartu: Open with Default Player dan Reveal in Finder/Explorer — beroperasi pada seluruh multi-pilihan.
- [x] Penyerahan seret-dan-lepas: seret kartu yang dipilih ke aplikasi apa pun yang menerima peletakan file (DaVinci Resolve, Final Cut Pro, Premiere Pro, dll.).
- [x] Alur multi-katalog: menu File dengan Open / Close / Open Recent, lembar "buka katalog" saat peluncuran pertama, daftar recents yang persisten, judul jendela yang menampilkan nama katalog yang terbuka.
- [x] Auto-spawn daemon bundel (dengan fallback port-sibuk) saat tidak ada backend yang berjalan saat startup.
- [x] Bantuan teks-hover (tooltip di Kotlin melalui `TooltipArea`; SwiftUI melalui `.help(_:)`) pada setiap elemen interaktif dan field metadata.
- [x] Mode gelap sebagai default; toggle tema terang/gelap.

### 🚧 Direncanakan

- [ ] Pemantauan file real-time (indeks ulang ketika folder dasar berubah).
- [ ] Koleksi cerdas (pencarian tersimpan dengan aturan filter langsung).
- [ ] Pembuatan video proxy untuk rekaman 8K ke atas.
- [ ] Pembaruan otomatis berbasis GitHub.
- [ ] Pipeline rilis CI/CD yang menghasilkan installer bertanda tangan per platform.
- [ ] Menyertakan binary daemon di dalam bundel aplikasi klien (saat ini peluncur menemukannya melalui `REELVAULT_CORE_BIN` atau pohon dev cargo).

## Memulai

### Prasyarat

- **Rust** (direkomendasikan 1.75+) — untuk membangun daemon core.
- **FFmpeg / FFprobe** — harus ada di `PATH`. Digunakan untuk ekstraksi metadata dan pembuatan thumbnail/scrub-frame.
- **JDK 17+** + Gradle (wrapper disertakan) — untuk klien desktop Kotlin.
- **Swift 5.9+ / Xcode 15+** — untuk klien macOS.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — untuk klien iOS.

Lihat [`SETUP.md`](SETUP.md) untuk petunjuk instalasi spesifik platform.

### Bangun daemon core

```bash
cd core
cargo build --release
```

Binary berada di `core/target/release/reelvault-core`. Jalankan langsung jika Anda ingin mengendarainya sendiri, atau biarkan salah satu klien menjalankannya untuk Anda saat peluncuran pertama:

```bash
# Default — gunakan katalog default platform di port default.
./target/release/reelvault-core

# Mulai tanpa katalog (klien menggunakan mode ini); cetak port yang terikat.
./target/release/reelvault-core --no-catalog --port 0

# Buka katalog tertentu saat startup.
./target/release/reelvault-core --db-path /path/to/library.db
```

Daemon mencetak baris `REELVAULT_LISTENING_ON=127.0.0.1:N` yang stabil di stdout yang diurai oleh klien untuk menemukan port yang ditetapkan.

### Jalankan klien desktop Kotlin Compose

```bash
cd kotlin-desktop
./gradlew run
```

Pada peluncuran pertama klien memeriksa `127.0.0.1:50051` dan — jika tidak ada yang mendengarkan — menjalankan daemon bundel. Urutan pencarian lokasi build:

1. `$REELVAULT_CORE_BIN` (jalur absolut ke executable daemon)
2. Binary di sebelah jar aplikasi
3. `core/target/release/reelvault-core` atau `core/target/debug/reelvault-core` di pohon dev
4. `reelvault-core` di `PATH`

Untuk pengembangan, cukup `cargo build` di dalam `core/` dan klien akan mengambil binary debug.

### Jalankan klien SwiftUI macOS

```bash
cd macos
swift run
```

Alur auto-spawn yang sama, urutan pencarian binary yang sama (dengan tambahan jalur `Resources/reelvault-core` dalam-bundel yang digunakan oleh bundel aplikasi bertanda tangan). Gunakan `⌘O` untuk membuka katalog dan `⇧⌘W` untuk menutupnya; daftar terbaru ada di bawah `File → Open Recent`.

### Jalankan klien SwiftUI iOS

Klien iOS adalah **hanya-remote** — terhubung ke daemon melalui LAN alih-alih menjalankan satu. Mulai daemon dalam mode remote pada mesin di Wi‑Fi yang sama:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Kemudian buat proyek Xcode (`.xcodeproj` tidak di-commit) dan build:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

Pada peluncuran pertama aplikasi menemukan daemon melalui mDNS, Anda mengotorisasi perangkat sekali dengan kode pemasangan 6 digit (buat dari **File → Pair a New Device** klien desktop, atau log daemon), lalu telusuri + streaming. Membutuhkan **iOS 18+** dan **Xcode 16+**. Detail lengkap, termasuk model streaming dan pemasangan, ada di [`ios/README.md`](ios/README.md).

## Dokumentasi

- [`CLAUDE.md`](CLAUDE.md) — Visi proyek, detail arsitektur, skema database, dan prinsip desain.
- [`SETUP.md`](SETUP.md) — Penyiapan lingkungan pengembangan.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Referensi terperinci untuk modul Rust core dan permukaan RPC.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Perbandingan berdampingan klien Kotlin dan SwiftUI.
- [`ios/README.md`](ios/README.md) — Klien iOS hanya-remote (iPhone / iPad): discovery, pemasangan, TLS yang disematkan, dan streaming HLS.
- [`macos/README.md`](macos/README.md) — Klien macOS native.

## Berkontribusi

Lihat [`CLAUDE.md`](CLAUDE.md) untuk panduan pengembangan. Pull request disambut — harap jaga paritas fitur di semua empat klien, di mana berlaku
(lihat CLAUDE.md untuk penyimpangan per-platform yang sah), dan tambahkan header SPDX ke file sumber baru apa pun (lihat Lisensi di bawah).

## Lisensi

ReelVault adalah perangkat lunak bebas, dilisensikan di bawah **GNU General Public License, versi 3 atau (sesuai pilihan Anda) versi yang lebih baru**. Teks lisensi lengkap ada di [`LICENSE`](LICENSE); pemberitahuan hak cipta singkat ada di [`COPYRIGHT`](COPYRIGHT).

Setiap file sumber membawa pengenal SPDX sehingga alat pemindaian lisensi (REUSE, FOSSology, detektor licensee GitHub, dll.) dapat mengidentifikasi lisensi secara terprogram:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

Jika Anda mendistribusikan versi modifikasi ReelVault — atau program apa pun yang menautkan ke Rust core sebagai pustaka — GPL mengharuskan Anda membuat sumber Anda tersedia dengan persyaratan yang sama. Lihat file LICENSE untuk kumpulan kewajiban lengkap.
