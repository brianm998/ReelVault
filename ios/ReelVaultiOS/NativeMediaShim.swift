// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AVFoundation
import CoreMedia
import Foundation
import ImageIO
import Photos
import UIKit
import UniformTypeIdentifiers

/// The native (AVFoundation/VideoToolbox) media backend for the embedded core
/// (docs/IOS_CORE_PORT.md §6.2). The Rust core can't call Swift directly, so we
/// hand it a table of C function pointers (`ReelVaultMediaCallbacks`) that it
/// invokes for the work the desktop core shells out to ffmpeg for: probing
/// metadata, decoding thumbnail frames, and transcoding proxies.
///
/// The callbacks are synchronous (the Rust trait is blocking) and are called off
/// the main thread, so each bridges AVFoundation's async APIs with a semaphore.
enum NativeMedia {
    /// Install the callbacks. Call once, before the core does any media work.
    static func register() {
        var cb = ReelVaultMediaCallbacks()
        cb.probe = rvProbe
        cb.extract_frame = rvExtractFrame
        cb.transcode_proxy = rvTranscodeProxy
        cb.free_string = rvFreeString
        reelvault_register_media_backend(cb)
        NSLog("ReelVault native: media backend registered")
    }

    // MARK: - Source resolution

    // The Rust core re-enters the backend ~12× per video during ingest (probe +
    // still + 10 scrub frames), each calling resolveAsset. For a Photos source
    // that would be 12 separate `requestAVAsset` calls — and with iCloud
    // "Optimize Storage" each can re-download the full-quality original. Cache
    // the resolved AVAsset per source for the duration of an ingest pass;
    // `clearAssetCache()` is called when enumeration finishes.
    private static let cacheLock = NSLock()
    private static var assetCache: [String: AVAsset] = [:]

    static func clearAssetCache() {
        cacheLock.lock()
        assetCache.removeAll()
        cacheLock.unlock()
    }

    /// Resolve a backend source descriptor to an AVAsset (cached). kind 0 = file
    /// path, 1 = Photos localIdentifier.
    static func resolveAsset(kind: Int32, srcId: String) async -> AVAsset? {
        let key = "\(kind):\(srcId)"
        cacheLock.lock()
        let cached = assetCache[key]
        cacheLock.unlock()
        if let cached { return cached }

        let resolved: AVAsset?
        switch kind {
        case 0:
            resolved = AVURLAsset(url: URL(fileURLWithPath: srcId))
        case 1:
            let fetch = PHAsset.fetchAssets(withLocalIdentifiers: [srcId], options: nil)
            if let asset = fetch.firstObject {
                resolved = await withCheckedContinuation { cont in
                    let opts = PHVideoRequestOptions()
                    opts.isNetworkAccessAllowed = true
                    opts.deliveryMode = .highQualityFormat
                    PHImageManager.default().requestAVAsset(forVideo: asset, options: opts) { avAsset, _, _ in
                        cont.resume(returning: avAsset)
                    }
                }
            } else {
                resolved = nil
            }
        default:
            resolved = nil
        }

        if let resolved {
            cacheLock.lock()
            assetCache[key] = resolved
            cacheLock.unlock()
        }
        return resolved
    }

    /// Run an async body to completion on a detached task, blocking the caller
    /// until it finishes — the bridge the synchronous C callbacks need. Safe
    /// because callbacks run off the main thread (the cooperative pool runs the
    /// detached task; the blocked thread is a plain background/DispatchQueue
    /// thread, so the pool isn't starved).
    static func blocking<T>(
        timeout: DispatchTimeInterval = .seconds(120),
        fallback: T,
        _ body: @escaping @Sendable () async -> T
    ) -> T {
        let sem = DispatchSemaphore(value: 0)
        let box = ResultBox<T>()
        Task.detached {
            box.value = await body()
            sem.signal()
        }
        // Bound the wait: a stuck iCloud `requestAVAsset` (no local copy, no
        // network) must not pin a worker thread forever.
        if sem.wait(timeout: .now() + timeout) == .timedOut {
            NSLog("ReelVault native: media operation timed out after \(timeout)")
            return fallback
        }
        return box.value ?? fallback
    }

    // MARK: - probe → ffprobe-shaped JSON

    static func probeJSON(kind: Int32, srcId: String) -> String? {
        blocking(fallback: nil) {
            guard let asset = await resolveAsset(kind: kind, srcId: srcId) else { return nil }
            do {
                let duration = try await asset.load(.duration)
                let durationSecs = CMTimeGetSeconds(duration)
                var streams: [[String: Any]] = []

                if let v = try await asset.loadTracks(withMediaType: .video).first {
                    let size = try await v.load(.naturalSize)
                    let fps = try await v.load(.nominalFrameRate)
                    var s: [String: Any] = [
                        "index": streams.count,
                        "codec_type": "video",
                        "width": Int(abs(size.width).rounded()),
                        "height": Int(abs(size.height).rounded()),
                        // Millihertz rational so fractional NTSC rates survive
                        // (29.97 → 29970/1000), not rounded to an integer.
                        "r_frame_rate": "\(Int((Double(fps) * 1000).rounded()))/1000",
                    ]
                    if let codec = try? await codecName(of: v) { s["codec_name"] = codec }
                    streams.append(s)
                }
                if let a = try await asset.loadTracks(withMediaType: .audio).first {
                    var s: [String: Any] = ["index": streams.count, "codec_type": "audio"]
                    if let codec = try? await codecName(of: a) { s["codec_name"] = codec }
                    streams.append(s)
                }

                // `duration` and `bit_rate` keys must be present (the core's
                // FFProbeFormat deserializer has no default for them); other
                // fields are optional. bit_rate is unknown here → null.
                let root: [String: Any] = [
                    "streams": streams,
                    "format": ["duration": durationSecs, "bit_rate": NSNull()],
                ]
                let data = try JSONSerialization.data(withJSONObject: root)
                return String(decoding: data, as: UTF8.self)
            } catch {
                NSLog("ReelVault native: probe failed for \(srcId): \(error.localizedDescription)")
                return nil
            }
        }
    }

    /// Map an AVAssetTrack's codec FourCC to an ffmpeg-style codec name.
    static func codecName(of track: AVAssetTrack) async throws -> String? {
        let descs = try await track.load(.formatDescriptions)
        guard let desc = descs.first else { return nil }
        let subtype = CMFormatDescriptionGetMediaSubType(desc)
        // FourCharCode → 4 chars.
        let chars = [
            UInt8((subtype >> 24) & 0xff), UInt8((subtype >> 16) & 0xff),
            UInt8((subtype >> 8) & 0xff), UInt8(subtype & 0xff),
        ]
        let fourcc = String(bytes: chars, encoding: .ascii)?.trimmingCharacters(in: .whitespaces) ?? ""
        switch fourcc.lowercased() {
        case "avc1", "h264": return "h264"
        case "hvc1", "hev1", "hvcc": return "hevc"
        case "ap4h", "ap4x", "apch", "apcn", "apcs", "apco": return "prores"
        case "aac ", "mp4a": return "aac"
        case "lpcm", "sowt", "in24", "fl32": return "pcm"
        default: return fourcc.isEmpty ? nil : fourcc
        }
    }

    // MARK: - extract_frame

    static func extractFrame(kind: Int32, srcId: String, timeSecs: Double, maxPx: Int, outPath: String) -> Bool {
        blocking(fallback: false) {
            guard let asset = await resolveAsset(kind: kind, srcId: srcId) else { return false }
            let gen = AVAssetImageGenerator(asset: asset)
            gen.appliesPreferredTrackTransform = true
            gen.maximumSize = CGSize(width: maxPx, height: maxPx) // fit longest side
            // Exact seek (like the desktop ffmpeg path): a ±tolerance would
            // collapse adjacent scrub positions to the same keyframe on short
            // clips, yielding duplicate scrub frames.
            gen.requestedTimeToleranceBefore = .zero
            gen.requestedTimeToleranceAfter = .zero
            let time = CMTime(seconds: max(0, timeSecs), preferredTimescale: 600)
            do {
                let cg = try await withCheckedThrowingContinuation { (cont: CheckedContinuation<CGImage, Error>) in
                    gen.generateCGImageAsynchronously(for: time) { image, _, error in
                        if let image { cont.resume(returning: image) }
                        else { cont.resume(throwing: error ?? NativeMediaError.noFrame) }
                    }
                }
                return writeJPEG(cg, to: outPath)
            } catch {
                NSLog("ReelVault native: extract_frame failed for \(srcId) @\(timeSecs)s: \(error.localizedDescription)")
                return false
            }
        }
    }

    static func writeJPEG(_ cg: CGImage, to path: String) -> Bool {
        // Encode to a sibling temp file and atomically move into place on
        // success — a failed/partial Finalize must never leave a corrupt JPEG at
        // `path` (the cache treats "file exists" as "thumbnail ready").
        let finalURL = URL(fileURLWithPath: path)
        let tmpURL = finalURL.deletingLastPathComponent()
            .appendingPathComponent(".\(UUID().uuidString).jpg")
        guard let dest = CGImageDestinationCreateWithURL(
            tmpURL as CFURL, UTType.jpeg.identifier as CFString, 1, nil
        ) else { return false }
        CGImageDestinationAddImage(dest, cg, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else {
            try? FileManager.default.removeItem(at: tmpURL)
            return false
        }
        do {
            // `replaceItemAt` only works if the destination exists; for the
            // common (new file) case, move; on collision, replace.
            if FileManager.default.fileExists(atPath: finalURL.path) {
                _ = try FileManager.default.replaceItemAt(finalURL, withItemAt: tmpURL)
            } else {
                try FileManager.default.moveItem(at: tmpURL, to: finalURL)
            }
            return true
        } catch {
            try? FileManager.default.removeItem(at: tmpURL)
            return false
        }
    }

    // MARK: - transcode_proxy

    static func transcodeProxy(kind: Int32, srcId: String, outPath: String, targetHeight: Int) -> Bool {
        blocking(fallback: false) {
            guard let asset = await resolveAsset(kind: kind, srcId: srcId) else { return false }
            // Closest standard export preset at/under the target height. (Exact
            // arbitrary heights would need an AVAssetWriter pipeline; a later
            // refinement — proxy generation isn't on the grid path.)
            let preset: String
            switch targetHeight {
            case ..<600: preset = AVAssetExportPreset640x480
            case 600..<1000: preset = AVAssetExportPreset1280x720
            default: preset = AVAssetExportPreset1920x1080
            }
            guard let export = AVAssetExportSession(asset: asset, presetName: preset) else { return false }
            let url = URL(fileURLWithPath: outPath)
            try? FileManager.default.removeItem(at: url)
            export.outputURL = url
            export.outputFileType = .mp4
            export.shouldOptimizeForNetworkUse = true
            await export.export()
            return export.status == .completed
        }
    }
}

enum NativeMediaError: Error { case noFrame }

/// Holds an async result across the `blocking` semaphore. `@unchecked Sendable`
/// because the `DispatchSemaphore` provides the happens-before between the
/// detached task's write and the caller's read.
private final class ResultBox<T>: @unchecked Sendable {
    var value: T?
}

// MARK: - @convention(c) trampolines (no captures → usable as C function pointers)

private func rvProbe(_ kind: Int32, _ srcId: UnsafePointer<CChar>?) -> UnsafeMutablePointer<CChar>? {
    guard let srcId else { return nil }
    guard let json = NativeMedia.probeJSON(kind: kind, srcId: String(cString: srcId)) else { return nil }
    return strdup(json) // freed by rvFreeString
}

private func rvExtractFrame(
    _ kind: Int32, _ srcId: UnsafePointer<CChar>?, _ timeSecs: Double, _ maxPx: Int32, _ outPath: UnsafePointer<CChar>?
) -> Int32 {
    guard let srcId, let outPath else { return -1 }
    let ok = NativeMedia.extractFrame(
        kind: kind, srcId: String(cString: srcId), timeSecs: timeSecs,
        maxPx: Int(maxPx), outPath: String(cString: outPath)
    )
    return ok ? 0 : -2
}

private func rvTranscodeProxy(
    _ kind: Int32, _ srcId: UnsafePointer<CChar>?, _ outPath: UnsafePointer<CChar>?, _ targetHeight: Int32
) -> Int32 {
    guard let srcId, let outPath else { return -1 }
    let ok = NativeMedia.transcodeProxy(
        kind: kind, srcId: String(cString: srcId), outPath: String(cString: outPath), targetHeight: Int(targetHeight)
    )
    return ok ? 0 : -2
}

private func rvFreeString(_ p: UnsafeMutablePointer<CChar>?) {
    free(p)
}
