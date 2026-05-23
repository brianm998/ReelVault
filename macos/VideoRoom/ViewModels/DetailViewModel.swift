// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI
import Combine

@MainActor
class DetailViewModel: ObservableObject {
    @Published var metadata: VideoMetadata?
    @Published var thumbnail: NSImage?
    @Published var isLoading = false
    @Published var error: String?
    @Published var notes = ""

    @Published var groupMembers: [VideoSummary] = []
    @Published var groupPreferredId: String = ""

    /// Proxies attached to the currently-selected video. Sorted by
    /// pixel count descending (server contract). UI sites can read
    /// `.last` for the smallest proxy (used by inline grid/list
    /// playback) or `.first` for the largest.
    @Published var proxies: [VideoRepository.ProxyInfo] = []

    /// ID of the proxy the user explicitly picked for full-screen
    /// detail-view playback. `nil` means "play the master" (with the
    /// implicit unplayable-master → smallest-proxy fallback applied by
    /// `playbackPath(for:)`).
    @Published var selectedProxyId: String?

    /// The summary backing the currently-selected card. Exposed so the
    /// right panel's proxy section can decide whether to render itself
    /// (keys off `hasProxies` and `playableNatively`, neither of which
    /// is carried by `VideoMetadata`).
    @Published var currentSummary: VideoSummary?

    private var currentVideoSummary: VideoSummary? { currentSummary }
    private let repository = VideoRepository.shared

    /// Called by the grid when the user selects a video — drives the group/stack
    /// section in addition to the regular metadata load.
    func setCurrentVideo(_ video: VideoSummary) {
        currentSummary = video
        if video.isInGroup {
            loadGroupMembers(groupId: video.groupId)
        } else {
            groupMembers = []
            groupPreferredId = ""
        }
        // Reset proxy selection on each new video, then lazily load the
        // proxy list when the summary advertises any. Servers send
        // `proxyCount == 0` for most rows, so the conditional saves a
        // round trip on the common case.
        selectedProxyId = nil
        if video.hasProxies {
            loadProxies(videoId: video.id)
        } else {
            proxies = []
        }
    }

    private func loadProxies(videoId: String) {
        Task {
            do {
                proxies = try await repository.listProxies(videoId: videoId)
            } catch {
                NSLog("Failed to load proxies for \(videoId): \(error)")
                proxies = []
            }
        }
    }

    /// User picked a specific proxy in the detail-view right panel.
    /// Pass `nil` to revert to playing the master.
    func setSelectedProxy(_ proxyId: String?) {
        selectedProxyId = proxyId
    }

    /// The on-disk path that the detail-view player should load, based
    /// on the current proxy selection and the master's playability:
    ///   * User explicitly picked a proxy → that proxy's path.
    ///   * Master is not natively playable AND a proxy exists →
    ///     smallest proxy's path.
    ///   * Otherwise → nil; the caller falls back to the master path.
    func playbackPath(for video: VideoSummary) -> String? {
        if let picked = selectedProxyId,
           let row = proxies.first(where: { $0.id == picked }) {
            return row.path
        }
        if !video.playableNatively, let smallest = proxies.last {
            return smallest.path
        }
        return nil
    }

    private func loadGroupMembers(groupId: String) {
        Task {
            do {
                let (members, preferred) = try await repository.listGroupMembers(groupId: groupId)
                groupMembers = members
                groupPreferredId = preferred
            } catch {
                NSLog("Failed to load group members: \(error)")
            }
        }
    }

    func setGroupPreferred(videoId: String) {
        guard let groupId = currentVideoSummary?.groupId, !groupId.isEmpty else { return }
        Task {
            do {
                if try await repository.setGroupPreferred(groupId: groupId, videoId: videoId) {
                    groupPreferredId = videoId
                } else {
                    error = "Failed to set preferred video"
                }
            } catch {
                self.error = "Failed to set preferred: \(error.localizedDescription)"
            }
        }
    }

    /// Remove the currently-displayed video from its stack. `onComplete`
    /// fires after the daemon call succeeds — callers wire it to
    /// `GridViewModel.refreshAfterStackChange(groupId:)` so the grid's
    /// representative + expanded-member caches catch up.
    func ungroupCurrent(onComplete: ((_ groupId: String) -> Void)? = nil) {
        guard let videoId = metadata?.id else { return }
        // Capture the *current* group id before we tell the daemon to
        // drop it — `VideoMetadata` doesn't track groupId, so we read it
        // from the `VideoSummary` we cached on selection.
        let oldGroupId = currentVideoSummary?.groupId ?? ""
        Task {
            do {
                if try await repository.ungroupVideo(videoId: videoId) {
                    groupMembers = []
                    groupPreferredId = ""
                    onComplete?(oldGroupId)
                } else {
                    error = "Failed to ungroup"
                }
            } catch {
                self.error = "Failed to ungroup: \(error.localizedDescription)"
            }
        }
    }

    func loadMetadata(videoId: String) {
        isLoading = true
        error = nil

        Task {
            do {
                let meta = try await repository.getVideoMetadata(videoId: videoId)
                self.metadata = meta
                self.notes = meta.notes
                self.isLoading = false
                await loadThumbnail(videoId: videoId)
            } catch {
                self.error = "Failed to load metadata: \(error.localizedDescription)"
                self.isLoading = false
            }
        }
    }

    private func loadThumbnail(videoId: String) async {
        do {
            thumbnail = try await repository.getThumbnail(videoId: videoId, size: "large")
        } catch {
            NSLog("Failed to load thumbnail: \(error)")
        }
    }

    func updateNotes(_ newNotes: String) {
        notes = newNotes
        guard let videoId = metadata?.id else { return }
        Task {
            do {
                let ok = try await repository.updateVideoNotes(videoId: videoId, notes: newNotes)
                if !ok { error = "Failed to update notes" }
            } catch {
                self.error = "Failed to update notes: \(error.localizedDescription)"
            }
        }
    }

    func clearError() { error = nil }

    func clear() {
        metadata = nil
        thumbnail = nil
        notes = ""
        error = nil
        groupMembers = []
        groupPreferredId = ""
        currentSummary = nil
        proxies = []
        selectedProxyId = nil
    }
}
