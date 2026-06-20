// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AVFoundation
import CoreLocation
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
        cb.extract_loudness = rvExtractLoudness
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
    /// Security-scoped URLs opened for kind-2 (bookmark) sources. Their scope
    /// must stay open while the cached AVAsset is read; released together with
    /// the asset cache at the end of an ingest pass.
    private static var scopedURLs: [URL] = []

    static func clearAssetCache() {
        cacheLock.lock()
        assetCache.removeAll()
        let urls = scopedURLs
        scopedURLs.removeAll()
        cacheLock.unlock()
        for url in urls { url.stopAccessingSecurityScopedResource() }
    }

    /// Decode a hex bookmark blob and resolve it to a (security-scoped) file
    /// URL. Shared by ingest/thumbnail (`resolveAsset`) and on-device playback.
    /// The caller must `startAccessingSecurityScopedResource()` before reading.
    static func resolveBookmark(hex: String) -> URL? {
        guard let data = Data(hexString: hex) else { return nil }
        var stale = false
        let url = try? URL(resolvingBookmarkData: data, options: [],
                           relativeTo: nil, bookmarkDataIsStale: &stale)
        if stale { NSLog("ReelVault native: bookmark is stale (file may have moved)") }
        return url
    }

    /// Resolve a backend source descriptor to an AVAsset (cached). kind 0 = file
    /// path, 1 = Photos localIdentifier, 2 = security-scoped bookmark (hex).
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
        case 2:
            if let url = NativeMedia.resolveBookmark(hex: srcId) {
                if url.startAccessingSecurityScopedResource() {
                    cacheLock.lock(); scopedURLs.append(url); cacheLock.unlock()
                }
                resolved = AVURLAsset(url: url)
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
                    // Color transfer / primaries — so the core's HDR + dynamic-range
                    // classification works on-device too (e.g. iPhone 16 Pro HLG
                    // flags as HDR in Local Library mode, matching the daemon).
                    if let color = try? await colorTags(of: v) {
                        if let trc = color.transfer { s["color_transfer"] = trc }
                        if let prim = color.primaries { s["color_primaries"] = prim }
                    }
                    streams.append(s)
                }
                if let a = try await asset.loadTracks(withMediaType: .audio).first {
                    var s: [String: Any] = ["index": streams.count, "codec_type": "audio"]
                    if let codec = try? await codecName(of: a) { s["codec_name"] = codec }
                    if let info = try? await audioStreamInfo(of: a) {
                        if info.channels > 0 { s["channels"] = info.channels }
                        if info.sampleRate > 0 { s["sample_rate"] = String(info.sampleRate) }
                        if info.bitDepth > 0 { s["bits_per_sample"] = info.bitDepth }
                    }
                    streams.append(s)
                }

                // Camera/date/GPS/lens — harvested from the container & track
                // metadata into the same `com.apple.quicktime.*` tag names
                // ffprobe emits on desktop, so the core's extractor populates
                // the identical columns on-device. Without this, local-mode
                // clips showed only codec/dimensions while the same clip looked
                // fully tagged once uploaded to a daemon.
                let tags = await formatTags(for: asset, kind: kind, srcId: srcId)

                // `duration` and `bit_rate` keys must be present (the core's
                // FFProbeFormat deserializer has no default for them); other
                // fields are optional. bit_rate is unknown here → null.
                var format: [String: Any] = ["duration": durationSecs, "bit_rate": NSNull()]
                if !tags.isEmpty { format["tags"] = tags }
                let root: [String: Any] = ["streams": streams, "format": format]
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

    /// Map a video track's CoreMedia color attachments to the ffmpeg-style
    /// transfer/primaries strings the core's extractor classifies on
    /// (`is_hdr_transfer` keys on smpte2084/arib-std-b67; `classify_dynamic_range`
    /// uses both). Returns nils when the track carries no color tags.
    static func colorTags(of track: AVAssetTrack) async throws -> (transfer: String?, primaries: String?) {
        guard let desc = try await track.load(.formatDescriptions).first else { return (nil, nil) }
        func ext(_ key: CFString) -> String? {
            CMFormatDescriptionGetExtension(desc, extensionKey: key) as? String
        }
        let transfer: String? = {
            guard let t = ext(kCMFormatDescriptionExtension_TransferFunction) else { return nil }
            if t == (kCMFormatDescriptionTransferFunction_ITU_R_709_2 as String) { return "bt709" }
            if t == (kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ as String) { return "smpte2084" }
            if t == (kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG as String) { return "arib-std-b67" }
            if t == (kCMFormatDescriptionTransferFunction_SMPTE_ST_428_1 as String) { return "smpte428" }
            return nil
        }()
        let primaries: String? = {
            guard let p = ext(kCMFormatDescriptionExtension_ColorPrimaries) else { return nil }
            if p == (kCMFormatDescriptionColorPrimaries_ITU_R_709_2 as String) { return "bt709" }
            if p == (kCMFormatDescriptionColorPrimaries_ITU_R_2020 as String) { return "bt2020" }
            if p == (kCMFormatDescriptionColorPrimaries_P3_D65 as String) { return "smpte432" }
            return nil
        }()
        return (transfer, primaries)
    }

    /// Channel count, sample rate (Hz), and PCM bit depth for an audio track.
    /// Extracted from the AudioStreamBasicDescription embedded in the track's
    /// format description — the same data ffprobe surfaces as `channels`,
    /// `sample_rate`, and `bits_per_sample` on the audio stream. `bitDepth` is
    /// 0 for compressed codecs (AAC) and only written when > 0.
    static func audioStreamInfo(of track: AVAssetTrack) async throws -> (channels: Int, sampleRate: Int, bitDepth: Int)? {
        guard let desc = try await track.load(.formatDescriptions).first else { return nil }
        guard let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(desc) else { return nil }
        let channels = Int(asbd.pointee.mChannelsPerFrame)
        guard channels > 0 else { return nil }
        return (
            channels: channels,
            sampleRate: Int(asbd.pointee.mSampleRate.rounded()),
            bitDepth: Int(asbd.pointee.mBitsPerChannel)
        )
    }

    /// Harvest the container/track metadata the core's extractor reads —
    /// camera make/model, capture date, GPS, lens, iris f-number — into
    /// ffprobe-shaped `format.tags`, keyed by the same `com.apple.quicktime.*`
    /// names ffprobe emits on desktop. The desktop daemon gets these from real
    /// ffprobe; on-device the native backend has to synthesize them or the
    /// local catalog shows only codec/dimensions (the gap this closes).
    ///
    /// Two sources are merged:
    ///   - AVAsset metadata (movie-level: make/model/software/creationdate/
    ///     location) plus the **video track's** metadata, which is where
    ///     iPhones record `com.apple.quicktime.camera.lens_model` (and the
    ///     iris f-number) — exactly the per-track keys ffprobe drops, so the
    ///     native path actually recovers *more* than ffprobe does.
    ///   - The PHAsset (Photos sources), authoritative for capture date and
    ///     location and a backstop when an edited AVAsset has dropped them.
    private static func formatTags(for asset: AVAsset, kind: Int32, srcId: String) async -> [String: String] {
        var tags: [String: String] = [:]
        func set(_ key: String, _ value: String?) {
            guard let v = value?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !v.isEmpty, tags[key] == nil else { return }
            tags[key] = v
        }

        // Movie-level + video-track keyed metadata. The QuickTime metadata
        // keyspace exposes each item's reverse-DNS key as the suffix of its
        // identifier ("mdta/com.apple.quicktime.make" → the key we want).
        var items = (try? await asset.load(.metadata)) ?? []
        if let v = try? await asset.loadTracks(withMediaType: .video).first {
            items += (try? await v.load(.metadata)) ?? []
        }
        for item in items {
            guard let raw = item.identifier?.rawValue else { continue }
            let key = String(raw.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).last ?? "")
            guard key.hasPrefix("com.apple.quicktime.") else { continue }
            if let value = try? await item.load(.stringValue) {
                set(key, value)
            }
        }

        // Capture date as `creation_time` in UTC RFC3339 — the first form the
        // core's extractor parses (and what ffprobe writes), so an on-device
        // date matches the daemon's to the millisecond. The QuickTime
        // `com.apple.quicktime.creationdate` string we harvested above carries
        // a colon-less offset ("-0700") the core's RFC3339 parser rejects, so
        // we emit the normalized UTC form instead of relying on it.
        var capture: Date?
        let common = (try? await asset.load(.commonMetadata)) ?? []
        for item in common + items where item.commonKey == .commonKeyCreationDate {
            if let d = try? await item.load(.dateValue) { capture = d; break }
        }

        if kind == 1,
           let ph = PHAsset.fetchAssets(withLocalIdentifiers: [srcId], options: nil).firstObject {
            if capture == nil { capture = ph.creationDate }
            if tags["com.apple.quicktime.location.ISO6709"] == nil, let loc = ph.location {
                set("com.apple.quicktime.location.ISO6709", Self.iso6709(from: loc))
            }
        }

        if let capture {
            let f = ISO8601DateFormatter()
            f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            f.timeZone = TimeZone(identifier: "UTC")
            set("creation_time", f.string(from: capture))
        }
        return tags
    }

    /// Format a CLLocation as the ISO 6709 string QuickTime uses
    /// (`±DD.dddddd±DDD.dddddd±AAA.aaa/`), matching `parse_iso6709` in the core.
    private static func iso6709(from loc: CLLocation) -> String {
        String(format: "%+.6f%+.6f%+.3f/",
               loc.coordinate.latitude, loc.coordinate.longitude, loc.altitude)
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

    // MARK: - extract_loudness (RMS envelope via AVAssetReader)

    /// Compute a normalized (0..1) loudness-over-time envelope for the audio
    /// track: read the PCM down-mixed to mono 8 kHz float, accumulate per-window
    /// RMS, convert to dBFS, and map a −60..0 dB window to 0..1 — the same shape
    /// the desktop ffmpeg/EBU-R128 path feeds the detail graph. Returns [] when
    /// there's no audio or the read fails.
    static func loudness(kind: Int32, srcId: String, maxSamples: Int) -> [Float] {
        blocking(fallback: []) {
            guard let asset = await resolveAsset(kind: kind, srcId: srcId) else { return [] }
            do {
                guard let track = try await asset.loadTracks(withMediaType: .audio).first else { return [] }
                let durationSecs = max(0.001, CMTimeGetSeconds(try await asset.load(.duration)))
                let reader = try AVAssetReader(asset: asset)
                let sampleRate = 8000.0
                let settings: [String: Any] = [
                    AVFormatIDKey: kAudioFormatLinearPCM,
                    AVLinearPCMBitDepthKey: 32,
                    AVLinearPCMIsFloatKey: true,
                    AVLinearPCMIsBigEndianKey: false,
                    AVLinearPCMIsNonInterleaved: false,
                    AVNumberOfChannelsKey: 1,
                    AVSampleRateKey: sampleRate,
                ]
                let output = AVAssetReaderTrackOutput(track: track, outputSettings: settings)
                output.alwaysCopiesSampleData = false
                guard reader.canAdd(output) else { return [] }
                reader.add(output)
                guard reader.startReading() else { return [] }

                let bins = max(2, min(maxSamples, 480))
                var sumsq = [Double](repeating: 0, count: bins)
                var counts = [Int](repeating: 0, count: bins)
                var frameIndex = 0

                while reader.status == .reading, let sbuf = output.copyNextSampleBuffer() {
                    guard let block = CMSampleBufferGetDataBuffer(sbuf) else { continue }
                    var lengthAtOffset = 0, totalLength = 0
                    var dataPtr: UnsafeMutablePointer<CChar>?
                    guard CMBlockBufferGetDataPointer(
                        block, atOffset: 0, lengthAtOffsetOut: &lengthAtOffset,
                        totalLengthOut: &totalLength, dataPointerOut: &dataPtr) == kCMBlockBufferNoErr,
                        let dataPtr else { continue }
                    let n = totalLength / MemoryLayout<Float>.size
                    dataPtr.withMemoryRebound(to: Float.self, capacity: n) { fptr in
                        for i in 0..<n {
                            let t = Double(frameIndex + i) / sampleRate
                            var b = Int(t / durationSecs * Double(bins))
                            if b < 0 { b = 0 } else if b >= bins { b = bins - 1 }
                            let v = Double(fptr[i])
                            sumsq[b] += v * v
                            counts[b] += 1
                        }
                    }
                    frameIndex += n
                }
                if reader.status == .failed { return [] }

                var out = [Float](repeating: 0, count: bins)
                for i in 0..<bins {
                    let rms = counts[i] > 0 ? (sumsq[i] / Double(counts[i])).squareRoot() : 0
                    let db = rms > 1e-9 ? 20 * log10(rms) : -60.0
                    out[i] = Float(max(0.0, min(1.0, (db + 60.0) / 60.0)))
                }
                return out
            } catch {
                NSLog("ReelVault native: loudness failed for \(srcId): \(error.localizedDescription)")
                return []
            }
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

private func rvExtractLoudness(
    _ kind: Int32, _ srcId: UnsafePointer<CChar>?, _ out: UnsafeMutablePointer<Float>?, _ maxSamples: Int32
) -> Int32 {
    guard let srcId, let out, maxSamples > 0 else { return -1 }
    let samples = NativeMedia.loudness(
        kind: kind, srcId: String(cString: srcId), maxSamples: Int(maxSamples))
    guard !samples.isEmpty else { return -1 }
    let n = min(samples.count, Int(maxSamples))
    for i in 0..<n { out[i] = samples[i] }
    return Int32(n)
}

private func rvFreeString(_ p: UnsafeMutablePointer<CChar>?) {
    free(p)
}

extension Data {
    /// Decode a lowercase/uppercase hex string into bytes (the inverse of the
    /// core's `bookmark://<hex>` encoding). Returns nil on odd length / non-hex.
    init?(hexString: String) {
        let chars = Array(hexString)
        guard chars.count % 2 == 0 else { return nil }
        var bytes = [UInt8](); bytes.reserveCapacity(chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let hi = chars[i].hexDigitValue, let lo = chars[i + 1].hexDigitValue else { return nil }
            bytes.append(UInt8(hi << 4 | lo))
            i += 2
        }
        self.init(bytes)
    }
}
