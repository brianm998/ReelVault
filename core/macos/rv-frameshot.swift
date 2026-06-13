// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
//
// rv-frameshot — extract a single frame from a video at a given timestamp via
// AVFoundation (the OS decoder), writing it as a JPEG. This is the daemon's
// escape hatch for ProRes RAW: ffmpeg can't develop Atomos S-Log3 ProRes RAW
// and `qlmanage` only ever renders the file's poster (no timestamp), so neither
// can produce distinct scrub frames. AVAssetImageGenerator can — it's the same
// pipeline AVPlayer uses, which decodes these clips correctly.
//
// Usage: rv-frameshot <input> <output.jpg> <timeSeconds> <maxPx>
//   maxPx bounds the longest side (0 = no cap). Exit 0 on success.
//
// Compiled by core/build.rs on macOS and embedded into the daemon binary, so
// there's no runtime Swift-toolchain dependency — only the always-present
// AVFoundation / AppKit frameworks.

import Foundation
import AVFoundation
import AppKit

let args = CommandLine.arguments
guard args.count == 5,
      let timeSec = Double(args[3]),
      let maxPx = Int(args[4]) else {
    FileHandle.standardError.write(Data("usage: rv-frameshot <input> <output.jpg> <timeSeconds> <maxPx>\n".utf8))
    exit(2)
}

let asset = AVURLAsset(url: URL(fileURLWithPath: args[1]))
let gen = AVAssetImageGenerator(asset: asset)
gen.appliesPreferredTrackTransform = true
// A little tolerance keeps the seek fast over a slow mount; exact frames aren't
// needed for a thumbnail / scrub strip.
gen.requestedTimeToleranceBefore = CMTime(seconds: 0.5, preferredTimescale: 600)
gen.requestedTimeToleranceAfter = CMTime(seconds: 0.5, preferredTimescale: 600)
if maxPx > 0 { gen.maximumSize = CGSize(width: maxPx, height: maxPx) }

let t = CMTime(seconds: timeSec, preferredTimescale: 600)
let sem = DispatchSemaphore(value: 0)
var produced: CGImage?
gen.generateCGImagesAsynchronously(forTimes: [NSValue(time: t)]) { _, image, _, result, _ in
    if result == .succeeded { produced = image }
    sem.signal()
}
sem.wait()

guard let image = produced else {
    FileHandle.standardError.write(Data("frame extraction failed at \(timeSec)s\n".utf8))
    exit(1)
}
let rep = NSBitmapImageRep(cgImage: image)
guard let data = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.85]) else {
    FileHandle.standardError.write(Data("jpeg encode failed\n".utf8))
    exit(1)
}
do {
    try data.write(to: URL(fileURLWithPath: args[2]))
} catch {
    FileHandle.standardError.write(Data("write failed: \(error)\n".utf8))
    exit(1)
}
