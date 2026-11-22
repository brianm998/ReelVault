import XCTest
@testable import ReelVaultKit

private final class FakePush: PushTransport, @unchecked Sendable {
    var existingHash: String?
    var received: Int64 = 0
    var chunks: [(offset: Int64, count: Int)] = []
    var applied: String?
    func bytesReceived(uploadId: String) async throws -> Int64 { received }
    func uploadChunk(uploadId: String, filename: String, total: Int64, offset: Int64,
                     contentHash: String, chunk: Data) async throws -> String? {
        chunks.append((offset, chunk.count))
        existingHash = contentHash
        return offset + Int64(chunk.count) >= total ? "remote-1" : nil
    }
    func remoteVideoForHash(_ contentHash: String) async throws -> String? {
        existingHash == contentHash ? "remote-1" : nil
    }
    func localCatalogData(videoId: String) async throws -> Reelvault_VideoCatalogData { Reelvault_VideoCatalogData() }
    func applyRemoteCatalogData(remoteVideoId: String, data: Reelvault_VideoCatalogData) async throws {
        applied = remoteVideoId
    }
}

final class SyncPushTests: XCTestCase {
    private func tempFile(bytes: Int) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mov")
        try Data(repeating: 7, count: bytes).write(to: url)
        return url
    }

    private func entry(hash: String = "abc123") -> Reelvault_SyncManifestEntry {
        var e = Reelvault_SyncManifestEntry()
        e.videoID = "local-1"; e.filename = "a.mov"; e.contentHash = hash
        return e
    }

    func testChunksResumeFromServerOffsetAndApplyCatalogData() async throws {
        let size = uploadChunkBytes * 2 + 5
        let file = try tempFile(bytes: size)
        defer { try? FileManager.default.removeItem(at: file) }
        let fake = FakePush()
        fake.received = Int64(uploadChunkBytes)
        try await SyncPush.pushFile(entry: entry(), file: file, total: Int64(size), transport: fake)
        XCTAssertEqual(fake.chunks.map { $0.offset }, [Int64(uploadChunkBytes), Int64(uploadChunkBytes * 2)])
        XCTAssertEqual(fake.chunks.last?.count, 5)
        XCTAssertEqual(fake.applied, "remote-1")
    }

    func testHashAlreadyOnRemoteSkipsUpload() async throws {
        let file = try tempFile(bytes: 10)
        defer { try? FileManager.default.removeItem(at: file) }
        let fake = FakePush()
        fake.existingHash = "abc123"
        try await SyncPush.pushFile(entry: entry(), file: file, total: 10, transport: fake)
        XCTAssertTrue(fake.chunks.isEmpty)
        XCTAssertEqual(fake.applied, "remote-1")
    }

    func testCheckPushableRejectsEmptyMissingAndUnhashed() throws {
        let empty = try tempFile(bytes: 0)
        defer { try? FileManager.default.removeItem(at: empty) }
        XCTAssertThrowsError(try SyncPush.checkPushable(filename: "a", file: empty, contentHash: "h"))
        XCTAssertThrowsError(try SyncPush.checkPushable(filename: "a", file: URL(fileURLWithPath: "/nope/x"), contentHash: "h"))
        let f = try tempFile(bytes: 3)
        defer { try? FileManager.default.removeItem(at: f) }
        XCTAssertThrowsError(try SyncPush.checkPushable(filename: "a", file: f, contentHash: ""))
        XCTAssertEqual(try SyncPush.checkPushable(filename: "a", file: f, contentHash: "h"), 3)
    }

    func testSafeFilenameStripsTraversal() {
        XCTAssertEqual(SyncPush.safeFilename("../../etc/passwd"), "passwd")
        XCTAssertEqual(SyncPush.safeFilename("clip.mov"), "clip.mov")
        XCTAssertEqual(SyncPush.safeFilename(".."), "video")
    }
}
