// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI
import AppKit

struct ListView: View {
    @ObservedObject var viewModel: GridViewModel
    @ObservedObject var detailViewModel: DetailViewModel
    var thumbnailHeight: CGFloat = 100
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
            videoList
        }
    }

    private var videoList: some View {
        let rendered = buildRenderedList(
            videos: viewModel.videos,
            expandedIds: viewModel.expandedGroupIds,
            members: viewModel.expandedGroupMembers
        )

        return ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(Array(rendered.enumerated()), id: \.element.id) { index, item in
                    VideoListRowView(
                        item: item,
                        thumbnail: viewModel.thumbnails[item.video.id],
                        thumbnailHeight: thumbnailHeight,
                        columns: viewModel.listColumns,
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

                    Divider()
                        .padding(.leading, thumbnailHeight * 16.0 / 9.0 + 16)
                }

                if viewModel.isLoading && !viewModel.videos.isEmpty {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding()
                }
            }
            .padding(.vertical, 4)
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
                : "This video is above the inline-playback ceiling. Create a lower-resolution proxy so VideoRoom can play it without falling back to an external editor.")
        }

        Divider()

        Button("Configure External Editors…") {
            onConfigureEditors()
        }
    }
}

// MARK: - VideoListRowView

struct VideoListRowView: View {
    let item: GridItemRow
    let thumbnail: NSImage?
    var thumbnailHeight: CGFloat = 100
    let columns: Set<String>
    let isPrimarySelected: Bool
    let isInMultiSelection: Bool
    let isAnchor: Bool
    let onClick: (_ shift: Bool, _ toggle: Bool) -> Void
    let onDoubleClick: () -> Void
    let onStackBadgeClick: () -> Void

    @State private var isHovered = false

    private var video: VideoSummary { item.video }
    private var isStackChild: Bool { item.isStackChild }
    private var thumbnailWidth: CGFloat { thumbnailHeight * 16.0 / 9.0 }

    var body: some View {
        HStack(spacing: 10) {
            // Indent stack children
            if isStackChild {
                Spacer().frame(width: 12)
            }

            // Thumbnail
            thumbnailArea

            // Info columns
            VStack(alignment: .leading, spacing: 3) {
                // Filename
                Text(video.filename)
                    .font(.body)
                    .fontWeight(.medium)
                    .lineLimit(1)

                // Tech line: resolution · codec · fps · duration
                let techLine = buildTechLine()
                if !techLine.isEmpty {
                    Text(techLine)
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                // Date + file size line
                let dateLine = buildDateLine()
                if !dateLine.isEmpty {
                    Text(dateLine)
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                // Tags row
                if columns.contains("tags") && !video.tags.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(video.tags, id: \.self) { tag in
                            Text(tag)
                                .font(.system(size: 10))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.accentColor.opacity(0.2))
                                .cornerRadius(4)
                        }
                    }
                }

                // Proxy badge
                if columns.contains("proxy") && video.hasProxies {
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
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer()
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 10)
        .background(rowBackground)
        .overlay(
            RoundedRectangle(cornerRadius: 0)
                .stroke(borderColor, lineWidth: borderWidth)
        )
        .onHover { hovered in isHovered = hovered }
        .onTapGesture(count: 2) {
            onDoubleClick()
        }
        .onTapGesture {
            let mods = ModifierSnapshot.lastMouseDownModifiers
            let shift = mods.contains(.shift)
            let toggle = mods.contains(.command) || mods.contains(.control)
            onClick(shift, toggle)
        }
    }

    private var thumbnailArea: some View {
        ZStack(alignment: .topLeading) {
            Rectangle()
                .fill(Color.black)
                .overlay {
                    if let image = thumbnail {
                        Image(nsImage: image)
                            .resizable()
                            .scaledToFill()
                            .clipped()
                    } else {
                        Image(systemName: "film")
                            .font(.system(size: 18))
                            .foregroundColor(.secondary)
                    }
                }
                .frame(width: thumbnailWidth, height: thumbnailHeight)
                .cornerRadius(4)

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

            // Resolution badge (top-right)
            if video.height > 0 {
                VStack {
                    HStack {
                        Spacer()
                        Text("\(video.height)p")
                            .font(.system(size: 9, weight: .medium))
                            .foregroundColor(.white)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 2)
                            .background(Color.black.opacity(0.7))
                            .cornerRadius(3)
                    }
                    Spacer()
                }
                .padding(4)
            }
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

    private func buildTechLine() -> String {
        var parts: [String] = []
        if columns.contains("resolution") && !video.resolution.isEmpty {
            parts.append(video.resolution)
        }
        if columns.contains("codec") && !video.codecVideo.isEmpty {
            parts.append(video.codecVideo)
        }
        if columns.contains("fps") && video.fps > 0 {
            parts.append(String(format: "%.2gfps", video.fps))
        }
        if columns.contains("duration") && !video.durationFormatted.isEmpty {
            parts.append(video.durationFormatted)
        }
        return parts.joined(separator: " · ")
    }

    private func buildDateLine() -> String {
        var parts: [String] = []
        if columns.contains("date") && video.creationDate > 0 {
            let date = Date(timeIntervalSince1970: Double(video.creationDate) / 1000.0)
            let formatter = DateFormatter()
            formatter.dateStyle = .medium
            formatter.timeStyle = .none
            parts.append(formatter.string(from: date))
        }
        if columns.contains("filesize") && !video.sizeFormatted.isEmpty {
            parts.append(video.sizeFormatted)
        }
        return parts.joined(separator: "  ")
    }

    private var rowBackground: Color {
        if isPrimarySelected { return Color.primary.opacity(0.15) }
        if isInMultiSelection { return Color.accentColor.opacity(0.08) }
        if isStackChild || item.isExpandedRepresentative { return Color.accentColor.opacity(0.06) }
        if isHovered { return Color.primary.opacity(0.04) }
        return Color.clear
    }

    private var borderColor: Color {
        if isAnchor { return Color(red: 0.12, green: 0.43, blue: 0.92) }
        if isPrimarySelected { return Color.accentColor }
        if isInMultiSelection { return Color.accentColor.opacity(0.65) }
        return Color.clear
    }

    private var borderWidth: CGFloat {
        if isAnchor || isPrimarySelected || isInMultiSelection { return 1.5 }
        return 0
    }
}

#Preview {
    ListView(
        viewModel: GridViewModel(),
        detailViewModel: DetailViewModel()
    )
    .frame(width: 800, height: 600)
}
