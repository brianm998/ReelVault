// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Bottom-trailing status badges on a grid card — the iOS port of the macOS
/// `CardStatusBadges` (same icons + tints): keyword, proxy, full-resolution,
/// audio. Kept visually identical so the three clients match.
struct CardStatusBadges: View {
    let video: VideoSummary
    var iconSize: CGFloat = 10
    var spacing: CGFloat = 4

    var body: some View {
        HStack(spacing: spacing) {
            if !video.tags.isEmpty {
                badge("tag.fill", Color(red: 0.39, green: 0.71, blue: 0.96))
            }
            if video.hasProxies {
                badge("rectangle.on.rectangle.angled", .white)
            }
            switch video.fullResolution {
            case .full:
                badge("checkmark.seal.fill", Color(red: 0.51, green: 0.78, blue: 0.52))
            case .notFull:
                badge("crop", .white)
            case .unspecified:
                EmptyView()
            }
            if video.hasAudio {
                badge("waveform", .white)
            }
        }
    }

    @ViewBuilder private func badge(_ name: String, _ tint: Color) -> some View {
        Image(systemName: name)
            .font(.system(size: iconSize))
            .foregroundStyle(tint)
            .frame(width: 18, height: 18)
            .background(Color.black.opacity(0.55))
            .clipShape(Circle())
    }
}
