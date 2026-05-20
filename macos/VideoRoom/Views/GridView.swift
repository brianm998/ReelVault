import SwiftUI
import AppKit

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
                        isPrimarySelected: viewModel.selectedVideoId == item.video.id,
                        isInMultiSelection: viewModel.selectedVideoIds.contains(item.video.id),
                        isAnchor: viewModel.anchorVideoId == item.video.id && viewModel.selectedVideoIds.count > 1,
                        onClick: { shift, toggle in
                            handleClick(item: item, rendered: rendered, shift: shift, toggle: toggle)
                        },
                        onDoubleClick: {
                            viewModel.openVideoInExternal(path: item.video.openPath)
                        },
                        onStackBadgeClick: {
                            viewModel.toggleStackExpansion(item.video.groupId)
                        }
                    )
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
    let isPrimarySelected: Bool
    let isInMultiSelection: Bool
    let isAnchor: Bool
    let onClick: (_ shift: Bool, _ toggle: Bool) -> Void
    let onDoubleClick: () -> Void
    let onStackBadgeClick: () -> Void

    @State private var isHovered = false

    private var video: VideoSummary { item.video }
    private var isInExpandedStack: Bool { item.isExpandedRepresentative || item.isStackChild }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Thumbnail area — always 16:9
            thumbnailArea
                .aspectRatio(16 / 9, contentMode: .fit)
                .frame(maxWidth: .infinity)

            // Info area — fixed height regardless of card width
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
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(cardBackground)
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(borderColor, lineWidth: borderWidth)
        )
        .cornerRadius(6)
        .onHover { isHovered = $0 }
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
            // Background / thumbnail
            ZStack {
                Rectangle().fill(Color.black)
                if let image = thumbnail {
                    Image(nsImage: image)
                        .resizable()
                        .scaledToFill()
                        .clipped()
                } else {
                    Image(systemName: "film")
                        .font(.system(size: 32))
                        .foregroundColor(.secondary)
                }
                // Hover play overlay
                if isHovered {
                    Color.black.opacity(0.4)
                    Image(systemName: "play.fill")
                        .font(.system(size: 32))
                        .foregroundColor(.white.opacity(0.9))
                }
            }

            // Stack/group badge — clickable, doesn't propagate to the card
            if video.isInGroup {
                stackBadge
                    .padding(6)
                    .contentShape(Rectangle())
                    .onTapGesture { onStackBadgeClick() }
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
