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
    let videos: [VideoSummary]
    let currentVideoId: String?
    let onCardClick: (VideoSummary) -> Void
    let onOpenInGrid: (VideoSummary) -> Void
    let onOpenInList: (VideoSummary) -> Void
    let onOpenInDetail: (VideoSummary) -> Void
    let onCollapse: () -> Void

    private let columns = [GridItem(.flexible())]

    var body: some View {
        VStack(spacing: 0) {
            // Header — title + collapse chevron, matching the details panel.
            HStack {
                Text(videos.isEmpty
                     ? "Selected location"
                     : "\(videos.count) video\(videos.count == 1 ? "" : "s") here")
                    .font(.headline)
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
                                scrubFrames: [],
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
                            }
                        }
                    }
                    .padding(8)
                }
            }
        }
    }
}
