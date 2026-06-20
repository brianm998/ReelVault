> [Bản gốc tiếng Anh](README.md)

# ReelVault

Ứng dụng lập danh mục video đa nền tảng lấy cảm hứng từ Lightroom — duyệt thư viện video lớn nhanh chóng và native.

**ReelVault dùng để tổ chức, khám phá và quản lý video. Đây KHÔNG phải phần mềm chỉnh sửa video.**

## Tổng quan

ReelVault giúp bạn:

- **Duyệt** hàng nghìn video trong lưới ảo hóa linh hoạt với kích thước ảnh thu nhỏ có thể điều chỉnh.
- **Scrub** theo phong cách Lightroom: di chuột qua ảnh thu nhỏ và trượt trái↔phải để xem trước các khung hình trên timeline.
- **Nhóm** các biến thể liên quan (ví dụ: xuất 4K và 1080p từ cùng một nguồn) thành các chồng theo phong cách Lightroom; đánh dấu một cái là ưu tiên cho lưới + mở.
- **Khám phá** nội dung qua tìm kiếm toàn văn, lọc thẻ và các dropdown lọc theo từng trường (camera, ống kính, codec, năm chụp, từ khóa).
- **Kiểm tra** metadata chi tiết: codec, độ phân giải, FPS, bitrate, không gian màu, HDR, EXIF, GPS, model camera/ống kính.
- **Tổ chức** với ghi chú tự do, từ khóa và thao tác chọn nhiều.
- **Bàn giao** clip cho các trình chỉnh sửa bên ngoài bằng kéo-và-thả — kéo một hoặc nhiều thẻ thẳng từ lưới vào DaVinci Resolve, Final Cut Pro, Premiere Pro hoặc bất kỳ ứng dụng nào chấp nhận thả file.
- Quy trình **đa danh mục**: mở / đóng / chuyển đổi danh mục SQLite từ menu File, với danh sách danh mục gần đây và tiêu đề cửa sổ theo danh mục.

## Kiến trúc

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

- **Rust core** (`core/`) — Daemon gRPC dựa trên Tonic. Quản lý danh mục SQLite, trích xuất metadata FFprobe, tạo thumbnail + scrub-frame, quét/lập chỉ mục, tìm kiếm và tổng hợp bộ lọc. Hỗ trợ hot-swap danh mục đang hoạt động lúc chạy qua các RPC `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog`, để một tiến trình daemon duy nhất có thể phục vụ nhiều thư viện trong suốt vòng đời của nó.

- **Client desktop Kotlin Compose** (`kotlin-desktop/`) — Giao diện Compose Multiplatform. Tự động phát hiện daemon đang chạy trên `127.0.0.1:50051`; nếu không có daemon nào chạy, nó tự khởi chạy daemon đi kèm (dự phòng sang cổng do OS cấp nếu 50051 bận).

- **Client SwiftUI macOS** (`macos/`) — Ứng dụng macOS native tương đương tính năng với cùng luồng tự động khởi chạy, menu File macOS thực sự (nhóm Commands) và tiêu đề cửa sổ phản ứng theo dõi danh mục đang mở.

- **Client SwiftUI iOS** (`ios/`) — Ứng dụng iPhone / iPad **chỉ remote**. Không có quyền truy cập file cục bộ và không nhúng daemon: nó khám phá daemon qua Wi‑Fi (mDNS), kết nối qua kênh TLS ghim dấu vân tay sau khi ghép nối một lần, duyệt qua gRPC và **phát trực tiếp** video (HLS giảm tỷ lệ) từ máy chủ media của daemon. Thay thế kéo editor bằng sheet chia sẻ iOS và thêm tải lên từ Ảnh / Files. Xem [`ios/README.md`](ios/README.md).

- **ReelVaultKit** (`kit/`) — Gói SwiftPM cục bộ của Swift dùng chung được sử dụng bởi **cả hai** client Apple: model, view-model, client gRPC, discovery, TLS ghim và lớp cache/phát trực tiếp media.

- **Danh mục SQLite** — Cơ sở dữ liệu chế độ WAL với FTS5 cho tìm kiếm toàn văn. Schema nằm trong [`core/schema.sql`](core/schema.sql).

> **Thumbnail ProRes RAW có chất lượng độc quyền trên macOS.** ffmpeg không thể phát triển ProRes RAW (Atomos S-Log3 / S-Gamut), vì vậy trên **macOS** daemon giải mã nó qua QuickLook / AVFoundation — màu sắc chính xác và scrub thực sự từng khung hình. Trên **Linux / Windows** không có bộ giải mã như vậy, vì vậy daemon dự phòng về ffmpeg: các khung hình phẳng hơn/tối hơn và một khung scrub lặp đi lặp lại. Rust core được xây dựng giống hệt nhau trên cả ba nền tảng — AVFoundation không bao giờ được liên kết vào nó. Chi tiết: [`core/README.md`](core/README.md) (đường dẫn build + giải mã) và [`macos/README.md`](macos/README.md) (client macOS). Mọi codec khác đều được xử lý bởi ffmpeg theo cùng một cách ở mọi nơi.

## Trạng thái dự án

**MVP hoạt động trên macOS và Compose Desktop.** Cả hai client đều có cùng bộ tính năng; client macOS thêm lệnh thanh menu native và khởi chạy editor theo NSWorkspace. **Client iOS chỉ remote** (iPhone / iPad) kết nối với daemon qua LAN và phát trực tiếp video — duyệt, kiểm tra, xếp chồng, chia sẻ và tải lên; xem [`ios/README.md`](ios/README.md).

### ✅ Đã hoàn thành

**Core**
- [x] Daemon gRPC với bề mặt RPC đầy đủ (video, tìm kiếm, quét, thẻ, bộ sưu tập, chồng, bộ lọc, trạng thái, cấu hình, vòng đời danh mục).
- [x] Danh mục SQLite với chế độ WAL + FTS5; hot-swap danh mục runtime qua `OpenCatalog` / `CloseCatalog`.
- [x] Trích xuất metadata FFprobe (codec, độ phân giải, FPS, bitrate, HDR, EXIF, GPS, camera/ống kính).
- [x] Tạo thumbnail và scrub-frame theo phong cách Lightroom (10 khung hình mỗi video) với khóa theo video để loại bỏ công việc trùng lặp.
- [x] Giới hạn ffmpeg đồng thời (mặc định theo số CPU máy chủ) để giữ quét thư viện lớn không làm quá tải bộ lưu trữ SAN.
- [x] Quét thư viện với đệ quy tùy chọn và tự động nhóm biến thể.
- [x] Cờ CLI cho `--db-path`, `--no-catalog`, `--port` (với dự phòng cổng do OS cấp) và dòng stdout `REELVAULT_LISTENING_ON=…` có thể phân tích cho bộ khởi chạy client.

**Cả hai client**
- [x] Chế độ xem lưới ảo hóa với số cột thích ứng và thanh trượt kích thước thumbnail.
- [x] Xem trước hover-scrub, lớp phủ hover-play, chọn nhiều với shift-range và toggle ⌘/Ctrl.
- [x] Giao diện xếp chồng (nhóm): huy hiệu chồng với số lượng thành viên, nhấp để mở rộng, ngôi sao để đặt ưu tiên, nút mở theo thành viên.
- [x] Bảng bên: vị trí thư viện (trái), chi tiết + metadata + ghi chú + từ khóa (phải). Tab bật/tắt cả hai, chevron riêng lẻ thu gọn từng cái.
- [x] Dropdown bộ lọc thanh trên (camera / ống kính / từ khóa / codec / năm) — chỉ hiển thị các trường có dữ liệu; kết hợp AND với tìm kiếm.
- [x] Menu sắp xếp với tất cả các trường chính (tên file, ngày tháng, thời lượng, kích thước, độ phân giải, fps, codec, bitrate, camera, ống kính, từ khóa); nhấp lại để đảo hướng.
- [x] Quản lý thẻ/từ khóa: tạo nhanh, áp dụng cho nhiều lựa chọn, lọc lưới bằng cách nhấp vào chevron `>`.
- [x] Menu ngữ cảnh nhấp chuột phải trên mỗi thẻ: Open with Default Player và Reveal in Finder/Explorer — hoạt động trên toàn bộ nhiều lựa chọn.
- [x] Bàn giao kéo-và-thả: kéo các thẻ đã chọn vào bất kỳ ứng dụng nào chấp nhận thả file (DaVinci Resolve, Final Cut Pro, Premiere Pro, v.v.).
- [x] Luồng đa danh mục: menu File với Open / Close / Open Recent, sheet "mở danh mục" khi khởi chạy lần đầu, danh sách recents bền vững, tiêu đề cửa sổ hiển thị tên danh mục đang mở.
- [x] Tự động khởi chạy daemon đi kèm (với dự phòng cổng bận) khi không có backend nào chạy lúc khởi động.
- [x] Trợ giúp văn bản hover (tooltip trên Kotlin qua `TooltipArea`; SwiftUI qua `.help(_:)`) trên mọi phần tử tương tác và trường metadata.
- [x] Chế độ tối mặc định; bật/tắt chủ đề sáng/tối.

### 🚧 Đã lên kế hoạch

- [ ] Giám sát file thời gian thực (lập chỉ mục lại khi thư mục cơ sở thay đổi).
- [ ] Bộ sưu tập thông minh (tìm kiếm đã lưu với quy tắc bộ lọc trực tiếp).
- [ ] Tạo video proxy cho cảnh quay 8K trở lên.
- [ ] Tự động cập nhật dựa trên GitHub.
- [ ] Pipeline phát hành CI/CD tạo ra trình cài đặt đã ký cho từng nền tảng.
- [ ] Đóng gói binary daemon bên trong các gói ứng dụng client (hiện tại bộ khởi chạy tìm nó qua `REELVAULT_CORE_BIN` hoặc cây dev cargo).

## Bắt đầu

### Điều kiện tiên quyết

- **Rust** (khuyến nghị 1.75+) — để xây dựng daemon core.
- **FFmpeg / FFprobe** — phải có trong `PATH`. Dùng để trích xuất metadata và tạo thumbnail/scrub-frame.
- **JDK 17+** + Gradle (wrapper đi kèm) — cho client desktop Kotlin.
- **Swift 5.9+ / Xcode 15+** — cho client macOS.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — cho client iOS.

Xem [`SETUP.md`](SETUP.md) để biết hướng dẫn cài đặt theo nền tảng.

### Xây dựng daemon core

```bash
cd core
cargo build --release
```

Binary nằm tại `core/target/release/reelvault-core`. Chạy trực tiếp nếu bạn muốn tự điều khiển, hoặc để một trong các client khởi chạy nó cho bạn khi khởi động lần đầu:

```bash
# Mặc định — sử dụng danh mục mặc định nền tảng trên cổng mặc định.
./target/release/reelvault-core

# Khởi động không có danh mục (client dùng chế độ này); in cổng đã gắn.
./target/release/reelvault-core --no-catalog --port 0

# Mở danh mục cụ thể khi khởi động.
./target/release/reelvault-core --db-path /path/to/library.db
```

Daemon in dòng `REELVAULT_LISTENING_ON=127.0.0.1:N` ổn định ra stdout mà các client phân tích để tìm cổng được cấp.

### Chạy client desktop Kotlin Compose

```bash
cd kotlin-desktop
./gradlew run
```

Khi khởi động lần đầu, client thăm dò `127.0.0.1:50051` và — nếu không có gì lắng nghe — khởi chạy daemon đi kèm. Thứ tự tra cứu vị trí build:

1. `$REELVAULT_CORE_BIN` (đường dẫn tuyệt đối đến executable daemon)
2. Binary bên cạnh jar ứng dụng
3. `core/target/release/reelvault-core` hoặc `core/target/debug/reelvault-core` trong cây dev
4. `reelvault-core` trong `PATH`

Để phát triển, chỉ cần `cargo build` bên trong `core/` và client sẽ tự lấy binary debug.

### Chạy client SwiftUI macOS

```bash
cd macos
swift run
```

Cùng luồng tự động khởi chạy, cùng thứ tự tra cứu binary (với việc bổ sung đường dẫn `Resources/reelvault-core` trong gói được sử dụng bởi các gói ứng dụng đã ký). Dùng `⌘O` để mở danh mục và `⇧⌘W` để đóng; danh sách gần đây nằm dưới `File → Open Recent`.

### Chạy client SwiftUI iOS

Client iOS là **chỉ remote** — nó kết nối với daemon qua LAN thay vì khởi chạy một cái. Khởi động daemon ở chế độ remote trên máy cùng Wi‑Fi:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

Sau đó tạo dự án Xcode (`.xcodeproj` không được commit) và build:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

Khi khởi chạy lần đầu, ứng dụng khám phá daemon qua mDNS, bạn ủy quyền thiết bị một lần bằng mã ghép nối 6 chữ số (tạo từ **File → Pair a New Device** của client desktop, hoặc log daemon), sau đó duyệt + phát trực tiếp. Yêu cầu **iOS 18+** và **Xcode 16+**. Chi tiết đầy đủ, bao gồm mô hình phát trực tiếp và ghép nối, có trong [`ios/README.md`](ios/README.md).

## Tài liệu

- [`CLAUDE.md`](CLAUDE.md) — Tầm nhìn dự án, chi tiết kiến trúc, schema cơ sở dữ liệu và nguyên tắc thiết kế.
- [`SETUP.md`](SETUP.md) — Thiết lập môi trường phát triển.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Tài liệu tham khảo chi tiết cho các module Rust core và bề mặt RPC.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — So sánh song song client Kotlin và SwiftUI.
- [`ios/README.md`](ios/README.md) — Client iOS chỉ remote (iPhone / iPad): discovery, ghép nối, TLS ghim và phát trực tiếp HLS.
- [`macos/README.md`](macos/README.md) — Client macOS native.

## Đóng góp

Xem [`CLAUDE.md`](CLAUDE.md) để biết hướng dẫn phát triển. Pull request được chào đón — vui lòng giữ tương đương tính năng hai client, và thêm tiêu đề SPDX vào bất kỳ file nguồn mới nào (xem Giấy phép bên dưới).

## Giấy phép

ReelVault là phần mềm tự do, được cấp phép theo **GNU General Public License, phiên bản 3 hoặc (theo lựa chọn của bạn) bất kỳ phiên bản sau nào**. Văn bản giấy phép đầy đủ nằm trong [`LICENSE`](LICENSE); thông báo bản quyền ngắn gọn trong [`COPYRIGHT`](COPYRIGHT).

Mỗi file nguồn mang một định danh SPDX để các công cụ quét giấy phép (REUSE, FOSSology, trình phát hiện licensee của GitHub, v.v.) có thể xác định giấy phép theo chương trình:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

Nếu bạn phân phối phiên bản sửa đổi của ReelVault — hoặc bất kỳ chương trình nào liên kết với Rust core như một thư viện — GPL yêu cầu bạn cung cấp nguồn của mình theo cùng điều khoản. Xem file LICENSE để biết toàn bộ nghĩa vụ.
