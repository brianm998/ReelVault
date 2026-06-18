> [English original](README.md)

# ReelVault

Lightroom'dan ilham alan, büyük video kütüphanelerini hızlı ve yerel olarak taramak
için tasarlanmış çapraz platform bir video kataloglama uygulaması.

**ReelVault; videoları düzenlemek, keşfetmek ve yönetmek için kullanılır. Video editörü DEĞİLDİR.**

## Genel Bakış

ReelVault ile şunları yapabilirsiniz:

- **Binlerce videoyu** ayarlanabilir küçük resim boyutuyla duyarlı, sanallaştırılmış bir
  ızgarada **tarayın**.
- **Lightroom tarzı scrubbing**: Küçük resmin üzerine gelin ve zaman çizelgesi boyunca
  kareleri önizlemek için sola↔sağa kaydırın.
- **İlgili varyantları** (örn. aynı kaynaktan 4K ve 1080p dışa aktarmalar) Lightroom
  tarzı yığınlara **gruplayın**; ızgara ve açma için birini tercih olarak işaretleyin.
- Tam metin arama, etiket filtreleme ve alan başına açılır filtreler (kamera, lens,
  codec, çekim yılı, anahtar kelime) aracılığıyla içerik **keşfedin**.
- Codec, çözünürlük, FPS, bit hızı, renk uzayı, HDR, EXIF, GPS, kamera/lens modeli
  gibi ayrıntılı meta verileri **inceleyin**.
- Serbest biçimli notlar, anahtar kelimeler ve çoklu seçim işlemleriyle **düzenleyin**.
- Klipleri harici editörlere sürükle-bırak yöntemiyle **aktarın** — bir veya daha fazla
  kartı doğrudan ızgaradan DaVinci Resolve, Final Cut Pro, Premiere Pro veya dosya
  bırakmayı destekleyen herhangi bir uygulamaya sürükleyin.
- **Çoklu katalog** iş akışları: Dosya menüsünden SQLite kataloglarını açın / kapatın /
  değiştirin; son kullanılan kataloglar listesi ve her katalog için pencere başlıkları.

## Mimari

```
Desktop / macOS clients          iOS client (iPhone / iPad)
   ↓ gRPC over loopback             ↓ gRPC + HTTPS media over the LAN
   │                                │ (mDNS discovery · pinned TLS · paired)
   └───────────────┬────────────────┘
                   ↓
        Rust Backend Daemon (reelvault-core)
                   ↓
        SQLite Catalog + FFmpeg/FFprobe + Filesystem
```

- **Rust core** (`core/`) — Tonic tabanlı gRPC daemon'ı. SQLite kataloğunu, FFprobe meta
  veri çıkarımını, küçük resim ve scrub karesi üretimini, tarama/indekslemeyi, aramayı ve
  filtre toplamayı yönetir. `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog` RPC'leri
  aracılığıyla çalışma zamanında aktif katalog değişimini destekler; böylece tek bir daemon
  süreci ömrü boyunca birden fazla kütüphaneye hizmet verebilir.

- **Kotlin Compose desktop client** (`desktop/`) — Compose Multiplatform arayüzü.
  `127.0.0.1:50051` adresinde çalışan bir daemon'ı otomatik olarak algılar; eğer çalışan
  yoksa, paketlenmiş daemon'ı kendisi başlatır (50051 meşgulse işletim sisteminin atadığı
  porta geçer).

- **SwiftUI macOS client** (`macos/`) — Aynı otomatik başlatma akışına, gerçek bir macOS
  Dosya menüsüne (Commands grubu) ve açık kataloğu izleyen reaktif bir pencere başlığına
  sahip, tam özellik eşliğinde yerel macOS uygulaması.

- **SwiftUI iOS client** (`ios/`) — **Yalnızca uzak** iPhone/iPad uygulaması.
  Yerel dosya erişimi yoktur ve gömülü daemon içermez: daemon'ı Wi‑Fi üzerinden (mDNS)
  keşfeder, tek seferlik eşleştirmeden sonra parmak izi sabitlenmiş TLS kanalı üzerinden
  bağlanır, gRPC üzerinden tarar ve daemon'ın medya sunucusundan video **akışı** yapar
  (küçültülmüş HLS). Editöre sürüklemeyi iOS paylaşım sayfasıyla değiştirir ve Fotoğraflar /
  Dosyalar'dan yükleme ekler. Bkz. [`ios/README.md`](ios/README.md).

- **ReelVaultKit** (`kit/`) — **Her iki** Apple istemcisi tarafından kullanılan yerel bir
  SwiftPM paketi: modeller, görünüm modelleri, gRPC istemcisi, keşif, sabitlenmiş TLS ve
  medya önbelleği/akış katmanı.

- **SQLite kataloğu** — Tam metin arama için FTS5 ile WAL modunda veritabanı.
  Şema [`core/schema.sql`](core/schema.sql) dosyasında bulunur.

> **ProRes RAW küçük resimleri yalnızca macOS kalitesindedir.** ffmpeg ProRes RAW
> (Atomos S-Log3 / S-Gamut) dosyalarını işleyemez, bu nedenle **macOS** üzerinde daemon
> bunu QuickLook / AVFoundation aracılığıyla çözer — doğru renk ve gerçek kare başına
> scrubbing. **Linux / Windows** üzerinde böyle bir çözücü yoktur, bu yüzden daemon
> ffmpeg'e geri döner: daha düz/daha karanlık kareler ve tek bir tekrarlanan scrub karesi.
> Rust core üç platformda da aynı şekilde derlenir — AVFoundation hiçbir zaman ona
> bağlanmaz. Ayrıntılar: [`core/README.md`](core/README.md) (derleme ve çözme yolları)
> ve [`macos/README.md`](macos/README.md) (macOS istemcisi). Diğer tüm codec'ler
> her yerde ffmpeg tarafından aynı şekilde işlenir.

## Proje Durumu

**MVP, macOS ve Compose Desktop'ta işlevseldir.** Her iki istemci de aynı özellik setini
sunar; macOS istemcisi yerel menü çubuğu komutları ve NSWorkspace aracılığıyla editör
başlatma ekler. **Yalnızca uzak iOS istemcisi** (iPhone / iPad) LAN üzerinden bir
daemon'a bağlanır ve video akışı yapar — tarama, inceleme, yığın, paylaşma ve yükleme;
bkz. [`ios/README.md`](ios/README.md).

### ✅ Tamamlananlar

**Core**
- [x] Tam RPC yüzeyli gRPC daemon'ı (videolar, arama, tarama, etiketler,
      koleksiyonlar, yığınlar, filtreler, durum, yapılandırma, katalog yaşam döngüsü).
- [x] WAL modu + FTS5 ile SQLite kataloğu; `OpenCatalog` / `CloseCatalog` aracılığıyla
      çalışma zamanında katalog değişimi.
- [x] FFprobe meta veri çıkarımı (codec, çözünürlük, FPS, bit hızı, HDR,
      EXIF, GPS, kamera/lens).
- [x] Küçük resim ve Lightroom tarzı scrub karesi üretimi (video başına 10 kare),
      tekrarlanan işleri önlemek için video başına kilitlerle.
- [x] Büyük kütüphane taramalarının SAN destekli depolamayı aşırı yüklememesi için
      eşzamanlı ffmpeg kısıtlaması (varsayılan olarak ana CPU çekirdek sayısı).
- [x] İsteğe bağlı özyinelemeli ve varyantların otomatik gruplamasıyla kütüphane tarama.
- [x] `--db-path`, `--no-catalog`, `--port` (işletim sistemi tarafından atanan port
      geri dönüşüyle birlikte) için CLI bayrakları ve istemci başlatıcıları için
      ayrıştırılabilir `REELVAULT_LISTENING_ON=…` stdout satırı.

**Her iki istemci**
- [x] Uyarlanabilir sütun sayısı ve küçük resim boyutu kaydırıcısıyla sanallaştırılmış
      ızgara görünümü.
- [x] Hover scrub önizleme, hover oynatma katmanı, shift-aralık ve ⌘/Ctrl geçiş ile
      çoklu seçim.
- [x] Yığın (grup) arayüzü: üye sayısı gösteren yığın rozetleri, genişletmek için
      tıklama, tercih ayarlamak için yıldız, üye başına açma düğmeleri.
- [x] Yan paneller: kütüphane konumları (sol), ayrıntılar + meta veriler + notlar +
      anahtar kelimeler (sağ). Tab her ikisini de değiştirir, bağımsız şevrolar her birini
      daraltır.
- [x] Üst çubuk filtre açılır menüleri (kamera / lens / anahtar kelime / codec / yıl) —
      yalnızca veri içeren alanlar gösterilir; aramayla AND olarak birleştirilir.
- [x] Tüm ana alanları içeren sıralama menüsü (dosya adı, tarihler, süre, boyut,
      çözünürlük, fps, codec, bit hızı, kamera, lens, anahtar kelime); yönü tersine
      çevirmek için tekrar tıklayın.
- [x] Etiket/anahtar kelime yönetimi: anında oluşturma, çoklu seçime uygulama,
      `>` şevronuna tıklayarak ızgarayı filtreleme.
- [x] Her kartta sağ tıklama bağlam menüsü: Varsayılan Oynatıcıyla Aç ve
      Finder/Explorer'da Göster — tam çoklu seçimde çalışır.
- [x] Sürükle-bırak aktarımı: seçili kartları dosya bırakmayı destekleyen herhangi bir
      uygulamaya sürükleyin (DaVinci Resolve, Final Cut Pro, Premiere Pro, vb.).
- [x] Çoklu katalog akışı: Aç / Kapat / Son Açılanlar içeren Dosya menüsü,
      ilk başlatmada "bir katalog aç" sayfası, kalıcı son kullanılanlar listesi, açık
      kataloğun adını gösteren pencere başlığı.
- [x] Başlangıçta hiçbir arka uç çalışmıyorsa paketlenmiş daemon'ın otomatik başlatılması
      (port meşgulken yedek dahil).
- [x] Her etkileşimli öğe ve meta veri alanında hover metin yardımı (Kotlin'de `TooltipArea`
      ile araç ipuçları; SwiftUI'de `.help(_:)` ile).
- [x] Varsayılan koyu mod; açık/koyu tema geçişi.

### 🚧 Planlanıyor

- [ ] Gerçek zamanlı dosya izleme (temel klasör değiştiğinde yeniden dizinleme).
- [ ] Akıllı koleksiyonlar (canlı filtre kurallarıyla kaydedilmiş aramalar).
- [ ] 8K+ çekimler için proxy video üretimi.
- [ ] GitHub tabanlı otomatik güncelleme.
- [ ] Platform başına imzalı yükleyiciler üreten CI/CD sürüm hattı.
- [ ] Daemon ikilisinin istemci uygulama paketlerine dahil edilmesi (şu anda başlatıcı
      onu `REELVAULT_CORE_BIN` veya bir cargo geliştirici ağacı aracılığıyla bulur).

## Başlarken

### Ön Koşullar

- **Rust** (1.75+ önerilir) — temel daemon'ı derlemek için.
- **FFmpeg / FFprobe** — `PATH` üzerinde olmalıdır. Meta veri çıkarımı ve
  küçük resim/scrub karesi üretimi için kullanılır.
- **JDK 17+** + Gradle (sarmalayıcı dahil) — Kotlin masaüstü istemcisi için.
- **Swift 5.9+ / Xcode 15+** — macOS istemcisi için.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — iOS istemcisi için.

Platforma özgü kurulum talimatları için bkz. [`SETUP.md`](SETUP.md).

### Temel Daemon'ı Derleme

```bash
cd core
cargo build --release
```

İkili dosya `core/target/release/reelvault-core` konumuna yerleşir. Kendiniz yönetmek
istiyorsanız doğrudan çalıştırın ya da istemcilerden birinin ilk başlatmada sizin için
başlatmasına izin verin:

```bash
# Varsayılan — varsayılan portta platform varsayılan kataloğunu kullan.
./target/release/reelvault-core

# Katalog olmadan başlat (istemciler bu modu kullanır); bağlanan portu yazdır.
./target/release/reelvault-core --no-catalog --port 0

# Başlatmada belirli bir kataloğu aç.
./target/release/reelvault-core --db-path /path/to/library.db
```

Daemon, istemcilerin atanan portu keşfetmek için ayrıştırdığı kararlı bir
`REELVAULT_LISTENING_ON=127.0.0.1:N` satırını stdout'a yazdırır.

### Kotlin Compose Desktop Client'ı Çalıştırma

```bash
cd desktop
./gradlew run
```

İlk başlatmada istemci `127.0.0.1:50051`'i araştırır ve — hiçbir şey dinlemiyorsa —
paketlenmiş daemon'ı başlatır. Derleme konumu arama sırası:

1. `$REELVAULT_CORE_BIN` (daemon yürütülebilir dosyasına mutlak yol)
2. Uygulama jar'ının yanındaki bir ikili dosya
3. Geliştirici ağacında `core/target/release/reelvault-core` veya `core/target/debug/reelvault-core`
4. `PATH` üzerinde `reelvault-core`

Geliştirme için `core/` içinde `cargo build` çalıştırın; istemci hata ayıklama ikilisini
otomatik olarak bulur.

### SwiftUI macOS Client'ı Çalıştırma

```bash
cd macos
swift run
```

Aynı otomatik başlatma akışı, aynı ikili arama sırası (imzalı uygulama paketleri
tarafından kullanılan bir paket içi `Resources/reelvault-core` yolunun eklenmesiyle
birlikte). Bir kataloğu açmak için `⌘O`, kapatmak için `⇧⌘W` kullanın; son kullanılanlar
listesi `File → Open Recent` altındadır.

### SwiftUI iOS Client'ı Çalıştırma

iOS istemcisi **yalnızca uzaktan** çalışır — kendi daemon'ını başlatmak yerine LAN
üzerinden bir daemon'a bağlanır. Daemon'ı aynı Wi‑Fi'deki bir makinede uzak modda
başlatın:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Ardından Xcode projesini oluşturun (`.xcodeproj` depoya dahil edilmez) ve derleyin:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

İlk başlatmada uygulama daemon'ı mDNS üzerinden keşfeder, cihazı 6 haneli bir eşleştirme
koduyla bir kez yetkilendirirsiniz (masaüstü istemcisindeki **File → Pair a New Device**
bölümünden veya daemon günlüğünden oluşturun), ardından tarayabilir ve akış yapabilirsiniz.
**iOS 18+** ve **Xcode 16+** gerektirir. Akış ve eşleştirme modeli de dahil olmak üzere
tam ayrıntılar için bkz. [`ios/README.md`](ios/README.md).

## Belgeler

- [`CLAUDE.md`](CLAUDE.md) — Proje vizyonu, mimari ayrıntılar, veritabanı şeması
  ve tasarım ilkeleri.
- [`SETUP.md`](SETUP.md) — Geliştirme ortamı kurulumu.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Rust core'un modülleri ve RPC yüzeyi için
  ayrıntılı referans.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Kotlin ve SwiftUI istemcilerinin
  yan yana karşılaştırması.
- [`ios/README.md`](ios/README.md) — Yalnızca uzak iOS istemcisi (iPhone / iPad):
  keşif, eşleştirme, sabitlenmiş TLS ve HLS akışı.
- [`macos/README.md`](macos/README.md) — Yerel macOS istemcisi.

## Katkıda Bulunma

Geliştirme yönergeleri için bkz. [`CLAUDE.md`](CLAUDE.md). Pull request'ler memnuniyetle
karşılanır — lütfen iki istemci arasında özellik eşliğini koruyun ve yeni kaynak
dosyalara SPDX başlıkları ekleyin (aşağıdaki Lisans bölümüne bakın).

## Lisans

ReelVault, **GNU General Public License, sürüm 3 veya (tercihine göre) sonraki herhangi
bir sürüm** kapsamında lisanslı özgür yazılımdır. Lisansın tam metni [`LICENSE`](LICENSE)
dosyasında; kısa telif hakkı bildirimi ise [`COPYRIGHT`](COPYRIGHT) dosyasındadır.

Her kaynak dosya bir SPDX tanımlayıcısı içerir; bu sayede lisans tarama araçları
(REUSE, FOSSology, GitHub'ın licensee dedektörü vb.) lisansı programatik olarak
tanımlayabilir:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

ReelVault'un değiştirilmiş bir sürümünü — ya da Rust core'u bir kütüphane olarak
bağlayan herhangi bir programı — dağıtıyorsanız, GPL kaynak kodunuzu aynı koşullar
altında kullanılabilir yapmanızı zorunlu kılar. Yükümlülüklerin tam listesi için
LICENSE dosyasına bakın.
