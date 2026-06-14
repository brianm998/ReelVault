// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The metadata inspector — a small fixed-width trailing panel in Grid/List
/// mode on iPad. Metadata only; playback lives in Detail mode (the "Play in
/// Detail" button switches there).
struct InspectorPanel: View {
    let video: VideoSummary?
    /// Switch to Detail (player) mode for the selected video.
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
                    VideoMetadataSection(video: video)
                }
                .padding()
            }
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
}
