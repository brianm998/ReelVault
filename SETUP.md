# VideoRoom Development Setup

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

The compiled binary will be at `core/target/release/videoroom-core`.

## Running the Core Daemon

```bash
./core/target/release/videoroom-core
```

The daemon will:
- Create a database at `~/.videoroom/catalog.db`
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

The proto definitions are in `core/proto/videoroom.proto` and should be used to generate client code for each frontend language.

## Troubleshooting

**"ffmpeg not found"**
- Ensure FFmpeg is installed and in your PATH
- Check: `which ffmpeg && which ffprobe`

**Database locked**
- Only one daemon instance should run at a time
- Kill any existing processes: `pkill videoroom-core`

**Port already in use**
- Change the port in `core/src/main.rs` if 50051 is occupied
- Or kill the process: `lsof -ti:50051 | xargs kill`

**Build issues on Linux**
- Install additional dev dependencies: `sudo apt-get install build-essential libssl-dev`

## Next Steps

1. **macOS Client**: Implement SwiftUI frontend (post-MVP)
2. **Desktop Client**: Implement Kotlin Compose frontend (MVP priority)
3. **Testing**: Add integration tests for gRPC API
4. **CI/CD**: Set up GitHub Actions for builds and releases
