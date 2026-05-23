// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import AppKit
import UniformTypeIdentifiers

/// Builds an NSItemProvider suitable for dragging one or more video files
/// out of VideoRoom into external apps (DaVinci Resolve, Premiere Pro, etc.)
enum DragExport {
    /// Single file drag.
    static func provider(for path: String) -> NSItemProvider {
        let url = URL(fileURLWithPath: path)
        return NSItemProvider(object: url as NSURL)
    }

    /// Multi-file drag. Returns one provider per file; SwiftUI's ForEach
    /// passes these to the system which aggregates them into a multi-file
    /// drag session automatically when items are dragged simultaneously.
    static func providers(for paths: [String]) -> [NSItemProvider] {
        paths.map { provider(for: $0) }
    }
}
