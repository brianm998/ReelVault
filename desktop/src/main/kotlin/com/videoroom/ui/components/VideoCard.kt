// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import com.videoroom.LocalAppWindow
import com.videoroom.data.models.VideoSummary
import com.videoroom.ui.theme.VideoRoomCornerRadius
import com.videoroom.ui.theme.VideoRoomSpacing
import com.videoroom.util.FileDragSource
import kotlinx.coroutines.delay
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
    /** Scrub-frame bytes (one per timeline position). When non-null and the
     *  cursor is hovering, the displayed thumbnail is picked based on the X
     *  position of the cursor over the card. */
    scrubFrames: List<ByteArray?> = emptyList(),
    /**
     * [shiftPressed] and [togglePressed] are read directly from the pointer
     * event at click time. [togglePressed] is Cmd on macOS / Ctrl on
     * Windows/Linux.
     */
    onClick: (shiftPressed: Boolean, togglePressed: Boolean) -> Unit = { _, _ -> },
    onDoubleClick: () -> Unit = {},
    onStackBadgeClick: () -> Unit = {},
    /** Fired when the cursor enters the thumbnail area; used to lazily load
     *  scrub frames the first time the user hovers this card. */
    onHoverEnter: () -> Unit = {},
    /** When `true`, the stationary-tooltip popup is suppressed regardless
     *  of dwell time — used by the caller to hide the popup while a
     *  context menu is open (which would otherwise render the help
     *  popup on top of the menu after the dwell elapsed). */
    suppressTooltip: Boolean = false,
    /** `true` when this card is the one currently playing inline. The
     *  thumbnail is replaced with [inlinePlayer]'s video surface. */
    isPlayingInline: Boolean = false,
    /** The shared grid-level VLCJ player. Non-null; the same instance is
     *  passed to all cards but only rendered in the one where
     *  [isPlayingInline] is true. */
    inlinePlayer: ComposeVideoPlayer? = null,
    /** Fired when the user clicks the play button overlay on a playable
     *  card. For oversize cards this still fires so the caller can open
     *  the proxy-creation dialog instead. */
    onPlayClick: () -> Unit = {},
    /** Fired when the user clicks the ✕ stop button on the inline player. */
    onStopPlayback: () -> Unit = {},
    /**
     * When `false` (VLC not installed), the play button is rendered with a
     * disabled/dimmed appearance and clicking it fires [onPlayClick] so the
     * caller can show an install-VLC modal immediately — without attempting
     * to start the player. When `true` (default), normal play behavior.
     */
    playEnabled: Boolean = true,
    /**
     * File paths to transfer when the user drags this card out to an
     * external app (e.g. DaVinci Resolve, Premiere Pro).
     *
     * When empty, the card's own [video.openPath] is used. When the card is
     * part of a multi-selection the caller should pass all selected paths so
     * a single drag delivers the whole batch.
     *
     * Non-existent paths are silently dropped; if every path is invalid the
     * drag is not initiated.
     */
    dragPaths: List<String> = emptyList(),
    /** The four catalog-scoped top-of-card stat slot keys. Each entry is a
     *  [com.videoroom.data.models.GridStatKey.raw] value; unknown strings
     *  render as blank. Padded / truncated internally to exactly four. */
    topSlots: List<String> = com.videoroom.data.models.defaultGridTopSlots,
    /** Fired when one of the five rating positions is clicked. Receives the
     *  new rating (0..5). Callers translate "click same position twice" into
     *  a clear (rating - 1). */
    onSetRating: (Int) -> Unit = {},
    /** Fired when the user right-clicks a top stat slot and picks a new
     *  stat key. Receives (slotIndex 0..3, GridStatKey.raw). */
    onPickStatSlot: (Int, String) -> Unit = { _, _ -> },
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

    // Decode scrub frames once per change. We pre-decode all frames into
    // ImageBitmaps so swapping during scrub is allocation-free.
    val scrubImages = remember(scrubFrames) {
        scrubFrames.map { bytes ->
            bytes?.let {
                try {
                    SkiaImage.makeFromEncoded(it).toComposeImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    // Hover state used to pick which scrub frame to show.
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var thumbSize by remember { mutableStateOf(IntSize.Zero) }

    // Stationary-tooltip dwell. Every Enter/Move event bumps
    // `motionTickMs` to the current clock; a LaunchedEffect keyed on that
    // value waits [TOOLTIP_DWELL_MS] and then sets `tooltipVisible`.
    //
    // Each new motion cancels the in-flight effect (the LaunchedEffect
    // restart-on-key behavior) and starts a fresh 2-second wait, so the
    // popup only ever surfaces after the cursor has actually been still
    // the full dwell. `motionTickMs = 0` means "no motion observed yet"
    // (also used on Exit) and skips the timer entirely.
    //
    // Once the tooltip is showing, subsequent motion within the card does
    // NOT dismiss it. This matters because a card with inline video playback
    // recomposes every frame; the natural micro-tremor of a user's hovering
    // cursor used to fire Move events every few seconds, restarting the
    // dwell timer and visibly flickering the tooltip in and out. The
    // tooltip now stays visible until the cursor leaves the card.
    var motionTickMs by remember { mutableStateOf(0L) }
    var tooltipVisible by remember { mutableStateOf(false) }
    var lastPointerPos by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(motionTickMs, suppressTooltip) {
        if (suppressTooltip || motionTickMs == 0L) {
            tooltipVisible = false
            return@LaunchedEffect
        }
        // Already showing — motion within the card shouldn't dismiss it.
        if (tooltipVisible) return@LaunchedEffect
        delay(TOOLTIP_DWELL_MS)
        // Re-check after the dwell in case the cursor left or the menu
        // opened while we were sleeping.
        if (motionTickMs > 0L && !suppressTooltip) {
            tooltipVisible = true
        }
    }
    // While the context menu is open the parent flips `suppressTooltip`
    // to true. Reset the motion clock too so that, once the menu closes,
    // the tooltip stays hidden until the user actually moves the cursor
    // again — otherwise a still-since-before-the-menu cursor would
    // trigger the dwell as soon as the menu dismissed.
    LaunchedEffect(suppressTooltip) {
        if (suppressTooltip) {
            motionTickMs = 0L
        }
    }

    // Choose which image to display: a scrub frame if we have one + position;
    // otherwise the regular thumbnail.
    val displayedImage = run {
        val x = hoverX
        val width = thumbSize.width
        if (x != null && width > 0 && scrubImages.any { it != null }) {
            val frac = (x / width).coerceIn(0f, 1f)
            val idx = (frac * scrubImages.size).toInt().coerceIn(0, scrubImages.size - 1)
            scrubImages[idx] ?: thumbnailImage
        } else {
            thumbnailImage
        }
    }

    val isInExpandedStack = isStackExpanded || isStackChild

    // Rich tooltip text shown when the user hovers the card. Built once per
    // change to the inputs so we don't allocate on every recomposition.
    val cardTooltip = remember(video, isStackChild, isStackExpanded) {
        buildString {
            append(video.filename)
            append('\n')
            append(video.resolution)
            append(" • ")
            append(video.durationFormatted)
            if (video.codecVideo.isNotEmpty()) {
                append(" • ")
                append(video.codecVideo)
            }
            if (video.fps > 0) {
                append(" • ")
                append(video.fps.toInt())
                append(" fps")
            }
            append('\n')
            append(
                if (video.sizeMB >= 1024) {
                    String.format("%.2f GB", video.sizeMB / 1024.0)
                } else {
                    String.format("%.1f MB", video.sizeMB)
                }
            )
            if (video.isInGroup) {
                append('\n')
                if (isStackChild) {
                    append("Member of a stack of ${video.groupSize} variants.")
                } else if (isStackExpanded) {
                    append("Stack of ${video.groupSize} (expanded). Click the stack badge to collapse.")
                } else {
                    append("Stack of ${video.groupSize}. Click the stack badge to expand.")
                }
            }
            append('\n')
            append("Click to select, Shift-click to multi-select, double-click to open.")
        }
    }

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

    // Lightroom-style band background — anchor cards get the fully-
    // saturated label swatch (or a neutral hi-light if unlabelled),
    // secondary-selected cards get a mid-brightness tint, and unselected
    // cards either keep their dimmed label tint or fall through to the
    // panel's surface colour.
    val colorLabelEnum = com.videoroom.data.models.ColorLabel.from(video.colorLabel)
    val bandBackground = when {
        isAnchor || (isSelected && !isInMultiSelection) ->
            if (colorLabelEnum == com.videoroom.data.models.ColorLabel.None) Color(0xFF505050)
            else colorLabelEnum.swatch
        isInMultiSelection ->
            if (colorLabelEnum == com.videoroom.data.models.ColorLabel.None) Color(0xFF3D3D3D)
            else colorLabelEnum.secondary
        isInExpandedStack -> MaterialTheme.colorScheme.primary
            .copy(alpha = 0.13f)
            .compositeOver(MaterialTheme.colorScheme.surface)
        colorLabelEnum != com.videoroom.data.models.ColorLabel.None -> colorLabelEnum.dimmed
        else -> MaterialTheme.colorScheme.surface
    }
    val cardBackground = bandBackground  // kept for any leftover references below

    // AWT window for drag-out support. Provided via LocalAppWindow (defined in
    // App.kt and passed through CompositionLocalProvider in main()).
    val awtWindow = LocalAppWindow.current

    // FileDragSource is stateless between gestures — safe to share across
    // recompositions via remember.
    val fileDragSource = remember { FileDragSource() }

    // Custom stationary tooltip. We own timing end-to-end here rather
    // than relying on Compose's TooltipArea, whose 500 ms hover gate
    // interacts poorly with the suppression pattern (toggling its
    // wrapping off/on cost the popup its hover state, so the help would
    // almost never appear). The wrapping Box hosts both the card and a
    // Popup that's rendered iff `tooltipVisible` flips to true after the
    // dwell. Pointer events on the Box are passed through to children
    // by default, so clicks/double-clicks and the inner thumbnail's own
    // scrub-tracking continue to work.
    Box(
        modifier = modifier
            // Drag-out support: detect drag motion in Compose then hand off
            // to AWT via FileDragSource.startDragIfPending().
            // Uses javaFileListFlavor — the cross-platform standard understood
            // by DaVinci Resolve, Premiere Pro, and every major file manager
            // on macOS, Windows, and Linux.
            .pointerInput(dragPaths, video.openPath) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPos = down.position
                    val effectivePaths = dragPaths.ifEmpty { listOf(video.openPath) }
                    fileDragSource.setPendingFiles(effectivePaths)

                    var handled = false
                    while (!handled) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break

                        if (!change.pressed) {
                            // Normal click — disarm.
                            fileDragSource.clearPending()
                            handled = true
                        } else {
                            val delta = change.position - startPos
                            val dist = kotlin.math.sqrt(
                                (delta.x * delta.x + delta.y * delta.y).toDouble()
                            ).toFloat()
                            if (dist >= DRAG_THRESHOLD_PX && awtWindow != null) {
                                // Compute screen coordinates from window-relative
                                // Compose position.
                                val winX = awtWindow.x
                                val winY = awtWindow.y
                                val screenX = (winX + change.position.x).toInt()
                                val screenY = (winY + change.position.y).toInt()
                                fileDragSource.startDragIfPending(awtWindow, screenX, screenY)
                                handled = true
                            }
                        }
                    }
                }
            }
            .onPointerEvent(PointerEventType.Enter) { event ->
                val p = event.changes.firstOrNull()?.position
                lastPointerPos = p
                // Entry counts as motion — the user might immediately
                // scrub, and we don't want a popup flashing over the
                // first frame.
                motionTickMs = System.currentTimeMillis()
            }
            .onPointerEvent(PointerEventType.Move) { event ->
                val p = event.changes.firstOrNull()?.position
                if (p != null && p != lastPointerPos) {
                    lastPointerPos = p
                    motionTickMs = System.currentTimeMillis()
                }
            }
            .onPointerEvent(PointerEventType.Exit) {
                lastPointerPos = null
                // Reset to "no motion observed" so the dwell timer
                // doesn't fire while the cursor is off the card.
                motionTickMs = 0L
                tooltipVisible = false
            }
    ) {
    // Lightroom-style three-band layout: top stat band, square thumbnail in
    // the middle, rating band at the bottom. No corner radius, no outer
    // padding — adjacent cards share crisp 1 px borders for a dense grid.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .shiftAwareClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick
            )
    ) {
        // ── Top stat band ────────────────────────────────────────────
        // Four stat cells, one in each corner of the band. Slot 0 = TL,
        // 1 = BL, 2 = TR, 3 = BR. Each cell is right-clickable to pick
        // which stat it renders; the choice applies catalog-wide.
        val paddedSlots = remember(topSlots) {
            val s = topSlots.toMutableList()
            while (s.size < 4) s.add("")
            s.take(4)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(bandBackground)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell(slotIndex = 0, key = paddedSlots[0], video = video, onPick = onPickStatSlot, alignEnd = false, weight = 1f)
                StatCell(slotIndex = 2, key = paddedSlots[2], video = video, onPick = onPickStatSlot, alignEnd = true, weight = 1f)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell(slotIndex = 1, key = paddedSlots[1], video = video, onPick = onPickStatSlot, alignEnd = false, weight = 1f)
                StatCell(slotIndex = 3, key = paddedSlots[3], video = video, onPick = onPickStatSlot, alignEnd = true, weight = 1f)
            }
        }

        // ── Square thumbnail in the middle ───────────────────────────
        run {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)  // Lightroom-style square
                    .background(Color.Black)
                    .onSizeChanged { thumbSize = it }
                    // Track cursor position over the thumbnail to drive
                    // Lightroom-style scrubbing. The X coordinate is mapped
                    // onto the scrub-frame array (0..N-1).
                    .onPointerEvent(PointerEventType.Enter) {
                        onHoverEnter()
                        it.changes.firstOrNull()?.position?.let { p -> hoverX = p.x }
                        // Tooltip motion-tracking lives on the *outer*
                        // wrapping Box (above) so it sees every move on
                        // the whole card, not just the thumbnail. No
                        // tooltip work to do here.
                    }
                    .onPointerEvent(PointerEventType.Move) {
                        val p = it.changes.firstOrNull()?.position
                        if (p != null && hoverX != p.x) {
                            hoverX = p.x
                        }
                    }
                    .onPointerEvent(PointerEventType.Exit) {
                        hoverX = null
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isPlayingInline && inlinePlayer?.available == true) {
                    // Live video surface — replaces thumbnail while playing.
                    inlinePlayer.Surface(modifier = Modifier.fillMaxSize())
                    // Health-check overlay: if libvlc renders nothing (black
                    // surface) for > 3 s, show install instructions over it.
                    VlcUnavailableOverlay(player = inlinePlayer)
                } else if (isPlayingInline) {
                    // libvlc not found — show install instructions immediately.
                    VlcUnavailableOverlay()
                } else if (displayedImage != null) {
                    Image(
                        bitmap = displayedImage,
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

                // Play-button overlay — visible on hover when:
                //   • VLC installed + master natively playable → show normally
                //   • VLC installed + master too large BUT a proxy exists
                //     → show normally; the click handler routes through
                //       playVideoPreferProxy and loads the smallest proxy
                //   • VLC not installed → show dimmed on every card so the
                //     user gets immediate feedback about what's missing
                //     rather than nothing happening on click.
                val canPlayInline = video.playableNatively || video.hasProxies
                if (!isPlayingInline && isHovered && (canPlayInline || !playEnabled)) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                Color.Black.copy(alpha = if (playEnabled) 0.55f else 0.35f),
                                RoundedCornerShape(50)
                            )
                            .pointerInput(onPlayClick, playEnabled) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    val up = waitForUpOrCancellation()
                                    if (up != null) { up.consume(); onPlayClick() }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = if (playEnabled) "Play inline" else "VLC not installed",
                            modifier = Modifier.size(30.dp),
                            // Dimmed when VLC is absent to signal the disabled state.
                            tint = if (playEnabled) Color.White else Color.White.copy(alpha = 0.45f),
                        )
                    }
                }

                // Stop button — top-end, overlaid on the live player.
                if (isPlayingInline) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(28.dp)
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                            .pointerInput(onStopPlayback) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    val up = waitForUpOrCancellation()
                                    if (up != null) { up.consume(); onStopPlayback() }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop playback",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White,
                        )
                    }
                }

                // (Resolution / duration / proxy-count chips that used to
                // float over the thumbnail are gone — those stats are now
                // surfaced via the four configurable top-of-card slots.)

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

                // Bottom-right icon row — at-a-glance status (keyword,
                // proxy). Lightroom-equivalent placement: small mono
                // icons over the thumbnail's bottom-right corner.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (video.tags.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sell,
                                contentDescription = "${video.tags.size} keyword(s)",
                                modifier = Modifier.size(10.dp),
                                tint = Color.White
                            )
                        }
                    }
                    if (video.hasProxies) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPicture,
                                contentDescription = "${video.proxyCount} proxy/proxies",
                                modifier = Modifier.size(10.dp),
                                tint = Color.White
                            )
                        }
                    }
                }

                // "Too large to play here" marker — bottom-center.
                // Shown when the server says this video exceeds the
                // configured max-native-playback-height AND no proxy is
                // available to substitute.
                if (!video.playableNatively && !video.hasProxies) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 28.dp),
                        color = Color(0xFFB8722E).copy(alpha = 0.9f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "Too large to play here",
                            modifier = Modifier.padding(
                                horizontal = VideoRoomSpacing.Small,
                                vertical = 2.dp,
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        } // end of square-thumbnail run { }

        // ── Bottom rating band ───────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(bandBackground),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            for (position in 1..5) {
                val filled = position <= video.rating
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .pointerInput(video.rating, position) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    up.consume()
                                    // Lightroom: clicking the current rating clears it back one.
                                    if (video.rating == position) onSetRating(position - 1)
                                    else onSetRating(position)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (filled) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "$position star",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFF2C739)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(Color.White.copy(alpha = 0.4f), RoundedCornerShape(50))
                        )
                    }
                }
                if (position < 5) Spacer(modifier = Modifier.width(6.dp))
            }
        }
    }

        // Stationary-tooltip popup. Sits inside the wrapping Box so it
        // shares the card's coordinate space; `Popup` itself doesn't
        // intercept pointer events from the underlying card, so the
        // user can keep moving the cursor and it'll dismiss naturally.
        if (tooltipVisible && cardTooltip.isNotBlank()) {
            Popup(
                alignment = Alignment.BottomStart,
                // Push it just below the card so it doesn't overlap the
                // thumbnail content the user is scrubbing.
                offset = IntOffset(0, 8),
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = MaterialTheme.shapes.small,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = cardTooltip,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * One cell in the top-of-card stat band. Renders the stat's value for the
 * given video, with a right-click `ContextMenuArea` offering every
 * [com.videoroom.data.models.GridStatKey] option. The user's choice applies
 * catalog-wide (via [onPick] → `GridViewModel.updateGridTopSlot`).
 */
@Composable
private fun RowScope.StatCell(
    slotIndex: Int,
    key: String,
    video: VideoSummary,
    onPick: (Int, String) -> Unit,
    alignEnd: Boolean,
    weight: Float,
) {
    val stat = com.videoroom.data.models.GridStatKey.fromRaw(key)
    val value = stat.valueFor(video)
    val items = remember(slotIndex, key) {
        com.videoroom.data.models.GridStatKey.values().map { choice ->
            androidx.compose.foundation.ContextMenuItem(
                if (choice == stat) "✓ ${choice.displayName}" else choice.displayName
            ) {
                onPick(slotIndex, choice.raw)
            }
        }
    }
    // ContextMenuArea wraps a Box so the right-click target is the whole
    // cell area, including the empty-value " — " placeholder. Without this,
    // empty cells have no NSView region for AWT to fire a right-click on.
    androidx.compose.foundation.ContextMenuArea(items = { items }) {
        Box(
            modifier = Modifier
                .weight(weight)
                .heightIn(min = 14.dp),
            contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Text(
                text = value.ifEmpty { "—" },
                style = if (slotIndex == 0) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.labelSmall
                },
                color = if (value.isEmpty()) Color.White.copy(alpha = 0.3f) else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (alignEnd) {
                    androidx.compose.ui.text.style.TextAlign.End
                } else {
                    androidx.compose.ui.text.style.TextAlign.Start
                }
            )
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

/// Time (ms) the cursor must sit motionless over a card before its help
/// popup surfaces. Matches the macOS client's `tooltipDwell`. Two seconds
/// is long enough that a user actively scrubbing — even with brief
/// frame-inspecting pauses — won't trigger the popup, and short enough
/// that an intentional rest to read the help feels responsive.
private const val TOOLTIP_DWELL_MS = 2000L

/** Minimum pointer travel (in pixels) before a press-and-move is treated as
 *  a drag-out gesture.  8 px matches the drag threshold used by most desktop
 *  environments and is large enough to avoid accidental drags on regular clicks. */
private const val DRAG_THRESHOLD_PX = 8f
