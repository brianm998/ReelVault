# VideoRoom macOS Client

A native SwiftUI application for managing and cataloging large video libraries on macOS.

## Requirements

- macOS 13.0 or later
- Xcode 14.0 or later
- Swift 5.9 or later
- VideoRoom Rust backend running on `localhost:50051`

## Building

### Using Xcode

1. Open `VideoRoom.xcodeproj` in Xcode
2. Select the VideoRoom target
3. Build: Cmd+B
4. Run: Cmd+R

### Using Swift Package Manager

```bash
cd macos
swift build
swift run VideoRoom
```

### Using Make (if available)

```bash
make build
make run
```

## Architecture

### Directory Structure

```
VideoRoom/
├── VideoRoom.swift           # App entry point
├── Models/
│   └── Video.swift          # Domain models (VideoSummary, VideoMetadata, etc.)
├── ViewModels/
│   ├── GridViewModel.swift  # List state management
│   └── DetailViewModel.swift # Detail state management
├── Views/
│   ├── ContentView.swift    # Main UI container
│   ├── GridView.swift       # Video grid display
│   ├── DetailView.swift     # Metadata detail panel
│   └── ConnectionErrorView.swift # Error fallback
├── Utilities/
│   └── VideoRepository.swift # gRPC client wrapper
├── Resources/
│   └── Preview Content/
└── Info.plist
```

### Design Pattern

The application follows **MVVM (Model-View-ViewModel)** architecture:

- **Models**: Domain models (`Video.swift`) representing data from the backend
- **ViewModels**: State management (`GridViewModel`, `DetailViewModel`) handling business logic and gRPC calls
- **Views**: SwiftUI components (`ContentView`, `GridView`, `DetailView`) displaying UI
- **Repository**: `VideoRepository` abstracts gRPC communication

### State Management

- **@Published**: Properties in ViewModels automatically trigger UI updates
- **@ObservedObject**: Views subscribe to ViewModel changes
- **@State**: Local UI state (e.g., search query, dark mode)
- **async/await**: Async gRPC calls with MainActor dispatch for UI updates

## Features

### MVP (Implemented)

- [x] Video grid with 4-column layout
- [x] Lazy loading and pagination
- [x] Video card with metadata badges (resolution, duration)
- [x] Hover effects (play icon, border highlighting)
- [x] Selection (primary color border on selected)
- [x] Detail panel with full metadata
- [x] Notes editor with auto-save
- [x] Tag display and management
- [x] Search with live debouncing
- [x] Dark/light theme toggle
- [x] Error handling and connection fallback

### Future

- [ ] Thumbnail preview generation
- [ ] Keyboard navigation (arrow keys, enter, escape)
- [ ] Drag-and-drop support
- [ ] Tag filtering
- [ ] Collection management UI
- [ ] External editor launching
- [ ] Library location management
- [ ] Smart collections
- [ ] Saved searches
- [ ] Batch operations

## Running Against the Backend

Ensure the VideoRoom Rust backend is running:

```bash
cd ../core
cargo run --bin videoroom-core
```

The macOS client will connect to `localhost:50051` via gRPC.

## Testing

### Manual Testing Checklist

- [ ] Grid loads and displays videos
- [ ] Scrolling is smooth and performant
- [ ] Pagination loads more videos when near the end
- [ ] Video selection highlights the card
- [ ] Detail panel updates when video is selected
- [ ] Metadata displays correctly
- [ ] Notes can be edited and are auto-saved
- [ ] Search filters videos in real-time
- [ ] Dark mode toggle works
- [ ] Error states display correctly
- [ ] Connection error fallback appears when backend is offline

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| Cmd+F | Focus search bar |
| Cmd+, | Open preferences |
| Escape | Deselect video |
| Arrow Keys | Navigate grid (future) |

## Performance Considerations

- **Lazy Grid**: Only renders visible + buffer cells
- **Pagination**: Loads 50 videos at a time
- **Debounced Search**: 500ms debounce to reduce API calls
- **Async Thumbnail Loading**: Doesn't block UI
- **MainActor Dispatch**: All UI updates marshaled to main thread

## Troubleshooting

### "Failed to connect to VideoRoom backend"

Ensure the Rust backend is running on port 50051:
```bash
cd ../core && cargo run --bin videoroom-core
```

### Grid is slow or janky

- Check if backend is overloaded
- Try reducing page size if needed
- Verify network latency to backend

### Build Errors

- Ensure you have Swift 5.9+
- Check that gRPC-Swift dependencies resolve
- Rebuild: `Cmd+Shift+K` then `Cmd+B` in Xcode

## Development Notes

### Adding a New Feature

1. Create model in `Models/Video.swift`
2. Add ViewModel method in `ViewModels/`
3. Update `VideoRepository` with gRPC call
4. Create View in `Views/`
5. Update main `ContentView` to integrate

### Modifying Metadata Display

Edit `MetadataItemView` in `DetailView.swift` to add more fields from `VideoMetadata`.

### Theming

Colors and fonts are defined in SwiftUI's `Color` and `Font` APIs. To customize:
- Colors: Use `Color.accentColor`, `Color(.controlBackgroundColor)`, etc.
- Fonts: Use `.font(.body)`, `.font(.caption)`, etc.

## Known Limitations

- Thumbnails are fetched but not yet displayed (proto integration pending)
- No proxy video support for large files
- No QuickLook integration yet
- No Finder integration
- External editor launching not yet implemented

## Contributing

When modifying the client:
- Keep ViewModels focused on state and async operations
- Keep Views as thin as possible (delegates logic to ViewModels)
- Use @escaping and async/await appropriately
- Always update error handling in ViewModels

## License

2024 VideoRoom Contributors. All rights reserved.
