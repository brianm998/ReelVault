@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.videoroom.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoroom.data.models.VideoSummary
import com.videoroom.ui.theme.VideoRoomCornerRadius
import com.videoroom.ui.theme.VideoRoomSpacing
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun VideoCard(
    video: VideoSummary,
    isSelected: Boolean = false,
    thumbnailBytes: ByteArray? = null,
    onClick: () -> Unit = {},
    onDoubleClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Decode thumbnail bytes into a Compose ImageBitmap
    val thumbnailImage = remember(thumbnailBytes) {
        thumbnailBytes?.let { bytes ->
            try {
                SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    Surface(
        modifier = modifier
            .clip(RectangleShape)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else if (isHovered) {
                    MaterialTheme.colorScheme.outlineVariant
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                },
                shape = VideoRoomCornerRadius.Large
            )
            .clip(VideoRoomCornerRadius.Large)
            .hoverable(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onDoubleClick = onDoubleClick
            )
            .height(220.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = VideoRoomCornerRadius.Large
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Thumbnail or placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.7f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailImage != null) {
                    Image(
                        bitmap = thumbnailImage,
                        contentDescription = video.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Placeholder when no thumbnail available
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = "No thumbnail",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }

                // Hover play overlay
                if (isHovered) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play (double-click)",
                            modifier = Modifier.size(64.dp),
                            tint = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }

                // Resolution badge
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(VideoRoomSpacing.Small),
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (video.height > 0) "${video.height}p" else "?",
                        modifier = Modifier.padding(
                            horizontal = VideoRoomSpacing.Small,
                            vertical = 2.dp
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Duration badge
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(VideoRoomSpacing.Small),
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = video.durationFormatted,
                        modifier = Modifier.padding(
                            horizontal = VideoRoomSpacing.Small,
                            vertical = 2.dp
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Info section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.3f)
                    .padding(VideoRoomSpacing.Small)
            ) {
                // Filename
                Text(
                    text = video.filename,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Metadata
                Text(
                    text = "${video.codecVideo.ifEmpty { "?" }} • ${video.fps.toInt()}fps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
