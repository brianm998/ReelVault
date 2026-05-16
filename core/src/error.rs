use thiserror::Error;

#[derive(Error, Debug)]
pub enum VideoRoomError {
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

// gRPC conversion (when tonic is re-enabled)
// impl From<VideoRoomError> for Status { ... }

pub type Result<T> = std::result::Result<T, VideoRoomError>;
