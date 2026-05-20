import SwiftUI

struct GridView: View {
    @ObservedObject var viewModel: GridViewModel
    @ObservedObject var detailViewModel: DetailViewModel

    var body: some View {
        ZStack {
            if viewModel.videos.isEmpty && !viewModel.isLoading {
                VStack(spacing: 16) {
                    Image(systemName: "folder.badge.questionmark")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)

                    Text("No videos found")
                        .font(.body)
                        .foregroundColor(.secondary)
                }
            } else if viewModel.isLoading && viewModel.videos.isEmpty {
                ProgressView()
            } else {
                ScrollView {
                    LazyVGrid(
                        columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4),
                        spacing: 8
                    ) {
                        ForEach(viewModel.videos) { video in
                            VideoCardView(
                                video: video,
                                isSelected: viewModel.selectedVideoId == video.id,
                                onSelect: {
                                    viewModel.selectVideo(video)
                                    detailViewModel.loadMetadata(videoId: video.id)
                                }
                            )
                            .onAppear {
                                // Load more when near the end
                                if viewModel.videos.last?.id == video.id && viewModel.hasMore {
                                    viewModel.loadMore()
                                }
                            }
                        }

                        if viewModel.isLoading && !viewModel.videos.isEmpty {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                                .gridCellUnsizedAxes([.horizontal])
                        }
                    }
                    .padding(8)
                }
            }

            // Error overlay
            if let error = viewModel.error {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Image(systemName: "exclamationmark.circle.fill")
                            .foregroundColor(.red)

                        Text(error)
                            .lineLimit(2)

                        Spacer()

                        Button(action: { viewModel.clearError() }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(12)
                    .background(Color(.controlBackgroundColor))
                    .border(Color(.separatorColor), width: 1)
                    .padding(12)

                    Spacer()
                }
            }
        }
    }
}

struct VideoCardView: View {
    let video: VideoSummary
    let isSelected: Bool
    let onSelect: () -> Void

    @State private var isHovered = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Thumbnail
            ZStack(alignment: .center) {
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color(.controlBackgroundColor))

                if isHovered {
                    Image(systemName: "play.fill")
                        .font(.system(size: 32))
                        .foregroundColor(.white)
                }

                // Badges
                VStack(alignment: .trailing, spacing: 8) {
                    // Resolution
                    Text("\(video.width)p")
                        .font(.caption2)
                        .foregroundColor(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.black.opacity(0.7))
                        .cornerRadius(4)

                    Spacer()

                    // Duration
                    Text(video.durationFormatted)
                        .font(.caption2)
                        .foregroundColor(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.black.opacity(0.7))
                        .cornerRadius(4)
                }
                .padding(8)
            }
            .aspectRatio(16/9, contentMode: .fit)

            // Info
            VStack(alignment: .leading, spacing: 4) {
                Text(video.filename)
                    .font(.caption)
                    .lineLimit(2)
                    .foregroundColor(.primary)

                Text("\(video.codecVideo) • \(Int(video.fps))fps")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
            .padding(8)
        }
        .border(
            isSelected ? Color.accentColor : isHovered ? Color.secondary : Color(.separatorColor),
            width: isSelected ? 2 : 1
        )
        .cornerRadius(8)
        .onHover { hovering in
            isHovered = hovering
        }
        .onTapGesture {
            onSelect()
        }
    }
}

#Preview {
    GridView(viewModel: GridViewModel(), detailViewModel: DetailViewModel())
}
