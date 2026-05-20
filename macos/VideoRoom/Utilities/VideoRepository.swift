import Foundation
import AppKit
import GRPC
import NIO

class VideoRepository: ObservableObject {
    static let shared = VideoRepository()

    private var channel: GRPCChannel?
    // Note: gRPC client type to be generated from proto files
    // private var client: Videoroom_VideoRoomNIOClient?
    private let queue = DispatchQueue(label: "com.videoroom.repository")

    @Published var isConnected = false

    private init() {}

    func connect() async -> Bool {
        return await withCheckedContinuation { continuation in
            queue.async { [weak self] in
                do {
                    let eventLoopGroup = MultiThreadedEventLoopGroup(numberOfThreads: 1)
                    let channel = try GRPCChannelPool.with(
                        target: .host("localhost", port: 50051),
                        transportSecurity: .plaintext,
                        eventLoopGroup: eventLoopGroup
                    )

                    self?.channel = channel
                    // self?.client = Videoroom_VideoRoomNIOClient(channel: channel)

                    DispatchQueue.main.async {
                        self?.isConnected = true
                    }

                    continuation.resume(returning: true)
                } catch {
                    NSLog("Failed to connect to gRPC: \(error)")
                    DispatchQueue.main.async {
                        self?.isConnected = false
                    }
                    continuation.resume(returning: false)
                }
            }
        }
    }

    func disconnect() {
        queue.async { [weak self] in
            try? self?.channel?.close().wait()
            self?.channel = nil
            self?.client = nil

            DispatchQueue.main.async {
                self?.isConnected = false
            }
        }
    }

    // MARK: - Video Operations

    func listVideos(
        limit: Int32 = 50,
        offset: Int32 = 0,
        searchQuery: String = "",
        sortBy: String = "filename"
    ) async throws -> [VideoSummary] {
        guard channel != nil else {
            throw NSError(domain: "VideoRepository", code: -1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        // TODO: Replace with actual gRPC call once proto files are compiled
        // var request = Videoroom_ListVideosRequest()
        // request.limit = limit
        // request.offset = offset
        // request.searchQuery = searchQuery
        // request.sortBy = sortBy
        // let response = try await client.listVideos(request)

        // Stub implementation
        return []
    }

    func getVideoMetadata(videoId: String) async throws -> VideoMetadata {
        guard channel != nil else {
            throw NSError(domain: "VideoRepository", code: -1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        // TODO: Replace with actual gRPC call once proto files are compiled
        // var request = Videoroom_GetMetadataRequest()
        // request.videoID = videoId
        // let proto = try await client.getMetadata(request)

        // Stub implementation - throw error for now
        throw NSError(domain: "VideoRepository", code: -1, userInfo: [NSLocalizedDescriptionKey: "Proto generation pending"])
    }

    func getThumbnail(videoId: String, size: String = "medium") async throws -> NSImage? {
        guard channel != nil else {
            throw NSError(domain: "VideoRepository", code: -1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        // TODO: Replace with actual gRPC call once proto files are compiled
        // var request = Videoroom_GetThumbnailRequest()
        // request.videoID = videoId
        // request.size = size
        // let call = client.getThumbnail(request)
        // var imageData = Data()
        // for try await response in call {
        //     imageData.append(response.data)
        // }

        return nil  // Stub: no thumbnail yet
    }

    func updateVideoNotes(videoId: String, notes: String) async throws -> Bool {
        guard channel != nil else {
            throw NSError(domain: "VideoRepository", code: -1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        // TODO: Replace with actual gRPC call once proto files are compiled
        return false  // Stub implementation
    }

    func tagVideos(videoIds: [String], tagId: String) async throws -> Bool {
        guard channel != nil else {
            throw NSError(domain: "VideoRepository", code: -1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        // TODO: Replace with actual gRPC call once proto files are compiled
        return false  // Stub implementation
    }

    func untagVideos(videoIds: [String], tagId: String) async throws -> Bool {
        guard channel != nil else {
            throw NSError(domain: "VideoRepository", code: -1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        // TODO: Replace with actual gRPC call once proto files are compiled
        return false  // Stub implementation
    }

    func addToCollection(videoIds: [String], collectionId: String) async throws -> Bool {
        guard channel != nil else {
            throw NSError(domain: "VideoRepository", code: -1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        // TODO: Replace with actual gRPC call once proto files are compiled
        return false  // Stub implementation
    }
}
