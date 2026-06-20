// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation

/// CRUD for persisted SyncProfiles via the local core's gRPC RPC surface.
///
/// ListSyncProfiles / UpsertSyncProfile / DeleteSyncProfile are new RPCs added
/// to reelvault.proto for the catalog-sync feature. The generated Reelvault_*
/// stubs are wired in after kit/regen-proto.sh runs; until then the store uses
/// UserDefaults as a local fallback so the UI compiles and runs without a rebuilt
/// core.
@MainActor
public final class SyncProfileStore: ObservableObject {
    @Published public private(set) var profiles: [SyncProfile] = []

    /// Key under which profiles are persisted locally (fallback, pre-RPC).
    private static let defaultsKey = "reelvault.syncProfiles"

    public init() {}

    // MARK: - Load

    public func load() async {
        // TODO: call ListSyncProfiles RPC on the local core once proto is regen'd:
        //   var req = Reelvault_ListSyncProfilesRequest()
        //   let response = try await localClient.listSyncProfiles(req)
        //   profiles = response.profiles.map { SyncProfile(from: $0) }

        // Fallback: read from UserDefaults.
        guard let data = UserDefaults.standard.data(forKey: Self.defaultsKey),
              let saved = try? JSONDecoder().decode([SyncProfile].self, from: data)
        else { return }
        profiles = saved
    }

    // MARK: - Upsert

    public func save(_ profile: SyncProfile) async {
        // TODO: call UpsertSyncProfile RPC on the local core once proto is regen'd:
        //   var proto = Reelvault_SyncProfileProto()
        //   proto.id = profile.id
        //   proto.name = profile.name
        //   proto.peerKey = profile.peerKey
        //   proto.direction = profile.direction.rawValue
        //   proto.filterJson = profile.filterJson
        //   proto.targetHeight = profile.targetHeight
        //   proto.deviceLabel = profile.deviceLabel
        //   var req = Reelvault_UpsertSyncProfileRequest()
        //   req.profile = proto
        //   _ = try? await localClient.upsertSyncProfile(req)

        // Fallback: persist to UserDefaults.
        if let idx = profiles.firstIndex(where: { $0.id == profile.id }) {
            profiles[idx] = profile
        } else {
            profiles.append(profile)
        }
        persist()
    }

    // MARK: - Delete

    public func delete(id: String) async {
        // TODO: call DeleteSyncProfile RPC on the local core once proto is regen'd:
        //   var req = Reelvault_DeleteSyncProfileRequest()
        //   req.id = id
        //   _ = try? await localClient.deleteSyncProfile(req)

        profiles.removeAll { $0.id == id }
        persist()
    }

    // MARK: - Private

    private func persist() {
        if let data = try? JSONEncoder().encode(profiles) {
            UserDefaults.standard.set(data, forKey: Self.defaultsKey)
        }
    }
}
