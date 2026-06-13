import SwiftUI

/// Shared badge rendering for the video cards (grid, list row, expanded-stack
/// row, and the map panel, which reuses the grid card). Centralising the
/// bottom-badge placement maths and the landscape-row / portrait-column status
/// group keeps the several card implementations from drifting apart.

/// Diameter of a card status / location badge. The bottom padding and the side
/// insets of the bottom badges are expressed as multiples of this so the
/// placement scales together with the badge.
let cardBadgeSize: CGFloat = 18

/// Vertical inset (above the photo-area bottom edge) for the bottom badges.
///
/// `gap` is the distance from the photo-area bottom up to the video frame's
/// bottom edge — the bottom letterbox plus the photo padding.
///
/// Landscape: one badge-height of padding by default, transitioning
/// continuously to "centred in the gap" once fewer than a badge-height of
/// clearance is left between the badge's top and the video's bottom edge
/// (i.e. once `gap < 3·badge`). That is `min(badge, (gap - badge) / 2)`,
/// clamped ≥ 0 so a near-square clip's badge never spills below the card.
///
/// Portrait: the bottom gap is ~0 (the video fills the height), so the
/// transition is meaningless — we just use one badge-height of padding and let
/// the status badges move to a vertical column (see `CardStatusBadges`).
func cardBottomBadgeInset(gap: CGFloat, portrait: Bool, badge: CGFloat = cardBadgeSize) -> CGFloat {
    if portrait { return badge }
    return max(0, min(badge, (gap - badge) / 2))
}

/// A single circular status badge: a tinted SF Symbol over a translucent black
/// disc. Rotated 90° clockwise when `rotate` is true (only the glyph turns —
/// the disc is circular), used for the portrait column.
private struct CardStatusBadge: View {
    let systemName: String
    let tint: Color
    let help: String
    var iconSize: CGFloat = 10
    var rotate: Bool = false

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: iconSize))
            .foregroundColor(tint)
            .rotationEffect(.degrees(rotate ? 90 : 0))
            .frame(width: cardBadgeSize, height: cardBadgeSize)
            .background(Color.black.opacity(0.55))
            .clipShape(Circle())
            .help(help)
    }
}

/// The keyword / proxy / full-resolution status badges shown at the bottom of a
/// video card.
///
/// Landscape: a horizontal row — left→right keyword, proxy, full-resolution.
/// Portrait: a vertical column hugging the bottom-right side, each badge icon
/// rotated 90° clockwise. The column's top→bottom order matches the landscape
/// left→right order, so the right-most landscape badge ends up at the bottom
/// (nearest the corner).
struct CardStatusBadges: View {
    let video: VideoSummary
    let portrait: Bool
    var iconSize: CGFloat = 10
    var spacing: CGFloat = 4

    @ViewBuilder private var badges: some View {
        if !video.tags.isEmpty {
            CardStatusBadge(
                systemName: "tag.fill",
                tint: Color(red: 0.39, green: 0.71, blue: 0.96), // light blue — "has keywords"
                help: "\(video.tags.count) keyword\(video.tags.count == 1 ? "" : "s")",
                iconSize: iconSize, rotate: portrait
            )
        }
        if video.hasProxies {
            CardStatusBadge(
                systemName: "rectangle.on.rectangle.angled",
                tint: .white,
                help: "\(video.proxyCount) proxy/proxies available for inline playback.",
                iconSize: iconSize, rotate: portrait
            )
        }
        switch video.fullResolution {
        case .full:
            CardStatusBadge(
                systemName: "checkmark.seal.fill",
                tint: Color(red: 0.51, green: 0.78, blue: 0.52), // subtle green — "full resolution"
                help: "Full resolution — matches a known native sensor mode for \(video.cameraDisplayName.isEmpty ? "this camera" : video.cameraDisplayName).",
                iconSize: iconSize, rotate: portrait
            )
        case .notFull:
            CardStatusBadge(
                systemName: "crop",
                tint: .white,
                help: "Not full resolution — recorded dimensions don't match any native sensor mode for \(video.cameraDisplayName.isEmpty ? "this camera" : video.cameraDisplayName).",
                iconSize: iconSize, rotate: portrait
            )
        case .unspecified:
            EmptyView()
        }
        if video.hasAudio {
            CardStatusBadge(
                systemName: "waveform",
                tint: .white,
                help: "Has an audio track",
                iconSize: iconSize, rotate: portrait
            )
        }
    }

    var body: some View {
        if portrait {
            VStack(spacing: spacing) { badges }
        } else {
            HStack(spacing: spacing) { badges }
        }
    }
}
