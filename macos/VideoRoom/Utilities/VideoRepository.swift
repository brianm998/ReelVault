// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import Foundation
import AppKit
import GRPCCore
import GRPCNIOTransportHTTP2

@MainActor
class VideoRepository: ObservableObject {
    static let shared = VideoRepository()

    private var grpcClient: GRPCClient<HTTP2ClientTransport.Posix>?
    private var serviceClient: Videoroom_VideoRoom.Client<HTTP2ClientTransport.Posix>?
    private var runTask: Task<Void, Never>?

    @Published var isConnected = false

    /// Host + port of the currently-active connection, or nil when offline.
    /// Used by the launcher flow to decide whether a reconnect is needed.
    private(set) var currentHost: String = "localhost"
    private(set) var currentPort: Int = 50051

    private init() {}

    // MARK: - Connection lifecycle

    /// Connect to the gRPC daemon at `host`:`port`. If the repository is
    /// already connected to a different host:port, it tears that down first
    /// so reconnecting to a freshly-spawned daemon "just works".
    func connect(host: String = "localhost", port: Int = 50051) async -> Bool {
        // Re-use the existing connection if we're already pointed at the same
        // endpoint.
        if grpcClient != nil && isConnected && currentHost == host && currentPort == port {
            return true
        }
        // Otherwise drop the previous connection.
        if grpcClient != nil {
            await disconnect()
        }

        do {
            let transport = try HTTP2ClientTransport.Posix(
                target: .dns(host: host, port: port),
                transportSecurity: .plaintext
            )
            let client = GRPCClient(transport: transport)
            self.grpcClient = client
            self.serviceClient = Videoroom_VideoRoom.Client(wrapping: client)
            self.currentHost = host
            self.currentPort = port

            runTask = Task {
                try? await client.runConnections()
            }

            // Quick smoke-test that the backend is actually reachable.
            do {
                _ = try await client.getStatusPing()
            } catch {
                // If the smoke test fails, tear it back down so a retry can start fresh.
                NSLog("VideoRoom backend not reachable: \(error)")
                client.beginGracefulShutdown()
                await runTask?.value
                runTask = nil
                grpcClient = nil
                serviceClient = nil
                isConnected = false
                return false
            }

            isConnected = true
            return true
        } catch {
            NSLog("Failed to connect to gRPC: \(error)")
            isConnected = false
            return false
        }
    }

    func disconnect() async {
        grpcClient?.beginGracefulShutdown()
        await runTask?.value
        runTask = nil
        grpcClient = nil
        serviceClient = nil
        isConnected = false
    }

    // MARK: - Catalog lifecycle

    /// Ask the daemon to mount the SQLite catalog at `path`. Returns the
    /// resulting [CatalogInfo] on success, or `nil` if the server rejected
    /// the request.
    func openCatalog(path: String) async -> CatalogInfo? {
        guard let service = serviceClient else { return nil }
        var req = Videoroom_OpenCatalogRequest()
        req.path = path
        do {
            let info = try await service.openCatalog(req)
            return CatalogInfo(
                path: info.path,
                name: info.name,
                videoCount: info.videoCount,
                openedAtMs: info.openedAtMs
            )
        } catch {
            NSLog("OpenCatalog failed: \(error)")
            return nil
        }
    }

    /// Ask the daemon to drop its current catalog. Subsequent RPCs will
    /// fail until `openCatalog` succeeds again.
    @discardableResult
    func closeCatalog() async -> Bool {
        guard let service = serviceClient else { return false }
        do {
            let resp = try await service.closeCatalog(Videoroom_CloseCatalogRequest())
            return resp.success
        } catch {
            NSLog("CloseCatalog failed: \(error)")
            return false
        }
    }

    /// Returns `.closed` when no catalog is open or the call fails.
    func getCurrentCatalog() async -> CatalogInfo {
        guard let service = serviceClient else { return .closed }
        do {
            let info = try await service.getCurrentCatalog(Videoroom_GetCurrentCatalogRequest())
            return CatalogInfo(
                path: info.path,
                name: info.name,
                videoCount: info.videoCount,
                openedAtMs: info.openedAtMs
            )
        } catch {
            NSLog("GetCurrentCatalog failed: \(error)")
            return .closed
        }
    }

    // MARK: - Videos

    func listVideos(
        limit: Int32 = 50,
        offset: Int32 = 0,
        searchQuery: String = "",
        sortBy: String = "indexed_at",
        sortAscending: Bool = false,
        locationPath: String = "",
        filterTagIds: [String] = [],
        filterCamera: String = "",
        filterLens: String = "",
        filterCodec: String = "",
        filterCaptureYear: Int32 = 0
    ) async throws -> (videos: [VideoSummary], totalCount: Int64) {
        guard let client = serviceClient else { throw RepositoryError.notConnected }

        if !searchQuery.isEmpty {
            var request = Videoroom_SearchRequest()
            request.query = searchQuery
            request.limit = limit
            request.offset = offset
            request.filterTags = filterTagIds
            let response = try await client.searchVideos(request)
            return (response.videos.map(Self.makeSummary), response.totalCount)
        }

        var request = Videoroom_ListVideosRequest()
        request.limit = limit
        request.offset = offset
        request.sortBy = sortBy
        request.sortAscending = sortAscending
        request.locationPath = locationPath
        request.filterTags = filterTagIds
        request.filterCamera = filterCamera
        request.filterLens = filterLens
        request.filterCodec = filterCodec
        request.filterCaptureYear = filterCaptureYear
        let response = try await client.listVideos(request)
        return (response.videos.map(Self.makeSummary), response.totalCount)
    }

    func getFilterOptions() async -> FilterOptions {
        guard let client = serviceClient else { return FilterOptions() }
        do {
            let response = try await client.getFilterOptions(Videoroom_GetFilterOptionsRequest())
            return FilterOptions(
                cameras: response.cameras,
                lenses: response.lenses,
                codecs: response.codecs,
                captureYears: response.captureYears
            )
        } catch {
            NSLog("Failed to fetch filter options: \(error)")
            return FilterOptions()
        }
    }

    // MARK: - Tags / keywords

    func listTags() async throws -> [Tag] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.listTags(Videoroom_ListTagsRequest())
        return response.tags.map {
            Tag(id: $0.id, name: $0.name, color: $0.color.isEmpty ? nil : $0.color, videoCount: $0.videoCount)
        }
    }

    /// Create a new tag, or return the existing one if a tag with this name
    /// already exists (the backend's create_tag is idempotent on name).
    func createTag(name: String, color: String = "") async throws -> Tag? {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_CreateTagRequest()
        request.name = name
        request.color = color
        let response = try await client.createTag(request)
        return Tag(
            id: response.id,
            name: response.name,
            color: response.color.isEmpty ? nil : response.color,
            videoCount: response.videoCount
        )
    }

    func tagVideos(videoIds: [String], tagId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_TagVideosRequest()
        request.videoIds = videoIds
        request.tagID = tagId
        let response = try await client.tagVideos(request)
        return response.success
    }

    func untagVideos(videoIds: [String], tagId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_UntagVideosRequest()
        request.videoIds = videoIds
        request.tagID = tagId
        let response = try await client.untagVideos(request)
        return response.success
    }

    func getVideoMetadata(videoId: String) async throws -> VideoMetadata {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_GetMetadataRequest()
        request.videoID = videoId
        let proto = try await client.getMetadata(request)
        return Self.makeMetadata(proto)
    }

    /// Fetch all scrub frames for [videoId] in parallel. Returns an array
    /// of [count] entries; individual entries may be nil if a frame failed.
    func getScrubFrames(videoId: String, count: Int = 10) async -> [NSImage?] {
        await withTaskGroup(of: (Int, NSImage?).self) { group in
            for i in 0..<count {
                group.addTask { [self] in
                    let image = try? await self.getThumbnail(videoId: videoId, size: "scrub_\(i)")
                    return (i, image)
                }
            }
            var result: [NSImage?] = Array(repeating: nil, count: count)
            for await (i, image) in group {
                result[i] = image
            }
            return result
        }
    }

    func getThumbnail(videoId: String, size: String = "medium") async throws -> NSImage? {
        guard let client = serviceClient else { throw RepositoryError.notConnected }

        var request = Videoroom_GetThumbnailRequest()
        request.videoID = videoId
        request.size = size

        return try await client.getThumbnail(request) { response in
            var data = Data()
            for try await chunk in response.messages {
                data.append(chunk.data)
            }
            return NSImage(data: data)
        }
    }

    func updateVideoNotes(videoId: String, notes: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_UpdateNotesRequest()
        request.videoID = videoId
        request.notes = notes
        let response = try await client.updateVideoNotes(request)
        return response.success
    }

    // MARK: - Library locations

    func listLibraryLocations() async throws -> [LibraryLocation] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.listLibraryLocations(Videoroom_ListLocationsRequest())
        return response.locations.map { l in
            LibraryLocation(
                path: l.path,
                recursive: l.recursive,
                enabled: l.enabled,
                videoCount: l.videoCount,
                lastScanned: l.lastScanned
            )
        }
    }

    /// Add a library location. Returns (success, server message).
    func addLibraryLocation(path: String, recursive: Bool = true) async throws -> (Bool, String) {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_AddLocationRequest()
        request.path = path
        request.recursive = recursive
        let response = try await client.addLibraryLocation(request)
        return (response.success, response.message)
    }

    /// Streaming scan — yields progress events as the backend works through the library.
    func scanLibrary(
        locationPath: String = "",
        autoGroup: Bool = true
    ) -> AsyncThrowingStream<ScanProgress, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                guard let client = serviceClient else {
                    continuation.finish(throwing: RepositoryError.notConnected)
                    return
                }
                var request = Videoroom_ScanLibraryRequest()
                request.locationPath = locationPath
                request.autoGroup = autoGroup

                do {
                    try await client.scanLibrary(request) { response in
                        for try await proto in response.messages {
                            let progress = ScanProgress(
                                status: proto.status,
                                videosFound: Int(proto.videosFound),
                                videosIndexed: Int(proto.videosIndexed),
                                currentFile: proto.currentFile,
                                progressPercent: proto.progressPercent
                            )
                            continuation.yield(progress)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Groups (Lightroom-style stacks)

    func listGroupMembers(groupId: String) async throws -> (members: [VideoSummary], preferredId: String) {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_ListGroupMembersRequest()
        request.groupID = groupId
        let response = try await client.listGroupMembers(request)
        return (response.members.map(Self.makeSummary), response.preferredVideoID)
    }

    func createGroup(videoIds: [String], name: String = "", preferredVideoId: String = "") async throws -> GroupInfo? {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_CreateGroupRequest()
        request.videoIds = videoIds
        request.name = name
        request.preferredVideoID = preferredVideoId
        let response = try await client.createGroup(request)
        return GroupInfo(
            id: response.id,
            name: response.name,
            size: Int(response.size),
            preferredVideoId: response.preferredVideoID
        )
    }

    func ungroupVideo(videoId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_UngroupVideoRequest()
        request.videoID = videoId
        let response = try await client.ungroupVideo(request)
        return response.success
    }

    func setGroupPreferred(groupId: String, videoId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_SetGroupPreferredRequest()
        request.groupID = groupId
        request.videoID = videoId
        let response = try await client.setGroupPreferred(request)
        return response.success
    }

    // MARK: - Proto → Model

    private static func makeSummary(_ p: Videoroom_VideoSummary) -> VideoSummary {
        VideoSummary(
            id: p.id,
            filename: p.filename,
            path: p.path,
            width: Int(p.width),
            height: Int(p.height),
            durationMs: Int(p.durationMs),
            fps: p.fps,
            codecVideo: p.codecVideo,
            codecAudio: p.codecAudio,
            bitrateKbps: 0,
            sizeBytes: Int(p.sizeBytes),
            indexedAt: p.indexedAt,
            creationDate: p.creationDate,
            tags: p.tags,
            hasThumbnail: p.hasThumbnail_p,
            groupId: p.groupID,
            groupSize: Int(p.groupSize),
            groupPreferredId: p.groupPreferredID,
            groupPreferredPath: p.groupPreferredPath
        )
    }

    private static func makeMetadata(_ p: Videoroom_VideoMetadata) -> VideoMetadata {
        VideoMetadata(
            id: p.id,
            filename: p.filename,
            path: p.path,
            width: Int(p.width),
            height: Int(p.height),
            durationMs: Int(p.durationMs),
            fps: p.fps,
            codecVideo: p.codecVideo,
            codecAudio: p.codecAudio,
            bitrateKbps: Int(p.bitrate / 1000),
            sizeBytes: Int(p.sizeBytes),
            colorSpace: p.colorSpace,
            hdr: p.hdr,
            audioChannels: Int(p.audioChannels),
            audioSampleRate: Int(p.audioSampleRate),
            creationDate: p.creationDate,
            cameraModel: p.cameraModel,
            lensModel: p.lensModel,
            gpsLat: p.gpsLatitude,
            gpsLon: p.gpsLongitude,
            gpsAltitude: p.gpsAltitude,
            notes: p.notes,
            tags: p.tags,
            collections: p.collections
        )
    }
}

private extension GRPCClient {
    /// Tiny no-op call to verify the backend is reachable during connect().
    /// Uses `GetStatus` specifically because that RPC is guaranteed to
    /// succeed even when the daemon has no catalog mounted yet — which is
    /// the normal state right after `ServerLauncher` spawns it with
    /// `--no-catalog`. Any RPC that touches SQL (e.g. `listLibraryLocations`)
    /// would error here and the launcher would wrongly report the daemon
    /// as unreachable.
    func getStatusPing() async throws {
        let service = Videoroom_VideoRoom.Client(wrapping: self)
        _ = try await service.getStatus(Videoroom_GetStatusRequest())
    }
}

enum RepositoryError: LocalizedError {
    case notConnected

    var errorDescription: String? {
        switch self {
        case .notConnected: return "Not connected to VideoRoom backend"
        }
    }
}
