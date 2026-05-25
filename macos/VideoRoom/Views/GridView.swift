// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI
import AppKit
import AVKit

/// One entry in the rendered grid — either a regular video, a stack
/// representative (collapsed or expanded), or a stack child shown inline.
struct GridItemRow: Identifiable {
    let video: VideoSummary
    let isExpandedRepresentative: Bool
    let isStackChild: Bool
    let memberPosition: Int  // 1-based
    let memberCount: Int

    var id: String { isStackChild ? "child:\(video.id)" : video.id }
}

struct GridView: View {
    @ObservedObject var viewModel: GridViewModel
    @ObservedObject var detailViewModel: DetailViewModel
    let thumbnailMinWidth: CGFloat
    /// Invoked by the context menu's "Configure External Editors…" entry.
    var onConfigureEditors: () -> Void = {}

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
            videoGrid
        }
    }

    private var videoGrid: some View {
        let rendered = buildRenderedList(
            videos: viewModel.videos,
            expandedIds: viewModel.expandedGroupIds,
            members: viewModel.expandedGroupMembers
        )

        return ScrollView {
            ScrollViewReader { proxy in
            // Lightroom-style: edge-to-edge cards with zero gutters so the
            // grid reads as a dense filmstrip. The `spacing: 0` on both axes
            // here is the crucial half of the layout; the card itself drops
            // its corner radius so adjacent cards share crisp 1 pt borders.
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: thumbnailMinWidth), spacing: 0)],
                spacing: 0
            ) {
                ForEach(Array(rendered.enumerated()), id: \.element.id) { index, item in
                    let multi = viewModel.selectedVideoIds
                    let cardDragPaths: [String] = {
                        if multi.contains(item.video.id) && multi.count > 1 {
                            return viewModel.videos
                                .filter { multi.contains($0.id) }
                                .map { $0.openPath }
                                .filter { !$0.isEmpty }
                        }
                        return [item.video.openPath]
                    }()
                    VideoCardView(
                        item: item,
                        thumbnail: viewModel.thumbnails[item.video.id],
                        scrubFrames: viewModel.scrubFrames[item.video.id] ?? [],
                        isPrimarySelected: viewModel.selectedVideoId == item.video.id,
                        isInMultiSelection: viewModel.selectedVideoIds.contains(item.video.id),
                        isAnchor: viewModel.anchorVideoId == item.video.id && viewModel.selectedVideoIds.count > 1,
                        isPlaying: viewModel.playingVideoId == item.video.id,
                        playPath: viewModel.playingVideoId == item.video.id
                            ? viewModel.playingVideoPath : nil,
                        topSlots: viewModel.topSlots,
                        onClick: { shift, toggle in
                            handleClick(item: item, rendered: rendered, shift: shift, toggle: toggle)
                        },
                        onStackBadgeClick: {
                            viewModel.toggleStackExpansion(item.video.groupId)
                        },
                        onHoverEnter: {
                            viewModel.loadScrubFrames(videoId: item.video.id)
                        },
                        onPlayClick: {
                            // Always go through the proxy-aware path: if the
                            // video is oversize and a proxy is available it
                            // will be used automatically; if natively playable
                            // (or no proxy exists) it falls through to direct.
                            viewModel.playVideoPreferProxy(videoId: item.video.id)
                        },
                        onStopPlayback: {
                            viewModel.stopPlayback()
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
                        dragPaths: cardDragPaths
                    )
                    .id(item.video.id)
                    .contextMenu {
                        videoContextMenu(for: item.video)
                    }
                    .onAppear {
                        if item.video.hasThumbnail {
                            viewModel.loadThumbnail(videoId: item.video.id)
                        }
                        // Load more when nearing the end (only count non-children)
                        if !item.isStackChild && index >= rendered.count - 5 && viewModel.hasMore {
                            viewModel.loadMore()
                        }
                    }
                }

                if viewModel.isLoading && !viewModel.videos.isEmpty {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .gridCellColumns(99)
                }
            }
            // Scroll to the selected video when switching to this view.
            .onAppear {
                if let id = viewModel.selectedVideoId {
                    proxy.scrollTo(id, anchor: .center)
                }
            }
            // Intentionally no .padding(...) here — the Lightroom-style grid
            // fills the viewport flush to the edge.
            } // ScrollViewReader
        }
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

    /// Build the contents of the right-click menu for a single video card.
    /// Operates on the full multi-selection when the right-clicked card is
    /// part of it; otherwise just the card itself.
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

        let registry = EditorRegistry.shared

        Button(n == 1 ? "Open with Default Player" : "Open \(n) videos with Default Player") {
            for path in resolvedFiles { registry.openWithDefault(path) }
        }

        if n == 1 {
            Button("Reveal in Finder") {
                registry.revealInFinder(resolvedFiles[0])
            }
        }

        let available = registry.availableEditors
        if !available.isEmpty {
            Divider()
            ForEach(available, id: \.editor.id) { (editor, _) in
                let suffix = (n > 1 && editor.supportsFileArgs) ? " (\(n) videos)" : ""
                Button("Open with \(editor.name)\(suffix)") {
                    registry.launch(editor, files: resolvedFiles)
                }
            }
        }

        // Stack-membership actions. Only meaningful when the
        // right-clicked card itself is part of a stack — even in a
        // multi-selection, "Remove from stack" operates on the *one*
        // card the user clicked (per the product spec), and "Unstack"
        // disbands that card's whole stack.
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

        // Proxy actions. "Create proxy" is offered on every video; for
        // videos that wouldn't otherwise fit under the configured
        // native-playback ceiling we surface it more prominently in the
        // detail panel too. The user is prompted on the resulting sheet
        // for the target resolution.
        Divider()
        if !video.isProxy {
            Button("Create proxy…") {
                viewModel.requestCreateProxy(videoId: video.id)
            }
            .help(video.playableNatively
                ? "Create a lower-resolution version of this video, saved alongside it. Useful for moving the source to slower storage while keeping fast inline playback in VideoRoom."
                : "This video is above the inline-playback ceiling. Create a lower-resolution proxy so VideoRoom can play it without falling back to an external editor.")
        }

        // "Go to Folder in Library" — identify the library location whose
        // path is the longest prefix of this video's path, then ask the
        // ViewModel to filter the grid to that location.
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

        // Lightroom-style user-mark submenus. Apply to the full multi-
        // selection (`targetIds`) so the user can rate or label many
        // videos at once. Star count uses 0..5 with "0 stars" reading as
        // "clear rating" — same as the keyboard shortcut.
        Divider()
        Menu("Set Rating") {
            ForEach((0...5).reversed(), id: \.self) { stars in
                Button(stars == 0 ? "No rating" : String(repeating: "★", count: stars)) {
                    viewModel.setRating(stars, for: targetIds)
                }
            }
        }
        Menu("Set Color Label") {
            ForEach(ColorLabel.allCases) { label in
                Button {
                    viewModel.setColorLabel(label.rawValue, for: targetIds)
                } label: {
                    HStack {
                        Circle()
                            .fill(label == .none ? Color.gray.opacity(0.3) : label.swatch)
                            .frame(width: 10, height: 10)
                        Text(label.displayName)
                    }
                }
            }
        }

        // Stack-master picker: surfaces only when the right-clicked card
        // is part of an expanded stack. Lets the user promote a different
        // variant to be the representative shown in the collapsed view.
        if video.isInGroup && video.id != video.groupPreferredId {
            Divider()
            Button("Set as Stack Master") {
                viewModel.setStackMaster(videoId: video.id, groupId: video.groupId)
            }
            .help("Make this video the representative shown when the stack is collapsed in the grid.")
        }

        Divider()

        Button("Configure External Editors…") {
            onConfigureEditors()
        }
    }
}

// MARK: - Rendered list builder

func buildRenderedList(
    videos: [VideoSummary],
    expandedIds: Set<String>,
    members: [String: [VideoSummary]]
) -> [GridItemRow] {
    var out: [GridItemRow] = []
    for video in videos {
        let isExpanded = video.isInGroup && expandedIds.contains(video.groupId)
        if !isExpanded {
            out.append(GridItemRow(
                video: video,
                isExpandedRepresentative: false,
                isStackChild: false,
                memberPosition: 0,
                memberCount: 0
            ))
            continue
        }

        let all = members[video.groupId] ?? []
        // Put preferred (the representative shown) first.
        let preferred = all.first { $0.id == video.id }
        let others = all.filter { $0.id != video.id }
        let reordered: [VideoSummary] = (preferred.map { [$0] } ?? []) + others
        let total = max(reordered.count, 1)

        if reordered.isEmpty {
            out.append(GridItemRow(
                video: video,
                isExpandedRepresentative: true,
                isStackChild: false,
                memberPosition: 1,
                memberCount: total
            ))
        } else {
            for (i, member) in reordered.enumerated() {
                let position = i + 1
                if member.id == video.id {
                    out.append(GridItemRow(
                        video: video,
                        isExpandedRepresentative: true,
                        isStackChild: false,
                        memberPosition: position,
                        memberCount: total
                    ))
                } else {
                    out.append(GridItemRow(
                        video: member,
                        isExpandedRepresentative: false,
                        isStackChild: true,
                        memberPosition: position,
                        memberCount: total
                    ))
                }
            }
        }
    }
    return out
}

// MARK: - VideoCardView

struct VideoCardView: View {
    let item: GridItemRow
    let thumbnail: NSImage?
    /// Scrub frames (one image per evenly-spaced timeline position). When the
    /// user hovers, we show the frame whose X position matches the cursor.
    let scrubFrames: [NSImage?]
    let isPrimarySelected: Bool
    let isInMultiSelection: Bool
    let isAnchor: Bool
    /// `true` when this card is the currently active inline player.
    let isPlaying: Bool
    /// Override URL for inline playback (proxy path). nil → `video.openPath`.
    let playPath: String?
    /// The four catalog-scoped top-of-card stat-slot choices. Each entry is a
    /// `GridStatKey.rawValue`; unknown strings render as blank.
    let topSlots: [String]
    let onClick: (_ shift: Bool, _ toggle: Bool) -> Void
    let onStackBadgeClick: () -> Void
    let onHoverEnter: () -> Void
    /// Fired when the play-button overlay is clicked on a `playableNatively`
    /// card (or when the user taps the affordance on an oversize card).
    let onPlayClick: () -> Void
    /// Fired when the ✕ stop button on the inline player is tapped.
    let onStopPlayback: () -> Void
    /// Fired when one of the five rating positions is clicked. Receives the
    /// new rating (0..5); the card-tap handler is responsible for translating
    /// "click same position twice" into a clear (rating - 1).
    let onSetRating: (_ rating: Int) -> Void
    /// Fired when the user right-clicks a top stat slot and picks a new key.
    /// The closure receives the slot index (0..3) and the new stat key.
    let onPickStatSlot: (_ slotIndex: Int, _ statKey: String) -> Void
    /// Paths to drag when the user drags this card out. When the card is part
    /// of a multi-selection, every selected file is included so the receiving
    /// app gets the full set in one drop.
    var dragPaths: [String] = []

    /// AVPlayer kept alive for the lifetime of this view instance. Created
    /// on first play, released when `isPlaying` goes false.
    @State private var avPlayer: AVPlayer? = nil

    @State private var isHovered = false
    @State private var hoverX: CGFloat? = nil
    @State private var thumbnailWidth: CGFloat = 0
    /// `true` when our custom card-tooltip popup is currently visible.
    /// Toggled by the dwell timer below — never directly by hover.
    @State private var tooltipVisible: Bool = false
    /// The pending "show tooltip" task. Each cursor-motion event cancels
    /// the in-flight task (if any) and schedules a fresh one, so
    /// continuous scrubbing leaves the popup hidden indefinitely. When
    /// the user stops moving, the most-recently-scheduled task fires
    /// exactly 2 seconds later.
    @State private var tooltipShowTask: DispatchWorkItem? = nil
    /// Last cursor position seen in the card's outer `onContinuousHover`.
    /// Used to ignore .active phases that re-fire without an actual move
    /// (focus changes, window activation, etc.), which would otherwise
    /// reset the dwell timer for free.
    @State private var lastTooltipCursor: CGPoint? = nil

    /// Which top-stat-band slot (0..3) currently has its picker popover
    /// open. `nil` means no picker is showing. Tracked as a single
    /// optional so only one slot's popover can be open at a time and
    /// clicks on one slot dismiss any other.
    @State private var openSlotPickerIndex: Int? = nil

    private var video: VideoSummary { item.video }
    private var isInExpandedStack: Bool { item.isExpandedRepresentative || item.isStackChild }

    /// Styled bubble that renders the rich help text. Sits in an
    /// `.overlay` below the card; positioning is handled by the caller.
    private var tooltipPopup: some View {
        Text(cardHelp)
            .font(.system(size: 11))
            .foregroundColor(.primary)
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color(NSColor.controlBackgroundColor))
                    .shadow(color: Color.black.opacity(0.25), radius: 6, x: 0, y: 2)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 6)
                    .stroke(Color(NSColor.separatorColor), lineWidth: 0.5)
            )
            // Cap the bubble at a comfortable reading width so very long
            // filenames don't stretch it off the edge of the window.
            .frame(maxWidth: 360, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// Cancel any pending tooltip-show task and hide the popup right now.
    /// Called on every cursor-motion event over the card, and on
    /// hover-exit — so the popup only ever surfaces after the cursor has
    /// genuinely stopped moving for the full dwell period.
    private func resetTooltipTimer() {
        tooltipShowTask?.cancel()
        tooltipShowTask = nil
        tooltipVisible = false
    }

    /// Schedule the tooltip to appear `Self.tooltipDwell` seconds from
    /// now. Re-armed on every motion event; the most recently scheduled
    /// task is the one that fires, so continuous scrubbing never
    /// produces a popup.
    private func scheduleTooltipShow() {
        resetTooltipTimer()
        let task = DispatchWorkItem {
            tooltipVisible = true
        }
        tooltipShowTask = task
        DispatchQueue.main.asyncAfter(
            deadline: .now() + VideoCardView.tooltipDwell,
            execute: task
        )
    }

    /// Dwell in seconds before the custom tooltip popup is allowed to
    /// appear after the cursor stops moving. Two seconds matches the
    /// product spec — long enough to absorb purposeful pauses mid-scrub,
    /// short enough that an intentional rest surfaces the info quickly.
    static let tooltipDwell: TimeInterval = 2.0

    /// Inset around the thumbnail inside the square photo area, mirroring
    /// the visible margin that surrounds each photo in Lightroom. Keeps
    /// the photo's full frame visible (no edge-cropping) and gives the
    /// colour-label tint behind it room to read.
    private var photoPadding: CGFloat { 8 }

    /// Multi-line help text shown when the user hovers the card. Pulls together
    /// the most useful at-a-glance facts about this video so the user doesn't
    /// have to select it just to see the basics.
    private var cardHelp: String {
        var lines: [String] = []
        lines.append(video.filename)

        var techParts: [String] = []
        techParts.append(video.resolution)
        techParts.append(video.durationFormatted)
        if !video.codecVideo.isEmpty { techParts.append(video.codecVideo) }
        if video.fps > 0 { techParts.append("\(Int(video.fps)) fps") }
        lines.append(techParts.joined(separator: " • "))

        lines.append(video.sizeFormatted)

        if video.isInGroup {
            if item.isStackChild {
                lines.append("Member of a stack of \(video.groupSize) variants.")
            } else if item.isExpandedRepresentative {
                lines.append("Stack of \(video.groupSize) (expanded). Click the stack badge to collapse.")
            } else {
                lines.append("Stack of \(video.groupSize). Click the stack badge to expand.")
            }
        }

        lines.append("Click to select, Shift-click to multi-select, double-click to open.")
        return lines.joined(separator: "\n")
    }

    /// Drag preview shown under the cursor while dragging out of VideoRoom.
    /// Displays the card thumbnail (or a placeholder), the filename, and a
    /// count badge when multiple files are selected.
    private var dragPreview: some View {
        ZStack(alignment: .bottomLeading) {
            if let img = displayedImage {
                Image(nsImage: img)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: 120, height: 68)
                    .clipped()
                    .cornerRadius(6)
            } else {
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color.secondary.opacity(0.3))
                    .frame(width: 120, height: 68)
            }
            // Filename label
            Text(video.filename)
                .font(.caption2)
                .lineLimit(1)
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(Color.black.opacity(0.65))
                .foregroundColor(.white)
                .cornerRadius(4)
                .padding(4)
            // Multi-file count badge
            if dragPaths.count > 1 {
                Text("×\(dragPaths.count)")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(Color.accentColor)
                    .clipShape(Capsule())
                    .padding(4)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
        }
        .frame(width: 120, height: 68)
    }

    /// The image to actually display: a scrub frame if we're hovering and have
    /// scrub data, otherwise the regular thumbnail.
    private var displayedImage: NSImage? {
        if let x = hoverX, thumbnailWidth > 0, !scrubFrames.isEmpty {
            let frac = max(0, min(1, x / thumbnailWidth))
            let idx = min(scrubFrames.count - 1, Int(frac * CGFloat(scrubFrames.count)))
            if let f = scrubFrames[idx] {
                return f
            }
        }
        return thumbnail
    }

    var body: some View {
        // Lightroom-style three-band layout: stats above, square thumbnail in
        // the middle, rating below. Card width tracks the LazyVGrid item
        // width; both bands' widths track the thumbnail's edge naturally.
        //
        // The middle section is a `Color.clear.aspectRatio(1, .fit)
        // .frame(maxWidth: .infinity)` placeholder so the layout engine
        // picks the strict square size BEFORE the actual content renders.
        // The photo-area background sits behind that, and the thumbnail
        // itself is inset by [photoPadding] so its full frame is visible
        // — matching Lightroom's letterbox behaviour where landscape
        // photos show top/bottom band of card background.
        VStack(alignment: .leading, spacing: 0) {
            topStatBand
                .frame(maxWidth: .infinity)
                .frame(height: 36)
                .background(topBandColor)
            // 1 pt separator under the top band.
            Rectangle()
                .fill(bandDividerColor)
                .frame(height: 1)
                .allowsHitTesting(false)
            Color.clear
                .frame(maxWidth: .infinity)
                .aspectRatio(1, contentMode: .fit)
                .background(photoAreaBackground)
                .overlay {
                    thumbnailArea
                        .padding(photoPadding)
                }
                // Selected + labelled cards get a thin colour-label
                // frame wrapped tight around the video itself — sized
                // to the video's aspect ratio, not the photo area's,
                // and with the frame's inner edge flush against the
                // video edge so there's no gap between band and video.
                //
                // A `GeometryReader` is necessary because the frame
                // must track the video's letterboxed bounds inside the
                // 1:1 photo area, and SwiftUI's aspect-ratio modifier
                // alone doesn't give a uniform-thickness band when the
                // two containers have different sizes.
                //
                // `.allowsHitTesting(false)` is essential — SwiftUI's
                // overlays sit *on top* of the parent's content, so a
                // shape view that covers the full photo area swallows
                // every click underneath.
                .overlay {
                    if let frame = thumbnailFrameColor {
                        GeometryReader { geo in
                            let aspect: CGFloat = (video.width > 0 && video.height > 0)
                                ? CGFloat(video.width) / CGFloat(video.height)
                                : 1
                            // The thumbnail container (inside photoPadding)
                            // is square — same size as min(width, height).
                            let photoSize = min(geo.size.width, geo.size.height)
                            let available = max(0, photoSize - 2 * photoPadding)
                            // Video's scaledToFit bounds inside that square.
                            let videoSize: CGSize = aspect >= 1
                                ? CGSize(width: available, height: available / aspect)
                                : CGSize(width: available * aspect, height: available)
                            let lineWidth: CGFloat = 3
                            Rectangle()
                                .strokeBorder(frame, lineWidth: lineWidth)
                                .frame(
                                    width: videoSize.width + 2 * lineWidth,
                                    height: videoSize.height + 2 * lineWidth
                                )
                                .position(x: geo.size.width / 2, y: geo.size.height / 2)
                        }
                        .allowsHitTesting(false)
                    }
                }
                // "Too large to play here" — vertical-centered in the
                // letterbox gap between the video's bottom edge and the
                // photo area's bottom edge. Same GeometryReader trick as
                // the colour-label frame; the badge sits flat in the
                // empty space below the video rather than overlapping it.
                .overlay {
                    if !video.playableNatively && !video.hasProxies {
                        GeometryReader { geo in
                            let aspect: CGFloat = (video.width > 0 && video.height > 0)
                                ? CGFloat(video.width) / CGFloat(video.height)
                                : 1
                            let photoH = geo.size.height
                            let available = max(0, photoH - 2 * photoPadding)
                            let videoH: CGFloat = aspect >= 1
                                ? available / aspect
                                : available
                            // Where the video's bottom edge falls inside
                            // the photo-area's coordinate space.
                            let topLetterbox = max(0, (available - videoH) / 2)
                            let videoBottom = photoPadding + topLetterbox + videoH
                            // Centre of the space below the video, capped
                            // so we don't dip into the bottom band divider.
                            let badgeCentreY = (videoBottom + photoH) / 2
                            Text("Too large to play here")
                                .font(.system(size: 9, weight: .medium))
                                .foregroundColor(.white)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(Color(red: 0.72, green: 0.45, blue: 0.18).opacity(0.9))
                                .cornerRadius(4)
                                .position(x: geo.size.width / 2, y: badgeCentreY)
                        }
                        .allowsHitTesting(false)
                    }
                }
                .clipped()
            // 1 pt separator above the bottom band.
            Rectangle()
                .fill(bandDividerColor)
                .frame(height: 1)
                .allowsHitTesting(false)
            ratingBand
                .frame(maxWidth: .infinity)
                .frame(height: 26)
                .background(bottomBandColor)
        }
        // Hover tint applied as a SwiftUI overlay so SwiftUI handles
        // compositing in the correct appearance context. Non-hit-
        // testable — otherwise the (covering) Color view would intercept
        // every click before it reached the stars / stack badge / stat
        // menu.
        .overlay(
            Color.white.opacity(hoverOverlayAlpha)
                .allowsHitTesting(false)
        )
        // 1 pt outer border between adjacent cards. Dark by default,
        // brightening on selection so the user always knows which card
        // they last touched. Also non-hit-testable for the same reason
        // — even though only the stroke line is drawn, `Rectangle()
        // .stroke(...)` reports a hit-test area covering the whole
        // bounding box, which would block child clicks.
        .overlay(
            Rectangle()
                .stroke(cardBorderColor, lineWidth: 1)
                .allowsHitTesting(false)
        )
        // Custom popup tooltip — we own the timing end-to-end rather
        // than relying on NSView's system tooltip, whose delay isn't
        // tunable per-view and was either firing during scrubs or
        // refusing to appear at all when we tried to suppress it. The
        // overlay sits just below the card with .allowsHitTesting(false)
        // so it doesn't steal clicks. Card-level onContinuousHover (next
        // modifier) drives the visibility timer.
        .overlay(alignment: .bottom) {
            if tooltipVisible {
                tooltipPopup
                    .offset(y: 8)
                    .allowsHitTesting(false)
                    .transition(.opacity.animation(.easeIn(duration: 0.12)))
            }
        }
        // Card-level motion tracking. Fires for moves anywhere on the
        // card — over the thumbnail (where we map x → scrub frame) and
        // over the info area below (which only contributes to the
        // tooltip dwell timer, no scrubbing). This is the ONLY hover
        // handler on the card; SwiftUI's `.onContinuousHover` doesn't
        // propagate, so nesting another inside the thumbnail would
        // swallow events from this one.
        .onContinuousHover { phase in
            switch phase {
            case .active(let location):
                // Drive isHovered from the continuous-hover phase rather than
                // from `.onHover`. On macOS, `.onHover` is backed by
                // NSTrackingArea cursor-entry/exit events; AppKit fires a
                // cursor-exit when the mouse button is pressed (mouse capture
                // changes), which makes `isHovered` flip to false at exactly
                // the wrong moment — the play-button overlay disappears and its
                // action is never delivered. `.onContinuousHover` tracks pointer
                // position (mouse-moved events) and continues firing `.active`
                // phases while a button is held, so hover state stays true for
                // the full press+release cycle.
                isHovered = true
                if lastTooltipCursor != location {
                    lastTooltipCursor = location
                    scheduleTooltipShow()
                }
                // Map card-local cursor → scrub frame. The thumbnail
                // occupies the top 16:9 region of the card. When the
                // cursor is over that region, expose its x to the
                // displayed-image picker; when it's below (info area)
                // clear `hoverX` so the regular thumbnail re-appears.
                let thumbHeight = thumbnailWidth * 9.0 / 16.0
                let withinThumbnail = thumbnailWidth > 0
                    && location.y >= 0
                    && location.y <= thumbHeight
                if withinThumbnail {
                    // First entry into the thumbnail kicks off lazy
                    // scrub-frame loading. `onHoverEnter` is idempotent
                    // so calling it repeatedly is fine, but gating on
                    // the previous nil keeps the log/Grpc call quieter.
                    if hoverX == nil { onHoverEnter() }
                    hoverX = location.x
                } else {
                    hoverX = nil
                }
            case .ended:
                isHovered = false
                lastTooltipCursor = nil
                resetTooltipTimer()
                hoverX = nil
            }
        }
        // While *any* AppKit menu is tracking the cursor — context
        // menus, menu-bar menus, anything backed by NSMenu — kill the
        // tooltip and cancel its pending show. The popup would render
        // on top of the menu and was particularly noticeable when the
        // user right-clicked, stayed still while reading the menu
        // options, and the 2-second dwell elapsed.
        //
        // Notifications fire from any NSMenu in the app, so a single
        // observer per card is enough. We deliberately don't *restart*
        // the timer on `didEndTracking` — the user has to move the
        // cursor again to re-arm, which matches the rest of the
        // dwell semantics.
        .onReceive(NotificationCenter.default.publisher(
            for: NSMenu.didBeginTrackingNotification
        )) { _ in
            resetTooltipTimer()
            lastTooltipCursor = nil
        }
        // AVPlayer lifecycle. `.onChange` fires *after* the first render
        // in which `isPlaying` is already true, so `avPlayer` would be nil
        // on that render (showing nothing). We also explicitly play() after
        // creation to ensure the player doesn't silently stall.
        .onChange(of: isPlaying) { _, playing in
            if playing {
                let url = URL(fileURLWithPath: playPath ?? video.openPath)
                let player = AVPlayer(url: url)
                // Disable stalling guard so short-form clips start instantly.
                player.automaticallyWaitsToMinimizeStalling = false
                player.play()
                avPlayer = player
            } else {
                avPlayer?.pause()
                avPlayer = nil
            }
        }
        // Eagerly create the player when the card first appears in
        // a playing state (e.g. after a grid re-render while another
        // card is playing — this card's `.onChange` won't fire because
        // it sees `isPlaying` as true from the very start).
        .onAppear {
            if isPlaying && avPlayer == nil {
                let url = URL(fileURLWithPath: playPath ?? video.openPath)
                let player = AVPlayer(url: url)
                player.automaticallyWaitsToMinimizeStalling = false
                player.play()
                avPlayer = player
            }
        }
        // Card-tap: select (with shift/toggle modifier support).
        //
        // Using plain `.onTapGesture` (not `.simultaneousGesture`). On macOS,
        // `.simultaneousGesture(TapGesture())` on a parent *appears* to fire
        // simultaneously with children in documentation, but in practice it claims
        // the NSEvent before child views see it — both child Buttons and child
        // `.onTapGesture` handlers stop firing entirely. Using `.onTapGesture`
        // gives children normal SwiftUI priority: Button actions and inner tap
        // gestures always win over this parent tap, so the play-button, stop-button,
        // and stack-badge clicks all reach their handlers. Clicking empty card space
        // falls through to here and selects the card.
        //
        // Trade-off: clicking the play button no longer *also* selects the card.
        // That's acceptable — the `.onContinuousHover` fix keeps `isHovered` true
        // through the full press/release cycle so the Button action fires reliably.
        //
        // `TapGesture.modifiers(...)` is unreliable on macOS SwiftUI; `NSApp.currentEvent`
        // at tap-recognition time reflects mouse-UP (modifier may already be released).
        // We read from `ModifierSnapshot`, captured at mouse-DOWN by the global monitor.
        .onTapGesture {
            let mods = ModifierSnapshot.lastMouseDownModifiers
            let shift = mods.contains(.shift)
            let toggle = mods.contains(.command) || mods.contains(.control)
            onClick(shift, toggle)
        }
        // File drag-out: lets users drag video files directly from the grid
        // into DaVinci Resolve, Premiere Pro, Final Cut Pro, Finder, etc.
        // When this card is part of a multi-selection, the primary path is
        // dragged; all paths are baked into `dragPaths` by the caller so
        // the count badge in the preview reflects the full set.
        .onDrag {
            let primary = dragPaths.first ?? video.openPath
            return DragExport.provider(for: primary)
        } preview: {
            dragPreview
        }
    }

    // Lightroom-style three-tone card. The top band sits one shade
    // lighter than the photo area; the bottom band sits a shade darker
    // than the top. Only the photo area takes the colour-label tint —
    // the top and bottom bands stay neutral grey at all times so the
    // grid reads as a uniform filmstrip. On selection, all three regions
    // brighten to the same neutral and the label is preserved as a thin
    // frame wrapping the photo.

    /// Top stat band. Lighter than the photo area by default.
    private var topBandColor: Color {
        if isAnchor || (isPrimarySelected && !isInMultiSelection) {
            return Color(white: 0.94)
        }
        if isInMultiSelection {
            return Color(white: 0.84)
        }
        return Color(white: 0.70)
    }

    /// Photo area. Takes the colour-label tint when unselected; goes to
    /// the bright selection neutral when the card is selected.
    private var photoAreaBackground: Color {
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

    /// Bottom rating band. Slightly darker than the top band when
    /// unselected; same bright neutral as the rest when selected.
    private var bottomBandColor: Color {
        if isAnchor || (isPrimarySelected && !isInMultiSelection) {
            return Color(white: 0.94)
        }
        if isInMultiSelection {
            return Color(white: 0.84)
        }
        return Color(white: 0.60)
    }

    /// Thin 1 pt separator between a band and the photo area. Provides
    /// the "small border underneath the top" / "border at the top of the
    /// rating row" the user spec calls for. Selected cards use a more
    /// muted divider so the bright background reads as one continuous
    /// surface; unselected cards get a darker divider for contrast.
    private var bandDividerColor: Color {
        if isAnchor || isPrimarySelected || isInMultiSelection {
            return Color.black.opacity(0.10)
        }
        return Color.black.opacity(0.35)
    }

    /// Thin colour-label frame drawn tight around the thumbnail when a
    /// card is selected AND carries a label. The frame preserves the
    /// label identity even though the background went bright neutral.
    private var thumbnailFrameColor: Color? {
        let label = ColorLabel(video.colorLabel)
        guard label != .none else { return nil }
        guard isAnchor || isPrimarySelected || isInMultiSelection else { return nil }
        return label.swatch
    }

    /// 1 pt outer card border. Dark by default so adjacent cards in the
    /// zero-gutter grid stay distinct; brightens on selection.
    private var cardBorderColor: Color {
        if isAnchor || isPrimarySelected || isInMultiSelection {
            return Color.white.opacity(0.6)
        }
        return Color.black.opacity(0.4)
    }

    // Backwards-compatibility shim — kept so any legacy reference still
    // compiles (the new layout uses the dedicated band/photo colours).
    private var cardBackground: Color { photoAreaBackground }
    private var cardBackgroundColor: Color { photoAreaBackground }

    // MARK: - Top stat band

    /// Top band: four stat cells, one in each corner of the band. Slot 0 =
    /// top-left, slot 1 = bottom-left, slot 2 = top-right, slot 3 =
    /// bottom-right. Each cell is right-clickable to pick which stat it
    /// renders. Mirrors the Lightroom Library top-of-card layout.
    @ViewBuilder
    private var topStatBand: some View {
        let slots = padSlots(topSlots)
        VStack(spacing: 0) {
            // Top row of the band: slots 0 (TL) and 2 (TR).
            HStack(alignment: .center, spacing: 6) {
                statCell(slotIndex: 0, key: slots[0], alignTrailing: false)
                statCell(slotIndex: 2, key: slots[2], alignTrailing: true)
            }
            .frame(maxWidth: .infinity)
            // Bottom row of the band: slots 1 (BL) and 3 (BR).
            HStack(alignment: .center, spacing: 6) {
                statCell(slotIndex: 1, key: slots[1], alignTrailing: false)
                statCell(slotIndex: 3, key: slots[3], alignTrailing: true)
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
    }

    /// A single stat cell. `slotIndex` is 0..3; `key` is the GridStatKey
    /// raw value that drives both the label and the picker preselection.
    ///
    /// Two distinct empty states:
    ///   • Slot itself is unset (`stat == .none`) → render "—" so the
    ///     user sees there's a picker available here.
    ///   • Slot is set but this video has no value for that stat
    ///     (e.g. picked "Camera model" but the clip has none) → render
    ///     nothing so the user isn't confused about whether the slot is
    ///     configured.
    ///
    /// Opens on a plain left-click via SwiftUI's `Menu`. The dropdown
    /// chevron is hidden so the band stays visually clean — the click
    /// affordance comes from the cell's tappability, not a visible arrow.
    @ViewBuilder
    private func statCell(slotIndex: Int, key: String, alignTrailing: Bool) -> some View {
        // Plain Text + onTapGesture + popover instead of a SwiftUI
        // `Menu`. The borderless-button `Menu` style on macOS adds
        // asymmetric internal padding around its label — visible as
        // leading-edge offsets that differ between bold (slot 0) and
        // regular (other slots) text, plus reserved trailing space
        // for the now-hidden disclosure chevron that pushes
        // "trailing"-aligned cells away from the card's right edge.
        // Owning the popover ourselves makes the text sit flush
        // against the cell's frame, so the four values line up
        // exactly with the band's edges.
        let stat = GridStatKey(rawValue: key) ?? .none
        let value = stat.value(for: video)
        let displayed: String = {
            if stat == .none { return "—" }
            return value      // empty if this video has no data for the chosen stat
        }()
        Text(displayed)
            .font(.system(size: 10, weight: slotIndex == 0 ? .semibold : .regular))
            .foregroundColor(stat == .none ? Color.black.opacity(0.4) : Color.black.opacity(0.85))
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
    }

    /// The picker content rendered inside the popover. A vertical
    /// list of every `GridStatKey` choice with a leading checkmark on
    /// the currently-selected one. Each row is a `.plain`-style
    /// Button so the rows feel like menu items without any borderless-
    /// menu chrome leaking back in.
    @ViewBuilder
    private func statPickerMenu(slotIndex: Int, currentStat: GridStatKey) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(GridStatKey.allCases) { choice in
                Button {
                    onPickStatSlot(slotIndex, choice.rawValue)
                    openSlotPickerIndex = nil
                } label: {
                    HStack(spacing: 6) {
                        // Reserve a constant 14 pt for the checkmark
                        // gutter so all rows line up regardless of
                        // whether they're the current choice.
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

    /// Ensure we always render four cells even if the server / VM hands us
    /// fewer entries (defensive).
    private func padSlots(_ raw: [String]) -> [String] {
        var slots = raw
        while slots.count < 4 { slots.append("") }
        if slots.count > 4 { slots = Array(slots.prefix(4)) }
        return slots
    }

    // MARK: - Rating band

    /// Five tappable star/dot positions along the bottom of the card.
    /// Clicking position N sets the rating to N; clicking position N when
    /// the current rating is already N clears (rating - 1) — Lightroom's
    /// toggle-off semantics. Position 0 doesn't exist; the band itself
    /// (outside the stars) is non-interactive.
    ///
    /// Uses `.onTapGesture` on a sized hit-target rather than a `Button`,
    /// because the card's outer `.onTapGesture` (which selects the card)
    /// can intercept Button presses on macOS — the buttons would render
    /// but never fire. SwiftUI gives `.onTapGesture` on children priority
    /// over the parent's `.onTapGesture`, which is exactly what we need.
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
                .contentShape(Rectangle())  // 20×20 hit target
                .onTapGesture {
                    // Click the *rightmost* filled star (i.e. the one
                    // whose position equals the current rating) to clear
                    // the rating to zero. Clicking any other star — to
                    // the left of the rightmost filled star or to the
                    // right of the unfilled region — sets the rating to
                    // that position. Matches the user spec.
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

    /// White overlay alpha used to indicate hover. Composed by SwiftUI on
    /// top of `cardBackground` *at draw time*, so the dynamic
    /// `controlBackgroundColor` resolves in the right appearance
    /// (previous attempts pre-blended via `NSColor.blended(...)`, which
    /// resolved the dynamic base color to its light-mode value off-screen
    /// and produced an almost-pure-white card in dark mode).
    ///
    /// 0.05 matches the Kotlin client's hover tint — a small, even lift
    /// that's noticeable enough to identify the hovered card without
    /// blowing out the white filename caption in the info area.
    private var hoverOverlayAlpha: Double {
        isHovered && !isInExpandedStack ? 0.05 : 0
    }

    private var borderColor: Color {
        if isAnchor { return Color(red: 0.12, green: 0.43, blue: 0.92) }   // tertiary-ish
        if isPrimarySelected { return Color.accentColor }
        if isInMultiSelection { return Color.accentColor.opacity(0.65) }
        if isInExpandedStack { return Color.accentColor.opacity(0.5) }
        if isHovered { return Color.secondary }
        return Color(.separatorColor)
    }

    private var borderWidth: CGFloat {
        if isAnchor || isPrimarySelected || isInMultiSelection { return 2.5 }
        if isInExpandedStack { return 1.5 }
        return 1
    }

    @ViewBuilder
    private var thumbnailArea: some View {
        ZStack(alignment: .topLeading) {
            // Background / thumbnail. We:
            //   * use `.background(GeometryReader { ... })` to measure width
            //     without inserting a GeometryReader into the hit-test path
            //     (GeometryReader can swallow hover events on macOS 15+ SwiftUI),
            //   * use `.contentShape(Rectangle())` to make the entire bounds
            //     hit-testable for hover tracking,
            //   * apply `.onContinuousHover` directly on the rendered content.
            Rectangle()
                .fill(Color.clear)  // background lives one layer up (photoAreaBackground)
                // Thumbnail / scrub-frame layer — always shown so the image
                // stays visible while the AVPlayer initializes and its first
                // frame is still being decoded (prevents a black flash).
                // Uses `.scaledToFit` (Lightroom letterboxing) so the
                // photo's full frame is visible inside the card — the
                // colour-label background fills any letterbox space above
                // and below (or left and right for portrait clips).
                .overlay {
                    if let image = displayedImage {
                        Image(nsImage: image)
                            .resizable()
                            .scaledToFit()
                    } else {
                        Image(systemName: "film")
                            .font(.system(size: 32))
                            .foregroundColor(.secondary)
                    }
                }
                // Player surface — layered on top of the thumbnail. AVPlayerNSView
                // has a transparent CALayer so the thumbnail shows through until
                // the first decoded frame composites over it.
                .overlay {
                    if isPlaying, let player = avPlayer {
                        // Live inline playback — AVPlayerNSView wraps AVPlayerView
                        // directly rather than going through SwiftUI's VideoPlayer,
                        // which crashes on some macOS configs when its private
                        // VideoPlayerView subclass can't be demangled at launch.
                        AVPlayerNSView(player: player)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .clipped()
                    }
                }
                // Play button — `.overlay(alignment: .center)` places the
                // Button at the exact visual centre of the Rectangle and
                // its hit area is the button's own natural size (44×44 circle).
                // Clicks outside the circle pass through to the card's tap
                // gesture. Using a separate overlay (not a ZStack child with
                // .frame(maxWidth:.infinity)) avoids the hit-area-at-origin
                // bug where `.contentShape(Circle().size(…))` positions the
                // hit region at the view's top-left corner, not its centre.
                .overlay(alignment: .center) {
                    if !isPlaying && isHovered {
                        Button(action: onPlayClick) {
                            Image(systemName: "play.fill")
                                .font(.system(size: 18))
                                .foregroundColor(.white)
                                .frame(width: 44, height: 44)
                                .background(Color.black.opacity(0.55))
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
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
                        .padding(5)
                    }
                }
                .background(
                    GeometryReader { proxy in
                        Color.clear
                            .onAppear { thumbnailWidth = proxy.size.width }
                            .onChange(of: proxy.size.width) { _, newValue in
                                thumbnailWidth = newValue
                            }
                    }
                )
                .contentShape(Rectangle())
                // (Scrub tracking + tooltip motion tracking are handled
                // together by the card-level `.onContinuousHover` further
                // down. A single hover handler at the card level avoids
                // the event-swallowing that happened when a second handler
                // was nested inside the thumbnail.)

            // Stack/group badge — clickable, doesn't propagate to the card
            if video.isInGroup {
                stackBadge
                    .padding(6)
                    .contentShape(Rectangle())
                    .onTapGesture { onStackBadgeClick() }
                    .help(item.isExpandedRepresentative
                          ? "Collapse this stack of \(video.groupSize) videos back to one card."
                          : "Expand this stack to see all \(video.groupSize) variants inline.")
            }

            // Bottom-right icon row — Lightroom-style. The old proxy / resolution /
            // duration chips have been retired; users who want resolution
            // or duration on the card put them in one of the four
            // configurable top stat slots. This corner keeps the at-a-
            // glance "has keywords" and "has proxies" indicators visible
            // without crowding the thumbnail.
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
                    }
                }
            }
            .padding(6)

            // (The "Too large to play here" marker now sits in the
            // letterbox area below the video, positioned by a
            // GeometryReader overlay on the photo-area box — see the
            // `body`. That overlay needs to know the photo area's full
            // dimensions to compute where the video's bottom edge lands.)
        }
    }

    private var stackBadge: some View {
        HStack(spacing: 3) {
            Image(systemName: "square.stack.3d.up.fill")
                .font(.system(size: 10))
            Text(positionText)
                .font(.system(size: 10, weight: .medium))
        }
        .foregroundColor(.white)
        .padding(.horizontal, 5)
        .padding(.vertical, 2)
        .background(stackBadgeColor)
        .cornerRadius(4)
    }

    /// "P×N" pill shown bottom-left on cards with one or more proxies.
    /// Teal-ish color to visually distinguish from the stack badge
    /// (which uses the accent color) — proxies and stacks are
    /// orthogonal concepts so they shouldn't read as the same thing.
    private var proxyBadge: some View {
        HStack(spacing: 3) {
            Image(systemName: "rectangle.compress.vertical")
                .font(.system(size: 10))
            Text("P×\(video.proxyCount)")
                .font(.system(size: 10, weight: .medium))
        }
        .foregroundColor(.white)
        .padding(.horizontal, 5)
        .padding(.vertical, 2)
        .background(Color(red: 0.25, green: 0.55, blue: 0.55))
        .cornerRadius(4)
    }

    private var positionText: String {
        if item.memberCount > 0 && item.memberPosition > 0 {
            return "\(item.memberPosition) / \(item.memberCount)"
        }
        return "\(video.groupSize)"
    }

    private var stackBadgeColor: Color {
        if item.isExpandedRepresentative { return Color.accentColor }
        if item.isStackChild { return Color(red: 0.12, green: 0.43, blue: 0.92).opacity(0.85) }
        return Color.accentColor.opacity(0.85)
    }

    private func badgeText(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .medium))
            .foregroundColor(.white)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(Color.black.opacity(0.7))
            .cornerRadius(3)
    }
}

#Preview {
    GridView(
        viewModel: GridViewModel(),
        detailViewModel: DetailViewModel(),
        thumbnailMinWidth: 220
    )
    .frame(width: 800, height: 600)
}
