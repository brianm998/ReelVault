// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AVFoundation
import CryptoKit
import Foundation
import Security

/// Downloads media (downscaled renditions or originals) from the daemon's
/// HTTPS media endpoint over a fingerprint-pinned `URLSession`, caching results
/// in `MediaCache`. Returns a local file URL the UI can hand to `AVPlayer`.
///
/// This is "download-then-play": correct and pin-secure, but not progressive.
/// True progressive/HLS streaming (an `AVAssetResourceLoaderDelegate` bridging
/// AVPlayer's range requests to the pinned endpoint) is a follow-up — it depends
/// on the core HTTPS media server (A3/A4) being in place to exercise.
public actor MediaClient {
    /// Where + how to reach a daemon's media server.
    public struct Endpoint: Sendable {
        public var host: String
        public var mediaPort: Int
        /// SHA-256 (hex) of the daemon cert to pin. `nil` disables pinning
        /// (only for trusted/dev setups).
        public var fingerprintHex: String?
        /// Pairing bearer token (added in A5); attached as `Authorization`.
        public var bearerToken: String?

        public init(host: String, mediaPort: Int, fingerprintHex: String?, bearerToken: String? = nil) {
            self.host = host
            self.mediaPort = mediaPort
            self.fingerprintHex = fingerprintHex
            self.bearerToken = bearerToken
        }

        /// `https://host:port/video/{id}?height=H` (height 0 => original).
        func renditionURL(videoId: String, height: Int) -> URL? {
            var comps = URLComponents()
            comps.scheme = "https"
            comps.host = host
            comps.port = mediaPort
            comps.path = "/video/\(videoId)"
            if height > 0 { comps.queryItems = [URLQueryItem(name: "height", value: String(height))] }
            return comps.url
        }
    }

    private let cache: MediaCache

    public init(cache: MediaCache = .shared) {
        self.cache = cache
    }

    /// Local file URL for the video at the given downscale height, downloading +
    /// caching on a miss. `height == 0` means the original.
    public func localURL(videoId: String, height: Int, from endpoint: Endpoint) async throws -> URL {
        if let cached = await cache.cachedURL(videoId: videoId, height: height) {
            return cached
        }
        guard let url = endpoint.renditionURL(videoId: videoId, height: height) else {
            throw MediaError.badURL
        }
        var req = URLRequest(url: url)
        if let token = endpoint.bearerToken {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let delegate = FingerprintPinningDelegate(expectedFingerprintHex: endpoint.fingerprintHex)
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let (tmp, response) = try await session.download(for: req)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw MediaError.http(http.statusCode)
        }
        return try await cache.adopt(tmp, videoId: videoId, height: height)
    }

    /// Convenience: produce an `AVPlayerItem` for the (downloaded, cached) rendition.
    public func playerItem(videoId: String, height: Int, from endpoint: Endpoint) async throws -> AVPlayerItem {
        let url = try await localURL(videoId: videoId, height: height, from: endpoint)
        return AVPlayerItem(url: url)
    }

    public enum MediaError: Error { case badURL, http(Int) }
}

/// URLSession delegate that pins the server's leaf certificate by SHA-256
/// fingerprint: it accepts the connection only if the presented cert matches.
public final class FingerprintPinningDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    private let expected: String?

    public init(expectedFingerprintHex: String?) {
        self.expected = expectedFingerprintHex?.lowercased()
    }

    public func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust
        else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        if Self.certificateMatches(trust, expectedFingerprintHex: expected) {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }

    /// Whether the server trust's leaf certificate matches the pinned SHA-256
    /// fingerprint. A `nil` fingerprint disables pinning (accepts any cert —
    /// dev only). Shared by the download (`MediaClient`) and upload
    /// (`UploadManager`) sessions so both pin identically.
    public static func certificateMatches(_ trust: SecTrust, expectedFingerprintHex expected: String?) -> Bool {
        guard let expected = expected?.lowercased() else { return true }
        guard let der = leafDER(from: trust) else { return false }
        let fp = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
        return fp == expected
    }

    private static func leafDER(from trust: SecTrust) -> Data? {
        if #available(macOS 12.0, iOS 15.0, *) {
            guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
                  let leaf = chain.first else { return nil }
            return SecCertificateCopyData(leaf) as Data
        } else {
            guard SecTrustGetCertificateCount(trust) > 0,
                  let leaf = SecTrustGetCertificateAtIndex(trust, 0) else { return nil }
            return SecCertificateCopyData(leaf) as Data
        }
    }
}
