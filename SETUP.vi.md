> [Bản gốc tiếng Anh](SETUP.md)

# Thiết lập Phát triển ReelVault

## Điều kiện tiên quyết

### Yêu cầu Hệ thống

- **macOS 11+**, **Linux (Ubuntu 20.04+)**, hoặc **Windows 10+**
- **Rust 1.70+** ([Cài đặt qua rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (để phân tích video và tạo thumbnail)

### Cài đặt FFmpeg

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
Tải từ https://ffmpeg.org/download.html hoặc dùng:
```bash
choco install ffmpeg
```

### Cài đặt Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Xác minh:
```bash
rustc --version
cargo --version
```

## Xây dựng Core

```bash
cd core
cargo build --release
```

Binary đã biên dịch sẽ nằm tại `core/target/release/reelvault-core`.

## Chạy Daemon Core

```bash
./core/target/release/reelvault-core
```

Daemon sẽ:
- Tạo cơ sở dữ liệu tại `~/.reelvault/catalog.db`
- Tạo thư mục cache thumbnail
- Lắng nghe tại `127.0.0.1:50051` cho các kết nối gRPC

## Chạy Tests

```bash
cd core
cargo test
```

## Lệnh Phát triển

**Kiểm tra code mà không biên dịch:**
```bash
cargo check
```

**Định dạng code:**
```bash
cargo fmt
```

**Lint code:**
```bash
cargo clippy
```

**Build với debug symbols:**
```bash
cargo build
```

## Kết nối Frontend

Sau khi daemon core đang chạy, các frontend có thể kết nối qua gRPC tại `http://127.0.0.1:50051`.

Các định nghĩa proto nằm trong `core/proto/reelvault.proto` và nên được dùng để tạo code client cho mỗi ngôn ngữ frontend.

## Xử lý Sự cố

**"ffmpeg không tìm thấy"**
- Đảm bảo FFmpeg đã cài đặt và có trong PATH của bạn
- Kiểm tra: `which ffmpeg && which ffprobe`

**Cơ sở dữ liệu bị khóa**
- Chỉ một instance daemon nên chạy tại một thời điểm
- Tắt các tiến trình hiện có: `pkill reelvault-core`

**Cổng đã được sử dụng**
- Thay đổi cổng trong `core/src/main.rs` nếu 50051 đang bị chiếm
- Hoặc tắt tiến trình: `lsof -ti:50051 | xargs kill`

**Vấn đề build trên Linux**
- Cài đặt các phụ thuộc dev bổ sung: `sudo apt-get install build-essential libssl-dev`

## Các Bước Tiếp theo

1. **Client macOS**: Triển khai frontend SwiftUI (sau MVP)
2. **Client Desktop**: Triển khai frontend Kotlin Compose (ưu tiên MVP)
3. **Kiểm thử**: Thêm integration test cho gRPC API
4. **CI/CD**: Thiết lập GitHub Actions cho các build và phát hành
