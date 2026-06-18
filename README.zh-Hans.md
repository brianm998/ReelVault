> [English original](README.md)

# ReelVault

一款跨平台视频目录管理应用，灵感来源于 Lightroom——快速、原生地浏览大型视频库。

**ReelVault 用于整理、发现和管理视频。它不是视频编辑器。**

## 概述

ReelVault 帮助您：

- **浏览** 在响应式虚拟化网格中浏览数千个视频，支持调整缩略图大小。
- **擦洗** Lightroom 风格预览：将鼠标悬停在缩略图上并左右滑动，预览时间轴上的帧画面。
- **分组** 将相关变体（例如同一素材的 4K 和 1080p 导出版本）整合为 Lightroom 风格的堆叠组；标记其中一个作为网格显示和打开的首选项。
- **发现** 通过全文搜索、标签过滤和逐字段过滤下拉菜单（相机、镜头、编解码器、拍摄年份、关键词）探索内容。
- **检查** 详细元数据：编解码器、分辨率、帧率、比特率、色彩空间、HDR、EXIF、GPS、相机/镜头型号。
- **整理** 使用自由格式备注、关键词和多选操作进行管理。
- **移交** 通过拖放将片段传递给外部编辑器——直接将一个或多个卡片从网格拖入 DaVinci Resolve、Final Cut Pro、Premiere Pro 或任何接受文件拖放的应用程序。
- **多目录** 工作流：通过文件菜单打开/关闭/切换 SQLite 目录，支持最近目录列表和每个目录的窗口标题。

## 架构

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

- **Rust 核心** (`core/`) — 基于 Tonic 的 gRPC 守护进程。负责管理 SQLite 目录、FFprobe 元数据提取、缩略图及擦洗帧生成、扫描/索引、搜索和过滤器聚合。通过 `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog` RPC 支持在运行时热切换活动目录，因此单个守护进程可在其生命周期内服务多个库。

- **Kotlin Compose 桌面客户端** (`desktop/`) — Compose Multiplatform 界面。自动检测 `127.0.0.1:50051` 上运行的守护进程；如果没有正在运行的守护进程，则自动启动捆绑的守护进程（若 50051 被占用则回退到系统分配的端口）。

- **SwiftUI macOS 客户端** (`macos/`) — 功能对等的原生 macOS 应用，具有相同的自动启动流程、真正的 macOS 文件菜单（Commands 组）以及跟踪已打开目录的响应式窗口标题。

- **SwiftUI iOS 客户端** (`ios/`) — 一款**仅限远程连接**的 iPhone / iPad 应用。它没有本地文件访问权限，也不内嵌守护进程：通过 Wi‑Fi（mDNS）发现守护进程，经过一次性配对后通过指纹固定的 TLS 通道连接，通过 gRPC 浏览，并从守护进程的媒体服务器**流式播放**视频（降采样 HLS）。以 iOS 分享表单替代编辑器拖出操作，并支持从照片/文件上传。详见 [`ios/README.md`](ios/README.md)。

- **ReelVaultKit** (`kit/`) — 一个本地 SwiftPM 包，包含**两个** Apple 客户端共用的 Swift 代码：模型、视图模型、gRPC 客户端、发现机制、固定 TLS 以及媒体缓存/流媒体层。

- **SQLite 目录** — WAL 模式数据库，使用 FTS5 实现全文搜索。数据库模式位于 [`core/schema.sql`](core/schema.sql)。

> **ProRes RAW 缩略图仅在 macOS 上具有完整质量。** ffmpeg 无法解码 ProRes RAW（Atomos S-Log3 / S-Gamut），因此在 **macOS** 上守护进程通过 QuickLook / AVFoundation 进行解码——色彩准确，支持真正的逐帧擦洗。在 **Linux / Windows** 上没有此类解码器，守护进程将回退至 ffmpeg：色彩较平/较暗，且擦洗帧为单帧重复。Rust 核心在所有三个平台上以相同方式构建——AVFoundation 从不链接其中。详细信息：[`core/README.md`](core/README.md)（构建及解码路径）和 [`macos/README.md`](macos/README.md)（macOS 客户端）。其他所有编解码器均由 ffmpeg 以相同方式在所有平台上处理。

## 项目状态

**MVP 在 macOS 和 Compose Desktop 上已可正常使用。** 两个客户端提供相同的功能集；macOS 客户端额外支持原生菜单栏命令和通过 NSWorkspace 启动编辑器。**仅限远程连接的 iOS 客户端**（iPhone / iPad）通过局域网连接守护进程并流式播放视频——支持浏览、检查、堆叠、分享和上传；详见 [`ios/README.md`](ios/README.md)。

### ✅ 已完成

**核心**
- [x] 具备完整 RPC 接口的 gRPC 守护进程（视频、搜索、扫描、标签、集合、堆叠、过滤器、状态、配置、目录生命周期）。
- [x] WAL 模式 + FTS5 的 SQLite 目录；通过 `OpenCatalog` / `CloseCatalog` 支持运行时目录热切换。
- [x] FFprobe 元数据提取（编解码器、分辨率、帧率、比特率、HDR、EXIF、GPS、相机/镜头）。
- [x] 缩略图和 Lightroom 风格擦洗帧生成（每个视频 10 帧），使用逐视频锁去重工作。
- [x] 并发 ffmpeg 限流（默认为主机 CPU 核心数），防止大型库扫描导致 SAN 存储过载。
- [x] 支持可选递归的库扫描及变体自动分组。
- [x] CLI 参数 `--db-path`、`--no-catalog`、`--port`（支持系统分配端口回退），以及供客户端启动器解析的 `REELVAULT_LISTENING_ON=…` 标准输出行。

**两个客户端**
- [x] 虚拟化网格视图，支持自适应列数和缩略图大小滑块。
- [x] 悬停擦洗预览、悬停播放叠加层、多选（支持 Shift 范围选择和 ⌘/Ctrl 切换选择）。
- [x] 堆叠（分组）UI：带成员数量的堆叠徽章、点击展开、星标设置首选项、逐成员打开按钮。
- [x] 侧面板：库位置（左侧）、详情 + 元数据 + 备注 + 关键词（右侧）。Tab 键切换两个面板，单独的箭头图标可折叠各面板。
- [x] 顶部栏过滤下拉菜单（相机/镜头/关键词/编解码器/年份）——仅显示有数据的字段；与搜索进行 AND 组合。
- [x] 排序菜单，涵盖所有主要字段（文件名、日期、时长、大小、分辨率、帧率、编解码器、比特率、相机、镜头、关键词）；再次点击可反转方向。
- [x] 标签/关键词管理：即时创建、应用于多选，点击 `>` 箭头按关键词过滤网格。
- [x] 每张卡片的右键上下文菜单：用默认播放器打开和在 Finder/Explorer 中显示——对完整的多选内容生效。
- [x] 拖放移交：将选中的卡片拖入任何接受文件拖放的应用（DaVinci Resolve、Final Cut Pro、Premiere Pro 等）。
- [x] 多目录流程：文件菜单包含打开/关闭/打开最近，首次启动"打开目录"表单，持久化最近列表，窗口标题显示已打开目录的名称。
- [x] 启动时如无后端运行，自动启动捆绑的守护进程（端口被占用时回退）。
- [x] 悬停文本帮助（Kotlin 通过 `TooltipArea`；SwiftUI 通过 `.help(_:)`），覆盖所有交互元素和元数据字段。
- [x] 默认深色模式；支持浅色/深色主题切换。

### 🚧 计划中

- [ ] 实时文件监视（当底层文件夹发生变化时重新索引）。
- [ ] 智能集合（带有实时过滤规则的已保存搜索）。
- [ ] 为 8K+ 素材生成代理视频。
- [ ] 基于 GitHub 的自动更新。
- [ ] 生成各平台签名安装包的 CI/CD 发布流水线。
- [ ] 将守护进程二进制文件打包到客户端应用包中（目前启动器通过 `REELVAULT_CORE_BIN` 或 cargo 开发目录树查找）。

## 快速开始

### 前提条件

- **Rust**（推荐 1.75+）——用于构建核心守护进程。
- **FFmpeg / FFprobe** — 必须在 `PATH` 中。用于元数据提取和缩略图/擦洗帧生成。
- **JDK 17+** + Gradle（包含 wrapper）——用于 Kotlin 桌面客户端。
- **Swift 5.9+ / Xcode 15+** ——用于 macOS 客户端。
- **Xcode 16+（iOS 18 SDK）+ [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) ——用于 iOS 客户端。

平台特定的安装说明请参阅 [`SETUP.md`](SETUP.md)。

### 构建核心守护进程

```bash
cd core
cargo build --release
```

二进制文件输出至 `core/target/release/reelvault-core`。您可以直接运行它，也可以让某个客户端在首次启动时自动生成：

```bash
# 默认——在默认端口上使用平台默认目录。
./target/release/reelvault-core

# 不带目录启动（客户端使用此模式）；输出绑定端口。
./target/release/reelvault-core --no-catalog --port 0

# 启动时打开指定目录。
./target/release/reelvault-core --db-path /path/to/library.db
```

守护进程会在标准输出中打印稳定的 `REELVAULT_LISTENING_ON=127.0.0.1:N` 行，客户端通过解析该行来发现分配的端口。

### 运行 Kotlin Compose 桌面客户端

```bash
cd desktop
./gradlew run
```

首次启动时客户端会探测 `127.0.0.1:50051`，如果没有监听，则会启动捆绑的守护进程。构建文件查找顺序：

1. `$REELVAULT_CORE_BIN`（守护进程可执行文件的绝对路径）
2. 应用 jar 文件同级目录中的二进制文件
3. 开发目录树中的 `core/target/release/reelvault-core` 或 `core/target/debug/reelvault-core`
4. `PATH` 中的 `reelvault-core`

开发时，只需在 `core/` 目录内执行 `cargo build`，客户端会自动找到调试版二进制文件。

### 运行 macOS SwiftUI 客户端

```bash
cd macos
swift run
```

相同的自动启动流程，相同的二进制文件查找顺序（额外增加了签名应用包使用的 `Resources/reelvault-core` 路径）。使用 `⌘O` 打开目录，`⇧⌘W` 关闭；最近列表位于 `File → Open Recent`。

### 运行 iOS SwiftUI 客户端

iOS 客户端**仅限远程连接**——它通过局域网连接守护进程，而不是自行启动一个。在同一 Wi‑Fi 网络的机器上以远程模式启动守护进程：

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

然后生成 Xcode 项目（`.xcodeproj` 不会提交到版本库）并构建：

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

首次启动时应用通过 mDNS 发现守护进程，您需要使用 6 位配对码对设备进行一次性授权（从桌面客户端的 **File → Pair a New Device** 或守护进程日志中生成），然后即可浏览和流式播放。需要 **iOS 18+** 和 **Xcode 16+**。完整详情（包括流媒体和配对模型）请参阅 [`ios/README.md`](ios/README.md)。

## 文档

- [`CLAUDE.md`](CLAUDE.md) — 项目愿景、架构详情、数据库模式和设计原则。
- [`SETUP.md`](SETUP.md) — 开发环境设置。
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Rust 核心模块和 RPC 接口的详细参考。
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Kotlin 和 SwiftUI 客户端的并排对比。
- [`ios/README.md`](ios/README.md) — 仅限远程连接的 iOS（iPhone / iPad）客户端：发现、配对、固定 TLS 和 HLS 流媒体。
- [`macos/README.md`](macos/README.md) — 原生 macOS 客户端。

## 贡献

开发指南请参阅 [`CLAUDE.md`](CLAUDE.md)。欢迎提交 Pull Request——请保持两个客户端的功能对等，并为所有新的源文件添加 SPDX 头（参见下方许可证说明）。

## 许可证

ReelVault 是自由软件，采用 **GNU 通用公共许可证第 3 版或（由您选择）任何更新版本**授权。完整许可证文本位于 [`LICENSE`](LICENSE)；简短版权声明位于 [`COPYRIGHT`](COPYRIGHT)。

每个源文件都带有 SPDX 标识符，以便许可证扫描工具（REUSE、FOSSology、GitHub 的 licensee 检测器等）能够以编程方式识别许可证：

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

如果您分发 ReelVault 的修改版本——或任何将 Rust 核心作为库链接的程序——GPL 要求您在相同条款下公开源代码。请参阅 LICENSE 文件了解完整义务说明。
