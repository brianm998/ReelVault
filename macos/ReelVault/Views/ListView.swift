// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import AppKit
import AVKit

struct ListView: View {
    @ObservedObject var viewModel: GridViewModel
    @ObservedObject var detailViewModel: DetailViewModel
    var thumbnailHeight: CGFloat = 100
    var onLocationClick: ((Double, Double) -> Void)? = nil

    var body: some View {
        ZStack {
            content
            // Error toast
            if let error = viewModel.error {
                VStack {
                    HStack {
                        Image(systemName: "exclamationmark.circle.fill")
                            .foregroundColor(.red)
                        Text(error).lineLimit(2)
                        Spacer()
                        Button(action: { viewModel.clearError() }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                        .buttonStyle(.plain)
                        .help("Dismiss this error message")
                    }
                    .padding(10)
                    .background(Color(.controlBackgroundColor))
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(.separatorColor)))
                    .cornerRadius(6)
                    .padding(12)
                    Spacer()
                }
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.videos.isEmpty && !viewModel.isLoading {
            emptyState
        } else if viewModel.isLoading && viewModel.videos.isEmpty {
            ProgressView()
        } else {
            videoList
        }
    }

    private var videoList: some View {
        let rendered = buildRenderedList(
            videos: viewModel.videos,
            expandedIds: viewModel.expandedGroupIds,
            members: viewModel.expandedGroupMembers
        )
        let displayRows = buildListDisplayRows(rendered)

        return ScrollView {
            ScrollViewReader { proxy in
            LazyVStack(spacing: 0) {
                ForEach(Array(displayRows.enumerated()), id: \.element.key) { index, displayRow in
                    switch displayRow {
                    case .single(let item):
                        let multi = viewModel.selectedVideoIds
                        let rowDragPaths: [String] = {
                            if multi.contains(item.video.id) && multi.count > 1 {
                                return viewModel.videos
                                    .filter { multi.contains($0.id) }
                                    .map { $0.openPath }
                                    .filter { !$0.isEmpty }
                            }
                            return [item.video.openPath]
                        }()
                        let stackMemberFilenames: [String] = {
                            guard item.video.isInGroup else { return [] }
                            return (viewModel.expandedGroupMembers[item.video.groupId] ?? [])
                                .filter { $0.id != item.video.id }
                                .map { $0.filename }
                        }()
                        VideoListRowView(
                            item: item,
                            thumbnail: viewModel.thumbnails[item.video.id],
                            thumbnailHeight: thumbnailHeight,
                            isPrimarySelected: viewModel.selectedVideoId == item.video.id,
                            isInMultiSelection: viewModel.selectedVideoIds.contains(item.video.id),
                            isAnchor: viewModel.anchorVideoId == item.video.id && viewModel.selectedVideoIds.count > 1,
                            topSlots: viewModel.topSlots,
                            stackMemberFilenames: stackMemberFilenames,
                            onClick: { shift, toggle in
                                handleClick(item: item, rendered: rendered, shift: shift, toggle: toggle)
                            },
                            onDoubleClick: {
                                viewModel.openVideoInExternal(path: item.video.openPath)
                            },
                            onStackBadgeClick: {
                                viewModel.toggleStackExpansion(item.video.groupId)
                            },
                            onSetRating: { rating in
                                viewModel.setRating(rating, for: [item.video.id])
                            },
                            onPickStatSlot: { slotIndex, key in
                                guard slotIndex >= 0, slotIndex < 4 else { return }
                                var slots = viewModel.topSlots
                                while slots.count < 4 { slots.append("") }
                                slots[slotIndex] = key
                                viewModel.topSlots = slots
                                viewModel.saveGridSettings()
                            },
                            dragPaths: rowDragPaths,
                            scrubFrames: viewModel.scrubFrames[item.video.id] ?? [],
                            onHoverEnter: {
                                viewModel.loadScrubFrames(videoId: item.video.id)
                            },
                            isPlaying: viewModel.playingVideoId == item.video.id,
                            playPath: viewModel.playingVideoId == item.video.id
                                ? viewModel.playingVideoPath : nil,
                            onPlayClick: {
                                viewModel.playVideoPreferProxy(videoId: item.video.id)
                            },
                            onStopPlayback: {
                                viewModel.stopPlayback()
                            },
                            onLocationClick: onLocationClick
                        )
                        .id(displayRow.key)
                        .contextMenu {
                            videoContextMenu(for: item.video)
                        }
                        .onAppear {
                            if item.video.hasThumbnail {
                                viewModel.loadThumbnail(videoId: item.video.id)
                            }
                            if item.video.isInGroup {
                                // Collapsed stack reps surface their members
                                // in the info column; cache is shared with
                                // the expand path.
                                viewModel.ensureStackMembersLoaded(item.video.groupId)
                            }
                            if index >= displayRows.count - 5 && viewModel.hasMore {
                                viewModel.loadMore()
                            }
                        }

                    case .horizontalStack(let representative, let children):
                        let allItems = [representative] + children
                        ScrollView(.horizontal, showsIndicators: true) {
                            HStack(alignment: .top, spacing: 4) {
                                ForEach(Array(allItems.enumerated()), id: \.element.id) { idx, stackItem in
                                    VideoListHorizontalCardView(
                                        item: stackItem,
                                        thumbnail: viewModel.thumbnails[stackItem.video.id],
                                        thumbnailHeight: thumbnailHeight,
                                        topSlots: viewModel.topSlots,
                                        isPrimarySelected: viewModel.selectedVideoId == stackItem.video.id,
                                        isInMultiSelection: viewModel.selectedVideoIds.contains(stackItem.video.id),
                                        isAnchor: viewModel.anchorVideoId == stackItem.video.id && viewModel.selectedVideoIds.count > 1,
                                        isRepresentative: idx == 0,
                                        onStackToggle: {
                                            viewModel.toggleStackExpansion(stackItem.video.groupId)
                                        },
                                        onClick: { shift, toggle in
                                            handleClick(item: stackItem, rendered: rendered, shift: shift, toggle: toggle)
                                        },
                                        onDoubleClick: {
                                            viewModel.openVideoInExternal(path: stackItem.video.openPath)
                                        },
                                        onSetRating: { rating in
                                            viewModel.setRating(rating, for: [stackItem.video.id])
                                        },
                                        onPickStatSlot: { slotIndex, key in
                                            guard slotIndex >= 0, slotIndex < 4 else { return }
                                            var slots = viewModel.topSlots
                                            while slots.count < 4 { slots.append("") }
                                            slots[slotIndex] = key
                                            viewModel.topSlots = slots
                                            viewModel.saveGridSettings()
                                        },
                                        scrubFrames: viewModel.scrubFrames[stackItem.video.id] ?? [],
                                        onHoverEnter: {
                                            viewModel.loadScrubFrames(videoId: stackItem.video.id)
                                        },
                                        onLocationClick: onLocationClick
                                    )
                                    .contextMenu {
                                        videoContextMenu(for: stackItem.video)
                                    }
                                }
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                        }
                        .id(displayRow.key)
                        .onAppear {
                            for si in allItems where si.video.hasThumbnail {
                                viewModel.loadThumbnail(videoId: si.video.id)
                            }
                            if index >= displayRows.count - 5 && viewModel.hasMore {
                                viewModel.loadMore()
                            }
                        }
                    }
                }

                if viewModel.isLoading && !viewModel.videos.isEmpty {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding()
                }
            }
            .padding(.vertical, 4)
            // Scroll to the selected video when switching to this view.
            .onAppear {
                if let selectedId = viewModel.selectedVideoId {
                    let matchKey = displayRows.first { row in
                        switch row {
                        case .single(let item):
                            return item.video.id == selectedId
                        case .horizontalStack(let rep, let children):
                            return rep.video.id == selectedId ||
                                   children.contains { $0.video.id == selectedId }
                        }
                    }?.key
                    if let key = matchKey {
                        proxy.scrollTo(key, anchor: .center)
                    }
                }
            }
            } // ScrollViewReader
        }
    }

    // MARK: - Horizontal stack helpers

    private enum ListDisplayRow {
        case single(GridItemRow)
        /// An expanded stack: the representative plus its loaded members,
        /// rendered side-by-side in a horizontally-scrollable strip.
        case horizontalStack(representative: GridItemRow, children: [GridItemRow])

        /// Stable key for SwiftUI's `ForEach`.
        var key: String {
            switch self {
            case .single(let item):
                return item.video.id
            case .horizontalStack(let rep, _):
                return "hstack:\(rep.video.id)"
            }
        }
    }

    private func buildListDisplayRows(_ rendered: [GridItemRow]) -> [ListDisplayRow] {
        var result: [ListDisplayRow] = []
        var i = 0
        while i < rendered.count {
            let item = rendered[i]
            if item.isExpandedRepresentative {
                var children: [GridItemRow] = []
                var j = i + 1
                while j < rendered.count && rendered[j].isStackChild {
                    children.append(rendered[j])
                    j += 1
                }
                result.append(.horizontalStack(representative: item, children: children))
                i = j
            } else {
                result.append(.single(item))
                i += 1
            }
        }
        return result
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "folder.badge.questionmark")
                .font(.system(size: 48))
                .foregroundColor(.secondary)
            Text("No videos found")
                .font(.body)
                .foregroundColor(.secondary)
            Text("Click the folder+ button in the top bar to add a library location.")
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }

    private func handleClick(item: GridItemRow, rendered: [GridItemRow], shift: Bool, toggle: Bool) {
        let video = item.video
        if shift {
            let anchorId = viewModel.anchorVideoId ?? video.id
            let rangeIds = computeVisualRange(rendered: rendered, anchorId: anchorId, targetId: video.id)
            viewModel.selectRange(video, rangeIds: rangeIds)
        } else if toggle {
            viewModel.toggleVideoSelection(video)
        } else {
            viewModel.selectVideo(video)
        }
        detailViewModel.setCurrentVideo(video)
        detailViewModel.loadMetadata(videoId: video.id)
    }

    private func computeVisualRange(rendered: [GridItemRow], anchorId: String, targetId: String) -> [String] {
        let ids = rendered.map { $0.video.id }
        guard let anchorIdx = ids.firstIndex(of: anchorId),
              let targetIdx = ids.firstIndex(of: targetId) else {
            return [targetId]
        }
        let (start, end) = anchorIdx <= targetIdx ? (anchorIdx, targetIdx) : (targetIdx, anchorIdx)
        return Array(ids[start...end])
    }

    @ViewBuilder
    private func videoContextMenu(for video: VideoSummary) -> some View {
        let multi = viewModel.selectedVideoIds
        let targetIds: [String] = (multi.contains(video.id) && multi.count > 1)
            ? Array(multi)
            : [video.id]
        let targetFiles: [String] = viewModel.videos
            .filter { targetIds.contains($0.id) }
            .map { $0.openPath }
            .filter { !$0.isEmpty }
        let resolvedFiles = targetFiles.isEmpty ? [video.openPath] : targetFiles
        let n = resolvedFiles.count

        Button(n == 1 ? "Open with Default Player" : "Open \(n) videos with Default Player") {
            for path in resolvedFiles {
                NSWorkspace.shared.open(URL(fileURLWithPath: path))
            }
        }

        if n == 1 {
            Button("Reveal in Finder") {
                NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: resolvedFiles[0])])
            }
        }

        if video.isInGroup {
            Divider()
            Button("Remove from stack") {
                viewModel.removeFromStack(videoId: video.id, groupId: video.groupId)
            }
            .help("Pull just this video out of its stack — the other members stay grouped together.")
            Button("Unstack") {
                viewModel.unstackGroup(groupId: video.groupId)
            }
            .help("Disband this entire stack so each member becomes a standalone video.")
        }

        Divider()
        if !video.isProxy {
            Button("Create proxy…") {
                viewModel.requestCreateProxy(videoId: video.id)
            }
            .help(video.playableNatively
                ? "Create a lower-resolution version of this video, saved alongside it."
                : "This video is above the inline-playback ceiling. Create a lower-resolution proxy so ReelVault can play it inline.")
        }

        // "Go to Folder in Library" — longest-prefix match against all
        // known library locations, then filter the grid to that location.
        let containingLocation = viewModel.libraryLocations
            .filter { video.path.hasPrefix($0.path) }
            .max(by: { $0.path.count < $1.path.count })
        if let loc = containingLocation {
            Divider()
            Button("Go to Folder in Library") {
                viewModel.setLocationFilter(loc.path)
            }
            .help("Filter the library panel to show only videos from \(loc.path)")
        }

        let manualCollections = viewModel.collections.filter { !$0.isSmart }
        let videoColIds = Set<String>()
        let addableCollections = manualCollections.filter { !videoColIds.contains($0.id) }
        let removableCollections = manualCollections.filter { videoColIds.contains($0.id) }
        if !addableCollections.isEmpty || !removableCollections.isEmpty {
            Divider()
        }
        if !addableCollections.isEmpty {
            Menu("Add to Collection") {
                ForEach(addableCollections) { col in
                    Button(col.name) {
                        viewModel.addToCollection(videoIds: targetIds, collectionId: col.id)
                    }
                }
            }
        }
        if !removableCollections.isEmpty {
            Menu("Remove from Collection") {
                ForEach(removableCollections) { col in
                    Button(col.name) {
                        viewModel.removeFromCollection(videoIds: targetIds, collectionId: col.id)
                    }
                }
            }
        }
    }
}

// MARK: - VideoListRowView

struct VideoListRowView: View {
    let item: GridItemRow
    let thumbnail: NSImage?
    var thumbnailHeight: CGFloat = 100
    let isPrimarySelected: Bool
    let isInMultiSelection: Bool
    let isAnchor: Bool
    /// Same four catalog-scoped top-of-card stat-slot choices the grid uses.
    /// Each entry is a `GridStatKey.rawValue`; unknown strings render as blank.
    var topSlots: [String] = []
    /// Filenames of the other members of this row's stack — empty when the
    /// video isn't in a stack or the members haven't been fetched yet.
    /// Rendered in the info column on collapsed-stack reps so the user
    /// can see what the stack contains without expanding it.
    var stackMemberFilenames: [String] = []
    let onClick: (_ shift: Bool, _ toggle: Bool) -> Void
    let onDoubleClick: () -> Void
    let onStackBadgeClick: () -> Void
    /// Fired when one of the five rating positions is clicked.
    var onSetRating: (_ rating: Int) -> Void = { _ in }
    /// Fired when the user picks a different stat key for one of the four top slots.
    var onPickStatSlot: (_ slotIndex: Int, _ statKey: String) -> Void = { _, _ in }
    /// Paths to drag when the user drags this row out. When the row is part
    /// of a multi-selection, every selected file is included so the receiving
    /// app gets the full set in one drop.
    var dragPaths: [String] = []
    var scrubFrames: [NSImage?] = []
    var onHoverEnter: () -> Void = {}
    var isPlaying: Bool = false
    /// Override URL for inline playback (proxy path). nil → `video.openPath`.
    var playPath: String? = nil
    var onPlayClick: () -> Void = {}
    var onStopPlayback: () -> Void = {}
    /// Fired when the user clicks the location badge. Receives (latitude, longitude).
    var onLocationClick: ((Double, Double) -> Void)? = nil

    @State private var isHovered = false
    @State private var hoverX: CGFloat? = nil
    @State private var avPlayer: AVPlayer? = nil
    /// Top-stat-band slot whose picker popover is currently open, or
    /// `nil` if none. Same single-source pattern as the grid card.
    @State private var openSlotPickerIndex: Int? = nil

    private var video: VideoSummary { item.video }
    private var isStackChild: Bool { item.isStackChild }
    private var thumbnailWidth: CGFloat { thumbnailHeight * 16.0 / 9.0 }
    private var isInExpandedStack: Bool {
        item.isExpandedRepresentative || item.isStackChild
    }
    /// Width of the grid-style card on the leading side of the row. The
    /// card's middle band is a square at `thumbnailHeight`, so the card
    /// itself is the same width — never wider than a grid card.
    private var cardWidth: CGFloat { thumbnailHeight }

    /// The image to display: a scrub frame when hovering + have scrub data,
    /// otherwise the static thumbnail. Mirrors `VideoCardView.displayedImage`.
    private var displayedImage: NSImage? {
        if isPlaying {
            return scrubFrames.first.flatMap { $0 } ?? thumbnail
        }
        if let x = hoverX, cardWidth > 0, !scrubFrames.isEmpty {
            let frac = max(0, min(1, x / cardWidth))
            let idx = min(scrubFrames.count - 1, Int(frac * CGFloat(scrubFrames.count)))
            return scrubFrames[idx] ?? thumbnail
        }
        return thumbnail
    }

    var body: some View {
        // Outer layout: a compact Lightroom-style card on the leading
        // edge (same three-band shape as the grid card, just sized to
        // the list-row), with the textual metadata living *beside* the
        // card rather than inside it. That keeps the card's geometry
        // identical to its grid-mode counterpart while letting list
        // mode surface the filename + the four configurable stat labels
        // in a dedicated trailing column.
        HStack(alignment: .top, spacing: 10) {
            if isStackChild {
                Spacer().frame(width: 12)
            }
            cardContainer
                .frame(width: cardWidth)
                .overlay(
                    Rectangle()
                        .stroke(cardBorderColor, lineWidth: 1)
                        .allowsHitTesting(false)
                )
            infoColumn
            Spacer(minLength: 0)
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 10)
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { onDoubleClick() }
        .onTapGesture {
            let mods = ModifierSnapshot.lastMouseDownModifiers
            let shift = mods.contains(.shift)
            let toggle = mods.contains(.command) || mods.contains(.control)
            onClick(shift, toggle)
        }
        // File drag-out: lets users drag video files directly from the list
        // into DaVinci Resolve, Premiere Pro, Final Cut Pro, Finder, etc.
        .onDrag {
            let primary = dragPaths.first ?? video.openPath
            return DragExport.provider(for: primary)
        } preview: {
            HStack(spacing: 6) {
                if let img = thumbnail {
                    Image(nsImage: img)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 48, height: 27)
                        .cornerRadius(3)
                        .clipped()
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(video.filename)
                        .font(.caption2)
                        .lineLimit(1)
                    if dragPaths.count > 1 {
                        Text("\(dragPaths.count) files")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding(6)
            .background(Color(.windowBackgroundColor))
            .cornerRadius(6)
            .shadow(radius: 2)
        }
        .onChange(of: isPlaying) { _, playing in
            if playing {
                let url = URL(fileURLWithPath: playPath ?? video.openPath)
                let player = AVPlayer(url: url)
                player.automaticallyWaitsToMinimizeStalling = false
                player.play()
                avPlayer = player
            } else {
                avPlayer?.pause()
                avPlayer = nil
            }
        }
        .onAppear {
            if isPlaying && avPlayer == nil {
                let url = URL(fileURLWithPath: playPath ?? video.openPath)
                let player = AVPlayer(url: url)
                player.automaticallyWaitsToMinimizeStalling = false
                player.play()
                avPlayer = player
            }
        }
    }

    /// Compact card on the leading edge of the list row: square thumbnail
    /// area (same proportions as the grid card) plus the bottom rating band.
    /// Textual metadata lives in `infoColumn` to the right, so the card
    /// never carries redundant data.
    private var cardContainer: some View {
        VStack(alignment: .leading, spacing: 0) {
            thumbnailArea
                .frame(width: cardWidth, height: thumbnailHeight)
                .background(rowMiddleBackground)
            Rectangle()
                .fill(bandDividerColor)
                .frame(height: 1)
                .allowsHitTesting(false)
            ratingBand
                .frame(maxWidth: .infinity)
                .frame(height: 22)
                .background(bottomBandColor)
                .help("Click a star to rate 1–5; click the current rating again to clear it")
        }
        .onContinuousHover { phase in
            switch phase {
            case .active(let location):
                isHovered = true
                if !isPlaying && location.y <= thumbnailHeight {
                    if hoverX == nil { onHoverEnter() }
                    hoverX = location.x
                } else {
                    hoverX = nil
                }
            case .ended:
                isHovered = false
                hoverX = nil
            }
        }
    }

    /// Textual metadata column rendered alongside (trailing) the
    /// `cardContainer`: the filename plus the four configurable stat
    /// labels (the same catalog-wide slots the grid card shows on top,
    /// relocated to a vertical list here in list view).
    private var infoColumn: some View {
        let slots = padSlots(topSlots)
        return VStack(alignment: .leading, spacing: 3) {
            Text(video.filename)
                .font(.body)
                .fontWeight(.medium)
                .lineLimit(1)

            // Four configurable stat labels — the same catalog-wide slots the
            // grid card shows in its top band, relocated to a vertical list
            // here in list view. Each is click-to-configure via the shared
            // picker, so grid and list stay in sync.
            ForEach(0..<4, id: \.self) { slotIndex in
                statCell(slotIndex: slotIndex, key: slots[slotIndex], alignTrailing: false)
            }

            // Collapsed stack: list the other members of the stack so the
            // user can see what's inside without expanding. Skipped when
            // the row is expanded (members are shown as cards instead) or
            // when the prefetch hasn't completed yet.
            if !isInExpandedStack && !stackMemberFilenames.isEmpty {
                Text("Stack:\n\(stackMemberFilenames.joined(separator: "\n"))")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.top, 2)
    }

    // MARK: - Lightroom bands

    @ViewBuilder
    private func statCell(slotIndex: Int, key: String, alignTrailing: Bool) -> some View {
        // Same popover-driven approach as the grid card — see
        // `LightroomCard.statCell` for the explanation of why we
        // ditch SwiftUI's `Menu` here. TL;DR: borderless-button menus
        // add invisible internal padding that misaligns the values
        // with the card's edges.
        let stat = GridStatKey(rawValue: key) ?? .none
        let value = stat.value(for: video)
        let displayed: String = {
            if stat == .none { return "—" }
            return value
        }()
        Text(displayed)
            .font(.system(size: 10, weight: slotIndex == 0 ? .semibold : .regular))
            .foregroundColor(stat == .none ? Color.secondary : Color.primary)
            .lineLimit(1)
            .truncationMode(.middle)
            .frame(maxWidth: .infinity, alignment: alignTrailing ? .trailing : .leading)
            .frame(minHeight: 14)
            .contentShape(Rectangle())
            .onTapGesture { openSlotPickerIndex = slotIndex }
            .popover(
                isPresented: Binding(
                    get: { openSlotPickerIndex == slotIndex },
                    set: { if !$0 { openSlotPickerIndex = nil } }
                ),
                arrowEdge: .bottom
            ) {
                statPickerMenu(slotIndex: slotIndex, currentStat: stat)
            }
            .help("Click to choose which stat is shown in this slot")
    }

    @ViewBuilder
    private func statPickerMenu(slotIndex: Int, currentStat: GridStatKey) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(GridStatKey.allCases) { choice in
                Button {
                    onPickStatSlot(slotIndex, choice.rawValue)
                    openSlotPickerIndex = nil
                } label: {
                    HStack(spacing: 6) {
                        Group {
                            if choice == currentStat {
                                Image(systemName: "checkmark")
                            } else {
                                Color.clear
                            }
                        }
                        .frame(width: 14, height: 14)
                        Text(choice.displayName)
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(minWidth: 180)
        .padding(.vertical, 4)
    }

    /// Bottom band: 5 tappable star/dot positions, matching the grid card's
    /// rating semantics (click N to set to N; click already-N to clear).
    @ViewBuilder
    private var ratingBand: some View {
        HStack(spacing: 4) {
            ForEach(1...5, id: \.self) { position in
                ZStack {
                    if position <= video.rating {
                        Image(systemName: "star.fill")
                            .font(.system(size: 11))
                            .foregroundColor(.black)
                    } else {
                        Image(systemName: "circle.fill")
                            .font(.system(size: 4))
                            .foregroundColor(Color(white: 0.35))
                    }
                }
                .frame(width: 20, height: 20)
                .contentShape(Rectangle())
                .onTapGesture {
                    if video.rating == position {
                        onSetRating(0)
                    } else {
                        onSetRating(position)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func padSlots(_ raw: [String]) -> [String] {
        var slots = raw
        while slots.count < 4 { slots.append("") }
        if slots.count > 4 { slots = Array(slots.prefix(4)) }
        return slots
    }

    // MARK: - Band / row colors (mirrors the grid card's logic)

    /// Top stat band: lighter than the middle row by default; bright neutral
    /// when the row is selected.
    private var topBandColor: Color {
        if isAnchor || (isPrimarySelected && !isInMultiSelection) {
            return Color(white: 0.94)
        }
        if isInMultiSelection {
            return Color(white: 0.84)
        }
        return Color(white: 0.70)
    }

    /// Middle row background: takes the colour-label tint when unselected,
    /// bright neutral when selected.
    private var rowMiddleBackground: Color {
        let label = ColorLabel(video.colorLabel)
        if isAnchor || (isPrimarySelected && !isInMultiSelection) {
            return Color(white: 0.94)
        }
        if isInMultiSelection {
            return Color(white: 0.84)
        }
        if isInExpandedStack {
            return Color(red: 0.52, green: 0.55, blue: 0.62)
        }
        if label != .none { return label.dimmed }
        return Color(white: 0.52)
    }

    private var bottomBandColor: Color {
        if isAnchor || (isPrimarySelected && !isInMultiSelection) {
            return Color(white: 0.94)
        }
        if isInMultiSelection {
            return Color(white: 0.84)
        }
        return Color(white: 0.60)
    }

    private var bandDividerColor: Color {
        if isAnchor || isPrimarySelected || isInMultiSelection {
            return Color.black.opacity(0.10)
        }
        return Color.black.opacity(0.35)
    }

    private var cardBorderColor: Color {
        if isAnchor || isPrimarySelected || isInMultiSelection {
            return Color.white.opacity(0.6)
        }
        return Color.black.opacity(0.5)
    }

    private var thumbnailArea: some View {
        // Width matches `cardWidth` (= `thumbnailHeight`) so the
        // middle band sits as a strict square — same proportions as
        // the grid card's photo area. The image inside uses
        // `.scaledToFit` (rather than `.scaledToFill`) so 16:9
        // footage letterboxes inside the square instead of being
        // cropped — matching the grid card's behaviour for non-
        // square clips.
        ZStack(alignment: .topLeading) {
            Group {
                if let image = displayedImage {
                    Image(nsImage: image)
                        .resizable()
                        .scaledToFit()
                        .padding(8)
                } else {
                    Image(systemName: "film")
                        .font(.system(size: 18))
                        .foregroundColor(.secondary)
                }
            }
            .frame(width: cardWidth, height: thumbnailHeight)
            // Player surface layered on top of the thumbnail.
            .overlay {
                if isPlaying, let player = avPlayer {
                    AVPlayerNSView(player: player)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .clipped()
                }
            }
            // Play button — center of thumbnail when hovered + selected + not playing.
            .overlay(alignment: .center) {
                if !isPlaying && isPrimarySelected && isHovered {
                    Button(action: onPlayClick) {
                        Image(systemName: "play.fill")
                            .font(.system(size: 14))
                            .foregroundColor(.white)
                            .frame(width: 36, height: 36)
                            .background(Color.black.opacity(0.55))
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .help("Play this video inline")
                }
            }
            // Stop button — top-trailing corner while playing.
            .overlay(alignment: .topTrailing) {
                if isPlaying {
                    Button(action: onStopPlayback) {
                        Image(systemName: "xmark")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 22, height: 22)
                            .background(Color.black.opacity(0.65))
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .padding(3)
                    .help("Stop inline playback")
                }
            }

            // Stack badge
            if video.isInGroup {
                HStack(spacing: 3) {
                    Image(systemName: "square.stack.3d.up.fill")
                        .font(.system(size: 9))
                    Text(stackPositionText)
                        .font(.system(size: 9, weight: .medium))
                }
                .foregroundColor(.white)
                .padding(.horizontal, 4)
                .padding(.vertical, 2)
                .background(stackBadgeColor)
                .cornerRadius(3)
                .padding(4)
                .contentShape(Rectangle())
                .onTapGesture { onStackBadgeClick() }
                .help(item.isExpandedRepresentative
                      ? "Collapse this stack of \(video.groupSize) videos back to one row."
                      : "Expand this stack to see all \(video.groupSize) variants inline.")
            }

            // Bottom-left location badge — shown when the video has GPS
            // coordinates. Tapping opens the global map focused on this video.
            if video.hasLocation, let handler = onLocationClick {
                VStack {
                    Spacer()
                    HStack {
                        Button {
                            handler(video.gpsLatitude, video.gpsLongitude)
                        } label: {
                            Image(systemName: "location.fill")
                                .font(.system(size: 9))
                                .foregroundColor(.white)
                                .padding(3)
                                .background(Color.black.opacity(0.55))
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .help("Recorded at \(String(format: "%.4f", video.gpsLatitude)), \(String(format: "%.4f", video.gpsLongitude)) — click to show on map")
                        Spacer()
                    }
                }
                .padding(4)
            }

            // Bottom-right status badges — keyword / proxy / full-resolution,
            // mirroring the grid card so list and grid cards read identically.
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    HStack(spacing: 4) {
                        if !video.tags.isEmpty {
                            Image(systemName: "tag.fill")
                                .font(.system(size: 10))
                                .foregroundColor(.white)
                                .padding(3)
                                .background(Color.black.opacity(0.55))
                                .clipShape(Circle())
                                .help("\(video.tags.count) keyword\(video.tags.count == 1 ? "" : "s")")
                        }
                        if video.hasProxies {
                            Image(systemName: "rectangle.on.rectangle.angled")
                                .font(.system(size: 10))
                                .foregroundColor(.white)
                                .padding(3)
                                .background(Color.black.opacity(0.55))
                                .clipShape(Circle())
                                .help("\(video.proxyCount) proxy/proxies available for inline playback.")
                        }
                        // Full-resolution badge — only when the daemon's
                        // classifier was sure either way; unspecified renders nothing.
                        switch video.fullResolution {
                        case .full:
                            Image(systemName: "checkmark.seal.fill")
                                .font(.system(size: 10))
                                .foregroundColor(.white)
                                .padding(3)
                                .background(Color.black.opacity(0.55))
                                .clipShape(Circle())
                                .help("Full resolution — matches a known native sensor mode for \(video.cameraDisplayName.isEmpty ? "this camera" : video.cameraDisplayName).")
                        case .notFull:
                            Image(systemName: "crop")
                                .font(.system(size: 10))
                                .foregroundColor(.white)
                                .padding(3)
                                .background(Color.black.opacity(0.55))
                                .clipShape(Circle())
                                .help("Not full resolution — recorded dimensions don't match any native sensor mode for \(video.cameraDisplayName.isEmpty ? "this camera" : video.cameraDisplayName).")
                        case .unspecified:
                            EmptyView()
                        }
                    }
                }
            }
            .padding(6)
        }
        .opacity(isStackChild ? 0.85 : 1.0)
    }

    private var stackPositionText: String {
        if item.memberCount > 0 && item.memberPosition > 0 {
            return "\(item.memberPosition)/\(item.memberCount)"
        }
        return "\(video.groupSize)"
    }

    private var stackBadgeColor: Color {
        if item.isExpandedRepresentative { return Color.accentColor }
        if item.isStackChild { return Color(red: 0.12, green: 0.43, blue: 0.92).opacity(0.85) }
        return Color.accentColor.opacity(0.85)
    }

    // Row selection / border styling now lives in the band-color helpers
    // above (`topBandColor`, `rowMiddleBackground`, `bottomBandColor`,
    // `cardBorderColor`) — those mirror the grid card so list rows and
    // grid cards share visual language.
}

// MARK: - VideoListHorizontalCardView

/// Compact card used inside the horizontal stack expansion strip.
/// Shows the same three-band card (top stat / thumbnail / rating) as
/// `VideoListRowView` but at a fixed `thumbnailHeight`-wide square
/// footprint, with the filename label below. No info column alongside —
/// the strip itself communicates that these are stack members.
struct VideoListHorizontalCardView: View {
    let item: GridItemRow
    let thumbnail: NSImage?
    var thumbnailHeight: CGFloat = 100
    var topSlots: [String] = []
    let isPrimarySelected: Bool
    let isInMultiSelection: Bool
    let isAnchor: Bool
    /// `true` for the first card in the strip (the representative).
    let isRepresentative: Bool
    let onStackToggle: () -> Void
    let onClick: (_ shift: Bool, _ toggle: Bool) -> Void
    let onDoubleClick: () -> Void
    var onSetRating: (_ rating: Int) -> Void = { _ in }
    var onPickStatSlot: (_ slotIndex: Int, _ key: String) -> Void = { _, _ in }
    var scrubFrames: [NSImage?] = []
    var onHoverEnter: () -> Void = {}
    var onLocationClick: ((Double, Double) -> Void)? = nil

    @State private var openSlotPickerIndex: Int? = nil
    @State private var hoverX: CGFloat? = nil

    private var video: VideoSummary { item.video }
    private var cardWidth: CGFloat { thumbnailHeight }

    private var displayedImage: NSImage? {
        if let x = hoverX, cardWidth > 0, !scrubFrames.isEmpty {
            let frac = max(0, min(1, x / cardWidth))
            let idx = min(scrubFrames.count - 1, Int(frac * CGFloat(scrubFrames.count)))
            return scrubFrames[idx] ?? thumbnail
        }
        return thumbnail
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            cardContainer
                .frame(width: cardWidth)
                .overlay(
                    Rectangle()
                        .stroke(cardBorderColor, lineWidth: 1)
                        .allowsHitTesting(false)
                )
            // Stack-member names are usually long enough that the truncated
            // cardWidth-bound label clips them — hovering reveals the full
            // name without needing to expand the card.
            Text(video.filename)
                .font(.system(size: 10))
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(width: cardWidth, alignment: .leading)
                .help(video.filename)
        }
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { onDoubleClick() }
        .onTapGesture {
            let mods = ModifierSnapshot.lastMouseDownModifiers
            let shift = mods.contains(.shift)
            let toggle = mods.contains(.command) || mods.contains(.control)
            onClick(shift, toggle)
        }
        .onDrag {
            DragExport.provider(for: video.openPath)
        }
    }

    private var cardContainer: some View {
        VStack(alignment: .leading, spacing: 0) {
            topBandView
                .frame(maxWidth: .infinity)
                .frame(height: 22)
                .background(topBandColor)
            Rectangle()
                .fill(bandDividerColor)
                .frame(height: 1)
                .allowsHitTesting(false)
            thumbnailArea
                .frame(width: cardWidth, height: thumbnailHeight)
                .background(thumbnailBackground)
            Rectangle()
                .fill(bandDividerColor)
                .frame(height: 1)
                .allowsHitTesting(false)
            ratingBand
                .frame(maxWidth: .infinity)
                .frame(height: 22)
                .background(bottomBandColor)
                .help("Click a star to rate 1–5; click the current rating again to clear it")
        }
    }

    @ViewBuilder
    private var topBandView: some View {
        let slots = padSlots(topSlots)
        HStack(spacing: 4) {
            if isRepresentative {
                Button(action: onStackToggle) {
                    Image(systemName: "chevron.up")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundColor(Color.black.opacity(0.7))
                }
                .buttonStyle(.plain)
                .frame(width: 14, height: 14)
                .help("Collapse this stack")
            }
            let stat = GridStatKey(rawValue: slots[0]) ?? .none
            let value = stat.value(for: video)
            let displayed = value.isEmpty ? "—" : value
            Text(displayed)
                .font(.system(size: 10, weight: .regular))
                .foregroundColor(stat == .none ? Color.black.opacity(0.4) : Color.black.opacity(0.85))
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { openSlotPickerIndex = 0 }
                .popover(
                    isPresented: Binding(
                        get: { openSlotPickerIndex == 0 },
                        set: { if !$0 { openSlotPickerIndex = nil } }
                    ),
                    arrowEdge: .bottom
                ) {
                    statPickerMenu(slotIndex: 0, currentStat: stat)
                }
                .help("Click to choose which stat is shown in this slot")
        }
        .padding(.horizontal, 6)
    }

    @ViewBuilder
    private func statPickerMenu(slotIndex: Int, currentStat: GridStatKey) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(GridStatKey.allCases) { choice in
                Button {
                    onPickStatSlot(slotIndex, choice.rawValue)
                    openSlotPickerIndex = nil
                } label: {
                    HStack(spacing: 6) {
                        Group {
                            if choice == currentStat {
                                Image(systemName: "checkmark")
                            } else {
                                Color.clear
                            }
                        }
                        .frame(width: 14, height: 14)
                        Text(choice.displayName)
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(minWidth: 180)
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private var thumbnailArea: some View {
        ZStack {
            Rectangle()
                .fill(Color.black)
                .overlay {
                    if let image = displayedImage {
                        Image(nsImage: image)
                            .resizable()
                            .scaledToFit()
                    } else {
                        Image(systemName: "film")
                            .font(.system(size: 18))
                            .foregroundColor(.secondary)
                    }
                }
                .frame(width: cardWidth, height: thumbnailHeight)
                .cornerRadius(4)

            if video.hasLocation, let handler = onLocationClick {
                VStack {
                    Spacer()
                    HStack {
                        Button { handler(video.gpsLatitude, video.gpsLongitude) } label: {
                            Image(systemName: "location.fill")
                                .font(.system(size: 9))
                                .foregroundColor(.white)
                                .padding(3)
                                .background(Color.black.opacity(0.55))
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .help("Recorded at \(String(format: "%.4f", video.gpsLatitude)), \(String(format: "%.4f", video.gpsLongitude)) — click to show on map")
                        Spacer()
                    }
                }
                .padding(4)
            }
        }
        .onContinuousHover { phase in
            switch phase {
            case .active(let location):
                if cardWidth > 0 {
                    if hoverX == nil { onHoverEnter() }
                    hoverX = location.x
                }
            case .ended:
                hoverX = nil
            }
        }
    }

    @ViewBuilder
    private var ratingBand: some View {
        HStack(spacing: 2) {
            ForEach(1...5, id: \.self) { position in
                ZStack {
                    if position <= video.rating {
                        Image(systemName: "star.fill")
                            .font(.system(size: 9))
                            .foregroundColor(.black)
                    } else {
                        Image(systemName: "circle.fill")
                            .font(.system(size: 3))
                            .foregroundColor(Color(white: 0.35))
                    }
                }
                .frame(width: 16, height: 16)
                .contentShape(Rectangle())
                .onTapGesture {
                    if video.rating == position { onSetRating(0) } else { onSetRating(position) }
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func padSlots(_ raw: [String]) -> [String] {
        var slots = raw
        while slots.count < 4 { slots.append("") }
        if slots.count > 4 { slots = Array(slots.prefix(4)) }
        return slots
    }

    // MARK: Band colors (mirrors VideoListRowView)

    private var topBandColor: Color {
        if isAnchor || (isPrimarySelected && !isInMultiSelection) { return Color(white: 0.94) }
        if isInMultiSelection { return Color(white: 0.84) }
        return Color(white: 0.70)
    }
    private var thumbnailBackground: Color {
        let label = ColorLabel(video.colorLabel)
        if isAnchor || (isPrimarySelected && !isInMultiSelection) { return Color(white: 0.94) }
        if isInMultiSelection { return Color(white: 0.84) }
        if label != .none { return label.dimmed }
        return Color(white: 0.52)
    }
    private var bottomBandColor: Color {
        if isAnchor || (isPrimarySelected && !isInMultiSelection) { return Color(white: 0.94) }
        if isInMultiSelection { return Color(white: 0.84) }
        return Color(white: 0.60)
    }
    private var bandDividerColor: Color {
        if isAnchor || isPrimarySelected || isInMultiSelection { return Color.black.opacity(0.10) }
        return Color.black.opacity(0.35)
    }
    private var cardBorderColor: Color {
        if isAnchor || isPrimarySelected || isInMultiSelection { return Color.white.opacity(0.6) }
        return Color.black.opacity(0.5)
    }
}

#Preview {
    ListView(
        viewModel: GridViewModel(),
        detailViewModel: DetailViewModel()
    )
    .frame(width: 800, height: 600)
}
