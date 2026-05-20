import SwiftUI
import AppKit

struct DetailView: View {
    @ObservedObject var viewModel: DetailViewModel
    @Binding var thumbnailWidth: CGFloat
    let onCollapse: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Button(action: onCollapse) {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Hide details panel (Tab)")

                Text("DETAILS")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.top, 12)
            .padding(.bottom, 8)

            // Thumbnail-size slider — controls the grid's adaptive column width
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("Thumbnail size")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Spacer()
                    Text("\(Int(thumbnailWidth))")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Slider(value: $thumbnailWidth, in: 120...400)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)

            Divider()

            // Content
            if let metadata = viewModel.metadata {
                ScrollView {
                    metadataContent(metadata)
                        .padding(16)
                }
            } else if viewModel.isLoading {
                Spacer()
                ProgressView()
                Spacer()
            } else {
                placeholderContent
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.controlBackgroundColor))
    }

    @ViewBuilder
    private func metadataContent(_ metadata: VideoMetadata) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            if let error = viewModel.error {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                    Text(error).font(.caption).lineLimit(2)
                    Spacer()
                }
                .padding(8)
                .background(Color(.controlBackgroundColor))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(.separatorColor)))
                .cornerRadius(4)
            }

            // Filename
            Text(metadata.filename)
                .font(.headline)
                .lineLimit(2)

            // Open externally
            Button {
                let url = URL(fileURLWithPath: metadata.path)
                if FileManager.default.fileExists(atPath: url.path) {
                    NSWorkspace.shared.open(url)
                }
            } label: {
                HStack {
                    Image(systemName: "play.circle.fill")
                    Text("Open in External App")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)

            Divider()

            // Technical metadata
            VStack(alignment: .leading, spacing: 12) {
                MetadataItemView(label: "Resolution", value: metadata.resolution)
                MetadataItemView(label: "Duration", value: metadata.durationFormatted)
                MetadataItemView(label: "FPS", value: String(format: "%.2f", metadata.fps))
                MetadataItemView(label: "Video Codec", value: metadata.codecVideo.isEmpty ? "—" : metadata.codecVideo)
                if !metadata.codecAudio.isEmpty {
                    MetadataItemView(label: "Audio Codec", value: metadata.codecAudio)
                }
                MetadataItemView(label: "Bitrate", value: metadata.bitrateFormatted)
                MetadataItemView(label: "Size", value: metadata.sizeFormatted)

                if !metadata.colorSpace.isEmpty {
                    MetadataItemView(label: "Color Space", value: metadata.colorSpace)
                }
                if metadata.hdr {
                    MetadataItemView(label: "HDR", value: "Yes")
                }
            }

            // EXIF section
            let hasExif = !metadata.cameraModel.isEmpty
                || !metadata.lensModel.isEmpty
                || metadata.creationDate > 0
                || metadata.gpsLat != 0 || metadata.gpsLon != 0
            if hasExif {
                Divider()
                Text("EXIF")
                    .font(.caption)
                    .foregroundColor(.secondary)
                VStack(alignment: .leading, spacing: 12) {
                    if !metadata.cameraModel.isEmpty {
                        MetadataItemView(label: "Camera", value: metadata.cameraModel)
                    }
                    if !metadata.lensModel.isEmpty {
                        MetadataItemView(label: "Lens", value: metadata.lensModel)
                    }
                    if metadata.creationDate > 0 {
                        MetadataItemView(label: "Captured", value: metadata.creationDateFormatted)
                    }
                    if metadata.gpsLat != 0 || metadata.gpsLon != 0 {
                        MetadataItemView(
                            label: "GPS",
                            value: String(format: "%.4f, %.4f", metadata.gpsLat, metadata.gpsLon)
                        )
                    }
                }
            }

            // Stack / group section
            if viewModel.groupMembers.count > 1 {
                Divider()
                HStack {
                    Text("Stack (\(viewModel.groupMembers.count))")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Spacer()
                    Button("Ungroup this") {
                        viewModel.ungroupCurrent()
                    }
                    .buttonStyle(.borderless)
                    .controlSize(.small)
                }
                Text("Double-click in the grid opens the preferred variant. Click ⭐ to change preferred.")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                VStack(spacing: 0) {
                    ForEach(Array(viewModel.groupMembers.enumerated()), id: \.element.id) { idx, member in
                        if idx > 0 { Divider() }
                        HStack(spacing: 6) {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(member.filename)
                                    .font(.system(size: 11))
                                    .lineLimit(1)
                                Text("\(member.height > 0 ? "\(member.height)p" : "?") • \(member.codecVideo) • \(member.sizeFormatted)")
                                    .font(.system(size: 9))
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            let isPreferred = member.id == viewModel.groupPreferredId
                            Button {
                                viewModel.setGroupPreferred(videoId: member.id)
                            } label: {
                                Image(systemName: isPreferred ? "star.fill" : "star")
                                    .foregroundColor(isPreferred ? .accentColor : .secondary)
                            }
                            .buttonStyle(.plain)
                            .help(isPreferred ? "Preferred" : "Make preferred")

                            Button {
                                let url = URL(fileURLWithPath: member.path)
                                if FileManager.default.fileExists(atPath: url.path) {
                                    NSWorkspace.shared.open(url)
                                }
                            } label: {
                                Image(systemName: "play.fill")
                            }
                            .buttonStyle(.plain)
                            .help("Open")
                        }
                        .padding(.vertical, 4)
                        .padding(.horizontal, 6)
                    }
                }
                .background(Color(.windowBackgroundColor).opacity(0.5))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(.separatorColor)))
                .cornerRadius(4)
            }

            Divider()

            // Notes
            VStack(alignment: .leading, spacing: 6) {
                Text("Notes")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextEditor(text: Binding(
                    get: { viewModel.notes },
                    set: { viewModel.updateNotes($0) }
                ))
                .font(.caption)
                .frame(height: 80)
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(.separatorColor)))
                .cornerRadius(4)
            }

            // Tags
            if !metadata.tags.isEmpty {
                Divider()
                Text("Tags")
                    .font(.caption)
                    .foregroundColor(.secondary)
                FlowLayout(items: metadata.tags)
            }
        }
    }

    private var placeholderContent: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "square.and.pencil")
                .font(.system(size: 32))
                .foregroundColor(.secondary)
            Text("Select a video to view details")
                .font(.body)
                .foregroundColor(.secondary)
            Spacer()
        }
        .frame(maxWidth: .infinity)
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

struct FlowLayout: View {
    let items: [String]
    var spacing: CGFloat = 8

    var body: some View {
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
