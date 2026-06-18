> [영어 원문](SETUP.md)

# ReelVault 개발 환경 설정

## 사전 요구 사항

### 시스템 요구 사항

- **macOS 11+**, **Linux (Ubuntu 20.04+)** 또는 **Windows 10+**
- **Rust 1.70+** ([rustup으로 설치](https://rustup.rs/))
- **FFmpeg & FFprobe** (동영상 분석 및 썸네일 생성용)

### FFmpeg 설치

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
https://ffmpeg.org/download.html 에서 다운로드하거나 다음을 사용합니다:
```bash
choco install ffmpeg
```

### Rust 설치

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

확인:
```bash
rustc --version
cargo --version
```

## 코어 빌드

```bash
cd core
cargo build --release
```

컴파일된 바이너리는 `core/target/release/reelvault-core`에 위치합니다.

## 코어 데몬 실행

```bash
./core/target/release/reelvault-core
```

데몬이 수행하는 작업:
- `~/.reelvault/catalog.db`에 데이터베이스 생성
- 썸네일 캐시 디렉토리 생성
- gRPC 연결을 위해 `127.0.0.1:50051`에서 수신 대기

## 테스트 실행

```bash
cd core
cargo test
```

## 개발 명령어

**컴파일 없이 코드 확인:**
```bash
cargo check
```

**코드 포맷:**
```bash
cargo fmt
```

**코드 린트:**
```bash
cargo clippy
```

**디버그 심볼과 함께 빌드:**
```bash
cargo build
```

## 프런트엔드 연결

코어 데몬이 실행 중이면 프런트엔드는 `http://127.0.0.1:50051`에서 gRPC를 통해 연결할 수 있습니다.

프로토 정의는 `core/proto/reelvault.proto`에 있으며 각 프런트엔드 언어에 맞는 클라이언트 코드를 생성하는 데 사용해야 합니다.

## 문제 해결

**"ffmpeg not found"**
- FFmpeg가 설치되어 있고 PATH에 있는지 확인하세요
- 확인: `which ffmpeg && which ffprobe`

**데이터베이스 잠금**
- 한 번에 하나의 데몬 인스턴스만 실행해야 합니다
- 기존 프로세스 종료: `pkill reelvault-core`

**포트가 이미 사용 중**
- 50051이 사용 중이면 `core/src/main.rs`에서 포트를 변경하세요
- 또는 프로세스 종료: `lsof -ti:50051 | xargs kill`

**Linux에서 빌드 문제**
- 추가 개발 의존성 설치: `sudo apt-get install build-essential libssl-dev`

## 다음 단계

1. **macOS 클라이언트**: SwiftUI 프런트엔드 구현 (post-MVP)
2. **데스크톱 클라이언트**: Kotlin Compose 프런트엔드 구현 (MVP 우선순위)
3. **테스트**: gRPC API를 위한 통합 테스트 추가
4. **CI/CD**: 빌드 및 릴리스를 위한 GitHub Actions 설정
