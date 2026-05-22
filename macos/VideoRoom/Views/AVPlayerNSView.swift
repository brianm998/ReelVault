// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI
import AVKit

/// NSViewRepresentable wrapper around AVPlayerView.
///
/// SwiftUI's `VideoPlayer` struct works by creating a private subclass called
/// `VideoPlayerView` whose superclass is `AVPlayerView` (mangled Swift name
/// `So12AVPlayerViewC`). On some macOS + Swift runtime combinations the
/// demangler fails at launch time:
///
///     failed to demangle superclass of VideoPlayerView from mangled name
///     'So12AVPlayerViewC': unknown error
///     Abort trap: 6
///
/// The crash happens because the Swift runtime attempts to demangle the
/// Objective-C bridged class name `AVPlayerView` before the AVKit framework
/// has been fully loaded into the process.
///
/// This wrapper bypasses the issue entirely by instantiating `AVPlayerView`
/// directly — no private subclass, no demangling required. The resulting
/// visual appearance is identical: an AVPlayerView with inline transport
/// controls, same as `VideoPlayer`.
struct AVPlayerNSView: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.player = player
        view.controlsStyle = .inline
        // Make the AVPlayerView's backing CALayer transparent so whatever is
        // stacked beneath it (thumbnail / ScrubPreview) shows through until
        // the first video frame is decoded and composited.
        view.wantsLayer = true
        view.layer?.backgroundColor = .clear
        return view
    }

    func updateNSView(_ nsView: AVPlayerView, context: Context) {
        // Guard against pointer equality to avoid redundant KVO
        // notifications from AVPlayerView when the player hasn't changed.
        if nsView.player !== player {
            nsView.player = player
        }
    }
}
