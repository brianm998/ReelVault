// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import Security

/// Uploads full-resolution videos from the device to the daemon's import
/// directory over a fingerprint-pinned, token-authenticated `URLSession`
/// (`POST /upload?filename=…`, the core media server's A6 endpoint). Single-shot
/// for now — resumable chunking is a server-side follow-up; the client mirrors
/// the server's single-shot contract.
///
/// `@MainActor` so its `@Published` job list drives SwiftUI directly. Each
/// upload runs on a background `URLSession`; progress hops back to the main
/// actor as `didSendBodyData` fires.
@MainActor
public final class UploadManager: ObservableObject {
    public struct Job: Identifiable, Equatable, Sendable {
        public let id = UUID()
        public let filename: String
        public var progress: Double = 0
        public var status: Status = .uploading
        /// Server-assigned video id once the upload is indexed.
        public var videoId: String?

        public enum Status: Equatable, Sendable {
            case uploading
            case finished
            case failed(String)
        }
    }

    @Published public private(set) var jobs: [Job] = []

    private let endpoint: MediaClient.Endpoint

    public init(endpoint: MediaClient.Endpoint) {
        self.endpoint = endpoint
    }

    /// Whether any job is still in flight (drives a busy indicator).
    public var isUploading: Bool { jobs.contains { $0.status == .uploading } }

    public func clearFinished() {
        jobs.removeAll { $0.status != .uploading }
    }

    /// Begin uploading a local file. `filename` defaults to the URL's last path
    /// component (the server sanitizes it to a bare name). Returns the job id.
    @discardableResult
    public func upload(fileURL: URL, filename: String? = nil) -> UUID {
        let name = filename ?? fileURL.lastPathComponent
        let job = Job(filename: name)
        let id = job.id
        jobs.append(job)
        Task { await perform(id: id, fileURL: fileURL, filename: name) }
        return id
    }

    /// Runs on the main actor; the actual transfer happens off-actor inside
    /// `send` (a background `URLSession`), and progress hops back here.
    private func perform(id: UUID, fileURL: URL, filename: String) async {
        let endpoint = self.endpoint
        let onProgress: @Sendable (Double) -> Void = { [weak self] fraction in
            Task { @MainActor in self?.update(id) { $0.progress = fraction } }
        }
        do {
            let videoId = try await UploadManager.send(
                fileURL: fileURL, filename: filename, endpoint: endpoint, onProgress: onProgress
            )
            update(id) { $0.progress = 1; $0.status = .finished; $0.videoId = videoId }
        } catch {
            update(id) { $0.status = .failed(error.localizedDescription) }
        }
    }

    private func update(_ id: UUID, _ mutate: (inout Job) -> Void) {
        guard let idx = jobs.firstIndex(where: { $0.id == id }) else { return }
        mutate(&jobs[idx])
    }

    /// Perform one pinned, authenticated upload, returning the server's video id
    /// (nil if the server accepted the file but indexing failed).
    private static func send(
        fileURL: URL,
        filename: String,
        endpoint: MediaClient.Endpoint,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws -> String? {
        guard let url = endpoint.uploadURL(filename: filename) else {
            throw MediaClient.MediaError.badURL
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        if let token = endpoint.bearerToken {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let delegate = PinnedUploadDelegate(
            expectedFingerprintHex: endpoint.fingerprintHex, onProgress: onProgress
        )
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        let (data, response): (Data, URLResponse) = try await withCheckedThrowingContinuation { cont in
            let task = session.uploadTask(with: req, fromFile: fileURL) { data, response, error in
                if let error {
                    cont.resume(throwing: error)
                } else if let data, let response {
                    cont.resume(returning: (data, response))
                } else {
                    cont.resume(throwing: MediaClient.MediaError.badURL)
                }
            }
            task.resume()
        }

        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw MediaClient.MediaError.http(http.statusCode)
        }
        return (try? JSONDecoder().decode(UploadResponse.self, from: data))?.video_id
    }

    // swiftlint:disable:next identifier_name
    private struct UploadResponse: Decodable { let video_id: String? }
}

extension MediaClient.Endpoint {
    /// `https://host:port/upload?filename=<name>`.
    func uploadURL(filename: String) -> URL? {
        var comps = URLComponents()
        comps.scheme = "https"
        comps.host = host
        comps.port = mediaPort
        comps.path = "/upload"
        comps.queryItems = [URLQueryItem(name: "filename", value: filename)]
        return comps.url
    }
}

/// URLSession delegate for uploads: pins the server cert (shared logic with
/// `FingerprintPinningDelegate`) and reports send progress.
private final class PinnedUploadDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let expected: String?
    private let onProgress: @Sendable (Double) -> Void

    init(expectedFingerprintHex: String?, onProgress: @escaping @Sendable (Double) -> Void) {
        self.expected = expectedFingerprintHex
        self.onProgress = onProgress
    }

    func urlSession(
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
        if FingerprintPinningDelegate.certificateMatches(trust, expectedFingerprintHex: expected) {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        guard totalBytesExpectedToSend > 0 else { return }
        onProgress(Double(totalBytesSent) / Double(totalBytesExpectedToSend))
    }
}
