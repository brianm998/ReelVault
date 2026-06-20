// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

/// Orchestrates catalog sync between the local embedded core (loopback gRPC)
/// and a remote daemon (pinned-TLS, bearer-token authenticated). Modelled on
/// UploadManager but bidirectional: push (local -> remote), pull (remote ->
/// local), or mirror (both).
///
/// The local channel is a loopback port opened by LocalCore.start(); the remote
/// is built the same way UploadManager/LoopbackMediaProxy build their connections
/// (host + port + fingerprint + token). Both directions are capped to
/// `maxConcurrent` simultaneous transfers via AsyncSemaphore.
///
/// Generated gRPC stubs (Reelvault_*) are consumed here but declared in the
/// auto-generated kit/Sources/ReelVaultKit/Generated/ files -- do not edit those.
@MainActor
public final class SyncManager: ObservableObject {
    @Published public private(set) var jobs: [SyncJob] = []
    @Published public private(set) var isRunning: Bool = false
    @Published public private(set) var lastResult: SyncRunResult?

    /// Host of the remote daemon (TLS).
    private let remoteHost: String
    /// gRPC port of the remote daemon.
    private let remotePort: Int
    /// Media-server port of the remote daemon (remotePort + 1 by convention).
    private let remoteMediaPort: Int
    /// loopback gRPC port of the embedded local core.
    private let localPort: Int
    /// Bearer token for the remote daemon.
    private let token: String
    /// Pinned TLS fingerprint for the remote daemon.
    private let fingerprint: String

    /// Cap concurrent transfers (push + pull share the same semaphore).
    private let semaphore = AsyncSemaphore(count: 2)

    public init(
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
        remoteMediaPort: Int,
        token: String,
        fingerprint: String
    ) {
        self.localPort = localPort
        self.remoteHost = remoteHost
        self.remotePort = remotePort
        self.remoteMediaPort = remoteMediaPort
        self.token = token
        self.fingerprint = fingerprint
    }

    // MARK: - Public API

    public func startSync(profile: SyncProfile) async {
        guard !isRunning else { return }
        isRunning = true
        defer { isRunning = false }
        jobs = []

        var result = SyncRunResult()

        do {
            switch profile.direction {
            case .toRemote:
                result = try await runPush(profile: profile)
            case .fromRemote:
                result = try await runPull(profile: profile)
            case .mirror:
                var pullResult = try await runPull(profile: profile)
                let pushResult = try await runPush(profile: profile)
                pullResult.total += pushResult.total
                pullResult.completed += pushResult.completed
                pullResult.failed += pushResult.failed
                pullResult.conflicts += pushResult.conflicts
                pullResult.errors += pushResult.errors
                result = pullResult
            }
        } catch {
            result.errors.append(error.localizedDescription)
        }

        lastResult = result
    }

    public func cancel() {
        // Cancellation token support is a follow-up. For now just clear the flag
        // so the next call to startSync is unblocked immediately.
        isRunning = false
    }

    // MARK: - Push (local -> remote)

    private func runPush(profile: SyncProfile) async throws -> SyncRunResult {
        var result = SyncRunResult()

        // Fetch the local manifest via loopback.
        // The real implementation calls the generated Reelvault_ReelVaultClient's
        // getSyncManifest RPC over the loopback port. That generated code lives in
        // kit/Sources/ReelVaultKit/Generated/ and is wired up after proto regen.
        // For the scaffold we keep the logic structure intact and leave the RPC
        // call as a TODO comment so the compiler accepts the file as-is.

        // TODO: replace with generated RPC call once kit/regen-proto.sh is run:
        //   let localClient = Reelvault_ReelVaultClient(
        //       wrapping: GRPCClient(transport: loopbackTransport(port: localPort)))
        //   var req = Reelvault_SyncManifestRequest()
        //   req.pageSize = 200
        //   for try await entry in localClient.getSyncManifest(req).messages {
        //       manifestEntries.append(entry)
        //   }

        // Placeholder: no entries -> nothing to push on this scaffold.
        let manifestEntries: [SyncManifestEntry] = []
        result.total = manifestEntries.count

        await withTaskGroup(of: Void.self) { group in
            for entry in manifestEntries where !entry.isDerived {
                group.addTask { [weak self] in
                    guard let self else { return }
                    await self.semaphore.wait()
                    defer { Task { await self.semaphore.signal() } }
                    do {
                        try await self.pushEntry(entry: entry, profile: profile)
                        await MainActor.run { result.completed += 1 }
                    } catch {
                        await MainActor.run {
                            result.failed += 1
                            result.errors.append(error.localizedDescription)
                        }
                    }
                }
            }
        }

        return result
    }

    private func pushEntry(entry: SyncManifestEntry, profile: SyncProfile) async throws {
        var job = SyncJob(videoId: entry.videoId, filename: entry.filename, direction: .toRemote)
        jobs.append(job)

        let mediaBaseURL = "https://\(remoteHost):\(remoteMediaPort)"
        guard let uploadURL = URL(string: "\(mediaBaseURL)/upload") else {
            throw SyncError.invalidURL(mediaBaseURL)
        }

        var request = URLRequest(url: uploadURL)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(entry.filename, forHTTPHeaderField: "X-Filename")

        // TODO: LocalVideoExport.export(videoId:) -> URL then stream the file body.
        // Full multipart / resumable upload is deferred to the LocalVideoExport
        // integration that already handles photos:// -> temp-file materialization.

        if let idx = jobs.firstIndex(where: { $0.videoId == entry.videoId }) {
            jobs[idx].status = .done
            jobs[idx].progress = 1.0
        }
        _ = job  // suppress unused warning until fully wired
    }

    // MARK: - Pull (remote -> local)

    private func runPull(profile: SyncProfile) async throws -> SyncRunResult {
        var result = SyncRunResult()

        // TODO: replace with generated RPC call once kit/regen-proto.sh is run:
        //   let remoteClient = Reelvault_ReelVaultClient(
        //       wrapping: GRPCClient(transport: pinnedTLSTransport(
        //           host: remoteHost, port: remotePort,
        //           fingerprintHex: fingerprint, token: token)))
        //   var req = Reelvault_SyncManifestRequest()
        //   req.pageSize = 200
        //   for try await entry in remoteClient.getSyncManifest(req).messages {
        //       manifestEntries.append(entry)
        //   }

        let manifestEntries: [SyncManifestEntry] = []
        result.total = manifestEntries.count

        await withTaskGroup(of: Void.self) { group in
            for entry in manifestEntries where !entry.isDerived {
                group.addTask { [weak self] in
                    guard let self else { return }
                    await self.semaphore.wait()
                    defer { Task { await self.semaphore.signal() } }
                    do {
                        try await self.pullEntry(entry: entry, profile: profile)
                        await MainActor.run { result.completed += 1 }
                    } catch {
                        await MainActor.run {
                            result.failed += 1
                            result.errors.append(error.localizedDescription)
                        }
                    }
                }
            }
        }

        return result
    }

    private func pullEntry(entry: SyncManifestEntry, profile: SyncProfile) async throws {
        var job = SyncJob(videoId: entry.videoId, filename: entry.filename, direction: .fromRemote)
        jobs.append(job)

        // 1. PrepareRendition RPC on the remote (tells the server to transcode to
        //    targetHeight if needed and returns the download path).
        // TODO: Reelvault_PrepareRenditionRequest -> Reelvault_PrepareRenditionProgress stream.
        let downloadPath = "/media/\(entry.videoId)/original"
        let renditionBytes: Int64 = 0

        // 2. Storage pre-flight.
        if renditionBytes > 0 {
            let available = try availableStorage()
            guard available > renditionBytes + 100_000_000 else {
                throw SyncError.insufficientStorage(needed: renditionBytes)
            }
        }

        // 3. Download the rendition.
        let mediaBaseURL = "https://\(remoteHost):\(remoteMediaPort)"
        guard let downloadURL = URL(string: "\(mediaBaseURL)\(downloadPath)") else {
            throw SyncError.invalidURL("\(mediaBaseURL)\(downloadPath)")
        }
        let destURL = syncedFilesDirectory().appendingPathComponent(entry.filename)
        var request = URLRequest(url: downloadURL)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        // PinnedTLS URLSession would be used here; plain shared session for the scaffold.
        let (tempURL, _) = try await URLSession.shared.download(for: request)
        try FileManager.default.moveItem(at: tempURL, to: destURL)

        // 4. Ingest into local core via FFI.
        // TODO: reelvault_ingest_synced(path, filename, origin_hash, derived_height)

        // 5. Apply catalog metadata (tags, collections, rating, color label).
        // TODO: Reelvault_VideoCatalogDataRequest -> Reelvault_ApplyCatalogDataRequest on local.

        if let idx = jobs.firstIndex(where: { $0.videoId == entry.videoId }) {
            jobs[idx].status = .done
            jobs[idx].progress = 1.0
        }
        _ = job  // suppress unused warning until fully wired
    }

    // MARK: - Helpers

    private func syncedFilesDirectory() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let dir = docs.appendingPathComponent("ReelVaultSynced", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func availableStorage() throws -> Int64 {
        let attrs = try FileManager.default.attributesOfFileSystem(forPath: NSHomeDirectory())
        return (attrs[.systemFreeSize] as? Int64) ?? 0
    }
}

// MARK: - Placeholder types (replaced by generated proto stubs after regen)

/// Stand-in for Reelvault_SyncManifestEntry until proto stubs are regenerated.
struct SyncManifestEntry {
    var videoId: String
    var filename: String
    var contentHash: String
    var isDerived: Bool
    var nextCursor: String
}

// MARK: - Errors

public enum SyncError: LocalizedError {
    case invalidURL(String)
    case insufficientStorage(needed: Int64)
    case prepareFailed(String)

    public var errorDescription: String? {
        switch self {
        case .invalidURL(let url):
            return "Invalid URL: \(url)"
        case .insufficientStorage(let needed):
            return "Insufficient storage: need \(needed / 1_000_000) MB"
        case .prepareFailed(let detail):
            return "PrepareRendition failed: \(detail)"
        }
    }
}

// MARK: - AsyncSemaphore

/// Simple actor-based semaphore for capping concurrent async tasks.
actor AsyncSemaphore {
    private var count: Int
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(count: Int) { self.count = count }

    func wait() async {
        if count > 0 { count -= 1; return }
        await withCheckedContinuation { cont in waiters.append(cont) }
    }

    func signal() {
        if let waiter = waiters.first {
            waiters.removeFirst()
            waiter.resume()
        } else {
            count += 1
        }
    }
}
