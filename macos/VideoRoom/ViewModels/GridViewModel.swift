import SwiftUI
import Combine

class GridViewModel: ObservableObject {
    @Published var videos: [VideoSummary] = []
    @Published var selectedVideoId: String?
    @Published var isLoading = false
    @Published var error: String?
    @Published var totalCount = 0
    @Published var hasMore = false
    @Published var searchQuery = ""
    @Published var sortBy = "filename"
    @Published var sortAscending = true

    private let repository = VideoRepository.shared
    private var currentPage = 0
    private let pageSize: Int32 = 50
    private var cancellables = Set<AnyCancellable>()

    init() {
        setupBindings()
    }

    private func setupBindings() {
        $searchQuery
            .debounce(for: 0.5, scheduler: DispatchQueue.main)
            .removeDuplicates()
            .sink { [weak self] _ in
                self?.currentPage = 0
                self?.videos = []
                self?.loadVideos()
            }
            .store(in: &cancellables)

        $sortBy
            .removeDuplicates()
            .sink { [weak self] _ in
                self?.currentPage = 0
                self?.videos = []
                self?.loadVideos()
            }
            .store(in: &cancellables)
    }

    func loadVideos() {
        isLoading = true
        error = nil

        Task {
            do {
                let results = try await repository.listVideos(
                    limit: pageSize,
                    offset: Int32(currentPage * Int(pageSize)),
                    searchQuery: searchQuery,
                    sortBy: sortBy
                )

                await MainActor.run {
                    if currentPage == 0 {
                        self.videos = results
                    } else {
                        self.videos.append(contentsOf: results)
                    }
                    self.hasMore = results.count == Int(pageSize)
                    self.isLoading = false
                }
            } catch {
                await MainActor.run {
                    self.error = "Failed to load videos: \(error.localizedDescription)"
                    self.isLoading = false
                }
            }
        }
    }

    func loadMore() {
        guard hasMore && !isLoading else { return }
        currentPage += 1
        loadVideos()
    }

    func selectVideo(_ video: VideoSummary) {
        selectedVideoId = video.id
    }

    func clearError() {
        error = nil
    }

    deinit {
        cancellables.removeAll()
    }
}
