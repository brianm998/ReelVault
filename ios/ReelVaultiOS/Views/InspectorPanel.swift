// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The metadata inspector — the fixed-width trailing panel in Grid/List mode on
/// iPad. Now mirrors the macOS detail panel's section set (marks, technical
/// details, EXIF, collections, notes, location, visuals) with each section
/// collapsible. Playback lives in Detail mode (the "Play in Detail" button
/// switches there).
struct InspectorPanel: View {
    @ObservedObject var grid: GridViewModel
    let video: VideoSummary?
    /// Bumped on catalog change events so the proxy list re-fetches live.
    var refreshTick: Int = 0
    /// Switch to the internal Map mode (the "Show on Map" location action).
    var onShowOnMap: () -> Void = {}
    /// Switch to Detail (player) mode for the selected video. Declared LAST so the
    /// call site's trailing closure binds to it (not onShowOnMap).
    var onPlay: () -> Void = {}

    var body: some View {
        if let video {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(video.filename)
                        .font(.headline)
                        .lineLimit(2)
                    Button(action: onPlay) {
                        Label("Play in Detail", systemImage: "play.rectangle")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)

                    CollapsibleSection("Marks & Keywords") {
                        MetadataEditorSection(grid: grid, videoId: video.id)
                    }
                    CollapsibleSection("Details") {
                        VideoMetadataSection(video: video, refreshTick: refreshTick,
                                             showHeader: false, grid: grid)
                    }
                    CollapsibleSection("EXIF") { ExifSection(video: video) }
                    CollapsibleSection("Collections") { CollectionsSection(grid: grid, videoId: video.id) }
                    CollapsibleSection("Notes") { NotesSection(videoId: video.id) }
                    CollapsibleSection("Location") {
                        LocationButtonsSection(grid: grid, videoId: video.id, showHeader: false,
                                               onShowOnMap: onShowOnMap)
                    }
                    CollapsibleSection("Capture Date") {
                        CaptureDateButtonSection(grid: grid, videoId: video.id, showHeader: false)
                    }
                    CollapsibleSection("Visuals") {
                        DetailGraphsView(grid: grid, videoId: video.id, showHeader: false)
                    }
                }
                .padding()
            }
            .background(.bar)
        } else if let col = activeSmartCollection {
            // A smart collection is selected but no video is picked — show its
            // filter rules in place of the generic "no selection" placeholder,
            // mirroring the macOS detail panel's placeholder behaviour.
            SmartCollectionCriteriaView(grid: grid, collection: col)
                .background(.bar)
        } else {
            ContentUnavailableView(
                "No selection",
                systemImage: "sidebar.right",
                description: Text("Select a video to see its details.")
            )
            .background(.bar)
        }
    }

    /// The currently-selected smart collection, if any — used to populate the
    /// inspector placeholder when no video card is selected.
    private var activeSmartCollection: Collection? {
        guard let id = grid.selectedCollectionId else { return nil }
        guard let col = grid.collections.first(where: { $0.id == id }), col.isSmart else { return nil }
        return col
    }
}
