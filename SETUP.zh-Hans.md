> [English original](SETUP.md)

# ReelVault 开发环境设置

## 前提条件

### 系统要求

- **macOS 11+**、**Linux（Ubuntu 20.04+）** 或 **Windows 10+**
- **Rust 1.70+**（[通过 rustup 安装](https://rustup.rs/)）
- **FFmpeg 和 FFprobe**（用于视频分析和缩略图生成）

### 安装 FFmpeg

**macOS：**
```bash
brew install ffmpeg
```

**Linux（Ubuntu/Debian）：**
```bash
sudo apt-get install ffmpeg
```

**Windows：**
从 https://ffmpeg.org/download.html 下载，或使用：
```bash
choco install ffmpeg
```

### 安装 Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

验证安装：
```bash
rustc --version
cargo --version
```

## 构建核心

```bash
cd core
cargo build --release
```

编译后的二进制文件将位于 `core/target/release/reelvault-core`。

## 运行核心守护进程

```bash
./core/target/release/reelvault-core
```

守护进程将会：
- 在 `~/.reelvault/catalog.db` 创建数据库
- 创建缩略图缓存目录
- 在 `127.0.0.1:50051` 监听 gRPC 连接

## 运行测试

```bash
cd core
cargo test
```

## 开发命令

**不编译检查代码：**
```bash
cargo check
```

**格式化代码：**
```bash
cargo fmt
```

**代码检查：**
```bash
cargo clippy
```

**构建（含调试符号）：**
```bash
cargo build
```

## 连接前端

核心守护进程运行后，前端可以通过 gRPC 连接 `http://127.0.0.1:50051`。

Proto 定义位于 `core/proto/reelvault.proto`，应使用该文件为每种前端语言生成客户端代码。

## 故障排除

**"ffmpeg not found"（未找到 ffmpeg）**
- 确保 FFmpeg 已安装并在 PATH 中
- 检查：`which ffmpeg && which ffprobe`

**数据库被锁定**
- 同一时间只能运行一个守护进程实例
- 终止现有进程：`pkill reelvault-core`

**端口已被占用**
- 如果 50051 被占用，修改 `core/src/main.rs` 中的端口
- 或终止占用进程：`lsof -ti:50051 | xargs kill`

**Linux 上的构建问题**
- 安装额外的开发依赖：`sudo apt-get install build-essential libssl-dev`

## 后续步骤

1. **macOS 客户端**：实现 SwiftUI 前端（MVP 后）
2. **桌面客户端**：实现 Kotlin Compose 前端（MVP 优先）
3. **测试**：为 gRPC API 添加集成测试
4. **CI/CD**：设置 GitHub Actions 用于构建和发布
