import SwiftUI
import Combine

class DetailViewModel: ObservableObject {
    @Published var metadata: VideoMetadata?
    @Published var thumbnail: NSImage?
    @Published var isLoading = false
    @Published var error: String?
    @Published var notes = ""

    private let repository = VideoRepository.shared

    func loadMetadata(videoId: String) {
        isLoading = true
        error = nil

        Task {
            do {
                let metadata = try await repository.getVideoMetadata(videoId: videoId)

                await MainActor.run {
                    self.metadata = metadata
                    self.notes = metadata.notes
                    self.isLoading = false
                }

                // Load thumbnail
                await loadThumbnail(videoId: videoId)
            } catch {
                await MainActor.run {
                    self.error = "Failed to load metadata: \(error.localizedDescription)"
                    self.isLoading = false
                }
            }
        }
    }

    private func loadThumbnail(videoId: String) async {
        do {
            let thumbnail = try await repository.getThumbnail(videoId: videoId)

            await MainActor.run {
                self.thumbnail = thumbnail
            }
        } catch {
            NSLog("Failed to load thumbnail: \(error)")
        }
    }

    func updateNotes(_ newNotes: String) {
        notes = newNotes
        guard let videoId = metadata?.id else { return }

        Task {
            do {
                let success = try await repository.updateVideoNotes(videoId: videoId, notes: newNotes)

                await MainActor.run {
                    if !success {
                        self.error = "Failed to update notes"
                    }
                }
            } catch {
                await MainActor.run {
                    self.error = "Failed to update notes: \(error.localizedDescription)"
                }
            }
        }
    }

    func addTag(_ tagId: String) {
        guard let videoId = metadata?.id else { return }

        Task {
            do {
                let success = try await repository.tagVideos(videoIds: [videoId], tagId: tagId)

                if success {
                    await MainActor.run {
                        // Reload metadata to reflect changes
                        self.loadMetadata(videoId: videoId)
                    }
                } else {
                    await MainActor.run {
                        self.error = "Failed to add tag"
                    }
                }
            } catch {
                await MainActor.run {
                    self.error = "Failed to add tag: \(error.localizedDescription)"
                }
            }
        }
    }

    func removeTag(_ tagId: String) {
        guard let videoId = metadata?.id else { return }

        Task {
            do {
                let success = try await repository.untagVideos(videoIds: [videoId], tagId: tagId)

                if success {
                    await MainActor.run {
                        // Reload metadata to reflect changes
                        self.loadMetadata(videoId: videoId)
                    }
                } else {
                    await MainActor.run {
                        self.error = "Failed to remove tag"
                    }
                }
            } catch {
                await MainActor.run {
                    self.error = "Failed to remove tag: \(error.localizedDescription)"
                }
            }
        }
    }

    func addToCollection(_ collectionId: String) {
        guard let videoId = metadata?.id else { return }

        Task {
            do {
                let success = try await repository.addToCollection(videoIds: [videoId], collectionId: collectionId)

                if success {
                    await MainActor.run {
                        // Reload metadata to reflect changes
                        self.loadMetadata(videoId: videoId)
                    }
                } else {
                    await MainActor.run {
                        self.error = "Failed to add to collection"
                    }
                }
            } catch {
                await MainActor.run {
                    self.error = "Failed to add to collection: \(error.localizedDescription)"
                }
            }
        }
    }

    func clearError() {
        error = nil
    }

    func clear() {
        metadata = nil
        thumbnail = nil
        notes = ""
        error = nil
    }
}
