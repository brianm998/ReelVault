> [영어 원문](README.md)

# ReelVault

Lightroom에서 영감을 받은 크로스 플랫폼 동영상 카탈로그 애플리케이션 — 대규모 동영상 라이브러리를 빠르고 네이티브하게 탐색합니다.

**ReelVault는 동영상을 정리하고, 발견하고, 관리하기 위한 도구입니다. 동영상 편집기가 아닙니다.**

## 개요

ReelVault로 할 수 있는 것들:

- 조절 가능한 썸네일 크기를 갖춘 반응형 가상화 그리드에서 수천 개의 동영상을 **탐색**합니다.
- Lightroom 스타일로 **스크럽**합니다: 썸네일 위에 마우스를 올리고 좌↔우로 슬라이드하여 타임라인 전체에 걸친 프레임을 미리 볼 수 있습니다.
- 관련 변형본(예: 동일한 소스의 4K 및 1080p 익스포트)을 Lightroom 스타일 스택으로 **그룹화**하고, 그리드 및 열기 기본값으로 하나를 즐겨찾기로 표시합니다.
- 전체 텍스트 검색, 태그 필터링, 필드별 필터 드롭다운(카메라, 렌즈, 코덱, 촬영 연도, 키워드)으로 콘텐츠를 **발견**합니다.
- 코덱, 해상도, FPS, 비트레이트, 색 공간, HDR, EXIF, GPS, 카메라/렌즈 모델 등 상세 메타데이터를 **검사**합니다.
- 자유 형식 메모, 키워드, 다중 선택 작업으로 **정리**합니다.
- 드래그 앤 드롭으로 클립을 외부 편집기에 **전달**합니다 — 그리드에서 DaVinci Resolve, Final Cut Pro, Premiere Pro 또는 파일 드롭을 허용하는 모든 앱으로 하나 이상의 카드를 직접 드래그합니다.
- **다중 카탈로그** 워크플로: 파일 메뉴에서 SQLite 카탈로그를 열고 / 닫고 / 전환하며, 최근 카탈로그 목록과 카탈로그별 창 제목을 제공합니다.

## 아키텍처

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

- **Rust 코어** (`core/`) — Tonic 기반 gRPC 데몬. SQLite 카탈로그, FFprobe 메타데이터 추출, 썸네일 및 스크럽 프레임 생성, 스캔/인덱싱, 검색, 필터 집계를 담당합니다. `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog` RPC를 통해 런타임에 활성 카탈로그를 핫스왑할 수 있어, 단일 데몬 프로세스가 수명 동안 여러 라이브러리를 처리할 수 있습니다.

- **Kotlin Compose 데스크톱 클라이언트** (`desktop/`) — Compose Multiplatform UI. `127.0.0.1:50051`에서 실행 중인 데몬을 자동으로 감지하며, 실행 중인 것이 없으면 번들된 데몬을 직접 실행합니다(50051이 사용 중이면 OS 할당 포트로 폴백).

- **SwiftUI macOS 클라이언트** (`macos/`) — 기능 동등성을 갖춘 네이티브 macOS 앱으로, 동일한 자동 실행 흐름, 진짜 macOS 파일 메뉴(Commands 그룹), 열린 카탈로그를 추적하는 반응형 창 제목을 제공합니다.

- **SwiftUI iOS 클라이언트** (`ios/`) — **원격 전용** iPhone / iPad 앱. 로컬 파일 접근 권한이 없고 데몬을 내장하지 않습니다: Wi-Fi(mDNS)로 데몬을 발견하고, 일회성 페어링 후 지문 고정된 TLS 채널로 연결하며, gRPC로 탐색하고, 데몬의 미디어 서버에서 동영상(다운스케일된 HLS)을 **스트리밍**합니다. 편집기 드래그아웃을 iOS 공유 시트로 대체하고 사진/파일에서 업로드 기능을 추가합니다. [`ios/README.md`](ios/README.md)를 참조하세요.

- **Kotlin Compose Android client** (`android/`) — A **remote-only**
  Android phone / tablet app. Connects to a daemon over the LAN (NSD
  discovery), streams video via ExoPlayer, and replaces editor drag-out with
  the Android share intent. Also embeds the full Rust core for on-device local
  library access — browse, catalog, and upload footage directly from the
  device. See [`android/README.md`](android/README.md).

- **ReelVaultKit** (`kit/`) — **두** Apple 클라이언트 모두에서 사용하는 공유 Swift 코드의 로컬 SwiftPM 패키지: 모델, 뷰 모델, gRPC 클라이언트, 디스커버리, 고정된 TLS, 미디어 캐시/스트리밍 레이어.

- **SQLite 카탈로그** — 전체 텍스트 검색을 위한 FTS5가 포함된 WAL 모드 데이터베이스. 스키마는 [`core/schema.sql`](core/schema.sql)에 있습니다.

> **ProRes RAW 썸네일은 macOS 전용 품질입니다.** ffmpeg는 ProRes RAW(Atomos S-Log3 / S-Gamut)를 현상할 수 없으므로, **macOS**에서 데몬은 QuickLook / AVFoundation을 통해 디코딩합니다 — 올바른 색상과 진정한 프레임별 스크러빙. **Linux / Windows**에는 그런 디코더가 없어 데몬이 ffmpeg로 폴백합니다: 더 평탄하고 어두운 프레임과 반복되는 단일 스크럽 프레임. Rust 코어는 세 플랫폼 모두에서 동일하게 빌드됩니다 — AVFoundation은 거기에 절대 링크되지 않습니다. 세부 내용: [`core/README.md`](core/README.md)(빌드 및 디코드 경로)와 [`macos/README.md`](macos/README.md)(macOS 클라이언트). 다른 모든 코덱은 어디서나 같은 방식으로 ffmpeg가 처리합니다.

## 프로젝트 상태

**MVP는 macOS와 Compose Desktop에서 작동합니다.** 두 클라이언트 모두 동일한 기능 세트를 제공하며, macOS 클라이언트는 네이티브 메뉴바 명령과 NSWorkspace 기반 편집기 실행을 추가합니다. **원격 전용 iOS 클라이언트**(iPhone / iPad)는 LAN을 통해 데몬에 연결하여 동영상을 스트리밍합니다 — 탐색, 검사, 스택, 공유, 업로드; [`ios/README.md`](ios/README.md)를 참조하세요.

### ✅ 완료됨

**코어**
- [x] 전체 RPC 표면을 갖춘 gRPC 데몬(동영상, 검색, 스캔, 태그, 컬렉션, 스택, 필터, 상태, 구성, 카탈로그 수명 주기).
- [x] WAL 모드 + FTS5를 갖춘 SQLite 카탈로그; `OpenCatalog` / `CloseCatalog`를 통한 런타임 카탈로그 핫스왑.
- [x] Rich metadata extraction via FFprobe + platform-native helpers: codec,
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera.
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera.
- [x] Lightroom 스타일 썸네일 및 스크럽 프레임 생성(동영상당 10프레임)과 중복 작업 방지를 위한 동영상별 잠금.
- [x] 대규모 라이브러리 스캔 시 SAN 스토리지 과부하를 방지하기 위한 동시 ffmpeg 제한(기본값: 호스트 CPU 수).
- [x] 선택적 재귀 및 변형본 자동 그룹화를 포함한 라이브러리 스캔.
- [x] `--db-path`, `--no-catalog`, `--port`(OS 할당 포트 폴백 포함) CLI 플래그와 클라이언트 런처가 파싱할 수 있는 `REELVAULT_LISTENING_ON=…` stdout 라인.

**두 클라이언트 모두**
- [x] 적응형 열 수와 썸네일 크기 슬라이더를 갖춘 가상화 그리드 뷰.
- [x] 호버 스크럽 미리보기, 호버 재생 오버레이, Shift 범위 및 ⌘/Ctrl 토글을 포함한 다중 선택.
- [x] 스택(그룹) UI: 멤버 수가 표시된 스택 배지, 클릭하여 확장, 즐겨찾기 설정을 위한 별표, 멤버별 열기 버튼.
- [x] 사이드 패널: 라이브러리 위치(왼쪽), 세부 정보 + 메타데이터 + 메모 + 키워드(오른쪽). Tab으로 둘 다 전환, 개별 꺾쇠 화살표로 각각 접기.
- [x] 상단 바 필터 드롭다운(카메라 / 렌즈 / 키워드 / 코덱 / 연도) — 데이터가 있는 필드만 표시; 검색과 AND 조합.
- [x] 모든 주요 필드(파일명, 날짜, 시간, 크기, 해상도, fps, 코덱, 비트레이트, 카메라, 렌즈, 키워드)를 갖춘 정렬 메뉴; 다시 클릭하면 방향 반전.
- [x] 태그/키워드 관리: 즉석 생성, 다중 선택에 적용, `>` 꺾쇠 클릭으로 그리드 필터링.
- [x] 모든 카드에서 마우스 오른쪽 버튼 컨텍스트 메뉴: 기본 플레이어로 열기 및 Finder/탐색기에서 표시 — 전체 다중 선택에 적용.
- [x] 드래그 앤 드롭 전달: 선택된 카드를 파일 드롭을 허용하는 모든 앱으로 드래그(DaVinci Resolve, Final Cut Pro, Premiere Pro 등).
- [x] 다중 카탈로그 흐름: 열기 / 닫기 / 최근 열기가 있는 파일 메뉴, 첫 실행 시 "카탈로그 열기" 시트, 영구적인 최근 목록, 열린 카탈로그 이름을 표시하는 창 제목.
- [x] 시작 시 백엔드가 실행 중이지 않을 때 번들된 데몬 자동 실행(포트 사용 중이면 폴백).
- [x] 모든 인터랙티브 요소와 메타데이터 필드에 호버 텍스트 도움말(Kotlin에서는 `TooltipArea`, SwiftUI에서는 `.help(_:)` 툴팁).
- [x] 다크 모드 기본값; 라이트/다크 테마 전환.

### 🚧 예정됨

- [ ] 실시간 파일 감시(기반 폴더가 변경될 때 재인덱싱).
- [ ] 스마트 컬렉션(라이브 필터 규칙이 있는 저장된 검색).
- [ ] 8K 이상 영상을 위한 프록시 동영상 생성.
- [ ] GitHub 기반 자동 업데이트.
- [ ] 플랫폼별 서명된 설치 프로그램을 생성하는 CI/CD 릴리스 파이프라인.
- [ ] 클라이언트 앱 번들 내 데몬 바이너리 번들링(현재 런처는 `REELVAULT_CORE_BIN`이나 cargo 개발 트리를 통해 찾음).

## 시작하기

### 사전 요구 사항

- **Rust**(1.75+ 권장) — 코어 데몬 빌드용.
- **FFmpeg / FFprobe** — `PATH`에 있어야 합니다. 메타데이터 추출 및 썸네일/스크럽 프레임 생성에 사용됩니다.
- **JDK 17+** + Gradle(래퍼 포함) — Kotlin 데스크톱 클라이언트용.
- **Swift 5.9+ / Xcode 15+** — macOS 클라이언트용.
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — iOS 클라이언트용.

플랫폼별 설치 지침은 [`SETUP.md`](SETUP.md)를 참조하세요.

### 코어 데몬 빌드

```bash
cd core
cargo build --release
```

바이너리는 `core/target/release/reelvault-core`에 위치합니다. 직접 구동하고 싶다면 직접 실행하거나, 첫 실행 시 클라이언트 중 하나가 자동으로 실행하도록 할 수 있습니다:

```bash
# 기본값 — 기본 포트에서 플랫폼 기본 카탈로그 사용.
./target/release/reelvault-core

# 카탈로그 없이 시작(클라이언트에서 사용하는 모드); 바인딩된 포트 출력.
./target/release/reelvault-core --no-catalog --port 0

# 시작 시 특정 카탈로그 열기.
./target/release/reelvault-core --db-path /path/to/library.db
```

데몬은 클라이언트가 할당된 포트를 알아내기 위해 파싱하는 안정적인 `REELVAULT_LISTENING_ON=127.0.0.1:N` 라인을 stdout에 출력합니다.

### Kotlin Compose 데스크톱 클라이언트 실행

```bash
cd desktop
./gradlew run
```

첫 실행 시 클라이언트는 `127.0.0.1:50051`을 검사하고 — 수신 중인 것이 없으면 — 번들된 데몬을 실행합니다. 빌드 위치 탐색 순서:

1. `$REELVAULT_CORE_BIN` (데몬 실행 파일의 절대 경로)
2. 애플리케이션 jar 옆의 바이너리
3. 개발 트리의 `core/target/release/reelvault-core` 또는 `core/target/debug/reelvault-core`
4. `PATH`의 `reelvault-core`

개발 중에는 `core/` 안에서 `cargo build`만 하면 클라이언트가 디버그 바이너리를 사용합니다.

### macOS SwiftUI 클라이언트 실행

```bash
cd macos
swift run
```

동일한 자동 실행 흐름, 동일한 바이너리 탐색 순서(서명된 앱 번들에서 사용되는 번들 내 `Resources/reelvault-core` 경로 추가). `⌘O`로 카탈로그를 열고 `⇧⌘W`로 닫습니다; 최근 목록은 `파일 → 최근 열기`에 있습니다.

### iOS SwiftUI 클라이언트 실행

iOS 클라이언트는 **원격 전용**입니다 — 데몬을 직접 실행하는 대신 LAN을 통해 데몬에 연결합니다. 동일한 Wi-Fi의 머신에서 원격 모드로 데몬을 시작합니다:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

그런 다음 Xcode 프로젝트를 생성하고(`.xcodeproj`는 버전 관리에 포함되지 않음) 빌드합니다:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

첫 실행 시 앱이 mDNS로 데몬을 발견하고, 6자리 페어링 코드로 장치를 한 번 인증한 다음(데스크톱 클라이언트의 **파일 → 새 기기 페어링** 메뉴 또는 데몬 로그에서 생성), 탐색 및 스트리밍을 시작합니다. **iOS 18+** 및 **Xcode 16+** 필요. 스트리밍 및 페어링 모델을 포함한 전체 세부 내용은 [`ios/README.md`](ios/README.md)에 있습니다.

## 문서

- [`CLAUDE.md`](CLAUDE.md) — 프로젝트 비전, 아키텍처 세부 정보, 데이터베이스 스키마, 설계 원칙.
- [`SETUP.md`](SETUP.md) — 개발 환경 설정.
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Rust 코어 모듈 및 RPC 표면에 대한 상세 참조.
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Kotlin과 SwiftUI 클라이언트의 나란히 비교.
- [`ios/README.md`](ios/README.md) — 원격 전용 iOS(iPhone / iPad) 클라이언트: 디스커버리, 페어링, 고정된 TLS, HLS 스트리밍.
- [`macos/README.md`](macos/README.md) — 네이티브 macOS 클라이언트.

## 기여

개발 가이드라인은 [`CLAUDE.md`](CLAUDE.md)를 참조하세요. Pull Request를 환영합니다 — 적용 가능한 경우 모든 네 클라이언트의 기능 패리티를 유지하고
(플랫폼별 합법적 편차는 CLAUDE.md 참조), 새 소스 파일에 SPDX 헤더를 추가하세요 (아래 라이선스 참조).

## 라이선스

ReelVault는 **GNU General Public License, 버전 3 또는 (선택에 따라) 그 이후 버전**에 따라 라이선스가 부여된 자유 소프트웨어입니다. 전체 라이선스 텍스트는 [`LICENSE`](LICENSE)에 있고, 간략한 저작권 고지는 [`COPYRIGHT`](COPYRIGHT)에 있습니다.

모든 소스 파일은 라이선스 스캔 도구(REUSE, FOSSology, GitHub의 licensee 감지기 등)가 프로그래밍 방식으로 라이선스를 식별할 수 있도록 SPDX 식별자를 포함합니다:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

ReelVault의 수정된 버전을 배포하거나 — Rust 코어를 라이브러리로 링크하는 프로그램을 배포할 경우 — GPL은 동일한 조건으로 소스를 공개할 것을 요구합니다. 의무 사항 전체는 LICENSE 파일을 참조하세요.
