// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The detail/inspector column of the iPad (regular-width) 3-column layout, and
/// the body of the metadata sheet on iPhone. Shows the streaming player +
/// metadata for the grid's current selection, or an empty-state prompt.
struct InspectorPanel: View {
    let video: VideoSummary?
    let connection: AppRouter.ConnectionInfo?

    var body: some View {
        if let video {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    StreamingPlayerView(video: video, endpoint: connection)
                    VideoMetadataSection(video: video)
                }
                .padding()
            }
            .navigationTitle(video.filename)
            .navigationBarTitleDisplayMode(.inline)
        } else {
            ContentUnavailableView(
                "No selection",
                systemImage: "sidebar.right",
                description: Text("Select a video to see its details and play it.")
            )
        }
    }
}
