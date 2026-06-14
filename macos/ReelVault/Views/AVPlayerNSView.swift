// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit
import AVKit
import AVFoundation

/// NSViewRepresentable that hosts an `AVPlayerLayer` directly inside a plain
/// `NSView` — no `AVPlayerView`, no AVKit chrome.
///
/// We previously used `AVPlayerView` with `controlsStyle = .none`, but
/// `AVPlayerView` keeps an opaque black layer behind its video content even
/// with controls disabled, which paints over the still thumbnail rendered
/// beneath the player while the first frame is decoding. Hosting an
/// `AVPlayerLayer` ourselves lets us keep the entire backing layer clear so
/// the thumbnail shows through during buffering and any letterbox area stays
/// transparent during playback.
///
/// (The older `AVPlayerView` approach also avoided a Swift-runtime crash that
/// only affected SwiftUI's `VideoPlayer` struct — instantiating `AVPlayerView`
/// directly was enough to dodge it. Going one level lower to `AVPlayerLayer`
/// likewise avoids the demangling path.)
struct AVPlayerNSView: NSViewRepresentable {
    let player: AVPlayer
    /// How the video fills the layer. Default preserves the file's own aspect
    /// ratio (letterboxed). The detail loupe passes `.resize` and sizes this
    /// view to the *original* video's aspect ratio so a proxy encoded at a
    /// different ratio is stretched to the original's shape rather than
    /// letterboxed (which would reveal the still thumbnail behind it).
    var videoGravity: AVLayerVideoGravity = .resizeAspect

    func makeNSView(context: Context) -> PlayerHostView {
        let view = PlayerHostView()
        view.wantsLayer = true
        view.layer?.backgroundColor = .clear
        view.videoGravity = videoGravity
        view.player = player
        return view
    }

    func updateNSView(_ nsView: PlayerHostView, context: Context) {
        nsView.videoGravity = videoGravity
        if nsView.player !== player {
            nsView.player = player
        }
    }

    /// NSView whose backing layer *is* an `AVPlayerLayer`. The layer's
    /// background is kept clear (AVPlayerLayer defaults to opaque black) so
    /// the SwiftUI thumbnail beneath stays visible while the first frame
    /// decodes, and any letterbox bars show through to the card's photo
    /// background instead of a black bar.
    final class PlayerHostView: NSView {
        override func makeBackingLayer() -> CALayer {
            let layer = AVPlayerLayer()
            layer.videoGravity = .resizeAspect
            layer.backgroundColor = .clear
            return layer
        }

        var videoGravity: AVLayerVideoGravity {
            get { (layer as? AVPlayerLayer)?.videoGravity ?? .resizeAspect }
            set { (layer as? AVPlayerLayer)?.videoGravity = newValue }
        }

        var player: AVPlayer? {
            get { (layer as? AVPlayerLayer)?.player }
            set { (layer as? AVPlayerLayer)?.player = newValue }
        }
    }
}
