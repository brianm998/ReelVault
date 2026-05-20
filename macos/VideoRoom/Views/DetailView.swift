import SwiftUI

struct DetailView: View {
    @ObservedObject var viewModel: DetailViewModel

    var body: some View {
        ZStack {
            if let metadata = viewModel.metadata {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        // Error
                        if let error = viewModel.error {
                            HStack(spacing: 8) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundColor(.orange)

                                Text(error)
                                    .font(.caption)
                                    .lineLimit(2)

                                Spacer()
                            }
                            .padding(12)
                            .background(Color(.controlBackgroundColor))
                            .border(Color(.separatorColor), width: 1)
                            .cornerRadius(4)
                        }

                        // Filename
                        Text(metadata.filename)
                            .font(.headline)
                            .lineLimit(2)

                        Divider()

                        // Metadata items
                        VStack(alignment: .leading, spacing: 12) {
                            MetadataItemView(label: "Resolution", value: metadata.resolution)
                            MetadataItemView(label: "Duration", value: metadata.durationFormatted)
                            MetadataItemView(label: "FPS", value: String(format: "%.2f", metadata.fps))
                            MetadataItemView(label: "Codec", value: metadata.codecVideo)
                            MetadataItemView(label: "Bitrate", value: metadata.bitrateFormatted)
                            MetadataItemView(label: "Size", value: metadata.sizeFormatted)

                            if !metadata.colorSpace.isEmpty {
                                MetadataItemView(label: "Color Space", value: metadata.colorSpace)
                            }

                            if !metadata.cameraModel.isEmpty {
                                MetadataItemView(label: "Camera", value: metadata.cameraModel)
                            }

                            if metadata.creationDate > 0 {
                                MetadataItemView(label: "Created", value: metadata.creationDateFormatted)
                            }
                        }

                        Divider()

                        // Notes
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Notes")
                                .font(.caption)
                                .foregroundColor(.secondary)

                            TextEditor(text: $viewModel.notes)
                                .font(.caption)
                                .frame(height: 80)
                                .border(Color(.separatorColor), width: 1)
                                .cornerRadius(4)
                                .onChange(of: viewModel.notes) { newValue in
                                    viewModel.updateNotes(newValue)
                                }
                        }

                        Divider()

                        // Tags
                        if !metadata.tags.isEmpty {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Tags")
                                    .font(.caption)
                                    .foregroundColor(.secondary)

                                HStack(spacing: 8) {
                                    ForEach(metadata.tags, id: \.self) { tag in
                                        HStack(spacing: 4) {
                                            Text(tag)
                                                .font(.caption)

                                            Button(action: {
                                                // Remove tag
                                            }) {
                                                Image(systemName: "xmark.circle.fill")
                                                    .font(.caption2)
                                            }
                                            .buttonStyle(.plain)
                                        }
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 4)
                                        .background(Color.accentColor.opacity(0.2))
                                        .cornerRadius(4)
                                    }
                                }
                            }
                        }

                        Spacer()
                    }
                    .padding(16)
                }
            } else if viewModel.isLoading {
                ProgressView()
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 32))
                        .foregroundColor(.secondary)

                    Text("Select a video to view details")
                        .font(.body)
                        .foregroundColor(.secondary)
                }
            }
        }
        .background(Color(.controlBackgroundColor))
    }
}

struct MetadataItemView: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)

            Text(value)
                .font(.body)
        }
    }
}

// Simple flow layout - tags wrap to next line
struct FlowLayout: View {
    let items: [String]
    var spacing: CGFloat = 8

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) {
            var currentRow: [String] = []

            ForEach(items, id: \.self) { item in
                if currentRow.isEmpty {
                    currentRow.append(item)
                } else {
                    currentRow.append(item)
                }
            }

            HStack(spacing: spacing) {
                ForEach(items, id: \.self) { item in
                    Text(item)
                        .font(.caption)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.accentColor.opacity(0.2))
                        .cornerRadius(4)
                }
            }
        }
    }
}

#Preview {
    DetailView(viewModel: DetailViewModel())
}
