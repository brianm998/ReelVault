// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// A compact playback-volume slider overlaid on the currently-playing inline
/// card (grid / list). Shown only for clips that carry an audio track — the
/// caller is responsible for that gate.
///
/// Mirrors the detail loupe's volume control but smaller so it fits over a
/// thumbnail. Because it's an interactive control, dragging it adjusts volume
/// rather than starting the card's `.onDrag` file drag-out (AppKit controls
/// capture their own mouse tracking first) — the same basis the stop button
/// relies on.
struct InlineVolumeControl: View {
    let volume: Int
    let onVolumeChange: (Int) -> Void

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: volume == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill")
                .font(.system(size: 9))
                .foregroundColor(.white)
            Slider(
                value: Binding(
                    get: { Double(volume) },
                    set: { onVolumeChange(Int($0.rounded())) }
                ),
                in: 0...100
            )
            .controlSize(.mini)
            .frame(width: 70)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
        .background(Capsule().fill(Color.black.opacity(0.6)))
        .help("Playback volume")
    }
}
