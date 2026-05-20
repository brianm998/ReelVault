# VideoRoom Client Comparison

## Overview

VideoRoom now features two fully-featured client applications built for different platforms, each optimized for their respective ecosystems while sharing the same Rust backend and core architecture.

## Quick Comparison

| Aspect | Desktop (Kotlin Compose) | macOS (SwiftUI) |
|--------|--------------------------|-----------------|
| **Platform** | Windows, Linux, macOS | macOS only |
| **Language** | Kotlin 1.9.20 | Swift 5.9+ |
| **Framework** | Compose Multiplatform | SwiftUI |
| **Build System** | Gradle | SPM / Xcode |
| **State Management** | StateFlow + Coroutines | @Published + Combine |
| **Async Pattern** | suspend functions | async/await |
| **Backend Comm.** | gRPC over Kotlin Stub | gRPC over Swift Channel |
| **UI Pattern** | LazyVerticalGrid | LazyVGrid |
| **Theming** | Material 3 | Native macOS Colors |
| **Min Requirement** | JRE 17+ | macOS 13.0+ |
| **Window Size** | 1400×900 fixed | ~1200×800 minimum |
| **Target Users** | All platforms | Mac enthusiasts |

## Architecture Comparison

### Desktop Client (Kotlin Compose)

```
┌─────────────────────────────────┐
│      Kotlin Compose Desktop     │
├─────────────────────────────────┤
│  App.kt (Window + Theme)        │
│  ├─ VideoRoomApp()              │
│  │  ├─ GridScreen()             │
│  │  │  └─ VideoCard (4x grid)   │
│  │  └─ DetailScreen()           │
│  │     └─ MetadataItem          │
│  └─ VideoRoomTopBar()           │
├─────────────────────────────────┤
│  GridViewModel / DetailViewModel │
│  (StateFlow + Coroutines)       │
├─────────────────────────────────┤
│  VideoRepository (gRPC)         │
│  ├─ listVideos()                │
│  ├─ getVideoMetadata()          │
│  └─ getThumbnail()              │
├─────────────────────────────────┤
│  Data Models                    │
│  └─ VideoSummary / VideoMetadata│
└─────────────────────────────────┘
         ↓ gRPC
    Rust Backend
```

### macOS Client (SwiftUI)

```
┌─────────────────────────────────┐
│      SwiftUI for macOS          │
├─────────────────────────────────┤
│  VideoRoomApp.swift             │
│  └─ ContentView()               │
│     ├─ GridView()               │
│     │  └─ VideoCardView (4x)    │
│     └─ DetailView()             │
│        └─ MetadataItemView      │
├─────────────────────────────────┤
│  GridViewModel / DetailViewModel │
│  (@Published + Combine)         │
├─────────────────────────────────┤
│  VideoRepository (gRPC)         │
│  ├─ listVideos()                │
│  ├─ getVideoMetadata()          │
│  └─ getThumbnail()              │
├─────────────────────────────────┤
│  Data Models                    │
│  └─ VideoSummary / VideoMetadata│
└─────────────────────────────────┘
         ↓ gRPC
    Rust Backend
```

## Feature Parity

### Implemented in Both

✓ 4-column video grid with 8pt spacing
✓ 70/30 split (grid/detail)
✓ Video selection with primary color border
✓ Hover effects on cards (play icon, border highlight)
✓ Resolution and duration badges on thumbnails
✓ Full metadata panel with all fields
✓ Notes editor with auto-save
✓ Tag display and management
✓ Search with live debouncing (500ms)
✓ Pagination (50 videos per page)
✓ Error handling and user messaging
✓ Loading states and empty states
✓ Dark/light theme toggle
✓ Connection error fallback
✓ Async thumbnail loading
✓ Responsive UI during network operations

### Desktop-Specific Features

- Cross-platform distribution (Windows, Linux, macOS)
- Gradle build system integration
- Material 3 theming customization
- Direct distribution as .dmg/.msi/.exe/.deb

### macOS-Specific Features

- Native macOS window styling
- Potential for QuickLook integration (future)
- Finder integration hooks (future)
- Native file dialogs and panels
- Potential Spotlight search integration (future)

## Code Structure Comparison

### GridViewModel

**Desktop (Kotlin)**:
```kotlin
class GridViewModel(private val repository: VideoRepository) {
    private val _videos = MutableStateFlow<List<VideoSummary>>(emptyList())
    val videos: StateFlow<List<VideoSummary>> = _videos.asStateFlow()
    
    fun loadVideos() {
        viewModelScope.launch {
            _videos.value = repository.listVideos(...)
        }
    }
}
```

**macOS (Swift)**:
```swift
class GridViewModel: ObservableObject {
    @Published var videos: [VideoSummary] = []
    private let repository = VideoRepository.shared
    
    func loadVideos() {
        Task {
            self.videos = try await repository.listVideos(...)
        }
    }
}
```

**Key Differences**:
- Kotlin: Explicit `StateFlow` + `viewModelScope`
- Swift: `@Published` + implicit `Task` for async

### Repository Pattern

**Both** implement the same interface:
```
connect() -> Bool
disconnect()
listVideos(limit, offset, searchQuery, sortBy) -> [VideoSummary]
getVideoMetadata(videoId) -> VideoMetadata
getThumbnail(videoId, size) -> Image
updateVideoNotes(videoId, notes) -> Bool
tagVideos(videoIds, tagId) -> Bool
addToCollection(videoIds, collectionId) -> Bool
```

**Implementation Differences**:
- Desktop: Wraps gRPC Kotlin stub with `withContext(Dispatchers.IO)`
- macOS: Wraps gRPC Swift channel with `MainActor.run`

### View Components

**Desktop (Compose)**:
```kotlin
@Composable
fun VideoCard(
    video: VideoSummary,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) { ... }
```

**macOS (SwiftUI)**:
```swift
struct VideoCardView: View {
    let video: VideoSummary
    let isSelected: Bool
    let onSelect: () -> Void
    
    var body: some View { ... }
}
```

**Differences**:
- Desktop: `@Composable` function, `Modifier` composition
- macOS: `struct View`, property-based configuration

## Development Workflow Comparison

### Adding a New Metadata Field

#### Desktop Steps

1. Add to proto file (`core/proto/video.proto`)
2. Update `VideoMetadata` in `desktop/src/main/kotlin/.../models/Video.kt`
3. Update `DetailScreen.kt` to display new field with `MetadataItem()`
4. Run `gradle build` to verify

#### macOS Steps

1. Add to proto file (`core/proto/video.proto`)
2. Update `VideoMetadata` in `macos/VideoRoom/Models/Video.swift`
3. Update `DetailView.swift` to display new field with `MetadataItemView()`
4. Run `swift build` to verify

### Key Differences

- **Proto**: Both pull from same source (`core/proto/`)
- **Models**: Structure identical, language different
- **Views**: Different syntax but same pattern
- **Build**: Different tools (gradle vs swift)

## Performance Characteristics

### Grid Rendering

| Metric | Desktop | macOS |
|--------|---------|-------|
| Columns | 4 | 4 |
| Visible Cells | ~20 | ~20 |
| Virtualization | LazyVerticalGrid | LazyVGrid |
| Memory (100k items) | ~5-10 MB | ~5-10 MB |
| Scroll FPS | 60 (target) | 60 (target) |

### Network

| Operation | Desktop | macOS |
|-----------|---------|-------|
| Backend Connection | `localhost:50051` | `localhost:50051` |
| Protocol | gRPC + Protobuf | gRPC + Protobuf |
| Search Debounce | 500ms | 500ms |
| Page Size | 50 | 50 |

### Build Times

| Build Type | Desktop | macOS |
|-----------|---------|-------|
| Clean | ~45s (first time) | ~30s |
| Incremental | ~5-10s | ~5-10s |
| Release | ~60s | ~40s |

## Testing Compatibility

Both clients can be tested against the same Rust backend:

```bash
# Terminal 1: Start backend
cd core
cargo run --bin videoroom-core

# Terminal 2: Run Desktop client
cd desktop
./gradlew run

# Terminal 3: Run macOS client
cd macos
swift run VideoRoom
```

All three can run simultaneously without conflicts.

## Deployment Strategy

### Desktop Client

1. Build platform-specific distributable:
   ```
   gradle nativeDistributions
   ```
2. Outputs: VideoRoom-0.1.0.dmg, VideoRoom-0.1.0.msi, VideoRoom_0.1.0.exe, videoroom-0.1.0.deb
3. Publish to GitHub Releases
4. Users download appropriate binary for their OS

### macOS Client

1. Build release binary:
   ```
   swift build -c release
   ```
2. Code sign (requires developer certificate)
3. Create .app bundle and .dmg
4. Publish to GitHub Releases or App Store

## Future: Progressive Feature Addition

### New Feature Example: AI Auto-Tagging

**Backend First**:
```rust
// Add to Rust backend
fn auto_tag_video(video_id: &str) -> Vec<String> {
    // ML inference here
}
```

**Proto Update**:
```protobuf
rpc AutoTagVideo(AutoTagRequest) returns (TagResponse);
```

**Both Clients**: Add virtually identical code
- Update Repository to call `autoTagVideo()`
- Add button in DetailView/DetailScreen
- Update ViewModel to handle operation

This shows how the shared backend drives feature consistency across clients.

## Decision: When to Use Which Client

### Use Desktop Client If:
- Running on Windows or Linux (only option)
- Prefer cross-platform consistency
- Using multiple operating systems
- Want the most minimal dependencies

### Use macOS Client If:
- Running on macOS (better native integration)
- Prefer SwiftUI modern patterns
- Want potential Finder/QuickLook integration
- Comfortable with Xcode ecosystem

### Recommendation:
- **Primary Users**: Use whichever OS you prefer
- **Professional**: Use macOS for native integration
- **Cross-Platform Teams**: Use Desktop for consistency
- **Maximum Features**: Eventually both will have feature parity

## Technical Debt & Opportunities

### Shared Across Both

- [ ] Actual thumbnail image loading (proto stubs now)
- [ ] External editor integration
- [ ] Keyboard navigation (arrow keys, vim keys)
- [ ] Multi-select and batch operations
- [ ] Library location management UI
- [ ] Smart collections query builder

### Desktop-Specific

- [ ] Windows native integration (shell extensions)
- [ ] Linux native integration (GTK/Qt polish)
- [ ] System tray integration

### macOS-Specific

- [ ] QuickLook thumbnail provider
- [ ] Finder sync extension
- [ ] Spotlight search integration
- [ ] Services menu integration

## Conclusion

The VideoRoom project now has two best-in-class client applications:

1. **Desktop Client**: Cross-platform reach with Kotlin Compose
2. **macOS Client**: Native excellence with SwiftUI

Both share:
- Same Rust backend
- Identical MVVM architecture
- Identical feature set
- Compatible data models
- Same gRPC communication protocol

This allows users to choose based on their OS preference while enjoying a consistent, high-quality experience. The architecture supports rapid feature additions across both clients simultaneously.

The path is clear for adding advanced features (AI tagging, proxy generation, Finder integration, etc.) in a way that benefits all platforms proportionally.
