// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.util

import java.awt.Component
import java.awt.Cursor
import java.awt.Point
import java.awt.Window
import java.awt.dnd.DnDConstants
import java.awt.dnd.DragGestureEvent
import java.awt.dnd.DragGestureListener
import java.awt.dnd.DragGestureRecognizer
import java.awt.dnd.DragSource
import java.awt.dnd.DragSourceAdapter
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.SwingUtilities

/**
 * Programmatic file drag-out for Compose Desktop.
 *
 * Compose Desktop's rendering happens inside a single AWT component (the
 * ComposeWindowPanel) which is a child of the ComposeWindow (a JFrame).
 * Because the Compose APIs that expose the underlying AWT component
 * ([LocalLayerContainer], [LocalWindow]) are marked `internal` in Compose
 * 1.6.x, this class instead synthesises a [DragGestureEvent] directly when
 * Compose's pointer-input system reports that a drag threshold has been
 * crossed, then calls [DragGestureEvent.startDrag] with a [FileTransferable].
 *
 * ## Usage
 *
 * ```kotlin
 * val fileDragSource = remember { FileDragSource() }
 * val awtWindow = LocalAppWindow.current  // provided by App.kt
 *
 * Modifier.pointerInput(paths, openPath) {
 *     awaitEachGesture {
 *         val down = awaitFirstDown(requireUnconsumed = false)
 *         val startPos = down.position
 *         fileDragSource.setPendingFiles(paths.ifEmpty { listOf(openPath) })
 *
 *         var handled = false
 *         while (!handled) {
 *             val event = awaitPointerEvent(PointerEventPass.Initial)
 *             val change = event.changes.firstOrNull() ?: break
 *             if (!change.pressed) {
 *                 fileDragSource.clearPending(); handled = true
 *             } else if (distanceTo(change.position, startPos) >= THRESHOLD) {
 *                 awtWindow?.let {
 *                     val sx = it.x + change.position.x.toInt()
 *                     val sy = it.y + change.position.y.toInt()
 *                     fileDragSource.startDragIfPending(it, sx, sy)
 *                 }
 *                 handled = true
 *             }
 *         }
 *     }
 * }
 * ```
 */
class FileDragSource {

    @Volatile private var pendingFiles: List<File> = emptyList()

    /** Arm the drag source with files to be transferred on the next [startDragIfPending] call. */
    fun setPendingFiles(paths: List<String>) {
        pendingFiles = paths.mapNotNull { File(it).takeIf { f -> f.exists() } }
    }

    /** Disarm without starting a drag (pointer released before drag threshold). */
    fun clearPending() {
        pendingFiles = emptyList()
    }

    /**
     * Start an AWT drag operation carrying the pending files, if any.
     *
     * This synthesises a minimal [DragGestureRecognizer] subclass, appends a
     * synthesised [MouseEvent] to its event list, then calls
     * [DragGestureRecognizer.fireDragGestureRecognized] which invokes the
     * registered [DragGestureListener] with a properly-constructed
     * [DragGestureEvent] — no reflection required.
     *
     * Must be invoked from any thread; internally dispatches to the AWT EDT via
     * [SwingUtilities.invokeLater].
     *
     * @param window   The [java.awt.Window] that owns the Compose content
     *                 (obtained via [com.reelvault.LocalAppWindow]).
     * @param screenX  Current pointer X in screen coordinates.
     * @param screenY  Current pointer Y in screen coordinates.
     */
    fun startDragIfPending(window: Window, screenX: Int, screenY: Int) {
        val files = pendingFiles
        if (files.isEmpty()) return
        pendingFiles = emptyList()

        SwingUtilities.invokeLater {
            try {
                // Find the deepest child component under the cursor — typically
                // the ComposeWindowPanel or one of its Swing children.  Falling
                // back to the window itself is safe: AWT DnD only needs a
                // Component reference for coordinate conversion.
                val comp: Component = window.findComponentAt(
                    screenX - window.x,
                    screenY - window.y
                ) ?: window

                val ds = DragSource.getDefaultDragSource()

                // Build a minimal DragGestureRecognizer subclass.  The abstract
                // registerListeners/unregisterListeners are NOPs because we drive
                // the gesture programmatically rather than via AWT mouse events.
                val recognizer = object : DragGestureRecognizer(
                    ds, comp, DnDConstants.ACTION_COPY
                ) {
                    override fun registerListeners() {}
                    override fun unregisterListeners() {}

                    /** Called by fireDragGestureRecognized once the event list is populated. */
                    fun trigger(point: Point, mouseEvent: MouseEvent) {
                        appendEvent(mouseEvent as InputEvent)
                        fireDragGestureRecognized(DnDConstants.ACTION_COPY, point)
                    }
                }

                // Register a one-shot listener that starts the AWT drag.
                recognizer.addDragGestureListener(DragGestureListener { event: DragGestureEvent ->
                    event.startDrag(
                        Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR),
                        FileTransferable(files),
                        object : DragSourceAdapter() {}
                    )
                })

                val compRelPoint = Point(screenX - window.x, screenY - window.y)
                val mouseEvent = MouseEvent(
                    comp,
                    MouseEvent.MOUSE_DRAGGED,
                    System.currentTimeMillis(),
                    MouseEvent.BUTTON1_DOWN_MASK,
                    compRelPoint.x,
                    compRelPoint.y,
                    1,
                    false,
                    MouseEvent.BUTTON1
                )
                recognizer.trigger(compRelPoint, mouseEvent)

            } catch (_: Exception) {
                // Ignore — InvalidDnDOperationException fires if a drag is
                // already in progress on some platforms.
            }
        }
    }
}
