// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// One row of a card's top stat band: a leading (start-aligned) subview and a
/// trailing (end-aligned) subview. Unlike a plain even split, a short value
/// only claims the width it needs and yields the rest to its neighbour — so a
/// long value truncates only when the *pair* genuinely overflows, never just
/// because it crossed the band's centre. When both fit (or both overflow) the
/// row falls back to an even split, matching the previous `HStack` behaviour.
///
/// Expects exactly two subviews; any other count falls back to a simple
/// left-to-right layout at intrinsic widths.
struct AdaptiveStatRow: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let width = proposal.width ?? subviews.reduce(0) { $0 + $1.sizeThatFits(.unspecified).width }
        let height = subviews.map { $0.sizeThatFits(.unspecified).height }.max() ?? 0
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        guard subviews.count == 2 else {
            var x = bounds.minX
            for sv in subviews {
                let w = sv.sizeThatFits(.unspecified).width
                sv.place(at: CGPoint(x: x, y: bounds.midY), anchor: .leading,
                         proposal: ProposedViewSize(width: w, height: bounds.height))
                x += w + spacing
            }
            return
        }
        let usable = max(0, bounds.width - spacing)
        let half = usable / 2
        let lDesired = min(subviews[0].sizeThatFits(.unspecified).width, usable)
        let rDesired = min(subviews[1].sizeThatFits(.unspecified).width, usable)
        // Asymmetric overflow → the short cell keeps its width and the long one
        // takes the rest. Both-fit or both-overflow → an even split.
        let lw: CGFloat
        let rw: CGFloat
        if lDesired <= half && rDesired <= half {
            lw = half; rw = half
        } else if lDesired > half && rDesired > half {
            lw = half; rw = half
        } else if lDesired > half {
            lw = usable - rDesired; rw = rDesired
        } else {
            lw = lDesired; rw = usable - lDesired
        }
        // Leading hugs the left edge; trailing hugs the right edge.
        subviews[0].place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading,
                          proposal: ProposedViewSize(width: lw, height: bounds.height))
        subviews[1].place(at: CGPoint(x: bounds.maxX, y: bounds.midY), anchor: .trailing,
                          proposal: ProposedViewSize(width: rw, height: bounds.height))
    }
}
