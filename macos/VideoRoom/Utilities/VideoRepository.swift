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

    // MARK: - Video Operations

    func listVideos(
        limit: Int32 = 50,
        offset: Int32 = 0,
        searchQuery: String = "",
        sortBy: String = "filename"
    ) async throws -> [VideoSummary] {
        guard let client = serviceClient else {
            throw RepositoryError.notConnected
        }

        if !searchQuery.isEmpty {
            var request = Videoroom_SearchRequest()
            request.query = searchQuery
            request.limit = limit
            request.offset = offset
            let response = try await client.searchVideos(request)
            return response.videos.map(Self.makeSummary)
        }

        var request = Videoroom_ListVideosRequest()
        request.limit = limit
        request.offset = offset
        request.sortBy = sortBy
        request.sortAscending = true
        let response = try await client.listVideos(request)
        return response.videos.map(Self.makeSummary)
    }

    func getVideoMetadata(videoId: String) async throws -> VideoMetadata {
        guard let client = serviceClient else {
            throw RepositoryError.notConnected
        }

        var request = Videoroom_GetMetadataRequest()
        request.videoID = videoId
        let proto = try await client.getMetadata(request)
        return Self.makeMetadata(proto)
    }

    func getThumbnail(videoId: String, size: String = "medium") async throws -> NSImage? {
        guard let client = serviceClient else {
            throw RepositoryError.notConnected
        }

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
        guard let client = serviceClient else {
            throw RepositoryError.notConnected
        }

        var request = Videoroom_UpdateNotesRequest()
        request.videoID = videoId
        request.notes = notes
        let response = try await client.updateVideoNotes(request)
        return response.success
    }

    func tagVideos(videoIds: [String], tagId: String) async throws -> Bool {
        guard let client = serviceClient else {
            throw RepositoryError.notConnected
        }

        var request = Videoroom_TagVideosRequest()
        request.videoIds = videoIds
        request.tagID = tagId
        let response = try await client.tagVideos(request)
        return response.success
    }

    func untagVideos(videoIds: [String], tagId: String) async throws -> Bool {
        guard let client = serviceClient else {
            throw RepositoryError.notConnected
        }

        var request = Videoroom_UntagVideosRequest()
        request.videoIds = videoIds
        request.tagID = tagId
        let response = try await client.untagVideos(request)
        return response.success
    }

    func addToCollection(videoIds: [String], collectionId: String) async throws -> Bool {
        guard let client = serviceClient else {
            throw RepositoryError.notConnected
        }

        var request = Videoroom_AddToCollectionRequest()
        request.videoIds = videoIds
        request.collectionID = collectionId
        let response = try await client.addToCollection(request)
        return response.success
    }

    // MARK: - Proto → Model

    private static func makeSummary(_ p: Videoroom_VideoSummary) -> VideoSummary {
        VideoSummary(
            id: p.id,
            filename: p.filename,
            width: Int(p.width),
            height: Int(p.height),
            durationMs: Int(p.durationMs),
            fps: p.fps,
            codecVideo: p.codecVideo,
            bitrateKbps: 0,
            sizeBytes: Int(p.sizeBytes)
        )
    }

    private static func makeMetadata(_ p: Videoroom_VideoMetadata) -> VideoMetadata {
        VideoMetadata(
            id: p.id,
            filename: p.filename,
            width: Int(p.width),
            height: Int(p.height),
            durationMs: Int(p.durationMs),
            fps: p.fps,
            codecVideo: p.codecVideo,
            codecAudio: p.codecAudio,
            bitrateKbps: Int(p.bitrate / 1000),
            sizeBytes: Int(p.sizeBytes),
            colorSpace: p.colorSpace,
            audioChannels: Int(p.audioChannels),
            creationDate: p.creationDate,
            cameraModel: p.cameraModel,
            lensModel: p.lensModel,
            gpsLat: p.gpsLatitude,
            gpsLon: p.gpsLongitude,
            notes: p.notes,
            tags: p.tags,
            collections: p.collections
        )
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
