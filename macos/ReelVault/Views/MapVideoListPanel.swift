// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI

/// Map-view right panel: the videos at the location(s) the user has selected on
/// the map, rendered with the same `VideoCardView` used by the grid.
/// Right-clicking a card offers "Open in Grid / List / Detail" — which switches
/// the main view to that mode focused on the video (the caller wires the actual
/// navigation). Stands in for the metadata inspector (`DetailView`) while the
/// map is the active top-level view.
struct MapVideoListPanel: View {
    @ObservedObject var gridViewModel: GridViewModel
    /// Right-click a card → open the location picker on it (framed on its spot).
    /// Declared right after gridViewModel so the call-site argument order
    /// (memberwise init) matches.
    let onEditLocation: (_ videoIds: [String], _ initial: (Double, Double)?) -> Void
    let videos: [VideoSummary]
    /// Show a progress indicator in place of the empty placeholder: a pin was
    /// clicked but its videos are still being resolved (e.g. the filtered
    /// location load is still in flight). Keeps stale/empty content off-screen
    /// while the selection catches up.
    let loading: Bool
    /// Name of the selected location when it resolves to a named place — shown
    /// in the header instead of the generic "here". Nil for an unnamed spot.
    let locationName: String?
    /// Minimum card width — drives the adaptive column count so widening the
    /// panel adds columns (like the grid) instead of enlarging cards.
    let thumbnailMinWidth: CGFloat
    let currentVideoId: String?
    let onCardClick: (VideoSummary) -> Void
    let onOpenInGrid: (VideoSummary) -> Void
    let onOpenInList: (VideoSummary) -> Void
    let onOpenInDetail: (VideoSummary) -> Void
    /// Right-click the header → open every video at this location in the
    /// grid / list view (a location filter takes the user there).
    let onOpenAllInGrid: () -> Void
    let onOpenAllInList: () -> Void
    let onCollapse: () -> Void

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: thumbnailMinWidth), spacing: 8)]
    }

    /// "N videos at <name>" for a named place, "N videos here" otherwise; the
    /// place name (or a generic prompt) when nothing's resolved yet.
    private var headerTitle: String {
        if !videos.isEmpty {
            let n = videos.count
            let noun = "video\(n == 1 ? "" : "s")"
            if let name = locationName { return "\(n) \(noun) at \(name)" }
            return "\(n) \(noun) here"
        }
        if loading { return "Loading…" }
        return locationName ?? "Selected location"
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header — title + collapse chevron, matching the details panel.
            HStack {
                Text(headerTitle)
                    .font(.headline)
                    // Right-click to open this location's videos in grid / list.
                    .contextMenu {
                        if !videos.isEmpty {
                            Button("Open in Grid view") { onOpenAllInGrid() }
                            Button("Open in List view") { onOpenAllInList() }
                        }
                    }
                Spacer()
                Button { onCollapse() } label: {
                    Image(systemName: "chevron.right")
                }
                .buttonStyle(.borderless)
                .help("Hide this panel (Tab)")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            Divider()

            if videos.isEmpty {
                Spacer()
                if loading {
                    // A location was clicked; its videos are still resolving.
                    VStack(spacing: 8) {
                        ProgressView()
                        Text("Loading videos at this location…")
                            .font(.callout)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(20)
                } else {
                    VStack(spacing: 8) {
                        Image(systemName: "mappin.and.ellipse")
                            .font(.system(size: 32))
                            .foregroundColor(.secondary)
                        Text("Click a location on the map to see its videos here.")
                            .font(.callout)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(20)
                }
                Spacer()
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(videos) { video in
                            VideoCardView(
                                item: GridItemRow(
                                    video: video,
                                    isExpandedRepresentative: false,
                                    isStackChild: false,
                                    memberPosition: 0,
                                    memberCount: 0
                                ),
                                thumbnail: gridViewModel.thumbnails[video.id],
                                // Hover-scrub through frames, same as grid/list.
                                scrubFrames: gridViewModel.scrubFrames[video.id] ?? [],
                                isPrimarySelected: video.id == currentVideoId,
                                isInMultiSelection: false,
                                isAnchor: false,
                                isPlaying: false,
                                playPath: nil,
                                topSlots: gridViewModel.topSlots,
                                onClick: { _, _ in onCardClick(video) },
                                onStackBadgeClick: {},
                                onHoverEnter: { gridViewModel.loadScrubFrames(videoId: video.id) },
                                onPlayClick: {},
                                onStopPlayback: {},
                                onSetRating: { rating in gridViewModel.setRating(rating, for: [video.id]) },
                                onPickStatSlot: { _, _ in },
                                dragPaths: video.openPath.isEmpty ? [] : [video.openPath]
                            )
                            .onAppear {
                                if video.hasThumbnail { gridViewModel.loadThumbnail(videoId: video.id) }
                            }
                            .contextMenu {
                                Button("Open in Grid") { onOpenInGrid(video) }
                                Button("Open in List") { onOpenInList(video) }
                                Button("Open in Detail") { onOpenInDetail(video) }
                                // Every card here has a location by definition
                                // (it's on the map), so offer Update + Remove.
                                Divider()
                                Button("Update Location…") {
                                    onEditLocation([video.id], (video.gpsLatitude, video.gpsLongitude))
                                }
                                Button("Remove Location") {
                                    gridViewModel.clearVideoLocations(videoIds: [video.id])
                                }
                            }
                        }
                    }
                    .padding(8)
                }
            }
        }
    }
}
