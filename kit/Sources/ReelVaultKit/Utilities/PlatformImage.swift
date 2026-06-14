// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import CoreGraphics

#if canImport(AppKit)
import AppKit
/// The platform's bitmap image type. `NSImage` on macOS, `UIImage` on iOS.
/// Shared code (the gRPC repository, the view-models) decodes thumbnails into a
/// `PlatformImage`; each app's SwiftUI layer renders it with `Image(nsImage:)` /
/// `Image(uiImage:)`. Keeping the type behind one alias lets the view-models stay
/// platform-agnostic.
public typealias PlatformImage = NSImage
#elseif canImport(UIKit)
import UIKit
public typealias PlatformImage = UIImage
#endif

public extension PlatformImage {
    /// Decode raw image bytes (e.g. a thumbnail JPEG from the daemon) into a
    /// platform image, or nil if the bytes aren't a valid image.
    static func fromData(_ data: Data) -> PlatformImage? {
        PlatformImage(data: data)
    }

    /// Wrap a decoded `CGImage` (e.g. an AVFoundation frame) as a platform image.
    static func fromCGImage(_ cg: CGImage) -> PlatformImage {
        #if canImport(AppKit)
        return NSImage(cgImage: cg, size: .zero)
        #else
        return UIImage(cgImage: cg)
        #endif
    }
}
