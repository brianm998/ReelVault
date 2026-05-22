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
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: thumbnailMinWidth), spacing: 8)],
                spacing: 8
            ) {
                ForEach(Array(rendered.enumerated()), id: \.element.id) { index, item in
                    VideoCardView(
                        item: item,
                        thumbnail: viewModel.thumbnails[item.video.id],
                        scrubFrames: viewModel.scrubFrames[item.video.id] ?? [],
                        isPrimarySelected: viewModel.selectedVideoId == item.video.id,
                        isInMultiSelection: viewModel.selectedVideoIds.contains(item.video.id),
                        isAnchor: viewModel.anchorVideoId == item.video.id && viewModel.selectedVideoIds.count > 1,
                        isPlaying: viewModel.playingVideoId == item.video.id,
                        onClick: { shift, toggle in
                            handleClick(item: item, rendered: rendered, shift: shift, toggle: toggle)
                        },
                        onDoubleClick: {
                            viewModel.openVideoInExternal(path: item.video.openPath)
                        },
                        onStackBadgeClick: {
                            viewModel.toggleStackExpansion(item.video.groupId)
                        },
                        onHoverEnter: {
                            viewModel.loadScrubFrames(videoId: item.video.id)
                        },
                        onPlayClick: {
                            if item.video.playableNatively {
                                viewModel.playVideo(videoId: item.video.id)
                            } else {
                                // Oversize — open the proxy-creation picker
                                viewModel.requestCreateProxy(videoId: item.video.id)
                            }
                        },
                        onStopPlayback: {
                            viewModel.stopPlayback()
                        }
                    )
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
            .padding(8)
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
    let onClick: (_ shift: Bool, _ toggle: Bool) -> Void
    let onDoubleClick: () -> Void
    let onStackBadgeClick: () -> Void
    let onHoverEnter: () -> Void
    /// Fired when the play-button overlay is clicked on a `playableNatively`
    /// card (or when the user taps the affordance on an oversize card).
    let onPlayClick: () -> Void
    /// Fired when the ✕ stop button on the inline player is tapped.
    let onStopPlayback: () -> Void

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
        VStack(alignment: .leading, spacing: 0) {
            // Thumbnail area — always 16:9
            thumbnailArea
                .aspectRatio(16 / 9, contentMode: .fit)
                .frame(maxWidth: .infinity)

            // Info area — fixed height regardless of card width.
            // Top inset is wider than the other three because SwiftUI's
            // `Text` ascender sits very close to the top of the layout
            // frame, so the filename reads as "touching" the thumbnail
            // when padded uniformly. The Kotlin client doesn't see this
            // because Material 3's Text adds extra built-in leading on
            // top, so a uniform 8 dp inset already looks comfortable
            // over there.
            VStack(alignment: .leading, spacing: 2) {
                Text(video.filename)
                    .font(.system(size: 11))
                    .lineLimit(2)
                    .foregroundColor(.primary)
                Text("\(video.codecVideo.isEmpty ? "?" : video.codecVideo) • \(Int(video.fps))fps")
                    .font(.system(size: 10))
                    .lineLimit(1)
                    .foregroundColor(.secondary)
            }
            .padding(EdgeInsets(top: 12, leading: 8, bottom: 8, trailing: 8))
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(cardBackground)
        // Hover tint applied as a SwiftUI overlay so SwiftUI handles
        // compositing in the correct appearance context — the previous
        // pre-blended NSColor approach baked in the *light-mode* base
        // colour, producing a near-white card in dark mode.
        .overlay(Color.white.opacity(hoverOverlayAlpha))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(borderColor, lineWidth: borderWidth)
        )
        .cornerRadius(6)
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
        .onHover { isHovered = $0 }
        // AVPlayer lifecycle. `.onChange` fires *after* the first render
        // in which `isPlaying` is already true, so `avPlayer` would be nil
        // on that render (showing nothing). We also explicitly play() after
        // creation to ensure the player doesn't silently stall.
        .onChange(of: isPlaying) { _, playing in
            if playing {
                let url = URL(fileURLWithPath: video.openPath)
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
                let url = URL(fileURLWithPath: video.openPath)
                let player = AVPlayer(url: url)
                player.automaticallyWaitsToMinimizeStalling = false
                player.play()
                avPlayer = player
            }
        }
        // Order matters: the count:2 gesture is registered first so SwiftUI
        // gives it priority. A single click then waits briefly for a possible
        // second click before firing the count:1 handler.
        .onTapGesture(count: 2) {
            onDoubleClick()
        }
        .onTapGesture(count: 1) {
            // `TapGesture.modifiers(...)` is unreliable on macOS SwiftUI, and
            // `NSApp.currentEvent` at tap-recognition time reflects the mouse-UP
            // event — by which point the user may have already released the
            // modifier. We read from `ModifierSnapshot`, which is captured at
            // mouse-DOWN time by the global NSEvent monitor.
            let mods = ModifierSnapshot.lastMouseDownModifiers
            let shift = mods.contains(.shift)
            // Accept either Command (Mac convention) or Control (Windows/Linux)
            // as the toggle modifier.
            let toggle = mods.contains(.command) || mods.contains(.control)
            onClick(shift, toggle)
        }
    }

    private var cardBackground: Color {
        if isInExpandedStack {
            return Color.accentColor.opacity(0.13)
        }
        return Color(.controlBackgroundColor)
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
                .fill(Color.black)
                .overlay {
                    if isPlaying, let player = avPlayer {
                        // Live inline playback — AVKit VideoPlayer fills our
                        // 16:9 frame. VideoPlayer manages its own player layer.
                        VideoPlayer(player: player)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .clipped()
                    } else if let image = displayedImage {
                        Image(nsImage: image)
                            .resizable()
                            .scaledToFill()
                            .clipped()
                    } else {
                        Image(systemName: "film")
                            .font(.system(size: 32))
                            .foregroundColor(.secondary)
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
                    if !isPlaying && isHovered && video.playableNatively {
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

            // Proxy badge — sibling to the stack badge but placed on the
            // bottom-left so it doesn't collide. Tooltip explains that
            // the user has lower-resolution variants available for
            // inline playback. Non-interactive for now — the right
            // panel's proxy section is where management happens.
            if video.hasProxies {
                VStack {
                    Spacer()
                    HStack {
                        proxyBadge
                            .padding(6)
                            .help(video.playableNatively
                                ? "This video has \(video.proxyCount) lower-resolution proxy/proxies. They can be played inline if the original is too large to load smoothly."
                                : "This video is above your inline-playback ceiling (\(video.height) px). \(video.proxyCount) proxy/proxies available.")
                        Spacer()
                    }
                }
            }

            // Resolution + duration badges (top-right and bottom-right)
            VStack {
                HStack {
                    Spacer()
                    badgeText(video.height > 0 ? "\(video.height)p" : "?")
                }
                Spacer()
                HStack {
                    Spacer()
                    badgeText(video.durationFormatted)
                }
            }
            .padding(6)

            // "Too large to play here" marker — bottom-center.
            // Shown when the server's `playableNatively` is false (video
            // height exceeds the configured max-native-playback-height).
            // Informational; the actual "create a proxy" affordance is
            // in the context menu so it doesn't interfere with the
            // grid's multi-select behavior.
            if !video.playableNatively {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Text("Too large to play here")
                            .font(.system(size: 9, weight: .medium))
                            .foregroundColor(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 2)
                            .background(Color(red: 0.72, green: 0.45, blue: 0.18).opacity(0.9))
                            .cornerRadius(4)
                        Spacer()
                    }
                    .padding(.bottom, 28) // sit above the duration badge
                }
            }
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
