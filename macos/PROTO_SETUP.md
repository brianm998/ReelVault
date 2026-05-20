# Proto File Generation for macOS Client

## Overview

The macOS client is fully implemented and ready to use once the gRPC proto files are compiled to Swift. This document explains what needs to be done to complete the proto setup.

## Current Status

✅ **Complete:**
- All Swift source files (.swift files)
- MVVM architecture with ViewModels and Views
- gRPC channel setup and repository pattern
- SwiftUI UI components
- Package.swift configuration
- All model structures

❌ **Pending:**
- Proto file compilation to Swift for gRPC types
- Mock implementations for gRPC stubs

## Proto Setup Instructions

### Step 1: Generate Swift Protobuf Files

The proto definitions live in `core/proto/` and need to be compiled to Swift. Use the following command:

```bash
cd macos

# Install protoc plugins for Swift
brew install protobuf
brew install swift-protobuf

# Download grpc-swift plugin (if not already done)
git clone https://github.com/grpc/grpc-swift.git
cd grpc-swift
make all
cd ..

# Generate Swift code from protos
protoc \
  --swift_out=VideoRoom \
  --grpc-swift_out=VideoRoom \
  -I../core/proto \
  ../core/proto/video.proto
```

This will generate:
- `VideoRoom/Generated/video.pb.swift` - Protocol buffer message definitions
- `VideoRoom/Generated/video.grpc.swift` - gRPC service definitions

### Step 2: Import Generated Files

Once generated, the Swift files should be in `VideoRoom/Generated/` directory:

```
macos/
└── VideoRoom/
    └── Generated/
        ├── video.pb.swift           # Message types
        └── video.grpc.swift         # Service client
```

The import statements in `VideoRepository.swift` will then work:
```swift
// These will be available after proto generation
let request = Videoroom_ListVideosRequest()
let client = Videoroom_VideoRoomNIOClient(channel: channel)
```

### Step 3: Update VideoRepository

Once proto files are generated, uncomment the actual gRPC calls in `VideoRepository.swift`:

**Before** (current stub):
```swift
func listVideos(...) async throws -> [VideoSummary] {
    // TODO: Replace with actual gRPC call once proto files are compiled
    return []  // Stub
}
```

**After** (uncomment once proto is ready):
```swift
func listVideos(...) async throws -> [VideoSummary] {
    guard let client = client else { ... }
    
    var request = Videoroom_ListVideosRequest()
    request.limit = limit
    request.offset = offset
    let response = try await client.listVideos(request)
    return response.videos.map { ... }  // Map proto to Swift models
}
```

## Proto Compilation Issues & Solutions

### Missing protoc Compiler

If `protoc` is not found:
```bash
# macOS
brew install protobuf

# Linux
apt-get install protobuf-compiler

# Or compile from source
git clone https://github.com/protocolbuffers/protobuf.git
cd protobuf && ./configure && make && make install
```

### Missing Swift Protobuf Plugin

```bash
# macOS
brew install swift-protobuf

# Or from source
git clone https://github.com/apple/swift-protobuf.git
cd swift-protobuf && make
```

### Missing gRPC Swift Plugin

```bash
# Build grpc-swift
git clone https://github.com/grpc/grpc-swift.git
cd grpc-swift
make
# The plugin is at: .build/release/protoc-gen-grpc-swift
```

## Automated Proto Build

To automate proto generation, add a build phase to your Xcode project (requires `.xcodeproj`):

1. Target > Build Phases > New Run Script Phase
2. Add script:
```bash
PROTO_DIR="${PROJECT_DIR}/../core/proto"
SWIFT_OUT="${PROJECT_DIR}/VideoRoom/Generated"

mkdir -p "$SWIFT_OUT"

protoc \
  --swift_out="$SWIFT_OUT" \
  --grpc-swift_out="$SWIFT_OUT" \
  -I"$PROTO_DIR" \
  "$PROTO_DIR"/*.proto

# Add generated files to target membership in Xcode
```

## Alternative: Using Swift Package Manager Plugin

In future Swift versions (5.10+), proto compilation can be automated via a build plugin in `Package.swift`:

```swift
targets: [
    .executableTarget(
        name: "VideoRoom",
        dependencies: [...],
        plugins: [
            .plugin(name: "GRPCSwiftPlugin", package: "grpc-swift")
        ]
    )
]
```

However, current setup requires manual compilation.

## Testing After Proto Setup

Once protos are compiled:

1. Build the project:
```bash
swift build
```

2. Run the client:
```bash
swift run VideoRoom
```

3. The app will connect to `localhost:50051` and load videos from the backend

## Troubleshooting Proto Compilation

### Error: "Cannot find type in scope"

This means the proto-generated file isn't being found. Check:
- Generated files exist in `VideoRoom/Generated/`
- Files are in the correct path
- `Package.swift` includes the generated directory

### Error: "Module not found"

Rebuild from scratch:
```bash
rm -rf .build
swift build
```

### Error: "grpc-swift plugin not found"

Make sure the protoc plugin path is set:
```bash
export PATH=$PATH:/path/to/grpc-swift/.build/release/
```

## Proto Message Definitions

The gRPC service expects these message types (defined in `core/proto/video.proto`):

```protobuf
// Request/Response types the client needs
message ListVideosRequest {
    int32 limit = 1;
    int32 offset = 2;
    string search_query = 3;
    string sort_by = 4;
}

message ListVideosResponse {
    repeated VideoSummary videos = 1;
    int32 total_count = 2;
}

message GetMetadataRequest {
    string video_id = 1;
}

message GetMetadataResponse {
    VideoMetadata metadata = 1;
}

// ... and many more
```

These will be generated as:
```swift
struct Videoroom_ListVideosRequest: Message { ... }
struct Videoroom_ListVideosResponse: Message { ... }
// etc.
```

## Next Steps

1. Generate proto files using instructions above
2. Uncomment actual gRPC calls in `VideoRepository.swift`
3. Build and run the application
4. The UI will load and connect to the backend

## Current Stub Implementation

Until protos are compiled, the app will:
- ✓ Launch successfully
- ✓ Show connection error message
- ✓ Display all UI components properly
- ✗ Load videos (empty list due to stub)
- ✗ Load metadata
- ✗ Save notes

Once proto files are in place, all functionality will work.

## Proto Files Location

The source proto definitions are in:
```
/Users/brian/git/VideoRoom/core/proto/
```

Current known proto files:
- `video.proto` - Main video catalog service

These define the gRPC service contract that both clients (Desktop and macOS) implement.
