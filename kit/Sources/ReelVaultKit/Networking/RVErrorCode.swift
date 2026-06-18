// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import GRPCCore

/// Stable numeric error codes mirroring the `ErrorCode` enum in reelvault.proto.
///
/// The daemon embeds these codes in both gRPC trailing metadata
/// (`rv-error-code` header) and in `Response.error_code` fields, so clients
/// can show localised messages without parsing English strings from the daemon.
public enum RVErrorCode {
    public static let unknown: Int32             = 0
    public static let database: Int32            = 1
    public static let videoNotFound: Int32       = 2
    public static let tagNotFound: Int32         = 3
    public static let collectionNotFound: Int32  = 4
    public static let metadataFailed: Int32      = 5
    public static let thumbnailFailed: Int32     = 6
    public static let fileNotFound: Int32        = 7
    public static let invalidPath: Int32         = 8
    public static let duplicateEntry: Int32      = 9
    public static let io: Int32                  = 10
    public static let config: Int32              = 11
    public static let ffmpeg: Int32              = 12
    public static let invalidRequest: Int32      = 13
    public static let `internal`: Int32          = 14

    /// Extract the structured error code from gRPC trailing metadata.
    /// Returns nil when the header is absent or unparseable.
    public static func from(_ rpcError: RPCError) -> Int32? {
        // StringValues conforms to Sequence, not Collection, so use first(where:)
        guard let raw = rpcError.metadata[stringValues: "rv-error-code"].first(where: { _ in true }) else { return nil }
        return Int32(raw)
    }

    /// A localised, user-facing description for the given code.
    /// Returns nil for `unknown` (0) and any unrecognised code.
    public static func localizedDescription(for code: Int32) -> String? {
        switch code {
        case database:           return String(localized: "Database error.", bundle: .module)
        case videoNotFound:      return String(localized: "Video not found.", bundle: .module)
        case tagNotFound:        return String(localized: "Tag not found.", bundle: .module)
        case collectionNotFound: return String(localized: "Collection not found.", bundle: .module)
        case metadataFailed:     return String(localized: "Metadata extraction failed.", bundle: .module)
        case thumbnailFailed:    return String(localized: "Thumbnail generation failed.", bundle: .module)
        case fileNotFound:       return String(localized: "File not found.", bundle: .module)
        case invalidPath:        return String(localized: "Invalid path.", bundle: .module)
        case duplicateEntry:     return String(localized: "Duplicate entry.", bundle: .module)
        case io:                 return String(localized: "I/O error.", bundle: .module)
        case config:             return String(localized: "Configuration error.", bundle: .module)
        case ffmpeg:             return String(localized: "FFmpeg error.", bundle: .module)
        case invalidRequest:     return String(localized: "Invalid request.", bundle: .module)
        case `internal`:         return String(localized: "Internal error.", bundle: .module)
        default:                 return nil
        }
    }
}

public extension RPCError {
    /// The structured ReelVault error code embedded in trailing metadata, if present.
    var rvErrorCode: Int32? { RVErrorCode.from(self) }
}

public extension Reelvault_Response {
    /// The structured error code from a failed response, or nil on success / unknown error.
    var rvErrorCode: Int32? {
        guard !success, errorCode != 0 else { return nil }
        return errorCode
    }
}
