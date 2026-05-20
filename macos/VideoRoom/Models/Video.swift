import Foundation

struct VideoSummary: Identifiable, Codable {
    let id: String
    let filename: String
    let width: Int
    let height: Int
    let durationMs: Int
    let fps: Double
    let codecVideo: String
    let bitrateKbps: Int
    let sizeBytes: Int

    var resolution: String {
        "\(width)×\(height)"
    }

    var durationFormatted: String {
        let totalSeconds = durationMs / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }

    var sizeMB: Double {
        Double(sizeBytes) / (1024 * 1024)
    }

    var sizeFormatted: String {
        if sizeMB > 1024 {
            return String(format: "%.2f GB", sizeMB / 1024)
        }
        return String(format: "%.2f MB", sizeMB)
    }

    var bitrateFormatted: String {
        if bitrateKbps > 1000 {
            return String(format: "%.2f Mbps", Double(bitrateKbps) / 1000)
        }
        return "\(bitrateKbps) kbps"
    }
}

struct VideoMetadata: Identifiable, Codable {
    let id: String
    let filename: String
    let width: Int
    let height: Int
    let durationMs: Int
    let fps: Double
    let codecVideo: String
    let codecAudio: String
    let bitrateKbps: Int
    let sizeBytes: Int
    let colorSpace: String
    let audioChannels: Int
    let creationDate: Int64
    let cameraModel: String
    let lensModel: String
    let gpsLat: Double
    let gpsLon: Double
    let notes: String
    let tags: [String]
    let collections: [String]

    var resolution: String {
        "\(width)×\(height)"
    }

    var durationFormatted: String {
        let totalSeconds = durationMs / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }

    var sizeFormatted: String {
        let sizeMB = Double(sizeBytes) / (1024 * 1024)
        if sizeMB > 1024 {
            return String(format: "%.2f GB", sizeMB / 1024)
        }
        return String(format: "%.2f MB", sizeMB)
    }

    var bitrateFormatted: String {
        if bitrateKbps > 1000 {
            return String(format: "%.2f Mbps", Double(bitrateKbps) / 1000)
        }
        return "\(bitrateKbps) kbps"
    }

    var creationDateFormatted: String {
        if creationDate == 0 {
            return "Unknown"
        }
        let date = Date(timeIntervalSince1970: TimeInterval(creationDate / 1000))
        return date.formatted(date: .abbreviated, time: .omitted)
    }
}

struct Tag: Identifiable, Codable {
    let id: String
    let name: String
    let color: String?
}

struct Collection: Identifiable, Codable {
    let id: String
    let name: String
    let isSmart: Bool
    let filterJson: String?
}

struct LibraryLocation: Identifiable, Codable {
    let id: String
    let path: String
    let recursive: Bool
    let enabled: Bool
}
