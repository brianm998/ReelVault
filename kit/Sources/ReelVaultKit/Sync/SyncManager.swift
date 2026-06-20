// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2
import Network

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
        //   var req = Reelvault_SyncManifestRequest()
        //   req.pageSize = 200
        //   for try await entry in localServiceClient.getSyncManifest(req).messages {
        //       manifestEntries.append(entry)
        //   }

        // Placeholder: no entries -> nothing to push on this scaffold.
        let manifestEntries: [SyncManifestEntry] = []
        result.total = manifestEntries.count

        // Collect per-entry outcomes as (succeeded: Bool, error: String?) tuples.
        let pushOutcomes = await withTaskGroup(
            of: (Bool, String?).self,
            returning: [(Bool, String?)].self
        ) { group in
            for entry in manifestEntries where !entry.isDerived {
                group.addTask { @MainActor @Sendable [weak self] in
                    guard let self else { return (false, nil) }
                    await self.semaphore.wait()
                    defer { Task { await self.semaphore.signal() } }
                    do {
                        try await self.pushEntry(entry: entry, profile: profile)
                        return (true, nil)
                    } catch {
                        return (false, error.localizedDescription)
                    }
                }
            }
            var outcomes: [(Bool, String?)] = []
            for await outcome in group { outcomes.append(outcome) }
            return outcomes
        }
        for (ok, errMsg) in pushOutcomes {
            if ok { result.completed += 1 } else {
                result.failed += 1
                if let msg = errMsg { result.errors.append(msg) }
            }
        }

        // Sync smart collections: local (source) → remote (destination).
        // We open short-lived transient gRPC connections here so the sync does
        // not interfere with the VideoRepository's persistent connection.
        do {
            guard let remoteTLS = await makeRemoteTLS() else {
                result.errors.append("push: could not verify remote TLS certificate for smart-collection sync")
                return result
            }
            let localTransport = try makeLocalTransport()
            let remoteTransport = try makeRemoteTransport(tls: remoteTLS)
            try await withGRPCClient(transport: localTransport) { localClient in
                try await withGRPCClient(
                    transport: remoteTransport,
                    interceptors: [BearerTokenInterceptor(token: self.token)]
                ) { remoteClient in
                    let localSvc = Reelvault_ReelVault.Client(wrapping: localClient)
                    let remoteSvc = Reelvault_ReelVault.Client(wrapping: remoteClient)
                    _ = try await self.syncSmartCollections(from: localSvc, to: remoteSvc)
                }
            }
        } catch {
            result.errors.append("push smart-collection sync: \(error.localizedDescription)")
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
        //   var req = Reelvault_SyncManifestRequest()
        //   req.pageSize = 200
        //   for try await entry in remoteServiceClient.getSyncManifest(req).messages {
        //       manifestEntries.append(entry)
        //   }

        let manifestEntries: [SyncManifestEntry] = []
        result.total = manifestEntries.count

        let pullOutcomes = await withTaskGroup(
            of: (Bool, String?).self,
            returning: [(Bool, String?)].self
        ) { group in
            for entry in manifestEntries where !entry.isDerived {
                group.addTask { @MainActor @Sendable [weak self] in
                    guard let self else { return (false, nil) }
                    await self.semaphore.wait()
                    defer { Task { await self.semaphore.signal() } }
                    do {
                        try await self.pullEntry(entry: entry, profile: profile)
                        return (true, nil)
                    } catch {
                        return (false, error.localizedDescription)
                    }
                }
            }
            var outcomes: [(Bool, String?)] = []
            for await outcome in group { outcomes.append(outcome) }
            return outcomes
        }
        for (ok, errMsg) in pullOutcomes {
            if ok { result.completed += 1 } else {
                result.failed += 1
                if let msg = errMsg { result.errors.append(msg) }
            }
        }

        // Sync smart collections: remote (source) → local (destination).
        do {
            guard let remoteTLS = await makeRemoteTLS() else {
                result.errors.append("pull: could not verify remote TLS certificate for smart-collection sync")
                return result
            }
            let localTransport = try makeLocalTransport()
            let remoteTransport = try makeRemoteTransport(tls: remoteTLS)
            try await withGRPCClient(transport: remoteTransport,
                                     interceptors: [BearerTokenInterceptor(token: self.token)]) { remoteClient in
                try await withGRPCClient(transport: localTransport) { localClient in
                    let remoteSvc = Reelvault_ReelVault.Client(wrapping: remoteClient)
                    let localSvc = Reelvault_ReelVault.Client(wrapping: localClient)
                    _ = try await self.syncSmartCollections(from: remoteSvc, to: localSvc)
                }
            }
        } catch {
            result.errors.append("pull smart-collection sync: \(error.localizedDescription)")
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

    // MARK: - Smart-collection tag-UUID rewrite

    /// Rewrites tag UUIDs in a smart-collection filter_json string from
    /// source-catalog IDs to destination-catalog IDs, matched by tag name.
    /// Tags with no name match on the destination are dropped silently.
    ///
    /// - Parameters:
    ///   - filterJson: The raw filter_json string from a smart collection
    ///     (e.g. `{"tagIds":["uuid-a","uuid-b"],"operator":"AND"}`).
    ///   - srcTags: id → name mapping for all tags in the SOURCE catalog.
    ///   - dstTags: id → name mapping for all tags in the DESTINATION catalog.
    /// - Returns: A new JSON string with the tagIds array replaced by
    ///   destination-side UUIDs. Returns the original string unchanged if it
    ///   cannot be parsed or contains no tagIds.
    nonisolated private func rewriteFilterJson(
        _ filterJson: String,
        srcTags: [String: String],
        dstTags: [String: String]
    ) -> String {
        guard !filterJson.isEmpty,
              let data = filterJson.data(using: .utf8),
              var dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tagIds = dict["tagIds"] as? [String]
        else { return filterJson }

        // Build name → dst-id lookup so we can resolve each source UUID to a name,
        // then find the matching UUID on the destination side.
        let nameToDst = Dictionary(uniqueKeysWithValues: dstTags.map { ($0.value, $0.key) })

        let remapped: [String] = tagIds.compactMap { srcId in
            guard let name = srcTags[srcId],
                  let dstId = nameToDst[name] else { return nil }
            return dstId
        }
        dict["tagIds"] = remapped

        guard let out = try? JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys]),
              let str = String(data: out, encoding: .utf8)
        else { return filterJson }
        return str
    }

    /// Fetches all tags from a live gRPC client and returns an id → name map.
    /// Uses the existing `ListTags` RPC (Reelvault_ListTagsRequest).
    private func fetchTagMap(
        client: Reelvault_ReelVault.Client<HTTP2ClientTransport.Posix>
    ) async throws -> [String: String] {
        let response = try await client.listTags(Reelvault_ListTagsRequest())
        return Dictionary(
            response.tags.map { ($0.id, $0.name) },
            uniquingKeysWith: { first, _ in first }
        )
    }

    /// Syncs smart collections from the source client to the destination client,
    /// rewriting tag UUIDs so they resolve against the destination catalog.
    ///
    /// Algorithm:
    ///   1. Fetch all tags from both sides to build id→name maps.
    ///   2. List smart collections from the source.
    ///   3. For each smart collection whose filter_json is non-empty, rewrite
    ///      the tagIds array from source UUIDs → destination UUIDs (matched by
    ///      tag name). Unmatched source tags are dropped silently.
    ///   4. Call CreateCollection on the destination with the rewritten filter.
    ///      If a collection with the same name already exists on the destination
    ///      it is created as a new copy — deduplication is a follow-up once
    ///      the proto exposes an idempotent upsert RPC.
    ///
    /// - Parameters:
    ///   - srcClient: gRPC client connected to the SOURCE catalog.
    ///   - dstClient: gRPC client connected to the DESTINATION catalog.
    /// - Returns: Count of collections successfully synced.
    @discardableResult
    private func syncSmartCollections(
        from srcClient: Reelvault_ReelVault.Client<HTTP2ClientTransport.Posix>,
        to dstClient: Reelvault_ReelVault.Client<HTTP2ClientTransport.Posix>
    ) async throws -> Int {
        // 1. Fetch tag maps from both sides in parallel.
        async let srcTagsFetch = fetchTagMap(client: srcClient)
        async let dstTagsFetch = fetchTagMap(client: dstClient)
        let (srcTags, dstTags) = try await (srcTagsFetch, dstTagsFetch)

        // 2. List smart collections from the source.
        let collectionsResponse = try await srcClient.listCollections(Reelvault_ListCollectionsRequest())
        let smartCollections = collectionsResponse.collections.filter { $0.isSmart }

        // 3 + 4. For each smart collection, rewrite filter_json and create on dst.
        var syncedCount = 0
        for collection in smartCollections {
            let rewrittenFilter = rewriteFilterJson(
                collection.filterJson,
                srcTags: srcTags,
                dstTags: dstTags
            )

            var req = Reelvault_CreateCollectionRequest()
            req.name = collection.name
            req.isSmart = true
            req.filterJson = rewrittenFilter

            // TODO: once the proto exposes an idempotent upsert RPC
            // (e.g. UpsertSmartCollection), switch to that so re-running sync
            // doesn't create duplicate collections on the destination.
            _ = try await dstClient.createCollection(req)
            syncedCount += 1
        }
        return syncedCount
    }

    // MARK: - gRPC transport construction

    /// Builds an HTTP/2 transport for the LOCAL embedded core (plaintext loopback).
    private func makeLocalTransport() throws -> HTTP2ClientTransport.Posix {
        try HTTP2ClientTransport.Posix(
            target: .ipv4(host: "127.0.0.1", port: localPort),
            transportSecurity: .plaintext
        )
    }

    /// Fetches and verifies the remote daemon's TLS certificate (TOFU pinning).
    /// Returns nil if the certificate cannot be fetched or the fingerprint does not match.
    private func makeRemoteTLS() async -> HTTP2ClientTransport.Posix.TransportSecurity? {
        guard let der = await PinnedTLS.fetchServerCertificate(
            host: remoteHost,
            port: remotePort,
            expectedFingerprintHex: fingerprint
        ) else {
            NSLog("SyncManager: could not fetch/verify remote certificate (fingerprint mismatch)")
            return nil
        }
        return PinnedTLS.clientSecurity(pinnedCertDER: der)
    }

    /// Builds an HTTP/2 transport for the REMOTE daemon using a pre-fetched TLS security value.
    private func makeRemoteTransport(
        tls: HTTP2ClientTransport.Posix.TransportSecurity
    ) throws -> HTTP2ClientTransport.Posix {
        if IPv4Address(remoteHost) != nil {
            return try HTTP2ClientTransport.Posix(
                target: .ipv4(host: remoteHost, port: remotePort),
                transportSecurity: tls
            )
        } else if IPv6Address(remoteHost) != nil {
            return try HTTP2ClientTransport.Posix(
                target: .ipv6(host: remoteHost, port: remotePort),
                transportSecurity: tls
            )
        } else {
            return try HTTP2ClientTransport.Posix(
                target: .dns(host: remoteHost, port: remotePort),
                transportSecurity: tls
            )
        }
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
