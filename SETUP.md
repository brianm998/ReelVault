# ReelVault Development Setup

## Prerequisites

### System Requirements

- **macOS 11+**, **Linux (Ubuntu 20.04+)**, or **Windows 10+**
- **Rust 1.70+** ([Install via rustup](https://rustup.rs/))
- **FFmpeg & FFprobe** (for video analysis and thumbnail generation)

### Install FFmpeg

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
Download from https://ffmpeg.org/download.html or use:
```bash
choco install ffmpeg
```

### Install Rust

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

Verify:
```bash
rustc --version
cargo --version
```

## Building the Core

```bash
cd core
cargo build --release
```

The compiled binary will be at `core/target/release/reelvault-core`.

## Running the Core Daemon

```bash
./core/target/release/reelvault-core
```

The daemon will:
- Create a database at `~/.reelvault/catalog.db`
- Create a thumbnail cache directory
- Listen on `127.0.0.1:50051` for gRPC connections

## Running Tests

```bash
cd core
cargo test
```

## Development Commands

**Check code without compiling:**
```bash
cargo check
```

**Format code:**
```bash
cargo fmt
```

**Lint code:**
```bash
cargo clippy
```

**Build with debug symbols:**
```bash
cargo build
```

## Connecting a Frontend

Once the core daemon is running, frontends can connect via gRPC at `http://127.0.0.1:50051`.

The proto definitions are in `core/proto/reelvault.proto` and should be used to generate client code for each frontend language.

## Troubleshooting

**"ffmpeg not found"**
- Ensure FFmpeg is installed and in your PATH
- Check: `which ffmpeg && which ffprobe`

**Database locked**
- Only one daemon instance should run at a time
- Kill any existing processes: `pkill reelvault-core`

**Port already in use**
- Change the port in `core/src/main.rs` if 50051 is occupied
- Or kill the process: `lsof -ti:50051 | xargs kill`

**Build issues on Linux**
- Install additional dev dependencies: `sudo apt-get install build-essential libssl-dev`

## Building the Desktop Client

**Prerequisites:** JDK 17+ (not Android Studio's JBR), VLC installed for video playback.

```bash
cd desktop
./gradlew run
```

Or from the repo root in the multi-module setup:
```bash
./gradlew :desktop:run
```

## Building the Android Client

**Prerequisites:** Android SDK (API 35), JDK 17+.

```bash
# Debug APK
./gradlew :android:assembleDebug

# Release AAB (requires signing env vars; see .github/workflows/android-release.yml)
./gradlew :android:bundleRelease
```

**Local macOS note:** If `./gradlew :android:assembleDebug` fails with a `jlink` error, Android
Studio's JBR is being picked up. Fix it by adding to `~/.gradle/gradle.properties`:
```
org.gradle.java.home=/Library/Java/JavaVirtualMachines/jdk-XX.jdk/Contents/Home
```
Substitute the path for your installed JDK 17+ (not the JBR).

## Building the Shared Kotlin Library

The `shared/` module contains proto stubs, models, and `VideoRepository`. It is compiled
automatically when you build `:desktop` or `:android`. To build it alone:

```bash
./gradlew :shared:compileKotlin
```

## Next Steps

1. **macOS Client**: Implement SwiftUI frontend (post-MVP)
2. **Testing**: Add integration tests for gRPC API
