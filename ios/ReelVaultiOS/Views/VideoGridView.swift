// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Scrollable adaptive grid of video cards, bound to the shared GridViewModel.
///
/// The grid is layout-agnostic: it reports a card to its parent via `onActivate`
/// (the parent decides whether that means "select into the inspector" — iPad /
/// regular width — or "push a detail screen" — iPhone / compact width). It also
/// drives the shared view-model's arrow-key navigation when a hardware keyboard
/// is attached (iPad), mirroring the macOS client. Cards lazily load thumbnails
/// over gRPC and highlight the current selection.
struct VideoGridView: View {
    @ObservedObject var grid: GridViewModel
    /// Minimum card width; the grid flows as many columns as fit.
    var minCardWidth: CGFloat = 150
    /// Enable hardware-keyboard arrow navigation (iPad / regular width). On
    /// iPhone-portrait (compact) the grid is touch-only, matching the plan's
    /// "keyboard shortcuts apply to macOS + iPad-with-keyboard, not iPhone" rule.
    var keyboardEnabled: Bool = false
    /// Multi-select mode (for share export): taps toggle a checkmark instead of
    /// activating the card.
    var selecting: Bool = false
    /// Activate a card: select it (regular) or push its detail (compact).
    var onActivate: (VideoSummary) -> Void

    private let spacing: CGFloat = 10

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: minCardWidth), spacing: spacing)]
    }

    var body: some View {
        GeometryReader { geo in
            // Approximate the flowed column count so arrow-key navigation knows
            // the grid geometry (the shared VM steps row-by-row through this).
            let cols = max(1, Int((geo.size.width + spacing) / (minCardWidth + spacing)))
            ScrollViewReader { proxy in
                ScrollView {
                    if grid.videos.isEmpty {
                        ContentUnavailableView(
                            "No videos",
                            systemImage: "film",
                            description: Text("The connected catalog is empty, or no videos match the current filter.")
                        )
                        .padding(.top, 80)
                    } else {
                        LazyVGrid(columns: columns, spacing: spacing) {
                            ForEach(grid.videos) { video in
                                Button {
                                    if selecting {
                                        grid.toggleVideoSelection(video)
                                    } else {
                                        grid.selectVideo(video)
                                        onActivate(video)
                                    }
                                } label: {
                                    VideoCardView(
                                        video: video,
                                        image: grid.thumbnails[video.id],
                                        isSelected: !selecting && grid.selectedVideoId == video.id,
                                        showCheck: selecting,
                                        isChecked: grid.selectedVideoIds.contains(video.id)
                                    )
                                    .onAppear { grid.loadThumbnail(videoId: video.id) }
                                }
                                .buttonStyle(.plain)
                                .id(video.id)
                            }
                        }
                        .padding(spacing)
                    }
                }
                .onChange(of: grid.pendingScrollVideoId) { _, id in
                    guard let id else { return }
                    withAnimation { proxy.scrollTo(id, anchor: .center) }
                    grid.pendingScrollVideoId = nil
                }
            }
            .modifier(GridKeyboardNavigation(grid: grid, columns: cols, enabled: keyboardEnabled, onActivate: onActivate))
            .onChange(of: cols) { _, c in grid.setNavContext(grid.videos, columns: c) }
            .onChange(of: grid.videos) { _, v in grid.setNavContext(v, columns: cols) }
            .onAppear { grid.setNavContext(grid.videos, columns: cols) }
        }
    }
}

/// Wires hardware-keyboard arrow/return navigation into the shared view-model.
/// Applied only when `enabled` (iPad / regular width) so iPhone stays touch-only
/// and the scroll view never grabs focus there.
private struct GridKeyboardNavigation: ViewModifier {
    @ObservedObject var grid: GridViewModel
    let columns: Int
    let enabled: Bool
    let onActivate: (VideoSummary) -> Void

    func body(content: Content) -> some View {
        if enabled {
            content
                .focusable()
                .onKeyPress { press in handle(press) }
        } else {
            content
        }
    }

    private func handle(_ press: KeyPress) -> KeyPress.Result {
        let dir: MoveDirection?
        switch press.key {
        case .upArrow: dir = .up
        case .downArrow: dir = .down
        case .leftArrow: dir = .left
        case .rightArrow: dir = .right
        case .return:
            if let v = grid.selectedVideo { onActivate(v) }
            return .handled
        default: dir = nil
        }
        guard let dir else { return .ignored }
        grid.setNavContext(grid.videos, columns: columns)
        // moveSelection updates grid.selectedVideo (the inspector observes it)
        // and sets pendingScrollVideoId so the new card scrolls on screen.
        _ = grid.moveSelection(dir)
        return .handled
    }
}

/// A single grid card: thumbnail (or placeholder) plus filename + resolution.
struct VideoCardView: View {
    let video: VideoSummary
    let image: PlatformImage?
    var isSelected: Bool = false
    /// Show the multi-select checkmark overlay (share-export mode).
    var showCheck: Bool = false
    var isChecked: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack {
                Rectangle().fill(.quaternary)
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: "film")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                }
            }
            .aspectRatio(16.0 / 9.0, contentMode: .fill)
            .frame(maxWidth: .infinity)
            .clipped()
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(alignment: .topTrailing) {
                if showCheck {
                    Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, isChecked ? Color.accentColor : .black.opacity(0.4))
                        .padding(6)
                }
            }
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(Color.accentColor, lineWidth: (isSelected || (showCheck && isChecked)) ? 3 : 0)
            )

            Text(video.filename)
                .font(.caption)
                .lineLimit(1)
            if video.width > 0 && video.height > 0 {
                Text("\(video.width)×\(video.height)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
        .opacity(video.isOnline ? 1 : 0.5)
    }
}
