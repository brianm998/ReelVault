// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// List mode: one row per video — a thumbnail on the left, filename + a few
/// configurable stats + rating on the right — mirroring the macOS ListView
/// (simplified for touch). Bound to the shared `GridViewModel`.
struct VideoListView: View {
    @ObservedObject var grid: GridViewModel
    /// Row thumbnail height (driven by the top-bar size slider).
    var thumbnailHeight: CGFloat = 64
    var selecting: Bool = false
    var onActivate: (VideoSummary) -> Void
    /// Double-tap to open Detail mode (iPad); nil on iPhone (single tap pushes detail).
    var onDoubleTap: ((VideoSummary) -> Void)? = nil

    var body: some View {
        if grid.videos.isEmpty {
            if !grid.hasLoadedOnce || grid.isLoading {
                // Loading, not empty — see VideoGridView / GridViewModel.hasLoadedOnce.
                ProgressView().frame(maxWidth: .infinity).padding(.top, 80)
            } else {
                ContentUnavailableView(
                    "No videos", systemImage: "list.bullet",
                    description: Text("The connected catalog is empty, or no videos match the current filter.")
                )
            }
        } else {
            let rows = stackRenderedVideos(grid)
            List {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    let video = row.video
                    VideoListRow(
                        video: video,
                        image: grid.thumbnails[video.id],
                        topSlots: grid.topSlots,
                        thumbnailHeight: thumbnailHeight,
                        isSelected: grid.selectedVideoId == video.id,
                        showCheck: selecting,
                        isChecked: grid.selectedVideoIds.contains(video.id),
                        isStackMember: row.isMember,
                        onToggleExpand: { grid.toggleStackExpansion(video.groupId) }
                    )
                    .listRowBackground(grid.selectedVideoId == video.id ? Color.accentColor.opacity(0.18) : Color.clear)
                    .contentShape(Rectangle())
                    .modifier(CardTapActions(
                        onActivate: {
                            if selecting {
                                grid.toggleVideoSelection(video)
                            } else {
                                grid.selectVideo(video)
                                onActivate(video)
                            }
                        },
                        onDoubleTap: (onDoubleTap != nil && !selecting)
                            ? { grid.selectVideo(video); onDoubleTap?(video) } : nil))
                    .contextMenu {
                        VideoCardMenu(
                            video: video, selectedCount: grid.selectedVideoIds.count,
                            onSetRating: { grid.setRating($0, for: [video.id]) },
                            onSetColorLabel: { grid.setColorLabel($0, for: [video.id]) },
                            onCombine: { grid.groupSelectedVideos() },
                            onPromote: { grid.setStackMaster(videoId: video.id, groupId: video.groupId) },
                            onRemoveFromStack: { grid.removeFromStack(videoId: video.id, groupId: video.groupId) },
                            onUnstack: { grid.unstackGroup(groupId: video.groupId) })
                    }
                    .onAppear {
                        grid.loadThumbnail(videoId: video.id)
                        // Page in the next batch as the user nears the end (see
                        // VideoGridView) — otherwise list mode is capped at the
                        // first page of a large catalog.
                        if index >= rows.count - 8 && grid.hasMore {
                            grid.loadMore()
                        }
                    }
                }
                if grid.hasMore {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                    .listRowSeparator(.hidden)
                }
            }
            .listStyle(.plain)
        }
    }
}

private struct VideoListRow: View {
    let video: VideoSummary
    let image: PlatformImage?
    let topSlots: [String]
    let thumbnailHeight: CGFloat
    let isSelected: Bool
    let showCheck: Bool
    let isChecked: Bool
    var isStackMember: Bool = false
    var onToggleExpand: () -> Void = {}

    var body: some View {
        HStack(spacing: 12) {
            if isStackMember {
                Image(systemName: "arrow.turn.down.right").font(.caption).foregroundStyle(.secondary)
            }
            if showCheck {
                Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isChecked ? Color.accentColor : .secondary)
            }
            thumbnail
            VStack(alignment: .leading, spacing: 3) {
                Text(video.filename).font(.body).lineLimit(1)
                Text(secondaryLine).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if video.rating > 0 {
                    HStack(spacing: 1) {
                        ForEach(0..<video.rating, id: \.self) { _ in
                            Image(systemName: "star.fill").font(.system(size: 8)).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
            if video.isInGroup && !isStackMember {
                Button(action: onToggleExpand) {
                    HStack(spacing: 2) {
                        Image(systemName: "square.stack.3d.up.fill").font(.system(size: 9))
                        Text("\(video.groupSize)").font(.caption2.weight(.semibold))
                    }
                    .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
            CardStatusBadges(video: video, iconSize: 9)
        }
        .padding(.leading, isStackMember ? 16 : 0)
        .opacity(video.isOnline ? 1 : 0.5)
        .padding(.vertical, 2)
    }

    private var thumbnail: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 4).fill(Color(white: 0.18))
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "film").foregroundStyle(.secondary)
            }
        }
        .frame(width: thumbnailHeight * 16 / 9, height: thumbnailHeight)
        .clipShape(RoundedRectangle(cornerRadius: 4))
    }

    /// A compact line of the most useful stats (resolution · duration · size).
    private var secondaryLine: String {
        [GridStatKey.resolutionName, .duration, .fileSize]
            .map { $0.value(for: video) }
            .filter { !$0.isEmpty }
            .joined(separator: "  ·  ")
    }
}
