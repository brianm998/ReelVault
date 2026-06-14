// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

/// The four ways to view the catalog, mirroring the macOS client's view modes.
/// `detail` is only selectable when a video is selected.
enum LibraryViewMode: String, CaseIterable, Identifiable {
    case grid, list, detail, map

    var id: String { rawValue }

    var label: String {
        switch self {
        case .grid: return "Grid"
        case .list: return "List"
        case .detail: return "Detail"
        case .map: return "Map"
        }
    }

    var systemImage: String {
        switch self {
        case .grid: return "square.grid.2x2"
        case .list: return "list.bullet"
        case .detail: return "play.rectangle"
        case .map: return "map"
        }
    }

    /// Grid/list show the filter + thumbnail-size top bar; detail/map don't.
    var showsBrowseTopBar: Bool { self == .grid || self == .list }
}
