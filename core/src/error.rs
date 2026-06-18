// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

use thiserror::Error;

#[derive(Error, Debug)]
pub enum ReelVaultError {
    #[error("Database error: {0}")]
    DatabaseError(String),

    #[error("Video not found: {0}")]
    VideoNotFound(String),

    #[error("Tag not found: {0}")]
    TagNotFound(String),

    #[error("Collection not found: {0}")]
    CollectionNotFound(String),

    #[error("Metadata extraction failed: {0}")]
    MetadataExtractionFailed(String),

    #[error("Thumbnail generation failed: {0}")]
    ThumbnailGenerationFailed(String),

    #[error("File not found: {0}")]
    FileNotFound(String),

    #[error("Invalid path: {0}")]
    InvalidPath(String),

    #[error("Duplicate entry: {0}")]
    DuplicateEntry(String),

    #[error("IO error: {0}")]
    IoError(#[from] std::io::Error),

    #[error("Configuration error: {0}")]
    ConfigError(String),

    #[error("FFmpeg error: {0}")]
    FfmpegError(String),

    #[error("Invalid request: {0}")]
    InvalidRequest(String),

    #[error("Internal error: {0}")]
    InternalError(String),
}

impl ReelVaultError {
    /// Returns the stable numeric error code for this error variant.
    /// Values mirror the `ErrorCode` enum in reelvault.proto.
    pub fn error_code(&self) -> i32 {
        match self {
            ReelVaultError::DatabaseError(_) => 1,
            ReelVaultError::VideoNotFound(_) => 2,
            ReelVaultError::TagNotFound(_) => 3,
            ReelVaultError::CollectionNotFound(_) => 4,
            ReelVaultError::MetadataExtractionFailed(_) => 5,
            ReelVaultError::ThumbnailGenerationFailed(_) => 6,
            ReelVaultError::FileNotFound(_) => 7,
            ReelVaultError::InvalidPath(_) => 8,
            ReelVaultError::DuplicateEntry(_) => 9,
            ReelVaultError::IoError(_) => 10,
            ReelVaultError::ConfigError(_) => 11,
            ReelVaultError::FfmpegError(_) => 12,
            ReelVaultError::InvalidRequest(_) => 13,
            ReelVaultError::InternalError(_) => 14,
        }
    }
}

// gRPC conversion
impl From<ReelVaultError> for tonic::Status {
    fn from(err: ReelVaultError) -> Self {
        let code = err.error_code();
        let mut status = match err {
            ReelVaultError::VideoNotFound(_)
            | ReelVaultError::TagNotFound(_)
            | ReelVaultError::CollectionNotFound(_)
            | ReelVaultError::FileNotFound(_) => tonic::Status::not_found(err.to_string()),
            ReelVaultError::InvalidRequest(_) | ReelVaultError::InvalidPath(_) => {
                tonic::Status::invalid_argument(err.to_string())
            }
            ReelVaultError::DuplicateEntry(_) => tonic::Status::already_exists(err.to_string()),
            _ => tonic::Status::internal(err.to_string()),
        };
        // Embed the numeric error code in trailing metadata so clients can map
        // it to a localised message without parsing English strings.
        if let Ok(val) = tonic::metadata::MetadataValue::try_from(code.to_string().as_str()) {
            status.metadata_mut().insert("rv-error-code", val);
        }
        status
    }
}

pub type Result<T> = std::result::Result<T, ReelVaultError>;
