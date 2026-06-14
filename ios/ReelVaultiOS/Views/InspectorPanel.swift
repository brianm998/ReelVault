// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The metadata inspector column of the iPad 3-column layout. Metadata only —
/// playback now lives in the dedicated Detail view mode (switch to Detail to
/// watch a video). Shows the current selection's details, or an empty state.
struct InspectorPanel: View {
    let video: VideoSummary?
    /// How to switch into Detail (player) mode for the selected video.
    var onPlay: () -> Void = {}

    var body: some View {
        if let video {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Button(action: onPlay) {
                        Label("Play in Detail", systemImage: "play.rectangle")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
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
                description: Text("Select a video to see its details.")
            )
        }
    }
}
