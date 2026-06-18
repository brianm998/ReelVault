> [ต้นฉบับภาษาอังกฤษ](SETUP.md)

# การตั้งค่าการพัฒนา ReelVault

## ข้อกำหนดเบื้องต้น

### ความต้องการของระบบ

- **macOS 11+**, **Linux (Ubuntu 20.04+)**, หรือ **Windows 10+**
- **Rust 1.70+** ([ติดตั้งผ่าน rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (สำหรับการวิเคราะห์วิดีโอและการสร้างภาพขนาดย่อ)

### ติดตั้ง FFmpeg

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
ดาวน์โหลดจาก https://ffmpeg.org/download.html หรือใช้:
```bash
choco install ffmpeg
```

### ติดตั้ง Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

ตรวจสอบ:
```bash
rustc --version
cargo --version
```

## สร้าง Core

```bash
cd core
cargo build --release
```

binary ที่คอมไพล์แล้วจะอยู่ที่ `core/target/release/reelvault-core`

## รัน Core Daemon

```bash
./core/target/release/reelvault-core
```

daemon จะ:
- สร้างฐานข้อมูลที่ `~/.reelvault/catalog.db`
- สร้างไดเรกทอรีแคชภาพขนาดย่อ
- รับฟังที่ `127.0.0.1:50051` สำหรับการเชื่อมต่อ gRPC

## รันการทดสอบ

```bash
cd core
cargo test
```

## คำสั่งการพัฒนา

**ตรวจสอบโค้ดโดยไม่คอมไพล์:**
```bash
cargo check
```

**จัดรูปแบบโค้ด:**
```bash
cargo fmt
```

**ตรวจสอบโค้ด:**
```bash
cargo clippy
```

**Build พร้อม debug symbols:**
```bash
cargo build
```

## เชื่อมต่อ Frontend

เมื่อ core daemon ทำงานแล้ว frontend สามารถเชื่อมต่อผ่าน gRPC ที่ `http://127.0.0.1:50051`

นิยาม proto อยู่ใน `core/proto/reelvault.proto` และควรใช้เพื่อสร้างโค้ด client สำหรับแต่ละภาษา frontend

## การแก้ไขปัญหา

**"ไม่พบ ffmpeg"**
- ตรวจสอบว่า FFmpeg ติดตั้งแล้วและอยู่ใน PATH ของคุณ
- ตรวจสอบ: `which ffmpeg && which ffprobe`

**ฐานข้อมูลถูกล็อก**
- ควรรัน daemon instance เดียวในแต่ละครั้ง
- ปิดกระบวนการที่มีอยู่: `pkill reelvault-core`

**พอร์ตถูกใช้งานแล้ว**
- เปลี่ยนพอร์ตใน `core/src/main.rs` หาก 50051 ถูกใช้อยู่
- หรือปิดกระบวนการ: `lsof -ti:50051 | xargs kill`

**ปัญหาการ build บน Linux**
- ติดตั้ง dev dependencies เพิ่มเติม: `sudo apt-get install build-essential libssl-dev`

## ขั้นตอนต่อไป

1. **macOS Client**: ติดตั้ง frontend ด้วย SwiftUI (หลัง MVP)
2. **Desktop Client**: ติดตั้ง frontend ด้วย Kotlin Compose (ลำดับความสำคัญ MVP)
3. **การทดสอบ**: เพิ่ม integration test สำหรับ gRPC API
4. **CI/CD**: ตั้งค่า GitHub Actions สำหรับการ build และการปล่อยเวอร์ชัน
