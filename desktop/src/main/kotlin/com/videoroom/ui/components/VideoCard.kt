@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.videoroom.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoroom.data.models.VideoSummary
import com.videoroom.ui.theme.VideoRoomCornerRadius
import com.videoroom.ui.theme.VideoRoomSpacing
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun VideoCard(
    video: VideoSummary,
    isSelected: Boolean = false,
    isInMultiSelection: Boolean = false,
    isAnchor: Boolean = false,
    isStackExpanded: Boolean = false,
    isStackChild: Boolean = false,
    stackMemberPosition: Int = 0,  // 1-based, only meaningful for stack children/representatives
    stackMemberCount: Int = 0,
    thumbnailBytes: ByteArray? = null,
    /**
     * [shiftPressed] and [togglePressed] are read directly from the pointer
     * event at click time. [togglePressed] is Cmd on macOS / Ctrl on
     * Windows/Linux.
     */
    onClick: (shiftPressed: Boolean, togglePressed: Boolean) -> Unit = { _, _ -> },
    onDoubleClick: () -> Unit = {},
    onStackBadgeClick: () -> Unit = {},
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

    val isInExpandedStack = isStackExpanded || isStackChild

    // Border priority:
    //   anchor (multi-select anchor) > primary > multi-selection > hover > stack > default
    val borderColor = when {
        isAnchor -> MaterialTheme.colorScheme.tertiary
        isSelected -> MaterialTheme.colorScheme.primary
        isInMultiSelection -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        isHovered -> MaterialTheme.colorScheme.outlineVariant
        // Cards in an expanded stack share a subtle primary-tinted border so the
        // group is visually cohesive even when none of them is selected.
        isInExpandedStack -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }
    val borderWidth = when {
        isAnchor || isSelected || isInMultiSelection -> 3.dp
        isInExpandedStack -> 2.dp
        else -> 1.dp
    }

    // Tint the card background when it's part of an expanded stack. We blend
    // the primary color over the normal surface at a low alpha so it works in
    // both light and dark themes.
    val cardBackground = if (isInExpandedStack) {
        MaterialTheme.colorScheme.primary
            .copy(alpha = 0.15f)
            .compositeOver(MaterialTheme.colorScheme.surface)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = modifier
            .clip(RectangleShape)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = VideoRoomCornerRadius.Large
            )
            .clip(VideoRoomCornerRadius.Large)
            .hoverable(interactionSource)
            .shiftAwareClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick
            )
            .height(220.dp),
        color = cardBackground,
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

                // Stack/group badge — shown on group representatives AND stack children
                // Clicking the badge toggles expansion of the stack.
                if (video.isInGroup) {
                    val badgeIsChild = isStackChild
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(VideoRoomSpacing.Small)
                            // Consume pointer down so the parent card click handler doesn't fire.
                            .pointerInput(onStackBadgeClick) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    val up = waitForUpOrCancellation()
                                    if (up != null) {
                                        up.consume()
                                        onStackBadgeClick()
                                    }
                                }
                            },
                        color = when {
                            isStackExpanded -> MaterialTheme.colorScheme.primary
                            badgeIsChild -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        },
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = VideoRoomSpacing.Small,
                                vertical = 2.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = if (isStackExpanded) "Collapse stack" else "Expand stack",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            // For expanded stacks, show "n / m" position. Otherwise just the count.
                            Text(
                                text = if (stackMemberCount > 0 && stackMemberPosition > 0) {
                                    "$stackMemberPosition / $stackMemberCount"
                                } else {
                                    "${video.groupSize}"
                                },
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
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

/**
 * A click modifier that captures the shift modifier state at mouse-down time
 * and distinguishes single-click from double-click.
 *
 * Reads keyboard modifiers via [AwaitPointerEventScope.currentEvent], which
 * contains the pointer event that triggered [awaitFirstDown]. This is the
 * most reliable way to get modifier state on Compose Desktop — it doesn't
 * depend on which widget has keyboard focus.
 */
private fun Modifier.shiftAwareClickable(
    onClick: (shiftPressed: Boolean, togglePressed: Boolean) -> Unit,
    onDoubleClick: () -> Unit
): Modifier = this.pointerInput(onClick, onDoubleClick) {
    awaitEachGesture {
        // 1) Wait for the first pointer down.
        awaitFirstDown(requireUnconsumed = false)
        // currentEvent is the pointer event that just delivered the down change.
        val mods = currentEvent.keyboardModifiers
        val shiftAtDown = mods.isShiftPressed
        // "Toggle" = Cmd on macOS, Ctrl on Windows/Linux. We accept either so
        // the same code path works cross-platform.
        val toggleAtDown = mods.isMetaPressed || mods.isCtrlPressed

        // 2) Wait for the release (or cancellation if dragged off).
        waitForUpOrCancellation() ?: return@awaitEachGesture

        // 3) Wait briefly for a second tap to detect double-click.
        val tapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis
        val secondDown = withTimeoutOrNull(tapTimeoutMs) {
            awaitFirstDown(requireUnconsumed = false)
        }

        if (secondDown != null) {
            waitForUpOrCancellation()
            onDoubleClick()
        } else {
            onClick(shiftAtDown, toggleAtDown)
        }
    }
}
