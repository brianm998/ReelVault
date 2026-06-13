// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.reelvault.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.reelvault.LocalAppWindow
import com.reelvault.data.models.FullResolutionStatus
import com.reelvault.data.models.VideoSummary
import com.reelvault.ui.theme.ReelVaultCornerRadius
import com.reelvault.ui.theme.ReelVaultSpacing
import com.reelvault.util.FileDragSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun VideoCard(
    video: VideoSummary,
    isSelected: Boolean = false,
    isInMultiSelection: Boolean = false,
    isAnchor: Boolean = false,
    // Group-selection border: each flag is true when that side of the card is
    // on the *outer* edge of the selection (the neighbouring card in that
    // direction is NOT selected). The white selection border is drawn only on
    // outer edges, so a block of selected cards reads as one outlined group
    // rather than each card boxed individually. Ignored when the card isn't
    // part of the selection. Default true → a lone selected card is fully boxed.
    selectionEdgeTop: Boolean = true,
    selectionEdgeBottom: Boolean = true,
    selectionEdgeLeft: Boolean = true,
    selectionEdgeRight: Boolean = true,
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
     *  [com.reelvault.data.models.GridStatKey.raw] value; unknown strings
     *  render as blank. Padded / truncated internally to exactly four. */
    topSlots: List<String> = com.reelvault.data.models.defaultGridTopSlots,
    /** Fired when the user clicks the location badge on a card that has GPS
     *  coordinates. The two doubles are (latitude, longitude). Callers
     *  should open the global map focused on that coordinate. */
    onLocationClick: ((Double, Double) -> Unit)? = null,
    /** Fired when one of the five rating positions is clicked. Receives the
     *  new rating (0..5). Callers translate "click same position twice" into
     *  a clear (rating - 1). */
    onSetRating: (Int) -> Unit = {},
    /** Fired when the user right-clicks a top stat slot and picks a new
     *  stat key. Receives (slotIndex 0..3, GridStatKey.raw). */
    onPickStatSlot: (Int, String) -> Unit = { _, _ -> },
    /** Resolves a (latitude, longitude) to a registered place-name (or null)
     *  for the "Location" stat slot; null shows raw coordinates. */
    placeNameFor: ((Double, Double) -> String?)? = null,
    /** Non-null when a proxy is actively being generated for this video.
     *  Triggers a progress overlay in the lower-left of the thumbnail. */
    proxyCreationState: com.reelvault.viewmodel.GridViewModel.ProxyCreationState? = null,
    /** Current inline-playback volume (0..100), shared with the detail loupe.
     *  Only surfaced (as a compact slider) while this card is playing a clip
     *  that has an audio track. */
    inlineVolume: Int = 100,
    /** Fired as the user drags the inline volume slider. Callers persist the
     *  level and apply it to the shared inline player. */
    onInlineVolumeChange: (Int) -> Unit = {},
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

    // Choose which image to display: a scrub frame if we have one + position;
    // otherwise the regular thumbnail.  While a video is playing inline the
    // thumbnail is shown as the base layer beneath the player surface, so
    // we pin it to the static thumbnail and skip the scrub logic.
    val displayedImage = run {
        if (isPlayingInline) return@run thumbnailImage
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

    // Lightroom-style three-tone card. Top band is lighter than the
    // photo area; bottom band sits a touch darker than the top. ONLY
    // the photo area takes the colour-label tint — the bands stay
    // neutral so the grid reads as a uniform filmstrip. Selection
    // brightens all three regions to the same neutral and the label is
    // preserved as a thin frame wrapping the thumbnail.
    val colorLabelEnum = com.reelvault.data.models.ColorLabel.from(video.colorLabel)
    // Background neutrals matched to the user's target screenshot. The
    // three default bands keep a subtle top→photo→bottom tone but sit much
    // closer together than before (the photo area is no longer a dark frame
    // around the thumbnail), and the selected state brightens further.
    val topBandColor = when {
        isSelected -> Color(0xFFDFDFDF)
        isInMultiSelection -> Color(0xFFB3B3B3)
        else -> Color(0xFF6B6B6B)
    }
    val photoAreaBackground = when {
        isSelected -> Color(0xFF999999)
        isInMultiSelection -> Color(0xFF707070)
        isInExpandedStack -> Color(0xFF535660)
        colorLabelEnum != com.reelvault.data.models.ColorLabel.None -> colorLabelEnum.dimmed
        else -> Color(0xFF474747)
    }
    val bottomBandColor = when {
        isSelected -> Color(0xFFCFCFCF)
        isInMultiSelection -> Color(0xFF9E9E9E)
        else -> Color(0xFF5C5C5C)
    }
    val bandDividerColor = when {
        isAnchor || isSelected || isInMultiSelection -> Color.Black.copy(alpha = 0.10f)
        else -> Color.Black.copy(alpha = 0.35f)
    }
    val thumbnailFrameColor: Color? =
        if (colorLabelEnum != com.reelvault.data.models.ColorLabel.None &&
            (isAnchor || isSelected || isInMultiSelection)) {
            colorLabelEnum.swatch
        } else null
    // White when the side is the selection group's outer edge; otherwise the
    // faint grey grid line every card draws in the zero-gutter grid. Fully
    // opaque so the selection border reads as a solid white stroke.
    val selectionBorderColor = Color.White
    val gridLineColor = Color.Black.copy(alpha = 0.4f)
    // Backwards-compat aliases — old call sites use these names.
    @Suppress("UnusedVariable") val bandBackground = topBandColor
    @Suppress("UnusedVariable") val cardBackground = photoAreaBackground
    @Suppress("UnusedVariable") val cardBackgroundColor = photoAreaBackground
    @Suppress("UnusedVariable") val showCornerLabelBadge = false

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
            // 1 dp per-side border. Every card draws the faint grid line so the
            // zero-gutter grid stays legible; a selected card paints its outer
            // edges white so a block of selected cards reads as one group
            // (shared edges between two selected cards stay grey).
            .drawWithContent {
                drawContent()
                val sw = 1.dp.toPx()
                val o = sw / 2f
                val w = size.width
                val h = size.height
                // Faint 1 dp grid line on every side so the zero-gutter grid
                // stays legible.
                drawLine(gridLineColor, Offset(0f, o), Offset(w, o), sw)
                drawLine(gridLineColor, Offset(0f, h - o), Offset(w, h - o), sw)
                drawLine(gridLineColor, Offset(o, 0f), Offset(o, h), sw)
                drawLine(gridLineColor, Offset(w - o, 0f), Offset(w - o, h), sw)
                // Selection border: a solid 2 dp white stroke, drawn LAST so it
                // sits on top of everything and inset 1 dp past the grid line so
                // a neighbour can't overdraw it. Only the selection block's
                // outer edges are drawn (so a run of selected cards reads as one
                // group); each edge's ends extend by half the stroke width at a
                // real (outer) corner so the two strokes meet flush — a sharp
                // right angle with no overhang — and run to the full card edge
                // at an inner boundary so they join the selected neighbour's
                // stroke seamlessly.
                if (isSelected || isInMultiSelection) {
                    val ssw = 2.dp.toPx()
                    val so = sw + ssw / 2f      // stroke centreline, 2 dp in
                    val ext = ssw / 2f          // corner fill = 1 dp
                    val hStart = if (selectionEdgeLeft) so - ext else 0f
                    val hEnd = if (selectionEdgeRight) w - so + ext else w
                    val vStart = if (selectionEdgeTop) so - ext else 0f
                    val vEnd = if (selectionEdgeBottom) h - so + ext else h
                    if (selectionEdgeTop)
                        drawLine(selectionBorderColor, Offset(hStart, so), Offset(hEnd, so), ssw)
                    if (selectionEdgeBottom)
                        drawLine(selectionBorderColor, Offset(hStart, h - so), Offset(hEnd, h - so), ssw)
                    if (selectionEdgeLeft)
                        drawLine(selectionBorderColor, Offset(so, vStart), Offset(so, vEnd), ssw)
                    if (selectionEdgeRight)
                        drawLine(selectionBorderColor, Offset(w - so, vStart), Offset(w - so, vEnd), ssw)
                }
            }
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
                        // Watch the Main pass (not Initial) so a child gesture
                        // that consumes the drag wins: the inline volume slider
                        // overlaid on a playing card must scrub volume rather
                        // than starting a file drag-out. Desktop list/grid
                        // scrolling is wheel-based, so this never competes with
                        // a scroll. The down was already armed above.
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull() ?: break

                        if (change.isConsumed) {
                            // A descendant (e.g. the volume slider) took this
                            // drag — disarm so we don't drag the file out.
                            fileDragSource.clearPending()
                            handled = true
                        } else if (!change.pressed) {
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
                .background(topBandColor)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            AdaptiveStatRow(
                modifier = Modifier.fillMaxWidth(),
                leading = { StatCell(slotIndex = 0, key = paddedSlots[0], video = video, onPick = onPickStatSlot, alignEnd = false, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
                trailing = { StatCell(slotIndex = 2, key = paddedSlots[2], video = video, onPick = onPickStatSlot, alignEnd = true, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
            )
            AdaptiveStatRow(
                modifier = Modifier.fillMaxWidth(),
                leading = { StatCell(slotIndex = 1, key = paddedSlots[1], video = video, onPick = onPickStatSlot, alignEnd = false, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
                trailing = { StatCell(slotIndex = 3, key = paddedSlots[3], video = video, onPick = onPickStatSlot, alignEnd = true, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
            )
        }

        // 1 dp separator under the top band.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(bandDividerColor)
        )

        // ── Square photo area ────────────────────────────────────────
        // Lightroom letterboxing: the photo area is a 1:1 box backed by
        // the colour-label tint (or neutral grey for unlabelled). The
        // actual thumbnail is inset by `photoPadding` and uses
        // ContentScale.Fit so the whole video frame stays visible —
        // letterbox space above and below shows the photo-area
        // background.
        //
        // The outer Box owns the background and the colour-label frame.
        // The inner Box owns padding + pointer events for the thumbnail
        // itself. When the card is selected + labelled, the colour-label
        // tint moves off the full photo area onto a thin frame tight
        // against the thumbnail bounds — the user spec calls for "the
        // smaller colored area should wrap the video, not the area
        // around it".
        run {
            val photoPadding = 8.dp
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(photoAreaBackground)
            ) {
                // The video's letterboxed bounds inside the (square,
                // photoPadding-inset) thumbnail area. Used both by the
                // colour-label frame and by the always-on 1 dp black frame
                // that outlines the video itself.
                val videoAspect = if (video.width > 0 && video.height > 0)
                    video.width.toFloat() / video.height.toFloat()
                else 1f
                val photoSize = minOf(maxWidth, maxHeight)
                val videoAvailable = (photoSize - photoPadding * 2).coerceAtLeast(0.dp)
                val videoFrameW = if (videoAspect >= 1f) videoAvailable else videoAvailable * videoAspect
                val videoFrameH = if (videoAspect >= 1f) videoAvailable / videoAspect else videoAvailable
                // Colour-label frame, only when selected + labelled. The
                // frame wraps the *video* itself — sized to the video's
                // aspect ratio, with its inner edge flush against the
                // video bounds (no gap). For a 16:9 video in a 1:1
                // photo area, the frame is a 16:9 rectangle, NOT a
                // square inset.
                if (thumbnailFrameColor != null) {
                    val frameLine = 3.dp
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(videoFrameW + frameLine * 2, videoFrameH + frameLine * 2)
                            .border(frameLine, thumbnailFrameColor)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(photoPadding)
                        .onSizeChanged { thumbSize = it }
                        // Track cursor position over the thumbnail to drive
                        // Lightroom-style scrubbing. The X coordinate is mapped
                        // onto the scrub-frame array (0..N-1). Scrubbing is
                        // suppressed while a video is playing inline so the
                        // player surface takes priority.
                        .onPointerEvent(PointerEventType.Enter) {
                            if (!isPlayingInline) {
                                onHoverEnter()
                                it.changes.firstOrNull()?.position?.let { p -> hoverX = p.x }
                            }
                        }
                        .onPointerEvent(PointerEventType.Move) {
                            if (!isPlayingInline) {
                                val p = it.changes.firstOrNull()?.position
                                if (p != null && hoverX != p.x) {
                                    hoverX = p.x
                                }
                            }
                        }
                        .onPointerEvent(PointerEventType.Exit) {
                            hoverX = null
                        },
                    contentAlignment = Alignment.Center
                ) {
                // Base layer: thumbnail (or placeholder) always visible.
                // When playing, this shows through the player surface until
                // the first frame is decoded, preventing any black flash or
                // stale frame from a previous video.
                if (displayedImage != null) {
                    Image(
                        bitmap = displayedImage,
                        contentDescription = video.filename,
                        modifier = Modifier.fillMaxSize(),
                        // Lightroom letterboxing — keep the full frame
                        // visible. The colour-label background fills any
                        // empty space above/below (or left/right for
                        // portrait clips) so the user always sees the
                        // entire shot, not a cropped square.
                        contentScale = ContentScale.Fit
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
                // Player surface layered on top. Surface() renders nothing
                // (returns early) until the first frame of the new video is
                // decoded, so the thumbnail above stays visible during
                // buffering with no dark background behind the video.
                if (isPlayingInline && inlinePlayer?.available == true) {
                    inlinePlayer.Surface(modifier = Modifier.fillMaxSize())
                    VlcUnavailableOverlay(player = inlinePlayer)
                } else if (isPlayingInline) {
                    // libvlc not found — show install instructions immediately.
                    VlcUnavailableOverlay()
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
                if (!isPlayingInline && video.isOnline && isSelected && isHovered && (canPlayInline || !playEnabled)) {
                    com.reelvault.ui.components.Tooltip(
                        text = if (playEnabled) "Play inline" else "Install VLC to enable inline playback"
                    ) {
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
                }

                // Offline indicator — the file was missing at the last scan
                // (moved/renamed, or its drive isn't mounted). Dim the thumbnail
                // and badge it so the user sees it can't play before clicking,
                // rather than hitting a playback error; the play affordance is
                // suppressed above for the same reason. Always shown (not gated
                // on hover) so offline clips are obvious at a glance.
                if (!video.isOnline && !isPlayingInline) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        com.reelvault.ui.components.Tooltip(
                            text = "File offline — moved, renamed, or its drive isn't " +
                                "mounted. Re-scan the library to update its location.",
                        ) {
                            Row(
                                modifier = Modifier
                                    .background(
                                        Color.Black.copy(alpha = 0.7f),
                                        RoundedCornerShape(50),
                                    )
                                    .padding(horizontal = ReelVaultSpacing.Small, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = "Offline",
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.White,
                                )
                                Text(
                                    text = "Offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }

                // Stop button — top-end, overlaid on the live player.
                if (isPlayingInline) {
                    com.reelvault.ui.components.Tooltip(
                        text = "Stop playback",
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
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
                }

                // Inline volume — only while playing a clip that has audio.
                // Sits at the bottom-centre, clear of the corner badges.
                if (isPlayingInline && video.codecAudio.isNotEmpty()) {
                    com.reelvault.ui.components.InlineVolumeControl(
                        volume = inlineVolume,
                        onVolumeChange = onInlineVolumeChange,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp),
                    )
                }

                // (Resolution / duration / proxy-count chips that used to
                // float over the thumbnail are gone — those stats are now
                // surfaced via the four configurable top-of-card slots.)

                // (Stack/group badge + bottom location/status badges moved out
                //  to the photo-area level below, so their insets are measured
                //  against the real photo-area edges — see "Card badges".)

                // ("Too large to play here" badge moved out of the inner
                //  padded box — it now sits in the letterbox area
                //  *below* the video, positioned by the outer
                //  BoxWithConstraints so the maths can use the photo
                //  area's full dimensions.)
                } // inner padded thumbnail Box

                // "Too large to play here" marker — sits in the letterbox
                // gap between the video's bottom edge and the photo area's
                // bottom edge. Centred vertically in that gap; hidden when
                // there is less than 20 dp of space (portrait/square clips).
                if (!video.playableNatively && !video.hasProxies && proxyCreationState == null) {
                    val aspect = if (video.width > 0 && video.height > 0)
                        video.width.toFloat() / video.height.toFloat() else 1f
                    val available = (maxHeight - photoPadding * 2).coerceAtLeast(0.dp)
                    val videoH = if (aspect >= 1f) available / aspect else available
                    val topLetterbox = ((available - videoH) / 2f).coerceAtLeast(0.dp)
                    val videoBottom = photoPadding + topLetterbox + videoH
                    val iconClearance = 26.dp
                    val gapTop = videoBottom + 2.dp
                    val gapBottom = (maxHeight - iconClearance).coerceAtLeast(gapTop)
                    val gapHeight = (gapBottom - gapTop).coerceAtLeast(0.dp)
                    if (gapHeight >= 20.dp) {
                        // Centre the banner pill in the letterbox gap without
                        // height-constraining it — giving the Box a fixed height
                        // passes max-height to the Surface and squishes the text.
                        val badgeHalfHeight = 9.dp
                        val topOffset = gapTop + (gapHeight / 2) - badgeHalfHeight
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = topOffset)
                                .wrapContentSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            com.reelvault.ui.components.Tooltip(
                                text = "This video exceeds the inline-playback height limit. Right-click to create a lower-resolution proxy."
                            ) {
                                Surface(
                                    color = Color(0xFFB8722E).copy(alpha = 0.9f),
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = "Too large to play here",
                                        modifier = Modifier.padding(
                                            horizontal = ReelVaultSpacing.Small,
                                            vertical = 2.dp,
                                        ),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }

                // 1 dp black border drawn tight around the video frame itself.
                // Last child of the photo-area box so it sits on top of the
                // letterboxed thumbnail; it carries no pointer handler so it
                // never blocks scrub-hover or the play button underneath.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(videoFrameW, videoFrameH)
                        .border(1.dp, Color.Black)
                )

                // ── Card badges (photo-area-relative) ───────────────────
                // Placed at the photo-area level (not inside the
                // photoPadding-inset thumbnail box) so their insets measure
                // against the real photo-area edges, matching the macOS card.
                // The top and bottom letterboxes are symmetric, so a single
                // `edgeGap` — photo-area edge to the nearest video edge —
                // drives both the stack band at the top and the badge inset at
                // the bottom.
                val isPortrait = videoAspect < 1f
                val topLetterbox = ((videoAvailable - videoFrameH) / 2f).coerceAtLeast(0.dp)
                val edgeGap = photoPadding + topLetterbox
                val bottomInset = cardBottomBadgeInset(edgeGap, isPortrait)

                // Stack/group badge — centred vertically in the band between
                // the photo-area top and the video's top edge (mirrors the
                // macOS grid card); clamped so a near-square clip still leaves
                // room. Clicking it toggles stack expansion.
                if (video.isInGroup) {
                    val badgeIsChild = isStackChild
                    val stackTip = when {
                        isStackExpanded -> "Collapse this stack of ${video.groupSize} videos back to one card"
                        isStackChild -> "Member of a stack of ${video.groupSize} variants"
                        else -> "Expand this stack to see all ${video.groupSize} variants inline"
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .height(edgeGap.coerceAtLeast(ReelVaultSpacing.Large))
                            // photoPadding + 6 dp from the photo-area left edge,
                            // matching the macOS card's stack-badge inset.
                            .padding(start = photoPadding + 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        com.reelvault.ui.components.Tooltip(text = stackTip) {
                            Surface(
                                modifier = Modifier
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
                                        horizontal = ReelVaultSpacing.Small,
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
                    }
                }

                // Bottom-left location badge — left inset of one badge width;
                // bottom inset transitions from a badge-height of padding to
                // centred-in-the-gap as the thumbnail shrinks.
                if (video.hasLocation && onLocationClick != null) {
                    com.reelvault.ui.components.Tooltip(
                        text = "Recorded at %.4f, %.4f — click to show on map".format(
                            video.gpsLatitude, video.gpsLongitude
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = CardBadgeSize, bottom = bottomInset),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(CardBadgeSize)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                                .pointerInput(video.gpsLatitude, video.gpsLongitude) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        val up = waitForUpOrCancellation()
                                        if (up != null) {
                                            up.consume()
                                            onLocationClick(video.gpsLatitude, video.gpsLongitude)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NearMe,
                                contentDescription = "Show on map",
                                modifier = Modifier.size(11.dp),
                                // Light green (matching the full-resolution badge).
                                tint = Color(0xFF81C784)
                            )
                        }
                    }
                }

                // Bottom-right status badges — keyword / proxy / full-resolution.
                // A row in landscape; a 90°-CW-rotated column up the right side
                // in portrait. Right inset of one badge width.
                CardStatusBadges(
                    video = video,
                    portrait = isPortrait,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = CardBadgeSize, bottom = bottomInset),
                    iconSize = 10.dp,
                )
            } // outer photo-area Box (background + corner badge)
        } // end of square-thumbnail run { }

        // 1 dp separator above the bottom band.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(bandDividerColor)
        )

        // ── Bottom rating band ───────────────────────────────────────
        com.reelvault.ui.components.Tooltip(
            text = "Click a star to rate 1–5. Click the current rating again to clear it."
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(bottomBandColor),
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
                                    // Click the rightmost filled star (the
                                    // one whose position == current rating)
                                    // → clear to zero. Any other star sets
                                    // the rating to that position.
                                    if (video.rating == position) onSetRating(0)
                                    else onSetRating(position)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (filled) {
                        // Light star with a thin #1F1F1F outline (a slightly
                        // larger dark star drawn behind the white one) so the
                        // filled stars carry the same dark rim as the empty
                        // dots and stay legible on a bright selected card.
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF1F1F1F)
                            )
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "$position star",
                                modifier = Modifier.size(14.dp),
                                tint = Color.White
                            )
                        }
                    } else {
                        // Light grey dots with a 1 px #1F1F1F ring so the empty
                        // rating slots stay legible even on a bright selected
                        // card (where a plain light dot would wash out).
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .border(1.dp, Color(0xFF1F1F1F), RoundedCornerShape(50))
                                .background(Color(0xFFB8B8B8), RoundedCornerShape(50))
                        )
                    }
                }
                if (position < 5) Spacer(modifier = Modifier.width(6.dp))
            }
        }
        } // Tooltip

        // ── Proxy-creation progress strip ────────────────────────────
        // Dedicated band at the very bottom of the card, beneath the
        // rating row. Only present while a proxy is being generated.
        if (proxyCreationState != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D3B6E).copy(alpha = 0.88f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.widthIn(min = 80.dp, max = 160.dp)
                ) {
                    Text(
                        text = "Creating proxy…",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    if (proxyCreationState.progressPercent > 0) {
                        LinearProgressIndicator(
                            progress = { (proxyCreationState.progressPercent / 100.0).toFloat() },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }

    }
}

/**
 * One cell in the top-of-card stat band. Renders the stat's value for the
 * given video, with a right-click `ContextMenuArea` offering every
 * [com.reelvault.data.models.GridStatKey] option. The user's choice applies
 * catalog-wide (via [onPick] → `GridViewModel.updateGridTopSlot`).
 */
@Composable
private fun StatCell(
    slotIndex: Int,
    key: String,
    video: VideoSummary,
    onPick: (Int, String) -> Unit,
    alignEnd: Boolean,
    placeNameFor: ((Double, Double) -> String?)? = null,
    /** Selected cards have a bright background, so their top-band text flips
     *  to black; unselected cards keep white text. */
    selected: Boolean = false,
) {
    val stat = com.reelvault.data.models.GridStatKey.fromRaw(key)
    val value = stat.valueFor(video, placeNameFor)
    // Two distinct empty states:
    //   • Slot is unset (`stat == None`) → show "—" so the user
    //     knows the cell is configurable.
    //   • Slot is set but this clip has no data for the chosen stat
    //     (e.g. picked "Camera" on a clip with no EXIF) → also show
    //     "—" so the cell remains a discoverable click target. The
    //     muted text colour of `None` is kept only for truly-unset
    //     slots so a fully-configured grid still reads as configured.
    val displayed: String =
        if (value.isEmpty()) "—" else value
    var expanded by remember { mutableStateOf(false) }
    // The cell's width is assigned by the enclosing [AdaptiveStatRow], which
    // gives a short value only as much room as it needs and lets its longer
    // neighbour use the rest. heightIn + clickable sit on the Box so the whole
    // assigned cell is the click target — not just the text glyphs. The
    // Tooltip wraps only the Text inside so its hover hint doesn't affect
    // layout. `Modifier.clickable` consumes the press so the outer card-level
    // `shiftAwareClickable` doesn't *also* re-select the card.
    Box(
        modifier = Modifier
            .heightIn(min = 14.dp)
            .clickable { expanded = true },
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        com.reelvault.ui.components.Tooltip(text = "Click to choose what this slot shows") {
            Text(
                text = displayed,
                style = if (slotIndex == 0) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.labelSmall
                },
                // Selected cards: black text on the bright band. Unselected:
                // white text on the dark band. Unset slots stay at half alpha.
                color = when {
                    stat == com.reelvault.data.models.GridStatKey.None ->
                        if (selected) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f)
                    selected -> Color.Black
                    else -> Color.White.copy(alpha = 0.92f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (alignEnd) {
                    androidx.compose.ui.text.style.TextAlign.End
                } else {
                    androidx.compose.ui.text.style.TextAlign.Start
                }
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            com.reelvault.data.models.GridStatKey.values().forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (choice == stat) "✓ ${choice.displayName}"
                            else choice.displayName
                        )
                    },
                    onClick = {
                        onPick(slotIndex, choice.raw)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * One row of the card's top stat band: a leading (start-aligned) cell and a
 * trailing (end-aligned) cell. Unlike a plain 50/50 split, a short value only
 * claims the width it needs and yields the rest to its neighbour, so a long
 * value truncates only when the *pair* genuinely overflows — never just
 * because it crossed the band's centre. When both fit (or both overflow) the
 * row falls back to an even split, matching the previous behaviour.
 */
@Composable
internal fun AdaptiveStatRow(
    modifier: Modifier = Modifier,
    gap: Dp = 6.dp,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            leading()
            trailing()
        },
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val usable = (maxW - gapPx).coerceAtLeast(0)
        val half = usable / 2
        // Single-line text width is independent of the height we probe with.
        val probeH = if (constraints.hasBoundedHeight) constraints.maxHeight else 100
        val lead = measurables.getOrNull(0)
        val trail = measurables.getOrNull(1)
        val lDesired = (lead?.maxIntrinsicWidth(probeH) ?: 0).coerceAtMost(usable)
        val rDesired = (trail?.maxIntrinsicWidth(probeH) ?: 0).coerceAtMost(usable)
        // Asymmetric overflow → the short cell keeps its width and the long one
        // takes the rest. Both-fit or both-overflow → an even split.
        val (lw, rw) = when {
            lDesired <= half && rDesired <= half -> half to half
            lDesired > half && rDesired > half -> half to half
            lDesired > half -> (usable - rDesired).coerceAtLeast(0) to rDesired
            else -> lDesired to (usable - lDesired).coerceAtLeast(0)
        }
        val lp = lead?.measure(
            Constraints(minWidth = lw, maxWidth = lw, minHeight = 0, maxHeight = constraints.maxHeight)
        )
        val rp = trail?.measure(
            Constraints(minWidth = rw, maxWidth = rw, minHeight = 0, maxHeight = constraints.maxHeight)
        )
        val rowH = maxOf(lp?.height ?: 0, rp?.height ?: 0)
        layout(maxW, rowH) {
            lp?.place(0, (rowH - lp.height) / 2)
            rp?.place(maxW - rp.width, (rowH - rp.height) / 2)
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
        awaitFirstDown(requireUnconsumed = true)
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

/** Minimum pointer travel (in pixels) before a press-and-move is treated as
 *  a drag-out gesture.  8 px matches the drag threshold used by most desktop
 *  environments and is large enough to avoid accidental drags on regular clicks. */
private const val DRAG_THRESHOLD_PX = 8f
