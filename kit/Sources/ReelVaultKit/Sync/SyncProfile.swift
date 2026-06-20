// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

public enum SyncDirection: String, Codable, Sendable {
    case toRemote = "TO_REMOTE"
    case fromRemote = "FROM_REMOTE"
    case mirror = "MIRROR"
}

public struct SyncProfile: Identifiable, Codable, Sendable {
    public var id: String
    public var name: String
    public var peerKey: String
    public var direction: SyncDirection
    public var filterJson: String
    public var targetHeight: Int32
    public var collectionResolutions: [String: CollectionResolution]
    public var deviceLabel: String
    public var lastRunMs: Int64?
    public var createdMs: Int64?
    public var updatedMs: Int64?
    /// When true, this profile is triggered automatically each time the app foregrounds.
    public var autoSync: Bool

    public init(
        id: String = UUID().uuidString,
        name: String,
        peerKey: String,
        direction: SyncDirection,
        filterJson: String = "",
        targetHeight: Int32 = 1080,
        collectionResolutions: [String: CollectionResolution] = [:],
        deviceLabel: String = "",
        autoSync: Bool = false
    ) {
        self.id = id
        self.name = name
        self.peerKey = peerKey
        self.direction = direction
        self.filterJson = filterJson
        self.targetHeight = targetHeight
        self.collectionResolutions = collectionResolutions
        self.deviceLabel = deviceLabel
        self.autoSync = autoSync
        self.lastRunMs = nil
        self.createdMs = nil
        self.updatedMs = nil
    }
}

public struct CollectionResolution: Codable, Sendable {
    public enum Action: String, Codable, Sendable {
        case combine, rename, skip
    }
    public var action: Action
    public var renameTo: String?

    public init(action: Action, renameTo: String? = nil) {
        self.action = action
        self.renameTo = renameTo
    }
}

public struct SyncJob: Identifiable, Sendable {
    public let id: String
    public var videoId: String
    public var filename: String
    public var direction: SyncDirection
    public var status: SyncJobStatus
    public var progress: Float

    public init(id: String = UUID().uuidString, videoId: String, filename: String, direction: SyncDirection) {
        self.id = id
        self.videoId = videoId
        self.filename = filename
        self.direction = direction
        self.status = .pending
        self.progress = 0
    }
}

public enum SyncJobStatus: Sendable {
    case pending, inFlight, done, failed(String), skipped
}

public struct SyncRunResult: Sendable {
    public var total: Int
    public var completed: Int
    public var failed: Int
    public var conflicts: Int
    public var errors: [String]

    public init(total: Int = 0, completed: Int = 0, failed: Int = 0, conflicts: Int = 0, errors: [String] = []) {
        self.total = total
        self.completed = completed
        self.failed = failed
        self.conflicts = conflicts
        self.errors = errors
    }
}
