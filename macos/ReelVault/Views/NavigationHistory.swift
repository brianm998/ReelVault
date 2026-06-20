// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// One browse location for the back/forward history: the active view mode, the
/// grid "source" filters, the selected video, and the complete library filter
/// state. The macOS analogue of iOS's `NavState` (RootSplitView.swift).
///
/// iOS captures a single mutually-exclusive `LibrarySection`, but the macOS
/// sidebar lets a collection, a keyword tag, and one *or more* library-location
/// paths be co-active (the daemon AND-combines them), so we capture all three to
/// round-trip losslessly — the same approach the Android client takes.
///
/// Library filter state (search / rating / colour / attributes / metadata
/// columns) is also captured so back/forward restores the exact filter the user
/// had at each browse location, not whatever is currently active.
struct NavState: Equatable {
    var viewMode: ContentView.ViewMode
    var locationPaths: [String]
    var collectionId: String?
    var tagId: String
    var selectedVideoId: String?
    // Library filter state
    var searchQuery: String
    var filterMinRating: Int32
    var filterColorLabel: String
    var filterHasLocation: AttributeFilterState
    var filterHasKeywords: AttributeFilterState
    var filterHasProxies: AttributeFilterState
    var filterFullResolution: AttributeFilterState
    var filterHasAudio: AttributeFilterState
    var filterOrientation: OrientationFilterState
    var metadataColumns: [MetadataColumn]
}

/// Browser-style session navigation history — a port of iOS's `NavigationHistory`.
/// `record` pushes a new location (truncating any forward entries — diverging
/// after a back starts a new branch); `goBack`/`goForward` move the cursor and
/// return the entry to restore. Recording the state a restore produces is a no-op
/// (it equals the cursor), so back/forward don't pollute the history.
@MainActor
final class NavigationHistory: ObservableObject {
    @Published private(set) var canGoBack = false
    @Published private(set) var canGoForward = false
    private var stack: [NavState] = []
    private var index = -1

    func record(_ s: NavState) {
        if index >= 0, stack[index] == s { return }            // dedup (absorbs restores)
        if index < stack.count - 1 { stack.removeSubrange((index + 1)...) }  // truncate forward
        stack.append(s)
        index = stack.count - 1
        refresh()
    }

    func goBack() -> NavState? {
        guard index > 0 else { return nil }
        index -= 1; refresh(); return stack[index]
    }

    func goForward() -> NavState? {
        guard index < stack.count - 1 else { return nil }
        index += 1; refresh(); return stack[index]
    }

    /// Wipe the history — called when the open catalog changes, so entries from a
    /// previous library (with now-meaningless video ids) can't be navigated to.
    func reset() {
        stack.removeAll(); index = -1; refresh()
    }

    private func refresh() {
        canGoBack = index > 0
        canGoForward = index < stack.count - 1
    }
}

/// Back/forward toolbar buttons, disabled at the ends of the history — the macOS
/// counterpart of iOS's `NavHistoryButtons`. Rendered at the leading edge of the
/// top bar.
struct NavHistoryButtons: View {
    @ObservedObject var history: NavigationHistory
    let goBack: () -> Void
    let goForward: () -> Void

    var body: some View {
        HStack(spacing: 2) {
            Button(action: goBack) {
                Image(systemName: "chevron.backward")
                    .font(.system(size: 20, weight: .semibold))
            }
                .buttonStyle(.borderless)
                .disabled(!history.canGoBack)
                .help("Back")
            Button(action: goForward) {
                Image(systemName: "chevron.forward")
                    .font(.system(size: 20, weight: .semibold))
            }
                .buttonStyle(.borderless)
                .disabled(!history.canGoForward)
                .help("Forward")
        }
    }
}
