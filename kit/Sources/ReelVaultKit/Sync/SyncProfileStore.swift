// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2

/// CRUD for persisted SyncProfiles through the local core's `sync_profiles`
/// table (ListSyncProfiles / UpsertSyncProfile / DeleteSyncProfile), the same
/// store the Kotlin clients use.
///
/// `autoSync` is a per-device preference with no column in the core's table, so
/// it is kept as a set of profile ids in UserDefaults; everything else lives in
/// the core.
@MainActor
public final class SyncProfileStore: ObservableObject {
    @Published public private(set) var profiles: [SyncProfile] = []
    /// Set when the last load/save/delete could not reach the core.
    @Published public private(set) var lastError: String?

    private static let autoSyncKey = "reelvault.syncProfiles.autoSyncIds"
    private let localPort: Int

    /// - Parameter localPort: loopback gRPC port of the embedded core.
    public init(localPort: Int) { self.localPort = localPort }

    // MARK: - Load

    public func load() async {
        do {
            let response = try await withLocalClient { try await $0.listSyncProfiles(Reelvault_ListSyncProfilesRequest()) }
            let auto = Self.autoSyncIds()
            profiles = response.profiles.map { Self.profile(from: $0, autoSync: auto.contains($0.id)) }
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    // MARK: - Upsert

    public func save(_ profile: SyncProfile) async {
        let req = Reelvault_UpsertSyncProfileRequest.with { $0.profile = Self.proto(from: profile) }
        do {
            _ = try await withLocalClient { try await $0.upsertSyncProfile(req) }
            Self.setAutoSync(profile.autoSync, for: profile.id)
            lastError = nil
        } catch {
            lastError = error.localizedDescription
            return
        }
        await load()
    }

    // MARK: - Delete

    public func delete(id: String) async {
        let req = Reelvault_DeleteSyncProfileRequest.with { $0.id = id }
        do {
            _ = try await withLocalClient { try await $0.deleteSyncProfile(req) }
            Self.setAutoSync(false, for: id)
            lastError = nil
        } catch {
            lastError = error.localizedDescription
            return
        }
        await load()
    }

    // MARK: - Mapping

    static func proto(from p: SyncProfile) -> Reelvault_SyncProfileProto {
        var out = Reelvault_SyncProfileProto()
        out.id = p.id
        out.name = p.name
        out.peerKey = p.peerKey
        out.direction = p.direction.rawValue
        out.filterJson = p.filterJson
        out.targetHeight = p.targetHeight
        out.deviceLabel = p.deviceLabel
        if !p.collectionResolutions.isEmpty,
           let data = try? JSONEncoder().encode(p.collectionResolutions),
           let s = String(data: data, encoding: .utf8) {
            out.collectionResolutions = s
        }
        return out
    }

    static func profile(from p: Reelvault_SyncProfileProto, autoSync: Bool) -> SyncProfile {
        var out = SyncProfile(
            id: p.id,
            name: p.name,
            peerKey: p.peerKey,
            direction: SyncDirection(rawValue: p.direction) ?? .toRemote,
            filterJson: p.filterJson,
            targetHeight: p.targetHeight,
            collectionResolutions: (try? JSONDecoder().decode(
                [String: CollectionResolution].self,
                from: Data(p.collectionResolutions.utf8)
            )) ?? [:],
            deviceLabel: p.deviceLabel,
            autoSync: autoSync
        )
        out.lastRunMs = p.lastRunMs == 0 ? nil : p.lastRunMs
        out.createdMs = p.createdMs == 0 ? nil : p.createdMs
        out.updatedMs = p.updatedMs == 0 ? nil : p.updatedMs
        return out
    }

    // MARK: - Auto-sync ids (device-local)

    /// Profiles flagged for sync-on-foreground, resolved against the core.
    public static func autoSyncIds() -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: autoSyncKey) ?? [])
    }

    private static func setAutoSync(_ on: Bool, for id: String) {
        var ids = autoSyncIds()
        if on { ids.insert(id) } else { ids.remove(id) }
        UserDefaults.standard.set(Array(ids), forKey: autoSyncKey)
    }

    // MARK: - Transport

    private func withLocalClient<R: Sendable>(
        _ body: @escaping @Sendable (Reelvault_ReelVault.Client<HTTP2ClientTransport.Posix>) async throws -> R
    ) async throws -> R {
        let transport = try HTTP2ClientTransport.Posix(
            target: .ipv4(host: "127.0.0.1", port: localPort),
            transportSecurity: .plaintext
        )
        return try await withGRPCClient(transport: transport) { raw in
            try await body(Reelvault_ReelVault.Client(wrapping: raw))
        }
    }
}
