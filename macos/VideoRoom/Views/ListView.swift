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
                    VideoListRowView(
                        item: item,
                        thumbnail: viewModel.thumbnails[item.video.id],
                        thumbnailHeight: thumbnailHeight,
                        columns: viewModel.listColumns,
                        isPrimarySelected: viewModel.selectedVideoId == item.video.id,
                        isInMultiSelection: viewModel.selectedVideoIds.contains(item.video.id),
                        isAnchor: viewModel.anchorVideoId == item.video.id && viewModel.selectedVideoIds.count > 1,
                        topSlots: viewModel.topSlots,
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
                        dragPaths: rowDragPaths
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
                    // No inter-row Divider here — each row's Lightroom
                    // container now has its own outer border, so an extra
                    // divider between rows would double up.
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
    /// Same four catalog-scoped top-of-card stat-slot choices the grid uses.
    /// Each entry is a `GridStatKey.rawValue`; unknown strings render as blank.
    var topSlots: [String] = []
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

    @State private var isHovered = false

    private var video: VideoSummary { item.video }
    private var isStackChild: Bool { item.isStackChild }
    private var thumbnailWidth: CGFloat { thumbnailHeight * 16.0 / 9.0 }
    private var isInExpandedStack: Bool {
        item.isExpandedRepresentative || item.isStackChild
    }

    var body: some View {
        // Lightroom-style three-band wrapper, matching the grid card: a
        // top stat band, a middle media-and-info row, and a bottom rating
        // band. The bands span the full row width; the existing
        // horizontal row layout sits unchanged in the middle.
        VStack(alignment: .leading, spacing: 0) {
            topStatBand
                .frame(maxWidth: .infinity)
                .frame(height: 22)
                .background(topBandColor)
            Rectangle()
                .fill(bandDividerColor)
                .frame(height: 1)
                .allowsHitTesting(false)
            rowBody
                .background(rowMiddleBackground)
            Rectangle()
                .fill(bandDividerColor)
                .frame(height: 1)
                .allowsHitTesting(false)
            ratingBand
                .frame(maxWidth: .infinity)
                .frame(height: 22)
                .background(bottomBandColor)
        }
        .overlay(
            Rectangle()
                .stroke(cardBorderColor, lineWidth: 1)
                .allowsHitTesting(false)
        )
        .contentShape(Rectangle())
        .onHover { hovered in isHovered = hovered }
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
    }

    /// Middle band: the existing horizontal layout (thumbnail + info
    /// columns). Lifted out of `body` so the surrounding three-band
    /// wrapper stays readable.
    private var rowBody: some View {
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
            // No .frame(maxWidth: .infinity) — that combined with the trailing
            // Spacer() left two flexible siblings, so SwiftUI split the leftover
            // width 50/50 and put a big gap *between* the thumbnail and the
            // info. Letting the VStack size to its content (intrinsic width)
            // and giving the Spacer all the flex pushes the info flush against
            // the thumbnail with the slack absorbed on the right of the row.

            Spacer(minLength: 0)
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 10)
    }

    // MARK: - Lightroom bands

    /// Top band: a single horizontal row of four configurable stat cells,
    /// mirroring the 4 catalog-scoped slots used by the grid card.
    @ViewBuilder
    private var topStatBand: some View {
        let slots = padSlots(topSlots)
        HStack(spacing: 12) {
            statCell(slotIndex: 0, key: slots[0], alignTrailing: false)
            statCell(slotIndex: 1, key: slots[1], alignTrailing: false)
            statCell(slotIndex: 2, key: slots[2], alignTrailing: false)
            statCell(slotIndex: 3, key: slots[3], alignTrailing: true)
        }
        .padding(.horizontal, 10)
    }

    @ViewBuilder
    private func statCell(slotIndex: Int, key: String, alignTrailing: Bool) -> some View {
        let stat = GridStatKey(rawValue: key) ?? .none
        let value = stat.value(for: video)
        let displayed: String = {
            if stat == .none { return "—" }
            return value
        }()
        Menu {
            ForEach(GridStatKey.allCases) { choice in
                Button {
                    onPickStatSlot(slotIndex, choice.rawValue)
                } label: {
                    if choice == stat {
                        Label(choice.displayName, systemImage: "checkmark")
                    } else {
                        Text(choice.displayName)
                    }
                }
            }
        } label: {
            Text(displayed)
                .font(.system(size: 10, weight: slotIndex == 0 ? .semibold : .regular))
                .foregroundColor(stat == .none ? Color.black.opacity(0.4) : Color.black.opacity(0.85))
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: alignTrailing ? .trailing : .leading)
                .contentShape(Rectangle())
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .frame(maxWidth: .infinity, alignment: alignTrailing ? .trailing : .leading)
        .fixedSize(horizontal: false, vertical: true)
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

    // Row selection / border styling now lives in the band-color helpers
    // above (`topBandColor`, `rowMiddleBackground`, `bottomBandColor`,
    // `cardBorderColor`) — those mirror the grid card so list rows and
    // grid cards share visual language.
}

#Preview {
    ListView(
        viewModel: GridViewModel(),
        detailViewModel: DetailViewModel()
    )
    .frame(width: 800, height: 600)
}
