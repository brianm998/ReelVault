use thiserror::Error;
use tonic::Status;

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

impl From<VideoRoomError> for Status {
    fn from(err: VideoRoomError) -> Status {
        match err {
            VideoRoomError::VideoNotFound(msg) => Status::not_found(msg),
            VideoRoomError::TagNotFound(msg) => Status::not_found(msg),
            VideoRoomError::CollectionNotFound(msg) => Status::not_found(msg),
            VideoRoomError::InvalidRequest(msg) => Status::invalid_argument(msg),
            VideoRoomError::DuplicateEntry(msg) => Status::already_exists(msg),
            VideoRoomError::DatabaseError(msg) => Status::internal(format!("Database error: {}", msg)),
            VideoRoomError::MetadataExtractionFailed(msg) => {
                Status::internal(format!("Metadata extraction failed: {}", msg))
            }
            VideoRoomError::ThumbnailGenerationFailed(msg) => {
                Status::internal(format!("Thumbnail generation failed: {}", msg))
            }
            VideoRoomError::FileNotFound(msg) => Status::not_found(format!("File not found: {}", msg)),
            VideoRoomError::InvalidPath(msg) => Status::invalid_argument(format!("Invalid path: {}", msg)),
            VideoRoomError::IoError(err) => Status::internal(format!("IO error: {}", err)),
            VideoRoomError::ConfigError(msg) => Status::internal(format!("Config error: {}", msg)),
            VideoRoomError::FfmpegError(msg) => {
                Status::internal(format!("FFmpeg error: {}", msg))
            }
            VideoRoomError::InternalError(msg) => Status::internal(msg),
        }
    }
}

pub type Result<T> = std::result::Result<T, VideoRoomError>;
