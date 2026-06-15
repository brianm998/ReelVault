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
/// One rendered row: a collapsed video, or an expanded stack member inserted
/// right after its representative. Used by both the grid and list so stacks
/// render and expand identically.
struct StackRow: Identifiable {
    let video: VideoSummary
    let isMember: Bool
    /// Distinct from the representative's id so an expanded member never collides
    /// with a top-level row in `ForEach`.
    var id: String { isMember ? "member:\(video.id)" : video.id }
}

/// Flatten `grid.videos` (collapsed representatives) into render rows, inserting
/// an expanded stack's members right after their representative.
@MainActor
func stackRenderedVideos(_ grid: GridViewModel) -> [StackRow] {
    var out: [StackRow] = []
    for video in grid.videos {
        out.append(StackRow(video: video, isMember: false))
        if video.isInGroup, grid.expandedGroupIds.contains(video.groupId),
           let members = grid.expandedGroupMembers[video.groupId] {
            for member in members where member.id != video.id {
                out.append(StackRow(video: member, isMember: true))
            }
        }
    }
    return out
}

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
                        if let error = grid.error {
                            // A failed load must look different from an empty
                            // catalog — otherwise "couldn't reach the library"
                            // and "no videos here" are indistinguishable.
                            ContentUnavailableView {
                                Label("Couldn’t load videos", systemImage: "exclamationmark.triangle")
                            } description: {
                                Text(error)
                            } actions: {
                                Button("Retry") { grid.loadVideos() }
                            }
                            .padding(.top, 80)
                        } else if !grid.hasLoadedOnce || grid.isLoading {
                            // Don't flash "No videos" before the first page lands:
                            // an empty grid is only genuinely empty once a load has
                            // completed (hasLoadedOnce) and none is in flight.
                            ProgressView()
                                .frame(maxWidth: .infinity)
                                .padding(.top, 80)
                        } else {
                            ContentUnavailableView(
                                "No videos",
                                systemImage: "film",
                                description: Text("The connected catalog is empty, or no videos match the current filter.")
                            )
                            .padding(.top, 80)
                        }
                    } else {
                        let rows = stackRenderedVideos(grid)
                        LazyVGrid(columns: columns, spacing: spacing) {
                            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                                let video = row.video
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
                                    onSetColorLabel: { grid.setColorLabel($0, for: [video.id]) },
                                    isStackMember: row.isMember,
                                    selectedCount: grid.selectedVideoIds.count,
                                    onToggleExpand: { grid.toggleStackExpansion(video.groupId) },
                                    onCombine: { grid.groupSelectedVideos() },
                                    onPromote: { grid.setStackMaster(videoId: video.id, groupId: video.groupId) },
                                    onRemoveFromStack: { grid.removeFromStack(videoId: video.id, groupId: video.groupId) },
                                    onUnstack: { grid.unstackGroup(groupId: video.groupId) }
                                )
                                .onAppear {
                                    grid.loadThumbnail(videoId: video.id)
                                    // Page in the next batch as the user nears the
                                    // end — without this the grid is stuck at the
                                    // first page (pageSize) of a large catalog.
                                    // Counts rendered rows (incl. expanded stack
                                    // members), which only over-estimates, so we
                                    // page slightly early — harmless.
                                    if index >= rows.count - 8 && grid.hasMore {
                                        grid.loadMore()
                                    }
                                }
                                .id(row.id)
                            }
                            if grid.hasMore {
                                ProgressView()
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                                    .gridCellColumns(max(1, cols))
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
    /// This card is an expanded stack member (rendered after its representative).
    var isStackMember: Bool = false
    /// Multi-selected count (Select mode), passed to the menu's "Combine" gate.
    var selectedCount: Int = 0
    var onToggleExpand: () -> Void = {}
    var onCombine: () -> Void = {}
    var onPromote: () -> Void = {}
    var onRemoveFromStack: () -> Void = {}
    var onUnstack: () -> Void = {}

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
        .contextMenu {
            VideoCardMenu(
                video: video, selectedCount: selectedCount,
                onSetRating: onSetRating, onSetColorLabel: onSetColorLabel,
                onCombine: onCombine, onPromote: onPromote,
                onRemoveFromStack: onRemoveFromStack, onUnstack: onUnstack)
        }
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
                } else if video.isInGroup && !isStackMember {
                    stackBadge
                }
            }
            .overlay(alignment: .bottomLeading) {
                if isStackMember {
                    Image(systemName: "arrow.turn.down.right")
                        .font(.system(size: 9))
                        .foregroundStyle(.white)
                        .frame(width: 18, height: 18)
                        .background(Color.black.opacity(0.55))
                        .clipShape(Circle())
                        .padding(4)
                }
            }
    }

    /// Stack indicator on a representative card: member count, taps to expand/collapse.
    private var stackBadge: some View {
        Button(action: onToggleExpand) {
            HStack(spacing: 2) {
                Image(systemName: "square.stack.3d.up.fill").font(.system(size: 9))
                Text("\(video.groupSize)").font(.system(size: 10, weight: .semibold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(Color.black.opacity(0.6), in: Capsule())
        }
        .buttonStyle(.plain)
        .padding(4)
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
/// the colour label (the iOS counterpart of the macOS right-click menu). Uses
/// Pickers so the current value gets a checkmark, and colored emoji for the
/// labels (menu-item SF Symbols can't be tinted per-item — they'd all take the
/// menu's accent, which is why the dots were all purple).
struct VideoCardMenu: View {
    let video: VideoSummary
    /// Count of multi-selected videos (Select mode) — gates "Combine into Stack".
    var selectedCount: Int = 0
    var onSetRating: (Int) -> Void
    var onSetColorLabel: (String) -> Void
    var onCombine: () -> Void = {}
    var onPromote: () -> Void = {}
    var onRemoveFromStack: () -> Void = {}
    var onUnstack: () -> Void = {}

    var body: some View {
        Picker("Rating", selection: Binding(get: { video.rating }, set: { onSetRating($0) })) {
            ForEach(Array((0...5).reversed()), id: \.self) { n in
                Text(n == 0 ? "None" : String(repeating: "★", count: n)).tag(n)
            }
        }
        Picker("Color Label", selection: Binding(get: { video.colorLabel }, set: { onSetColorLabel($0) })) {
            ForEach(ColorLabel.allCases) { label in
                Text("\(Self.dot(label)) \(label.displayName)").tag(label.rawValue)
            }
        }
        if selectedCount >= 2 || video.isInGroup {
            Divider()
            if selectedCount >= 2 {
                Button { onCombine() } label: { Label("Combine into Stack", systemImage: "square.stack.3d.up") }
            }
            if video.isInGroup {
                if video.id != video.groupPreferredId {
                    Button { onPromote() } label: { Label("Promote to Stack Cover", systemImage: "star") }
                }
                Button { onRemoveFromStack() } label: { Label("Remove from Stack", systemImage: "rectangle.stack.badge.minus") }
                Button(role: .destructive) { onUnstack() } label: { Label("Unstack", systemImage: "square.stack.3d.up.slash") }
            }
        }
    }

    private static func dot(_ label: ColorLabel) -> String {
        switch label {
        case .none: return "⚪️"
        case .red: return "🔴"
        case .yellow: return "🟡"
        case .green: return "🟢"
        case .blue: return "🔵"
        case .purple: return "🟣"
        }
    }
}
