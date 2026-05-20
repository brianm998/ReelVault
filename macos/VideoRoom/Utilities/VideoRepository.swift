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

    private init() {}

    // MARK: - Connection lifecycle

    func connect(host: String = "localhost", port: Int = 50051) async -> Bool {
        if grpcClient != nil {
            return isConnected
        }

        do {
            let transport = try HTTP2ClientTransport.Posix(
                target: .dns(host: host, port: port),
                transportSecurity: .plaintext
            )
            let client = GRPCClient(transport: transport)
            self.grpcClient = client
            self.serviceClient = Videoroom_VideoRoom.Client(wrapping: client)

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

    // MARK: - Videos

    func listVideos(
        limit: Int32 = 50,
        offset: Int32 = 0,
        searchQuery: String = "",
        sortBy: String = "indexed_at",
        sortAscending: Bool = false,
        locationPath: String = ""
    ) async throws -> (videos: [VideoSummary], totalCount: Int64) {
        guard let client = serviceClient else { throw RepositoryError.notConnected }

        if !searchQuery.isEmpty {
            var request = Videoroom_SearchRequest()
            request.query = searchQuery
            request.limit = limit
            request.offset = offset
            let response = try await client.searchVideos(request)
            return (response.videos.map(Self.makeSummary), response.totalCount)
        }

        var request = Videoroom_ListVideosRequest()
        request.limit = limit
        request.offset = offset
        request.sortBy = sortBy
        request.sortAscending = sortAscending
        request.locationPath = locationPath
        let response = try await client.listVideos(request)
        return (response.videos.map(Self.makeSummary), response.totalCount)
    }

    func getVideoMetadata(videoId: String) async throws -> VideoMetadata {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_GetMetadataRequest()
        request.videoID = videoId
        let proto = try await client.getMetadata(request)
        return Self.makeMetadata(proto)
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
    func getStatusPing() async throws {
        // Just calling listLibraryLocations as a cheap, idempotent ping.
        let service = Videoroom_VideoRoom.Client(wrapping: self)
        _ = try await service.listLibraryLocations(Videoroom_ListLocationsRequest())
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
