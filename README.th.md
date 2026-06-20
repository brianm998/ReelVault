> [ต้นฉบับภาษาอังกฤษ](README.md)

# ReelVault

แอปพลิเคชันจัดทำแคตตาล็อกวิดีโอข้ามแพลตฟอร์มที่ได้รับแรงบันดาลใจจาก Lightroom — เรียกดูคลังวิดีโอขนาดใหญ่ได้อย่างรวดเร็วแบบ native

**ReelVault สร้างขึ้นสำหรับการจัดระเบียบ ค้นหา และจัดการวิดีโอ ไม่ใช่โปรแกรมตัดต่อวิดีโอ**

## ภาพรวม

ReelVault ช่วยให้คุณ:

- **เรียกดู** วิดีโอนับพันรายการในกริดเสมือนที่ตอบสนองได้ดี พร้อมปรับขนาดภาพขนาดย่อได้ตามต้องการ
- **สครับ** แบบ Lightroom: วางเมาส์บนภาพขนาดย่อแล้วเลื่อนซ้าย↔ขวาเพื่อดูตัวอย่างเฟรมตลอดไทม์ไลน์
- **จัดกลุ่ม** ไฟล์ที่เกี่ยวข้องกัน (เช่น ไฟล์ส่งออก 4K และ 1080p จากแหล่งเดียวกัน) เป็นสแต็กแบบ Lightroom; ทำเครื่องหมายรายการหนึ่งเป็นที่ต้องการสำหรับกริดและการเปิด
- **ค้นพบ** เนื้อหาผ่านการค้นหาข้อความเต็ม การกรองแท็ก และดรอปดาวน์ตัวกรองแต่ละฟิลด์ (กล้อง เลนส์ codec ปีที่บันทึก คำสำคัญ)
- **ตรวจสอบ** เมทาดาตาโดยละเอียด: codec ความละเอียด FPS บิตเรต ช่องสี HDR EXIF GPS รุ่นกล้อง/เลนส์
- **จัดการ** ด้วยบันทึกอิสระ คำสำคัญ และการดำเนินการแบบเลือกหลายรายการ
- **ส่งมอบ** คลิปไปยังโปรแกรมตัดต่อภายนอกด้วยการลากและวาง — ลากการ์ดหนึ่งใบหรือมากกว่าจากกริดไปยัง DaVinci Resolve, Final Cut Pro, Premiere Pro หรือแอปใดก็ได้ที่รับไฟล์ที่วาง
- เวิร์กโฟลว์ **หลายแคตตาล็อก**: เปิด / ปิด / สลับแคตตาล็อก SQLite จากเมนู File พร้อมรายการแคตตาล็อกล่าสุดและชื่อหน้าต่างแยกตามแคตตาล็อก

## สถาปัตยกรรม

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

- **Rust core** (`core/`) — daemon gRPC ที่ใช้ Tonic เป็นฐาน จัดการแคตตาล็อก SQLite การดึงเมทาดาตาด้วย FFprobe การสร้างภาพขนาดย่อและ scrub-frame การสแกน/จัดทำดัชนี การค้นหา และการรวมตัวกรอง รองรับ hot-swap แคตตาล็อกที่ใช้งานอยู่ขณะรันไทม์ผ่าน RPC: `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog` เพื่อให้กระบวนการ daemon เดียวสามารถให้บริการหลายไลบรารีตลอดอายุการใช้งาน

- **Kotlin Compose desktop client** (`desktop/`) — UI ของ Compose Multiplatform ตรวจจับ daemon ที่กำลังทำงานบน `127.0.0.1:50051` โดยอัตโนมัติ หากไม่มี daemon ทำงาน จะเรียกใช้ daemon ที่รวมมาด้วยตัวเอง (สำรองไปยังพอร์ตที่ OS กำหนดหาก 50051 ไม่ว่าง)

- **SwiftUI macOS client** (`macos/`) — แอป macOS native ที่มีฟีเจอร์เท่าเทียมกัน พร้อมกระแส auto-spawn เดียวกัน เมนู File macOS จริง (กลุ่ม Commands) และชื่อหน้าต่างแบบ reactive ที่ติดตามแคตตาล็อกที่เปิดอยู่

- **SwiftUI iOS client** (`ios/`) — แอป iPhone / iPad **สำหรับ remote เท่านั้น** ไม่มีการเข้าถึงไฟล์ในเครื่องและไม่มี daemon ฝังไว้: ค้นหา daemon ผ่าน Wi‑Fi (mDNS) เชื่อมต่อผ่านช่อง TLS ที่ pin fingerprint หลังจากจับคู่ครั้งเดียว เรียกดูผ่าน gRPC และ **สตรีม** วิดีโอ (HLS ที่ลดขนาดแล้ว) จากเซิร์ฟเวอร์มีเดียของ daemon แทนที่การลาก editor ด้วย iOS share sheet และเพิ่มการอัปโหลดจาก Photos / Files ดู [`ios/README.md`](ios/README.md)

- **Kotlin Compose Android client** (`android/`) — A **remote-only**
  Android phone / tablet app. Connects to a daemon over the LAN (NSD
  discovery), streams video via ExoPlayer, and replaces editor drag-out with
  the Android share intent. Also embeds the full Rust core for on-device local
  library access — browse, catalog, and upload footage directly from the
  device. See [`android/README.md`](android/README.md).

- **ReelVaultKit** (`kit/`) — แพ็กเกจ SwiftPM ในเครื่องของ Swift ที่ใช้ร่วมกันโดย **ทั้งสอง** Apple client: โมเดล view-model client gRPC การค้นพบ TLS ที่ pin และเลเยอร์แคช/สตรีมมิงมีเดีย

- **แคตตาล็อก SQLite** — ฐานข้อมูลโหมด WAL พร้อม FTS5 สำหรับการค้นหาข้อความเต็ม Schema อยู่ใน [`core/schema.sql`](core/schema.sql)

> **ภาพขนาดย่อ ProRes RAW มีคุณภาพเฉพาะบน macOS** ffmpeg ไม่สามารถประมวลผล ProRes RAW (Atomos S-Log3 / S-Gamut) ดังนั้นบน **macOS** daemon จะถอดรหัสผ่าน QuickLook / AVFoundation — สีที่ถูกต้องและการสครับแบบ per-frame ที่แท้จริง บน **Linux / Windows** ไม่มีตัวถอดรหัสเช่นนี้ daemon จึงถอยไปใช้ ffmpeg: เฟรมที่แบนกว่า/มืดกว่าและเฟรมสครับเดียวที่ซ้ำ Rust core สร้างเหมือนกันบนทั้งสามแพลตฟอร์ม — AVFoundation ไม่เคยถูกลิงก์เข้ามา รายละเอียด: [`core/README.md`](core/README.md) (เส้นทาง build + ถอดรหัส) และ [`macos/README.md`](macos/README.md) (macOS client) ทุก codec อื่นจัดการโดย ffmpeg ในแบบเดียวกันทุกที่

## สถานะโปรเจกต์

**MVP ใช้งานได้บน macOS และ Compose Desktop** ทั้งสอง client มีชุดฟีเจอร์เดียวกัน; macOS client เพิ่มคำสั่ง menu-bar native และการเรียกใช้ editor ผ่าน NSWorkspace **iOS client สำหรับ remote เท่านั้น** (iPhone / iPad) เชื่อมต่อกับ daemon ผ่าน LAN และสตรีมวิดีโอ — เรียกดู ตรวจสอบ จัดเป็นสแต็ก แชร์ และอัปโหลด; ดู [`ios/README.md`](ios/README.md)

### ✅ เสร็จสมบูรณ์

**Core**
- [x] daemon gRPC พร้อมพื้นผิว RPC ครบถ้วน (วิดีโอ ค้นหา สแกน แท็ก คอลเลกชัน สแต็ก ตัวกรอง สถานะ การตั้งค่า วงจรชีวิตแคตตาล็อก)
- [x] แคตตาล็อก SQLite พร้อมโหมด WAL + FTS5; hot-swap แคตตาล็อก runtime ผ่าน `OpenCatalog` / `CloseCatalog`
- [x] Rich metadata extraction via FFprobe + platform-native helpers: codec,
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera. ความละเอียด FPS บิตเรต HDR EXIF GPS กล้อง/เลนส์)
- [x] การสร้างภาพขนาดย่อและ scrub-frame แบบ Lightroom (10 เฟรมต่อวิดีโอ) พร้อม lock แต่ละวิดีโอเพื่อหลีกเลี่ยงงานซ้ำ
- [x] การจำกัด ffmpeg พร้อมกัน (ค่าเริ่มต้นตามจำนวน CPU ของโฮสต์) เพื่อป้องกันการสแกนไลบรารีขนาดใหญ่ทำให้ที่เก็บข้อมูล SAN ทำงานหนักเกินไป
- [x] การสแกนไลบรารีพร้อมการซ้ำแบบเลือกได้และการจัดกลุ่มตัวแปรอัตโนมัติ
- [x] ค่าสถานะ CLI สำหรับ `--db-path`, `--no-catalog`, `--port` (พร้อม fallback พอร์ตที่ OS กำหนด) และบรรทัด stdout `REELVAULT_LISTENING_ON=…` ที่แยกวิเคราะห์ได้สำหรับตัวเรียกใช้ client

**ทั้งสอง client**
- [x] มุมมองกริดเสมือนพร้อมจำนวนคอลัมน์แบบ adaptive และแถบเลื่อนขนาดภาพขนาดย่อ
- [x] ตัวอย่าง hover-scrub, overlay hover-play, การเลือกหลายรายการพร้อม shift-range และ ⌘/Ctrl-toggle
- [x] UI สแต็ก (กลุ่ม): ป้ายสแต็กพร้อมจำนวนสมาชิก คลิกเพื่อขยาย ดาวเพื่อตั้งค่าที่ต้องการ ปุ่มเปิดแต่ละสมาชิก
- [x] แผงด้านข้าง: ตำแหน่งไลบรารี (ซ้าย), รายละเอียด + เมทาดาตา + บันทึก + คำสำคัญ (ขวา) Tab สลับทั้งสอง เชฟรอนแยกยุบแต่ละอัน
- [x] ดรอปดาวน์ตัวกรองแถบบน (กล้อง / เลนส์ / คำสำคัญ / codec / ปี) — แสดงเฉพาะฟิลด์ที่มีข้อมูล; รวมกับ AND ร่วมกับการค้นหา
- [x] เมนูเรียงลำดับพร้อมฟิลด์หลักทั้งหมด (ชื่อไฟล์ วันที่ ระยะเวลา ขนาด ความละเอียด fps codec บิตเรต กล้อง เลนส์ คำสำคัญ); คลิกอีกครั้งเพื่อกลับทิศทาง
- [x] การจัดการแท็ก/คำสำคัญ: สร้างได้ทันที ใช้กับการเลือกหลายรายการ กรองกริดโดยคลิกเชฟรอน `>`
- [x] เมนูคลิกขวาบนการ์ดทุกใบ: Open with Default Player และ Reveal in Finder/Explorer — ทำงานกับการเลือกหลายรายการทั้งหมด
- [x] การส่งมอบด้วยลากและวาง: ลากการ์ดที่เลือกไปยังแอปใดก็ได้ที่รับไฟล์ที่วาง (DaVinci Resolve, Final Cut Pro, Premiere Pro ฯลฯ)
- [x] กระแสหลายแคตตาล็อก: เมนู File พร้อม Open / Close / Open Recent, sheet "เปิดแคตตาล็อก" เมื่อเปิดครั้งแรก, รายการล่าสุดแบบถาวร, ชื่อหน้าต่างที่แสดงชื่อแคตตาล็อกที่เปิด
- [x] Auto-spawn daemon ที่รวมมา (พร้อม fallback เมื่อพอร์ตไม่ว่าง) เมื่อไม่มี backend ทำงานขณะเริ่มต้น
- [x] ความช่วยเหลือแบบ hover-text (tooltip บน Kotlin ผ่าน `TooltipArea`; SwiftUI ผ่าน `.help(_:)`) บนทุกองค์ประกอบแบบโต้ตอบและฟิลด์เมทาดาตา
- [x] โหมดมืดเป็นค่าเริ่มต้น; การสลับธีมสว่าง/มืด

### 🚧 วางแผนไว้

- [ ] การตรวจสอบไฟล์แบบเรียลไทม์ (จัดทำดัชนีใหม่เมื่อโฟลเดอร์พื้นฐานเปลี่ยน)
- [ ] คอลเลกชันอัจฉริยะ (การค้นหาที่บันทึกพร้อมกฎตัวกรองสด)
- [ ] การสร้างวิดีโอ proxy สำหรับฟุตเทจ 8K ขึ้นไป
- [ ] การอัปเดตอัตโนมัติผ่าน GitHub
- [ ] ไปป์ไลน์การปล่อย CI/CD ที่สร้างตัวติดตั้งที่ลงชื่อแล้วตามแพลตฟอร์ม
- [ ] การรวม binary daemon ไว้ในแพ็กเกจแอป client (ปัจจุบัน launcher ค้นหาผ่าน `REELVAULT_CORE_BIN` หรือ cargo dev tree)

## เริ่มต้นใช้งาน

### ข้อกำหนดเบื้องต้น

- **Rust** (แนะนำ 1.75+) — สำหรับสร้าง core daemon
- **FFmpeg / FFprobe** — ต้องอยู่ใน `PATH` ใช้สำหรับการดึงเมทาดาตาและการสร้างภาพขนาดย่อ/scrub-frame
- **JDK 17+** + Gradle (รวม wrapper) — สำหรับ Kotlin desktop client
- **Swift 5.9+ / Xcode 15+** — สำหรับ macOS client
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — สำหรับ iOS client

ดู [`SETUP.md`](SETUP.md) สำหรับคำแนะนำการติดตั้งตามแพลตฟอร์ม

### สร้าง core daemon

```bash
cd core
cargo build --release
```

binary จะอยู่ที่ `core/target/release/reelvault-core` รันโดยตรงหากต้องการควบคุมเอง หรือปล่อยให้ client ใดเรียกใช้แทนคุณเมื่อเปิดครั้งแรก:

```bash
# ค่าเริ่มต้น — ใช้แคตตาล็อกเริ่มต้นของแพลตฟอร์มบนพอร์ตเริ่มต้น
./target/release/reelvault-core

# เริ่มต้นโดยไม่มีแคตตาล็อก (client ใช้โหมดนี้); พิมพ์พอร์ตที่ผูก
./target/release/reelvault-core --no-catalog --port 0

# เปิดแคตตาล็อกเฉพาะเมื่อเริ่มต้น
./target/release/reelvault-core --db-path /path/to/library.db
```

daemon พิมพ์บรรทัด `REELVAULT_LISTENING_ON=127.0.0.1:N` ที่คงที่บน stdout ซึ่ง client แยกวิเคราะห์เพื่อค้นหาพอร์ตที่กำหนด

### รัน Kotlin Compose desktop client

```bash
cd desktop
./gradlew run
```

เมื่อเปิดครั้งแรก client จะตรวจสอบ `127.0.0.1:50051` และ — หากไม่มีอะไรรับฟัง — จะเรียกใช้ daemon ที่รวมมา ลำดับการค้นหาตำแหน่ง build:

1. `$REELVAULT_CORE_BIN` (เส้นทางสัมบูรณ์ไปยัง executable ของ daemon)
2. binary ถัดจาก jar ของแอปพลิเคชัน
3. `core/target/release/reelvault-core` หรือ `core/target/debug/reelvault-core` ใน dev tree
4. `reelvault-core` บน `PATH`

สำหรับการพัฒนา เพียง `cargo build` ภายใน `core/` แล้ว client จะเลือก debug binary ขึ้นมา

### รัน macOS SwiftUI client

```bash
cd macos
swift run
```

กระแส auto-spawn เดียวกัน ลำดับการค้นหา binary เดียวกัน (พร้อมเพิ่มเส้นทาง `Resources/reelvault-core` ในแพ็กเกจที่ใช้โดยแพ็กเกจแอปที่ลงชื่อแล้ว) ใช้ `⌘O` เพื่อเปิดแคตตาล็อกและ `⇧⌘W` เพื่อปิด; รายการล่าสุดอยู่ใต้ `File → Open Recent`

### รัน iOS SwiftUI client

iOS client เป็น **สำหรับ remote เท่านั้น** — เชื่อมต่อกับ daemon ผ่าน LAN แทนที่จะเรียกใช้งานหนึ่ง เริ่ม daemon ในโหมด remote บนเครื่องที่อยู่บน Wi‑Fi เดียวกัน:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

จากนั้นสร้างโปรเจกต์ Xcode (`.xcodeproj` ไม่ถูก commit) และ build:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

เมื่อเปิดครั้งแรก แอปจะค้นพบ daemon ผ่าน mDNS คุณอนุญาตอุปกรณ์หนึ่งครั้งด้วยรหัสจับคู่ 6 หลัก (สร้างจาก **File → Pair a New Device** ของ desktop client หรือ log ของ daemon) แล้วเรียกดู + สตรีม ต้องใช้ **iOS 18+** และ **Xcode 16+** รายละเอียดทั้งหมดรวมถึงโมเดลการสตรีมและการจับคู่อยู่ใน [`ios/README.md`](ios/README.md)

## เอกสารประกอบ

- [`CLAUDE.md`](CLAUDE.md) — วิสัยทัศน์โปรเจกต์ รายละเอียดสถาปัตยกรรม schema ฐานข้อมูล และหลักการออกแบบ
- [`SETUP.md`](SETUP.md) — การตั้งค่าสภาพแวดล้อมการพัฒนา
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — เอกสารอ้างอิงโดยละเอียดสำหรับโมดูล Rust core และพื้นผิว RPC
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — การเปรียบเทียบแบบเคียงข้างกันของ Kotlin และ SwiftUI client
- [`ios/README.md`](ios/README.md) — iOS client สำหรับ remote เท่านั้น (iPhone / iPad): การค้นพบ การจับคู่ TLS ที่ pin และการสตรีม HLS
- [`macos/README.md`](macos/README.md) — macOS client แบบ native

## การมีส่วนร่วม

ดู [`CLAUDE.md`](CLAUDE.md) สำหรับแนวทางการพัฒนา ยินดีรับ Pull request — กรุณารักษาความเท่าเทียมของฟีเจอร์ใน client ทั้งสี่ตัวตามที่เหมาะสม
(ดู CLAUDE.md สำหรับการเบี่ยงเบนต่อแพลตฟอร์มที่ถูกต้อง) และเพิ่มส่วนหัว SPDX ให้กับไฟล์ซอร์สใหม่ (ดูสิทธิ์การใช้งานด้านล่าง)

## สิทธิ์การใช้งาน

ReelVault เป็นซอฟต์แวร์ฟรี ได้รับอนุญาตภายใต้ **GNU General Public License เวอร์ชัน 3 หรือ (ตามตัวเลือกของคุณ) เวอร์ชันที่ใหม่กว่า** ข้อความสิทธิ์การใช้งานเต็มรูปแบบอยู่ใน [`LICENSE`](LICENSE); ประกาศลิขสิทธิ์สั้นๆ อยู่ใน [`COPYRIGHT`](COPYRIGHT)

ไฟล์ซอร์สทุกไฟล์มีตัวระบุ SPDX เพื่อให้เครื่องมือสแกนสิทธิ์การใช้งาน (REUSE, FOSSology, ตัวตรวจจับ licensee ของ GitHub ฯลฯ) สามารถระบุสิทธิ์การใช้งานโดยทางโปรแกรม:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

หากคุณแจกจ่าย ReelVault เวอร์ชันที่แก้ไข — หรือโปรแกรมใดก็ตามที่ลิงก์กับ Rust core เป็นไลบรารี — GPL กำหนดให้คุณต้องเปิดเผยซอร์สของคุณภายใต้เงื่อนไขเดียวกัน ดูไฟล์ LICENSE สำหรับชุดพันธะทั้งหมด
