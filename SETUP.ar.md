> [النسخة الإنجليزية الأصلية](SETUP.md)

# إعداد بيئة تطوير ReelVault

## المتطلبات الأساسية

### متطلبات النظام

- **macOS 11+**، أو **Linux (Ubuntu 20.04+)**، أو **Windows 10+**
- **Rust 1.70+** ([التثبيت عبر rustup](https://rustup.rs/))
- **FFmpeg و FFprobe** (لتحليل الفيديو وإنشاء الصور المصغرة)

### تثبيت FFmpeg

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
قم بالتنزيل من https://ffmpeg.org/download.html أو استخدم:
```bash
choco install ffmpeg
```

### تثبيت Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

للتحقق:
```bash
rustc --version
cargo --version
```

## بناء النواة

```bash
cd core
cargo build --release
```

سيكون الملف الثنائي المُجمَّع في `core/target/release/reelvault-core`.

## تشغيل خادم النواة

```bash
./core/target/release/reelvault-core
```

سيقوم الخادم بـ:
- إنشاء قاعدة بيانات في `~/.reelvault/catalog.db`
- إنشاء مجلد تخزين مؤقت للصور المصغرة
- الاستماع على `127.0.0.1:50051` لاتصالات gRPC

## تشغيل الاختبارات

```bash
cd core
cargo test
```

## أوامر التطوير

**فحص الكود دون تجميع:**
```bash
cargo check
```

**تنسيق الكود:**
```bash
cargo fmt
```

**فحص جودة الكود:**
```bash
cargo clippy
```

**البناء مع رموز التصحيح:**
```bash
cargo build
```

## الاتصال بواجهة أمامية

بمجرد تشغيل خادم النواة، يمكن للواجهات الأمامية الاتصال عبر gRPC على `http://127.0.0.1:50051`.

تعريفات proto موجودة في `core/proto/reelvault.proto` ويجب استخدامها لإنشاء كود العميل لكل لغة واجهة أمامية.

## استكشاف الأخطاء وإصلاحها

**"لم يتم العثور على ffmpeg"**
- تأكد من تثبيت FFmpeg وأنه في PATH الخاص بك
- تحقق: `which ffmpeg && which ffprobe`

**قاعدة البيانات مقفلة**
- يجب تشغيل نسخة واحدة فقط من الخادم في وقت واحد
- أوقف أي عمليات قائمة: `pkill reelvault-core`

**المنفذ قيد الاستخدام**
- غيّر المنفذ في `core/src/main.rs` إذا كان 50051 مشغولاً
- أو أوقف العملية: `lsof -ti:50051 | xargs kill`

**مشاكل البناء على Linux**
- ثبّت تبعيات التطوير الإضافية: `sudo apt-get install build-essential libssl-dev`

## الخطوات التالية

1. **عميل macOS**: تنفيذ واجهة SwiftUI (ما بعد MVP)
2. **عميل سطح المكتب**: تنفيذ واجهة Kotlin Compose (أولوية MVP)
3. **الاختبار**: إضافة اختبارات تكاملية لـ gRPC API
4. **CI/CD**: إعداد GitHub Actions للبناء والإصدارات
