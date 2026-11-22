// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2

/// Resumable-upload chunk size (matches `UPLOAD_CHUNK_BYTES` in shared/).
let uploadChunkBytes = 8 * 1024 * 1024

/// The remote/local operations one push needs; a protocol so the chunk/resume
/// logic is testable without a daemon (mirrors `PushTransport` in shared/).
protocol PushTransport: Sendable {
    /// Bytes the remote already holds for `uploadId` (0 when none).
    func bytesReceived(uploadId: String) async throws -> Int64
    /// Send one chunk; returns the remote video id once the final chunk is indexed.
    func uploadChunk(uploadId: String, filename: String, total: Int64, offset: Int64,
                     contentHash: String, chunk: Data) async throws -> String?
    func remoteVideoForHash(_ contentHash: String) async throws -> String?
    func localCatalogData(videoId: String) async throws -> Reelvault_VideoCatalogData
    func applyRemoteCatalogData(remoteVideoId: String, data: Reelvault_VideoCatalogData) async throws
}

enum SyncPush {
    /// Validate a push source and return its size: a missing/non-file original,
    /// an empty one, or one whose content hash is not computed yet fails with a
    /// specific message instead of an opaque upload error.
    static func checkPushable(filename: String, file: URL, contentHash: String) throws -> Int64 {
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: file.path, isDirectory: &isDir), !isDir.boolValue else {
            throw SyncPushError.notPushable("\(filename): original is not a local file (\(file.path))")
        }
        let size = (try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int64) ?? 0
        if size == 0 { throw SyncPushError.notPushable("\(filename): original is empty (0 bytes); nothing to push") }
        if contentHash.isEmpty { throw SyncPushError.notPushable("\(filename): content hash not computed yet") }
        return size
    }

    /// A remote-supplied filename reduced to a bare, non-hidden name (no path traversal).
    static func safeFilename(_ name: String) -> String {
        let base = (name as NSString).lastPathComponent
        let trimmed = base.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return trimmed.isEmpty ? "video" : base
    }

    /// Push one file: skip the upload when the remote already indexed the hash,
    /// otherwise stream chunks (resuming from the server's `bytes_received`),
    /// then copy the local catalog data onto the remote video. Mirrors `pushFile` in shared/.
    static func pushFile(
        entry: Reelvault_SyncManifestEntry,
        file: URL,
        total: Int64,
        transport t: PushTransport,
        onProgress: @Sendable (Int64) async -> Void = { _ in }
    ) async throws {
        var remoteVideoId = try await t.remoteVideoForHash(entry.contentHash)
        if remoteVideoId == nil {
            // Deterministic id so an interrupted push resumes (hex hash is alphanumeric).
            let uploadId = "sync-" + String(entry.contentHash.filter { $0.isLetter || $0.isNumber }.prefix(40))
            var offset = min(try await t.bytesReceived(uploadId: uploadId), total)
            // Full .part but nothing indexed for the hash (checked above): resend so the server finalizes it.
            if offset == total { offset = 0 }
            let handle = try FileHandle(forReadingFrom: file)
            defer { try? handle.close() }
            while offset < total {
                let len = Int(min(Int64(uploadChunkBytes), total - offset))
                try handle.seek(toOffset: UInt64(offset))
                guard let chunk = try handle.read(upToCount: len), chunk.count == len else {
                    throw SyncPushError.notPushable("\(entry.filename): short read at offset \(offset)")
                }
                let id = try await t.uploadChunk(
                    uploadId: uploadId, filename: entry.filename, total: total,
                    offset: offset, contentHash: entry.contentHash, chunk: chunk
                )
                offset += Int64(len)
                if offset >= total { remoteVideoId = id }
                await onProgress(offset)
            }
            if remoteVideoId == nil { remoteVideoId = try await t.remoteVideoForHash(entry.contentHash) }
        }
        guard let newId = remoteVideoId else {
            throw SyncPushError.notPushable("\(entry.filename): remote did not index the upload")
        }
        let data = try await t.localCatalogData(videoId: entry.videoID)
        try await t.applyRemoteCatalogData(remoteVideoId: newId, data: data)
    }
}

enum SyncPushError: LocalizedError {
    case notPushable(String)
    case http(Int)
    var errorDescription: String? {
        switch self {
        case .notPushable(let m): return m
        case .http(let c): return "HTTP \(c)"
        }
    }
}

/// Fingerprint-pinned, bearer-authenticated HTTP to the remote media server.
final class PinnedHTTP: @unchecked Sendable {
    private let session: URLSession
    private let token: String
    private let base: String

    init(fingerprint: String, token: String, base: String) {
        self.token = token
        self.base = base
        self.session = URLSession(
            configuration: .ephemeral,
            delegate: FingerprintPinningDelegate(expectedFingerprintHex: fingerprint),
            delegateQueue: nil
        )
    }

    func invalidate() { session.finishTasksAndInvalidate() }

    private func request(_ pathAndQuery: String, method: String) throws -> URLRequest {
        guard let url = URL(string: base + pathAndQuery) else { throw MediaClient.MediaError.badURL }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return req
    }

    private static func check(_ response: URLResponse) throws {
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw SyncPushError.http(http.statusCode)
        }
    }

    func bytesReceived(uploadId: String) async throws -> Int64 {
        let (data, resp) = try await session.upload(
            for: try request("/upload/\(uploadId)/status", method: "POST"), from: Data())
        try Self.check(resp)
        let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        return (obj?["bytes_received"] as? NSNumber)?.int64Value ?? 0
    }

    func uploadChunk(uploadId: String, filename: String, total: Int64, offset: Int64,
                     contentHash: String, chunk: Data) async throws -> String? {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&=+?#")
        let enc = { (s: String) in s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s }
        let q = "/upload?filename=\(enc(filename))&upload_id=\(uploadId)&total_size=\(total)"
            + "&offset=\(offset)&content_hash=\(enc(contentHash))"
        var req = try request(q, method: "POST")
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let (data, resp) = try await session.upload(for: req, from: chunk)
        try Self.check(resp)
        let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        if let id = obj?["video_id"] as? String, !id.isEmpty { return id }
        return nil
    }

    /// Download `path` (e.g. `/video/<id>?height=720`) to `dest`, replacing any existing file.
    func download(path: String, to dest: URL) async throws {
        let (tmp, resp) = try await session.download(for: try request(path, method: "GET"))
        try Self.check(resp)
        let fm = FileManager.default
        try? fm.removeItem(at: dest)
        try fm.moveItem(at: tmp, to: dest)
    }
}

struct GRPCPushTransport: PushTransport {
    let http: PinnedHTTP
    let local: Reelvault_ReelVault.Client<HTTP2ClientTransport.Posix>
    let remote: Reelvault_ReelVault.Client<HTTP2ClientTransport.Posix>

    func bytesReceived(uploadId: String) async throws -> Int64 { try await http.bytesReceived(uploadId: uploadId) }

    func uploadChunk(uploadId: String, filename: String, total: Int64, offset: Int64,
                     contentHash: String, chunk: Data) async throws -> String? {
        try await http.uploadChunk(uploadId: uploadId, filename: filename, total: total,
                                   offset: offset, contentHash: contentHash, chunk: chunk)
    }

    func remoteVideoForHash(_ contentHash: String) async throws -> String? {
        var req = Reelvault_LookupByContentHashRequest()
        req.contentHash = [contentHash]
        return try await remote.lookupByContentHash(req).hits.first { $0.contentHash == contentHash }?.videoID
    }

    func localCatalogData(videoId: String) async throws -> Reelvault_VideoCatalogData {
        var req = Reelvault_VideoCatalogDataRequest()
        req.videoID = videoId
        return try await local.getVideoCatalogData(req)
    }

    func applyRemoteCatalogData(remoteVideoId: String, data: Reelvault_VideoCatalogData) async throws {
        var req = Reelvault_ApplyCatalogDataRequest()
        req.videoID = remoteVideoId
        req.data = data
        _ = try await remote.applyVideoCatalogData(req)
    }
}
