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
    var minCardWidth: CGFloat = 170
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
                                VideoCardView(
                                    video: video,
                                    image: grid.thumbnails[video.id],
                                    topSlots: grid.topSlots,
                                    isSelected: !selecting && grid.selectedVideoId == video.id,
                                    showCheck: selecting,
                                    isChecked: grid.selectedVideoIds.contains(video.id),
                                    onActivate: {
                                        if selecting {
                                            grid.toggleVideoSelection(video)
                                        } else {
                                            grid.selectVideo(video)
                                            onActivate(video)
                                        }
                                    },
                                    onSetRating: { grid.setRating($0, for: [video.id]) },
                                    onSetColorLabel: { grid.setColorLabel($0, for: [video.id]) }
                                )
                                .onAppear { grid.loadThumbnail(videoId: video.id) }
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

/// A grid card matching the macOS/desktop clients: a configurable top stat band
/// (Lightroom-style 2×2 slots), a square photo area with a colour-label tint and
/// status badges, and a 5-star rating band. Tapping the photo/stats activates
/// the card; tapping a star sets the rating.
struct VideoCardView: View {
    let video: VideoSummary
    let image: PlatformImage?
    var topSlots: [String] = defaultGridTopSlots
    var isSelected: Bool = false
    /// Show the multi-select checkmark overlay (share-export mode).
    var showCheck: Bool = false
    var isChecked: Bool = false
    var onActivate: () -> Void = {}
    var onSetRating: (Int) -> Void = { _ in }
    var onSetColorLabel: (String) -> Void = { _ in }

    private let bandColor = Color(white: 0.11)   // card chrome (dark)
    private let dividerColor = Color.black.opacity(0.6)

    private var colorLabel: ColorLabel { ColorLabel(video.colorLabel) }

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                topStatBand
                Rectangle().fill(dividerColor).frame(height: 1)
                photoArea
            }
            .contentShape(Rectangle())
            .onTapGesture { onActivate() }

            Rectangle().fill(dividerColor).frame(height: 1)
            ratingBand
        }
        .background(bandColor)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .strokeBorder(Color.accentColor, lineWidth: showBorder ? 3 : 0)
        )
        .opacity(video.isOnline ? 1 : 0.5)
        .contextMenu { VideoCardMenu(video: video, onSetRating: onSetRating, onSetColorLabel: onSetColorLabel) }
    }

    /// Highlight the card when it's the active selection or a checked
    /// multi-select item.
    private var showBorder: Bool { isSelected || (showCheck && isChecked) }

    // MARK: Top stat band (2×2 configurable slots)

    private var paddedSlots: [String] {
        var s = topSlots
        while s.count < 4 { s.append("") }
        return Array(s.prefix(4))
    }

    private var topStatBand: some View {
        let slots = paddedSlots
        return VStack(spacing: 1) {
            HStack(spacing: 6) {
                statCell(slots[0], leading: true, emphasized: true)
                statCell(slots[2], leading: false, emphasized: false)
            }
            HStack(spacing: 6) {
                statCell(slots[1], leading: true, emphasized: false)
                statCell(slots[3], leading: false, emphasized: false)
            }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
    }

    private func statCell(_ key: String, leading: Bool, emphasized: Bool) -> some View {
        let text = GridStatKey(rawValue: key).map { $0.value(for: video) } ?? ""
        return Text(text)
            .font(.system(size: 10, weight: emphasized ? .semibold : .regular))
            .lineLimit(1)
            .truncationMode(.middle)
            .foregroundStyle(.white.opacity(0.92))
            .frame(maxWidth: .infinity, alignment: leading ? .leading : .trailing)
    }

    // MARK: Photo area (square, colour-label tint, badges)

    private var photoArea: some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .background(colorLabel == .none ? Color(white: 0.28) : colorLabel.dimmed)
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                } else {
                    Image(systemName: "film")
                        .font(.title2)
                        .foregroundStyle(.white.opacity(0.5))
                }
            }
            .clipped()
            .overlay(alignment: .bottomTrailing) {
                CardStatusBadges(video: video).padding(4)
            }
            .overlay(alignment: .topLeading) {
                if video.hasLocation {
                    Image(systemName: "mappin.circle.fill")
                        .font(.system(size: 11))
                        .foregroundStyle(.white)
                        .padding(4)
                        .shadow(radius: 1)
                }
            }
            .overlay(alignment: .topTrailing) {
                if showCheck {
                    Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, isChecked ? Color.accentColor : .black.opacity(0.4))
                        .padding(6)
                }
            }
    }

    // MARK: Rating band (5 tappable stars)

    private var ratingBand: some View {
        HStack(spacing: 4) {
            ForEach(1...5, id: \.self) { position in
                Image(systemName: position <= video.rating ? "star.fill" : "star")
                    .font(.system(size: position <= video.rating ? 11 : 10))
                    .foregroundStyle(position <= video.rating ? .white : .white.opacity(0.3))
                    .frame(width: 18, height: 18)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        // Tap a set star again to clear (Lightroom-style).
                        onSetRating(video.rating == position ? 0 : position)
                    }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 3)
    }
}

/// Long-press context menu for a video card / list row: set the star rating and
/// the colour label (the iOS counterpart of the macOS right-click menu).
struct VideoCardMenu: View {
    let video: VideoSummary
    var onSetRating: (Int) -> Void
    var onSetColorLabel: (String) -> Void

    var body: some View {
        Menu("Rating") {
            ForEach(Array((0...5).reversed()), id: \.self) { n in
                Button {
                    onSetRating(n)
                } label: {
                    Label(n == 0 ? "None" : String(repeating: "★", count: n),
                          systemImage: video.rating == n ? "checkmark" : "")
                }
            }
        }
        Menu("Color Label") {
            ForEach(ColorLabel.allCases) { label in
                Button {
                    onSetColorLabel(label.rawValue)
                } label: {
                    Label(label.displayName,
                          systemImage: video.colorLabel == label.rawValue ? "checkmark" : "circle.fill")
                }
            }
        }
    }
}
