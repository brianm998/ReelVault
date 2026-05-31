// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.reelvault.ViewMode
import java.awt.Cursor
import java.util.prefs.Preferences

/**
 * Slim vertical drag handle that resizes the panel it sits next to.
 *
 * The handle is rendered as a 6-dp transparent column on the inner edge
 * of a side panel. Hovering it switches the cursor to the platform's
 * horizontal-resize affordance so the handle is discoverable without
 * any visible chrome. Dragging emits incremental pixel deltas via
 * [onDrag]; the caller is responsible for accumulating the delta into
 * the panel's width and clamping into a legal range.
 *
 * Lightroom's library/develop panels are resized the same way — narrow
 * hover region, no visible bar, cursor change for discoverability.
 *
 * @param isLeftPanel Reserved for future use (currently used only by
 *   the caller to decide whether to negate the delta sign). Kept as a
 *   parameter so the call sites read symmetrically with the macOS
 *   equivalent.
 */
@Composable
fun PanelResizeHandle(
    isLeftPanel: Boolean,
    onDrag: (deltaPx: Float) -> Unit,
) {
    // Compose Desktop ships an AWT-backed PointerIcon for any
    // java.awt.Cursor constant — including W_RESIZE_CURSOR — so we
    // can use the system's native resize cursor on hover.
    val resizeCursor = remember {
        PointerIcon(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR))
    }
    Box(
        modifier = Modifier
            .width(6.dp)
            .fillMaxHeight()
            // Tiny tinted strip so dragging gives visual feedback even
            // without a visible chrome. The opacity is low enough that
            // the handle blends into adjacent dividers when idle.
            .background(Color.White.copy(alpha = 0.04f))
            .pointerHoverIcon(resizeCursor)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> onDrag(delta) }
            )
    )
}

/**
 * Persistent per-view-mode panel preferences (width + open state for
 * the Left and Right panels in each of Grid / List / Detail).
 *
 * Storage uses Java's [Preferences] API at user scope — same approach
 * the rest of the desktop client uses for window-size persistence —
 * which writes to `~/.java/.userPrefs/com/reelvault/panels/` on
 * Linux / macOS and the Windows registry under HKEY_CURRENT_USER on
 * Windows. The 12-entry key set is small enough that the API's per-
 * key overhead is irrelevant.
 *
 * The constants match the macOS client's `PanelPrefs` (240 dp default,
 * 180–600 dp range) so a user who moved between platforms sees the
 * same out-of-the-box layout.
 */
object PanelPrefs {
    enum class Side(val rawValue: String) { LEFT("left"), RIGHT("right") }

    const val DEFAULT_WIDTH: Float = 240f
    const val MIN_WIDTH: Float = 180f
    const val MAX_WIDTH: Float = 600f

    private val prefs: Preferences = Preferences.userRoot().node("com/reelvault/panels")

    fun clamp(value: Float): Float = value.coerceIn(MIN_WIDTH, MAX_WIDTH)

    private fun widthKey(side: Side, mode: ViewMode): String =
        "${side.rawValue}.width.${mode.name.lowercase()}"

    private fun openKey(side: Side, mode: ViewMode): String =
        "${side.rawValue}.open.${mode.name.lowercase()}"

    fun loadWidth(side: Side, mode: ViewMode): Float {
        val raw = prefs.getFloat(widthKey(side, mode), -1f)
        return if (raw > 0f) clamp(raw) else DEFAULT_WIDTH
    }

    fun loadExpanded(side: Side, mode: ViewMode): Boolean =
        prefs.getBoolean(openKey(side, mode), true)

    fun saveWidth(side: Side, mode: ViewMode, value: Float) {
        prefs.putFloat(widthKey(side, mode), value)
    }

    fun saveExpanded(side: Side, mode: ViewMode, value: Boolean) {
        prefs.putBoolean(openKey(side, mode), value)
    }
}
