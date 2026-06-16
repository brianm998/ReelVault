// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Bottom-trailing status badges on a grid card — the iOS port of the macOS
/// `CardStatusBadges` (same icons + tints): keyword, proxy, full-resolution,
/// audio. Kept visually identical so the three clients match.
///
/// Landscape: a horizontal row left → right.
/// Portrait:  a vertical column, each icon rotated 90° CW so the badges read
///            naturally along the right edge of the tall video frame. The column
///            order (top → bottom) mirrors the landscape row (left → right), so
///            the rightmost landscape badge ends up at the bottom corner.
struct CardStatusBadges: View {
    let video: VideoSummary
    var portrait: Bool = false
    var iconSize: CGFloat = 10
    var spacing: CGFloat = 4

    @ViewBuilder private var badges: some View {
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

    var body: some View {
        if portrait {
            VStack(spacing: spacing) { badges }
        } else {
            HStack(spacing: spacing) { badges }
        }
    }

    @ViewBuilder private func badge(_ name: String, _ tint: Color) -> some View {
        Image(systemName: name)
            .font(.system(size: iconSize))
            .foregroundStyle(tint)
            .rotationEffect(.degrees(portrait ? 90 : 0))
            .frame(width: 18, height: 18)
            .background(Color.black.opacity(0.55))
            .clipShape(Circle())
    }
}
