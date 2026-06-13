// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import AppKit
import UniformTypeIdentifiers

/// A details-panel section with a clickable title row that collapses/expands
/// its content. Collapsed shows only the title (+ chevron); expanded shows the
/// whole field.
private struct CollapsibleSection<Content: View>: View {
    let title: String
    @Binding var expanded: Bool
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { expanded.toggle() } label: {
                HStack(spacing: 4) {
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.system(size: 10))
                    Text(title)
                        .font(.caption)
                    Spacer()
                }
                .foregroundColor(.secondary)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expanded { content() }
        }
    }
}

/// Reorders a stack's member list when one row is dropped onto another.
/// Computes the new order from the dragged id and the drop target, then commits
/// once via `onReorder` (which persists it through CreateGroup).
private struct StackReorderDrop: DropDelegate {
    let targetId: String
    @Binding var draggingId: String?
    let memberIds: [String]
    let onReorder: ([String]) -> Void

    func dropUpdated(info: DropInfo) -> DropProposal? { DropProposal(operation: .move) }

    func performDrop(info: DropInfo) -> Bool {
        defer { draggingId = nil }
        guard let dragging = draggingId, dragging != targetId,
              let from = memberIds.firstIndex(of: dragging),
              let to = memberIds.firstIndex(of: targetId) else { return false }
        var ids = memberIds
        let moved = ids.remove(at: from)
        ids.insert(moved, at: to)
        onReorder(ids)
        return true
    }
}

struct DetailView: View {
    @ObservedObject var viewModel: DetailViewModel
    @ObservedObject var gridViewModel: GridViewModel
    let onCollapse: () -> Void
    /// When `true`, the proxy section rows are clickable: a click swaps
    /// the detail-view (loupe) player to the selected proxy. In Grid
    /// and List mode the section is read-only because inline playback
    /// always auto-selects the lowest-res proxy.
    var isLoupeMode: Bool = false
    /// Opens the LocationPicker sheet for the given video IDs. `initial`
    /// is the existing (lat, lon) when one is already set, or nil.
    var onEditLocation: (_ videoIds: [String], _ initial: (Double, Double)?) -> Void = { _, _ in }
    /// Opens the CaptureDate sheet for the given video IDs. `initialTs` is
    /// the existing Unix-ms capture timestamp when one is set, or nil.
    var onEditCaptureDate: (_ videoIds: [String], _ initialTs: Int64?) -> Void = { _, _ in }
    /// Switches to map mode focused on (lat, lon), highlighting this video and
    /// any others captured at the same spot.
    var onShowOnMap: (_ latitude: Double, _ longitude: Double) -> Void = { _, _ in }

    // Per-section collapse state for the panel (persists for the session).
    @State private var detailsExpanded = true
    @State private var exifExpanded = true
    @State private var notesExpanded = true
    @State private var keywordsExpanded = true
    @State private var stackExpanded = true
    @State private var proxiesExpanded = true
    /// Id of the stack member currently being dragged to reorder, or nil.
    @State private var draggingMemberId: String?

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Button(action: onCollapse) {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Hide details panel (Tab)")

                Text("DETAILS")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.top, 12)
            .padding(.bottom, 8)

            Divider()

            // Content
            if gridViewModel.selectedVideoId == nil {
                // No primary selection — nothing selected, or the selected video
                // was just filtered out of the grid. Show the placeholder rather
                // than the (now-hidden) video's leftover metadata, which the
                // detail view-model still holds until the next selection.
                placeholderContent
            } else if let metadata = viewModel.metadata {
                ScrollView {
                    ScrollViewReader { svProxy in
                        metadataContent(metadata)
                            .padding(16)
                            // Top-bar "Playing proxy" click scrolls here (#13b).
                            .onChange(of: viewModel.scrollToProxiesToken) { _, _ in
                                proxiesExpanded = true
                                withAnimation { svProxy.scrollTo("proxies-section", anchor: .top) }
                            }
                    }
                }
            } else if viewModel.isLoading {
                Spacer()
                ProgressView()
                Spacer()
            } else {
                placeholderContent
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.controlBackgroundColor))
    }

    @ViewBuilder
    private func metadataContent(_ metadata: VideoMetadata) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            if let error = viewModel.error {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                    Text(error).font(.caption).lineLimit(2)
                    Spacer()
                }
                .padding(8)
                .background(Color(.controlBackgroundColor))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(.separatorColor)))
                .cornerRadius(4)
            }

            // Filename — drag it out to an external editor / file manager,
            // with an arrow to open it in the system's default player.
            HStack(alignment: .top, spacing: 6) {
                Text(metadata.filename)
                    .font(.headline)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .onDrag { DragExport.provider(for: metadata.path) }
                Button {
                    gridViewModel.openVideoInExternal(path: metadata.path)
                } label: {
                    Image(systemName: "arrow.up.forward.app")
                }
                .buttonStyle(.borderless)
                .help("Open this video in your system's default video player")
            }

            Divider()

            // Technical metadata
            CollapsibleSection(title: "Video Details", expanded: $detailsExpanded) {
            VStack(alignment: .leading, spacing: 12) {
                MetadataItemView(label: "Resolution", value: metadata.resolution)
                MetadataItemView(label: "Duration", value: metadata.durationFormatted)
                MetadataItemView(label: "FPS", value: String(format: "%.2f", metadata.fps))
                if let frames = metadata.frameCountFormatted {
                    MetadataItemView(label: "Frames", value: frames)
                }
                MetadataItemView(label: "Video Codec", value: metadata.codecVideo.isEmpty ? "—" : metadata.codecVideo)
                if !metadata.codecAudio.isEmpty {
                    MetadataItemView(label: "Audio Codec", value: metadata.codecAudio)
                }
                MetadataItemView(label: "Bitrate", value: metadata.bitrateFormatted)
                MetadataItemView(label: "Size", value: metadata.sizeFormatted)

                if !metadata.colorSpace.isEmpty {
                    MetadataItemView(label: "Color Space", value: metadata.colorSpace)
                }
                if metadata.hdr {
                    MetadataItemView(label: "HDR", value: "Yes")
                }
                switch metadata.fullResolution {
                case .full:
                    MetadataItemView(label: "Resolution Status", value: "Full resolution")
                case .notFull:
                    MetadataItemView(label: "Resolution Status", value: "Not full resolution")
                case .unspecified:
                    EmptyView()  // no row when unknown
                }
            }
            }

            // EXIF section
            let hasGps = metadata.gpsLat != 0 || metadata.gpsLon != 0
            let hasShotEXIF = metadata.iso > 0
                || metadata.aperture > 0
                || metadata.exposureTimeS > 0
                || metadata.focalLengthMm > 0
                || !metadata.exposureMode.isEmpty
                || !metadata.exposureProgram.isEmpty
                || !metadata.whiteBalance.isEmpty
            let hasExif = !metadata.cameraModel.isEmpty
                || !metadata.lensModel.isEmpty
                || metadata.creationDate > 0
                || hasGps
                || hasShotEXIF
            if hasExif {
                Divider()
                CollapsibleSection(title: "EXIF", expanded: $exifExpanded) {
                VStack(alignment: .leading, spacing: 12) {
                    if !metadata.cameraModel.isEmpty {
                        CameraMetadataRow(
                            internalName: metadata.cameraModel,
                            displayName: metadata.cameraDisplayName
                        )
                    }
                    if !metadata.lensModel.isEmpty {
                        MetadataItemView(label: "Lens", value: metadata.lensModel)
                    }
                    if metadata.focalLengthMm > 0 {
                        MetadataItemView(
                            label: "Focal Length",
                            value: String(format: "%.0f mm", metadata.focalLengthMm))
                    }
                    if metadata.aperture > 0 {
                        MetadataItemView(
                            label: "Aperture",
                            value: String(format: "f/%.1f", metadata.aperture))
                    }
                    if metadata.exposureTimeS > 0 {
                        MetadataItemView(
                            label: "Exposure",
                            value: formatExposureTime(metadata.exposureTimeS))
                    }
                    if metadata.iso > 0 {
                        MetadataItemView(label: "ISO", value: String(metadata.iso))
                    }
                    if !metadata.exposureProgram.isEmpty {
                        MetadataItemView(label: "Exposure Program", value: metadata.exposureProgram)
                    }
                    if !metadata.exposureMode.isEmpty {
                        MetadataItemView(label: "Exposure Mode", value: metadata.exposureMode)
                    }
                    if !metadata.whiteBalance.isEmpty {
                        MetadataItemView(label: "White Balance", value: metadata.whiteBalance)
                    }
                    if metadata.creationDate > 0 {
                        MetadataItemView(label: "Captured", value: metadata.creationDateFormatted)
                    }
                    if hasGps {
                        // Prefer a user-defined name when one is registered
                        // within 250 m of these coordinates; the raw
                        // lat/long collapses behind a disclosure twirl-down
                        // so the named view stays uncluttered.
                        let matchedName = gridViewModel.nameForLocation(
                            latitude: metadata.gpsLat, longitude: metadata.gpsLon)
                        if let name = matchedName {
                            NamedGpsRow(
                                name: name.name,
                                latitude: metadata.gpsLat,
                                longitude: metadata.gpsLon
                            )
                        } else {
                            MetadataItemView(
                                label: "GPS",
                                value: String(format: "%.4f, %.4f",
                                              metadata.gpsLat, metadata.gpsLon)
                            )
                        }
                    }
                }
                }
            }

            // "Set / Edit location" button — surfaced even when no EXIF
            // exists so the user can geotag a video that lacks GPS.
            Button {
                let selected = gridViewModel.selectedVideoIds
                let targets: [String] = (selected.count > 1 && selected.contains(metadata.id))
                    ? Array(selected) : [metadata.id]
                let initial: (Double, Double)? = hasGps ? (metadata.gpsLat, metadata.gpsLon) : nil
                onEditLocation(targets, initial)
            } label: {
                HStack {
                    Image(systemName: "mappin.and.ellipse")
                    Text(hasGps ? "Change location…" : "Set location…")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .help(hasGps
                  ? "Replace the existing GPS coordinate via an interactive map."
                  : "Open a map and pin where this video was captured. Applies to every video currently selected.")

            // "Remove location" button — only shown when a GPS coordinate
            // is already set. Lets the user undo a mis-tagged location.
            if hasGps {
                Button {
                    let selected = gridViewModel.selectedVideoIds
                    let targets: [String] = (selected.count > 1 && selected.contains(metadata.id))
                        ? Array(selected) : [metadata.id]
                    gridViewModel.clearVideoLocations(videoIds: targets) {
                        if let id = viewModel.metadata?.id {
                            viewModel.loadMetadata(videoId: id)
                        }
                    }
                } label: {
                    HStack {
                        Image(systemName: "mappin.slash")
                        Text("Remove location")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .help("Clear the GPS coordinate from this video. Applies to every video currently selected.")
            }

            // "Show on Map" — only when this video has a GPS coordinate.
            // Switches to map mode focused on the spot, with the right-side list
            // highlighting this video plus any others captured there.
            if hasGps {
                Button {
                    onShowOnMap(metadata.gpsLat, metadata.gpsLon)
                } label: {
                    HStack {
                        Image(systemName: "map")
                        Text("Show on Map")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .help("Switch to the map, zoomed in on where this video was recorded, with any videos captured there listed alongside.")
            }

            // "Set / Change capture date" button — works on the
            // multi-selection just like the location button.
            let hasDate = metadata.creationDate > 0
            Button {
                let selected = gridViewModel.selectedVideoIds
                let targets: [String] = (selected.count > 1 && selected.contains(metadata.id))
                    ? Array(selected) : [metadata.id]
                let initialTs: Int64? = hasDate ? metadata.creationDate : nil
                onEditCaptureDate(targets, initialTs)
            } label: {
                HStack {
                    Image(systemName: "calendar")
                    Text(hasDate ? "Change capture date…" : "Set capture date…")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .help(hasDate
                  ? "Replace this video's recorded date and time with a calendar pick."
                  : "Pick the day (and optionally time) this video was captured. Applies to every video currently selected.")

            Divider()

            // Notes
            CollapsibleSection(title: "Notes", expanded: $notesExpanded) {
                TextEditor(text: Binding(
                    get: { viewModel.notes },
                    set: { viewModel.updateNotes($0) }
                ))
                .font(.caption)
                .frame(height: 80)
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(.separatorColor)))
                .cornerRadius(4)
                .help("Free-form notes about this video. Saved automatically and searchable from the top-bar search field.")
            }

            Divider()

            // Keywords
            CollapsibleSection(title: "Keywords", expanded: $keywordsExpanded) {
            KeywordsSection(
                primaryVideoTags: metadata.tags,
                allTags: gridViewModel.tags,
                selectedVideoIds: gridViewModel.selectedVideoIds.isEmpty
                    ? [metadata.id]
                    : gridViewModel.selectedVideoIds,
                activeFilterTagId: gridViewModel.filterTagId,
                onApplyKeyword: { name, ids in
                    gridViewModel.applyKeyword(name, to: ids) {
                        if let id = viewModel.metadata?.id {
                            viewModel.loadMetadata(videoId: id)
                        }
                    }
                },
                onRemoveKeywordByName: { name, ids in
                    if let tagId = gridViewModel.tags.first(where: { $0.name == name })?.id {
                        gridViewModel.removeKeyword(tagId: tagId, from: ids) {
                            if let id = viewModel.metadata?.id {
                                viewModel.loadMetadata(videoId: id)
                            }
                        }
                    }
                },
                onFilterByTag: { gridViewModel.setTagFilter($0) }
            )
            }

            Divider()

            // Collections
            CollectionsSection(
                videoCollectionIds: metadata.collections,
                allCollections: gridViewModel.collections,
                selectedVideoIds: gridViewModel.selectedVideoIds.isEmpty
                    ? [metadata.id]
                    : gridViewModel.selectedVideoIds,
                selectedCollectionId: gridViewModel.selectedCollectionId,
                onAddToCollection: { colId, ids in
                    gridViewModel.addToCollection(videoIds: ids, collectionId: colId)
                },
                onRemoveFromCollection: { colId, ids in
                    gridViewModel.removeFromCollection(videoIds: ids, collectionId: colId)
                },
                onFilterByCollection: { gridViewModel.setCollectionFilter($0) }
            )

            Divider()

            // Stack / group section
            if viewModel.groupMembers.count > 1 {
                Divider()
                HStack {
                    Button { stackExpanded.toggle() } label: {
                        HStack(spacing: 4) {
                            Image(systemName: stackExpanded ? "chevron.down" : "chevron.right")
                                .font(.system(size: 10))
                            Text("Stack (\(viewModel.groupMembers.count))")
                                .font(.caption)
                        }
                        .foregroundColor(.secondary)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Spacer()
                    Button("Ungroup this") {
                        viewModel.ungroupCurrent { groupId in
                            // Refresh the grid's expanded-stack caches +
                            // representative `groupId/groupSize` data so
                            // the card stops claiming to be a stack
                            // member. Without this hop, the right panel
                            // updates but the grid keeps treating it as
                            // expandable.
                            gridViewModel.refreshAfterStackChange(groupId: groupId)
                        }
                    }
                    .buttonStyle(.borderless)
                    .controlSize(.small)
                    .help("Remove this video from the stack. The other members stay grouped.")
                }
                if stackExpanded {
                Text("Drag a row to reorder. Double-click in the grid opens the preferred variant. Click ⭐ to change preferred.")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                VStack(spacing: 0) {
                    ForEach(Array(viewModel.groupMembers.enumerated()), id: \.element.id) { idx, member in
                        if idx > 0 { Divider() }
                        HStack(spacing: 6) {
                            Image(systemName: "line.3.horizontal")
                                .font(.system(size: 10))
                                .foregroundColor(.secondary)
                                .help("Drag to reorder this variant within the stack")
                            VStack(alignment: .leading, spacing: 1) {
                                Text(member.filename)
                                    .font(.system(size: 11))
                                    .lineLimit(1)
                                Text("\(member.height > 0 ? "\(member.height)p" : "?") • \(member.codecVideo) • \(member.sizeFormatted)")
                                    .font(.system(size: 9))
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            let isPreferred = member.id == viewModel.groupPreferredId
                            Button {
                                viewModel.setGroupPreferred(videoId: member.id)
                            } label: {
                                Image(systemName: isPreferred ? "star.fill" : "star")
                                    .foregroundColor(isPreferred ? .accentColor : .secondary)
                            }
                            .buttonStyle(.plain)
                            .help(isPreferred
                                  ? "This is the preferred variant. It's the thumbnail shown in the grid and the file opened on double-click."
                                  : "Make this the preferred variant of the stack. The grid thumbnail and double-click action will switch to this file.")

                            Button {
                                let url = URL(fileURLWithPath: member.path)
                                if FileManager.default.fileExists(atPath: url.path) {
                                    NSWorkspace.shared.open(url)
                                }
                            } label: {
                                Image(systemName: "play.fill")
                            }
                            .buttonStyle(.plain)
                            .help("Open \(member.filename) in your system's default video player.")
                        }
                        .padding(.vertical, 4)
                        .padding(.horizontal, 6)
                        .opacity(draggingMemberId == member.id ? 0.4 : 1.0)
                        .contentShape(Rectangle())
                        // Drag a row onto another to reorder the stack. Persisted
                        // via CreateGroup (a pure reorder server-side).
                        .onDrag {
                            draggingMemberId = member.id
                            return NSItemProvider(object: member.id as NSString)
                        }
                        .onDrop(of: [UTType.text], delegate: StackReorderDrop(
                            targetId: member.id,
                            draggingId: $draggingMemberId,
                            memberIds: viewModel.groupMembers.map { $0.id },
                            onReorder: { ids in
                                viewModel.reorderGroupMembers(orderedIds: ids) { gid in
                                    gridViewModel.refreshAfterStackChange(groupId: gid)
                                }
                            }
                        ))
                    }
                }
                .background(Color(.windowBackgroundColor).opacity(0.5))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(.separatorColor)))
                .cornerRadius(4)
                }
            }

            // Proxies section — shown whenever the catalog has any
            // proxies attached to the current video, OR while a proxy
            // is actively being generated for it.
            //
            // In Loupe mode each row is clickable: it tells the
            // detail-view player to swap to that proxy. In Grid/List
            // mode rows are read-only because inline playback always
            // picks the smallest proxy automatically.
            let activeProxyCreation: GridViewModel.ProxyCreationState? = {
                guard let vid = viewModel.currentSummary?.id else { return nil }
                return gridViewModel.activeProxyCreations[vid]
            }()
            if !viewModel.proxies.isEmpty || activeProxyCreation != nil {
                Divider()
                Button { proxiesExpanded.toggle() } label: {
                    HStack(spacing: 4) {
                        Image(systemName: proxiesExpanded ? "chevron.down" : "chevron.right")
                            .font(.system(size: 10))
                        Text(viewModel.proxies.isEmpty ? "Proxies" : "Proxies (\(viewModel.proxies.count))")
                            .font(.caption)
                        Spacer()
                    }
                    .foregroundColor(.secondary)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .id("proxies-section")
                if proxiesExpanded {
                if let state = activeProxyCreation {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(state.message.isEmpty ? "Generating proxy…" : state.message)
                                .font(.system(size: 10))
                                .foregroundColor(.primary)
                            Spacer()
                            if state.progressPercent > 0 {
                                Text("\(Int(state.progressPercent))%")
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundColor(.secondary)
                            }
                        }
                        if state.progressPercent > 0 {
                            ProgressView(value: state.progressPercent, total: 100)
                                .progressViewStyle(.linear)
                                .frame(maxWidth: .infinity)
                        } else {
                            ProgressView()
                                .progressViewStyle(.linear)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .padding(8)
                    .background(Color.accentColor.opacity(0.12))
                    .cornerRadius(4)
                }
                if !viewModel.proxies.isEmpty {
                if let summary = viewModel.currentSummary, !summary.playableNatively {
                    // Surface the "master too large to play here"
                    // notice immediately above the proxy list — these
                    // proxies are precisely how the user can still
                    // play the clip without launching an external
                    // editor.
                    Text(isLoupeMode
                         ? "The original is above the inline-playback ceiling. Pick a proxy below to play it here."
                         : "The original is above the inline-playback ceiling. Switch to Detail view to play a proxy in-app.")
                        .font(.system(size: 10))
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.accentColor.opacity(0.12))
                        .cornerRadius(4)
                }
                VStack(spacing: 0) {
                    ForEach(Array(viewModel.proxies.enumerated()), id: \.element.id) { idx, proxy in
                        if idx > 0 { Divider() }
                        // Highlight the proxy that's actually playing (the
                        // auto-chosen one by default), falling back to the
                        // user's explicit pick when nothing is playing yet.
                        let isSelected = proxy.id == (viewModel.playingProxyId ?? viewModel.selectedProxyId)
                        HStack(spacing: 6) {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(proxy.filename)
                                    .font(.system(size: 11))
                                    .lineLimit(1)
                                Text(proxyDetailLine(proxy))
                                    .font(.system(size: 9))
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            if isLoupeMode {
                                Button {
                                    // Toggle: clicking the currently-
                                    // selected proxy reverts to the
                                    // master.
                                    viewModel.setSelectedProxy(isSelected ? nil : proxy.id)
                                } label: {
                                    Image(systemName: isSelected ? "play.circle.fill" : "play.circle")
                                        .foregroundColor(isSelected ? .accentColor : .secondary)
                                }
                                .buttonStyle(.plain)
                                .help(isSelected
                                      ? "Currently playing this proxy. Click to revert to the original."
                                      : "Play this proxy in the detail view instead of the original.")
                            }
                            // Open this proxy in the system's default player.
                            Button {
                                gridViewModel.openVideoInExternal(path: proxy.path)
                            } label: {
                                Image(systemName: "arrow.up.forward.app")
                                    .foregroundColor(.secondary)
                            }
                            .buttonStyle(.plain)
                            .help("Open this proxy in your system's default video player")
                            // Break-link button — always visible so
                            // the user can correct false auto-detections
                            // regardless of view mode.
                            Button {
                                viewModel.breakProxyLink(proxyId: proxy.id, onChanged: {
                                    gridViewModel.loadVideos()
                                    gridViewModel.loadLibraryLocations()
                                })
                            } label: {
                                Image(systemName: "link.badge.minus")
                                    .foregroundColor(.secondary)
                            }
                            .buttonStyle(.plain)
                            .help("Break this proxy link. The proxy file itself stays in the catalog; only the relationship with this master is removed.")
                        }
                        .padding(.vertical, 4)
                        .padding(.horizontal, 6)
                        .background(isSelected ? Color.accentColor.opacity(0.12) : .clear)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            guard isLoupeMode else { return }
                            viewModel.setSelectedProxy(isSelected ? nil : proxy.id)
                        }
                        // Drag a proxy row out to an external editor / file
                        // manager, dropping that proxy's file.
                        .onDrag { DragExport.provider(for: proxy.path) }
                    }
                }
                .background(Color(.windowBackgroundColor).opacity(0.5))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(.separatorColor)))
                .cornerRadius(4)
                } // if !viewModel.proxies.isEmpty
            }

            // "Add selected as proxy" button — surfaces when the user
            // has multi-selected exactly one OTHER video alongside the
            // currently-inspected master. Click master, Cmd-click
            // candidate, click button: discoverable without a separate
            // picker dialog.
            if let masterId = viewModel.currentSummary?.id {
                let secondarySelections = gridViewModel.selectedVideoIds.filter { $0 != masterId }
                if gridViewModel.selectedVideoIds.count == 2,
                   let candidateId = secondarySelections.first {
                    Button {
                        viewModel.forceProxyLink(proxyId: candidateId, onChanged: {
                            gridViewModel.loadVideos()
                            gridViewModel.loadLibraryLocations()
                        })
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "link.badge.plus")
                            Text("Add selected video as proxy")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .help("Manually link the second selected video to this master as a proxy. Use this when auto-detection missed a valid proxy.")
                }
                }
            }

            // No proxy yet — offer to create one. Loupe (detail) mode only per
            // spec, and never for clips that are themselves proxies. Opens the
            // existing resolution picker, which asks for the target size before
            // encoding and adds the result to the catalog.
            if isLoupeMode,
               viewModel.proxies.isEmpty,
               activeProxyCreation == nil,
               let summary = viewModel.currentSummary,
               !summary.isProxy {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Proxies (0)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Button {
                        gridViewModel.requestCreateProxy(videoId: summary.id)
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "film.stack")
                            Text("Create proxy…")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .help("Generate a lower-resolution proxy for this video and add it to the catalog. You'll choose the size next.")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

        }
    }

    @ViewBuilder
    private var placeholderContent: some View {
        // When a smart collection is selected but no card is, explain how it
        // gathers videos rather than just prompting for a selection.
        if let id = gridViewModel.selectedCollectionId,
           let col = gridViewModel.collections.first(where: { $0.id == id }), col.isSmart {
            smartCollectionCriteriaView(col)
        } else {
            VStack(spacing: 12) {
                Spacer()
                Image(systemName: "square.and.pencil")
                    .font(.system(size: 32))
                    .foregroundColor(.secondary)
                Text("Select a video to view details")
                    .font(.body)
                    .foregroundColor(.secondary)
                Spacer()
            }
            .frame(maxWidth: .infinity)
        }
    }

    /// The selection rules of a smart collection, shown in place of the
    /// "select a video" placeholder when one is the active view.
    private func smartCollectionCriteriaView(_ col: Collection) -> some View {
        let criteria = gridViewModel.smartCollectionCriteria(col)
        return ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(col.name).font(.headline)
                Text("Smart collection — videos are gathered automatically by these rules:")
                    .font(.caption)
                    .foregroundColor(.secondary)
                if criteria.isEmpty {
                    Text("No rules set — this collection matches every video.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .padding(.top, 4)
                } else {
                    ForEach(criteria.indices, id: \.self) { i in
                        HStack(alignment: .top, spacing: 8) {
                            Text(criteria[i].label)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .frame(width: 72, alignment: .leading)
                            Text(criteria[i].value)
                                .font(.callout)
                            Spacer()
                        }
                    }
                    .padding(.top, 2)
                }
                Spacer()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
    }

    /// "<height>p • <size> • auto-detected" subtitle for a proxy row.
    private func proxyDetailLine(_ proxy: VideoRepository.ProxyInfo) -> String {
        let res = proxy.height > 0 ? "\(proxy.height)p" : "?"
        let size = ByteCountFormatter.string(fromByteCount: proxy.sizeBytes, countStyle: .binary)
        return proxy.autoDetected ? "\(res) • \(size) • auto-detected" : "\(res) • \(size)"
    }
}

struct MetadataItemView: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
            Text(value)
                .font(.body)
        }
        .help(tooltipFor(label: label, value: value))
    }

    /// Friendly explanation of each metadata field, surfaced on hover.
    private func tooltipFor(label: String, value: String) -> String {
        switch label {
        case "Resolution":  return "Image dimensions in pixels. Larger numbers = sharper picture."
        case "Duration":    return "Total playback length of this clip."
        case "FPS":         return "Frames per second — higher values mean smoother motion."
        case "Video Codec": return "Compression format used to encode the video stream (e.g. h264, hevc, prores)."
        case "Audio Codec": return "Compression format used for the audio track."
        case "Bitrate":     return "Average data rate. Higher generally means better quality at a given resolution."
        case "Size":        return "File size on disk."
        case "Color Space": return "Color encoding standard (e.g. bt709 for HD, bt2020 for 4K HDR)."
        case "HDR":         return "High Dynamic Range content with extended brightness and color range."
        case "Resolution Status":
            return "\"Full\" when the recorded dimensions match a known native sensor mode for this camera (e.g. a timelapse rendered at full sensor resolution). \"Not full\" when the camera is known but the recorded size doesn't match either a native mode or a common video standard."
        case "Camera":      return "Camera model recorded in the file's metadata (when available)."
        case "Lens":        return "Lens model recorded in the file's metadata."
        case "Captured":    return "Original recording date (year-month-day) from the file's metadata."
        case "GPS":         return "Latitude and longitude where the video was recorded (when present)."
        default:            return "\(label): \(value)"
        }
    }
}

/// Camera-model row that defaults to the marketing-friendly name (e.g.
/// "Sony a7R III") and reveals a small ⓘ affordance when the core has a
/// mapping for the internal name. Clicking the icon flips the displayed
/// string to the internal model code (e.g. "SONY ILCE-7RM3") and back.
///
/// When the marketing name equals the internal name (no mapping known),
/// the row collapses to a plain `MetadataItemView` — there's no point
/// offering a toggle that would do nothing.
struct CameraMetadataRow: View {
    let internalName: String
    let displayName: String
    @State private var showInternal: Bool = false

    private var hasMarketing: Bool {
        !displayName.isEmpty
            && displayName != internalName
    }

    var body: some View {
        if !hasMarketing {
            MetadataItemView(label: "Camera", value: internalName)
        } else {
            VStack(alignment: .leading, spacing: 2) {
                Text("Camera")
                    .font(.caption)
                    .foregroundColor(.secondary)
                HStack(spacing: 6) {
                    Text(showInternal ? internalName : displayName)
                        .font(.body)
                    Button {
                        showInternal.toggle()
                    } label: {
                        Image(systemName: showInternal
                              ? "info.circle.fill"
                              : "info.circle")
                            .foregroundColor(.secondary)
                            .font(.system(size: 13))
                    }
                    .buttonStyle(.plain)
                    .help(showInternal
                          ? "Showing the internal model name from the file's metadata. " +
                            "Click to switch back to the marketing name."
                          : "Showing the marketing name. Click to reveal the internal " +
                            "model code recorded in the file's metadata (\(internalName)).")
                }
            }
        }
    }
}

/// GPS field with a user-defined name resolved from the catalog's
/// named-locations table. Shows the name as the primary identity; the raw
/// lat/long sits inside a DisclosureGroup so it's accessible but not
/// visually noisy. Mirrors the LocationPickerView's "name-first" treatment.
struct NamedGpsRow: View {
    let name: String
    let latitude: Double
    let longitude: Double
    @State private var showCoords: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Location")
                .font(.caption)
                .foregroundColor(.secondary)
            HStack(spacing: 4) {
                Image(systemName: "tag.fill")
                    .font(.caption2)
                    .foregroundColor(.accentColor)
                Text(name)
                    .font(.body)
            }
            DisclosureGroup(
                isExpanded: $showCoords,
                content: {
                    Text(String(format: "%.6f, %.6f", latitude, longitude))
                        .font(.caption.monospaced())
                        .foregroundColor(.secondary)
                },
                label: {
                    Text(showCoords ? "Hide coordinates" : "Show coordinates")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            )
        }
        .help("Named place at \(latitude), \(longitude). Twirl down to see exact coordinates.")
    }
}

struct FlowLayout: View {
    let items: [String]
    var spacing: CGFloat = 8

    var body: some View {
        HStack(spacing: spacing) {
            ForEach(items, id: \.self) { item in
                Text(item)
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.accentColor.opacity(0.2))
                    .cornerRadius(4)
            }
        }
    }
}

/// Right-panel "Keywords" section.
///
///   * Text field at top — type a new keyword and press Return to apply it
///     to every selected video.
///   * List of known keywords with usage counts. Each row has a `>` chevron
///     that sets the grid filter to that keyword, and a clickable name that
///     toggles the keyword on the current selection.
struct KeywordsSection: View {
    let primaryVideoTags: [String]
    let allTags: [Tag]
    let selectedVideoIds: [String]
    let activeFilterTagId: String
    let onApplyKeyword: (_ name: String, _ videoIds: [String]) -> Void
    let onRemoveKeywordByName: (_ name: String, _ videoIds: [String]) -> Void
    let onFilterByTag: (_ tagId: String) -> Void

    @State private var newKeyword: String = ""

    private var primaryTagSet: Set<String> { Set(primaryVideoTags) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Keywords")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()
                if !activeFilterTagId.isEmpty {
                    Button("Clear filter") {
                        onFilterByTag("")
                    }
                    .buttonStyle(.borderless)
                    .controlSize(.small)
                    .help("Stop filtering the grid by the currently selected keyword.")
                }
            }
            Text("Applies to \(selectedVideoIds.count) selected video\(selectedVideoIds.count == 1 ? "" : "s")")
                .font(.system(size: 10))
                .foregroundColor(.secondary)

            TextField("Add a keyword…", text: $newKeyword, onCommit: {
                let trimmed = newKeyword.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    onApplyKeyword(trimmed, selectedVideoIds)
                    newKeyword = ""
                }
            })
            .textFieldStyle(.roundedBorder)
            .help("Type a new keyword and press Return to apply it to all selected videos. If the keyword doesn't exist yet, it will be created.")

            if allTags.isEmpty {
                Text("No keywords yet. Type one above and press Return.")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
            } else {
                VStack(spacing: 0) {
                    ForEach(allTags) { tag in
                        keywordRow(tag)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func keywordRow(_ tag: Tag) -> some View {
        let isOnVideo = primaryTagSet.contains(tag.name)
        let isActiveFilter = tag.id == activeFilterTagId

        HStack(spacing: 4) {
            // ">" filter button on the left
            Button {
                onFilterByTag(isActiveFilter ? "" : tag.id)
            } label: {
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(isActiveFilter ? .accentColor : .secondary)
                    .frame(width: 16, height: 16)
            }
            .buttonStyle(.plain)
            .help(isActiveFilter
                  ? "Currently filtering the grid by '\(tag.name)'. Click again to clear."
                  : "Filter the grid to show only videos tagged '\(tag.name)'.")

            // Click name to toggle on selection
            Button {
                if selectedVideoIds.isEmpty { return }
                if isOnVideo {
                    onRemoveKeywordByName(tag.name, selectedVideoIds)
                } else {
                    onApplyKeyword(tag.name, selectedVideoIds)
                }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: isOnVideo ? "checkmark" : "")
                        .font(.system(size: 10))
                        .foregroundColor(.accentColor)
                        .frame(width: 12, alignment: .leading)
                    Text(tag.name)
                        .font(.system(size: 11))
                        .foregroundColor(isActiveFilter ? .accentColor : .primary)
                    Spacer()
                    Text("(\(tag.videoCount))")
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(selectedVideoIds.isEmpty)
            .help(rowHelp(tag: tag, isOnVideo: isOnVideo))
        }
        .padding(.vertical, 2)
    }

    private func rowHelp(tag: Tag, isOnVideo: Bool) -> String {
        if selectedVideoIds.isEmpty {
            let n = tag.videoCount
            return "'\(tag.name)' is used on \(n) video\(n == 1 ? "" : "s"). Select a video to add or remove this keyword."
        }
        let n = selectedVideoIds.count
        let plural = n == 1 ? "" : "s"
        if isOnVideo {
            return "'\(tag.name)' is on the current video. Click to remove it from the \(n) selected video\(plural)."
        }
        return "Click to apply '\(tag.name)' to the \(n) selected video\(plural)."
    }
}

/// Format an EXIF exposure time. Sub-second exposures render as "1/Nth"
/// with N rounded to the nearest standard shutter step, matching how a
/// photographer reads them. Anything ≥ 1 s renders as "X.X s".
func formatExposureTime(_ seconds: Double) -> String {
    if seconds <= 0 { return "" }
    if seconds >= 1.0 {
        return String(format: "%.1f s", seconds)
    }
    let denom = Int((1.0 / seconds).rounded())
    return "1/\(denom)"
}

// MARK: - Collections section

/// Right-panel "Collections" block — shows all manual collections with a
/// check badge on ones the primary video belongs to. Clicking a checked row
/// removes the video; clicking an unchecked row adds it. A ">" chevron on
/// the left scopes the grid to that collection.
struct CollectionsSection: View {
    let videoCollectionIds: [String]
    let allCollections: [Collection]
    let selectedVideoIds: [String]
    let selectedCollectionId: String?
    let onAddToCollection: (_ collectionId: String, _ videoIds: [String]) -> Void
    let onRemoveFromCollection: (_ collectionId: String, _ videoIds: [String]) -> Void
    let onFilterByCollection: (String?) -> Void

    private var videoColSet: Set<String> { Set(videoCollectionIds) }
    private var manualCollections: [Collection] { allCollections.filter { !$0.isSmart } }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Collections")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()
                if selectedCollectionId != nil {
                    Button("Clear filter") { onFilterByCollection(nil) }
                        .font(.caption)
                        .help("Stop filtering the grid by the current collection.")
                }
            }

            if manualCollections.isEmpty {
                Text("No collections yet. Use the + in the left panel.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            } else {
                ForEach(manualCollections) { col in
                    let isInCol = videoColSet.contains(col.id)
                    let isActive = col.id == selectedCollectionId
                    HStack(spacing: 4) {
                        // ">" chevron to scope the grid to this collection.
                        Button {
                            onFilterByCollection(isActive ? nil : col.id)
                        } label: {
                            Image(systemName: "chevron.right")
                                .font(.system(size: 10))
                                .foregroundColor(isActive ? .accentColor : .secondary)
                                .frame(width: 14)
                        }
                        .buttonStyle(.plain)
                        .help(isActive
                            ? "Currently filtering by '\(col.name)'. Click again to clear."
                            : "Filter the grid to show only videos in '\(col.name)'.")

                        Button {
                            if isInCol {
                                onRemoveFromCollection(col.id, selectedVideoIds)
                            } else {
                                onAddToCollection(col.id, selectedVideoIds)
                            }
                        } label: {
                            HStack(spacing: 4) {
                                if isInCol {
                                    Image(systemName: "checkmark")
                                        .font(.system(size: 10, weight: .semibold))
                                        .foregroundColor(.accentColor)
                                }
                                Text(col.name)
                                    .font(.system(size: 12))
                                    .foregroundColor(isInCol ? .accentColor : .primary)
                                    .lineLimit(1)
                                Spacer(minLength: 4)
                                Text("(\(col.videoCount))")
                                    .font(.system(size: 10))
                                    .foregroundColor(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                        .help(isInCol
                            ? "Remove selected video(s) from '\(col.name)'."
                            : "Add selected video(s) to '\(col.name)'.")
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
