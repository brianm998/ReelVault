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

    private var currentVideoSummary: VideoSummary?
    private let repository = VideoRepository.shared

    /// Called by the grid when the user selects a video — drives the group/stack
    /// section in addition to the regular metadata load.
    func setCurrentVideo(_ video: VideoSummary) {
        currentVideoSummary = video
        if video.isInGroup {
            loadGroupMembers(groupId: video.groupId)
        } else {
            groupMembers = []
            groupPreferredId = ""
        }
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
        currentVideoSummary = nil
    }
}
