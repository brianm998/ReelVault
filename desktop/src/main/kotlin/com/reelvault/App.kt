// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.reelvault.data.ReleaseInfo
import com.reelvault.data.UpdateChecker
import com.reelvault.data.models.CatalogInfo
import com.reelvault.data.repository.VideoRepository
import com.reelvault.data.server.RecentCatalogs
import com.reelvault.data.server.ServerLauncher
import com.reelvault.ui.screens.GridScreen
import com.reelvault.ui.screens.DetailScreen
import com.reelvault.ui.screens.DetailViewScreen
import com.reelvault.ui.screens.ListScreen
import com.reelvault.ui.components.PanelPrefs
import com.reelvault.ui.components.PanelResizeHandle
import com.reelvault.ui.screens.InfoOverlayState
import com.reelvault.ui.screens.OpenCatalogDialog
import com.reelvault.ui.theme.AccentScheme
import com.reelvault.ui.theme.ReelVaultTheme
import com.reelvault.ui.theme.ReelVaultSpacing
import com.reelvault.viewmodel.GridViewModel
import com.reelvault.viewmodel.DetailViewModel
import com.reelvault.viewmodel.NavDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ReelVault")

// The four arrow keys, used to gate grid / list selection navigation.
private val arrowKeys = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight
)

// Window-level shift key tracking. Updated by the Window's key listener and
// read at click time by VideoCard / GridScreen.
val LocalShiftPressed = compositionLocalOf { false }

/**
 * Provides the AWT [java.awt.Window] that hosts this Compose tree.
 * Used by drag-out support ([FileDragSource]) to register a
 * [java.awt.dnd.DragGestureRecognizer] on the underlying rendering component.
 *
 * Provided once by the [Window] scope in [main]; null if this composable is
 * ever rendered outside a real window (previews, tests).
 */
val LocalAppWindow = compositionLocalOf<java.awt.Window?> { null }

/**
 * Tracks whether a [com.reelvault.ui.components.PathCompletingTextField]
 * currently holds keyboard focus. The field sets this on focus-gained and
 * clears it on focus-lost. [Window.onPreviewKeyEvent] reads it so the
 * Tab → toggle-panels shortcut is suppressed while the field is active —
 * Tab should drive bash-style path completion, not collapse the side panels.
 */
val LocalPathFieldFocused = compositionLocalOf { mutableStateOf(false) }

/**
 * Number of editable text fields that currently hold keyboard focus (normally
 * 0 or 1). [Window.onPreviewKeyEvent] reads it to stand down the window-level
 * single-key shortcuts (view modes 'g'/'l'/'m'/'d', the 'i' overlay, space,
 * and the rating / colour-label digits) while the user is typing — the "key
 * responder" rule: a focused text field owns every keystroke until it releases
 * focus. macOS gets this for free (its NSEvent monitor checks the first
 * responder); the Compose client routes single keys through the window before
 * the focused widget, so it needs this explicit signal.
 *
 * A counter, not a boolean, so a focus hand-off between two fields — where the
 * gain can fire before the loss (or vice-versa) — can never leave it stuck.
 */
val LocalTextEntryActive = compositionLocalOf { mutableStateOf(0) }

/**
 * Mark a text field as a keyboard responder: while it holds focus it bumps the
 * [LocalTextEntryActive] counter, so window-level single-key shortcuts stand
 * down. Apply to every editable field (TextField / OutlinedTextField /
 * BasicTextField). The [DisposableEffect] releases the count if the field is
 * removed from composition while still focused (e.g. its dialog is dismissed),
 * which would otherwise leave the counter stuck and disable shortcuts for good.
 */
fun Modifier.trackTextEntryFocus(): Modifier = composed {
    val active = LocalTextEntryActive.current
    val counted = remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (counted.value) {
                active.value -= 1
                counted.value = false
            }
        }
    }
    onFocusChanged { state ->
        if (state.isFocused != counted.value) {
            active.value += if (state.isFocused) 1 else -1
            counted.value = state.isFocused
        }
    }
}

/** Top-level view mode for the central content area. */
enum class ViewMode { GRID, LIST, DETAIL, MAP }

/**
 * Request focus, swallowing the transient focus-tree inconsistencies Compose
 * Desktop can throw while a heavyweight SwingPanel (the map's JXMapViewer or
 * the video surface) hands AWT focus back to Compose. In that window
 * `requestFocus()` can throw "ActiveParent with no focused child"
 * (IllegalArgumentException) or hit a not-yet-attached target
 * (IllegalStateException) — both on the EDT, which kills the app. The
 * orphaned-focus state is self-correcting on the next event, so skipping one
 * reclaim attempt is far better than crashing. Used for every focus request
 * that can race a heavyweight-peer focus hand-off: the root-focus reclaim (the
 * focus-reclaim Box and the Escape handler) and the map's Name/Rename-location
 * dialog auto-focusing its field (see [com.reelvault.ui.screens.LocationNameDialog]).
 *
 * `internal` (not `private`) so dialogs in other files can reuse it. The global
 * [installEdtFocusCrashGuard] only catches the `IllegalStateException` variant
 * thrown from event dispatch; this wrapper also catches the `IllegalArgument`
 * variant a direct `requestFocus()` can throw, so it must guard every call site.
 */
internal fun FocusRequester.requestFocusSafely() {
    try {
        requestFocus()
    } catch (_: IllegalArgumentException) {
        // Focus tree mid-transition (heavyweight peer handing focus back).
    } catch (_: IllegalStateException) {
        // No active focus target yet; the next event reclaims it.
    }
}

/**
 * Keep the AWT event-dispatch thread alive across a Compose-Desktop focus race
 * that [requestFocusSafely] can't cover.
 *
 * AWT delivers a `keyTyped` for every key press regardless of whether Compose
 * consumed the paired `keyPressed`; Compose turns that into an internal KeyDown
 * and routes it through `FocusOwnerImpl.dispatchKeyEvent`. If a recomposition
 * is in flight at that instant (common during a library scan, when gRPC
 * progress events churn the UI on the EDT) no node may hold focus, and
 * FocusOwnerImpl throws
 *   IllegalStateException("Event can't be processed because we do not have an
 *   active focus target.")
 * straight from event dispatch — not from a `requestFocus()` call, so the
 * try/catch in [requestFocusSafely] never sees it. The exception escapes
 * `EventDispatchThread.pumpEvents` and kills the EDT, taking the app with it.
 *
 * The invisible root Box already reclaims orphaned focus on the next event (see
 * its `onFocusChanged`), so the only thing missing is surviving this one event.
 * Push an [java.awt.EventQueue] that swallows exactly this exception — every
 * other Throwable is rethrown untouched — so the EDT keeps running and the next
 * event restores a valid focus target. Best-effort: if the queue can't be
 * installed we simply keep the prior behavior.
 */
private fun installEdtFocusCrashGuard() {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemEventQueue.push(
            object : java.awt.EventQueue() {
                override fun dispatchEvent(event: java.awt.AWTEvent) {
                    try {
                        super.dispatchEvent(event)
                    } catch (e: IllegalStateException) {
                        if (e.message?.contains("active focus target") == true) {
                            logger.warn(
                                "Swallowed Compose focus-dispatch race on the EDT " +
                                    "(no active focus target); root focus reclaims " +
                                    "on the next event."
                            )
                        } else {
                            throw e
                        }
                    }
                }
            }
        )
    }.onFailure { logger.warn("Could not install EDT focus-crash guard", it) }
}

fun main() {
    // apple.awt.application.name drives the macOS Dock tooltip and menu-bar
    // app label. The reliable place to set it is as a JVM `-D` arg
    // (configured in build.gradle.kts) — by the time main() runs the
    // launcher has already initialised parts of AWT/Cocoa, and a runtime
    // System.setProperty here was empirically too late even though it
    // happens before application{}. The runtime fallback below is kept as
    // belt-and-suspenders for the case where the JVM arg got stripped
    // (some launchers, including the gradle daemon under certain
    // configurations, mangle quoting on `-D` values).
    if (System.getProperty("apple.awt.application.name").isNullOrEmpty()) {
        System.setProperty("apple.awt.application.name", "ReelVault")
    }
    // Set the Dock-tooltip name via NSProcessInfo. The JVM flags
    // (-Xdock:name, -Dapple.awt.application.name) only update the
    // menu-bar label — the tooltip on the Dock tile reads
    // NSProcessInfo.processName, which defaults to "java" for any
    // raw `java` process. See MacDockName.kt for the full why.
    com.reelvault.util.MacDockName.set("ReelVault")
    logger.info(
        "Launching ReelVault; apple.awt.application.name='{}', " +
            "-Xdock visible via inputArguments={}",
        System.getProperty("apple.awt.application.name"),
        java.lang.management.ManagementFactory.getRuntimeMXBean()
            .inputArguments.filter { it.startsWith("-Xdock") || it.contains("apple.awt") }
    )
    // OSM tile server blocks Java's default `Java/<version>` UA — JXMapViewer
    // renders blank without this. Safe to set before networking starts.
    if (System.getProperty("http.agent").isNullOrEmpty()) {
        System.setProperty(
            "http.agent",
            "ReelVault/0.1 (+https://github.com/reelvault/reelvault)"
        )
    }
    // Compose Desktop's interop blending — read at first Window creation,
    // so technically fine inside application{} too, but kept here with the
    // other launch-time properties for symmetry.
    System.setProperty("compose.interop.blending", "true")

    // Belt-and-suspenders for the Dock tile: set the icon image via
    // java.awt.Taskbar so the macOS Dock shows the ReelVault icon even
    // when launched outside the packaged .app (e.g. `./gradlew run`).
    // Window(icon = …) sets the per-window icon but doesn't always
    // propagate to the Dock tile on macOS; Taskbar does.
    runCatching {
        if (java.awt.Taskbar.isTaskbarSupported()) {
            val taskbar = java.awt.Taskbar.getTaskbar()
            if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                val url = {}.javaClass.classLoader.getResource("icons/AppIcon.png")
                if (url != null) {
                    taskbar.iconImage = javax.imageio.ImageIO.read(url)
                }
            }
        }
    }

    // Survive the Compose focus-dispatch race that can otherwise kill the EDT
    // mid-scan (see installEdtFocusCrashGuard). Installed before any windows
    // exist so it wraps every event from the first one onward.
    installEdtFocusCrashGuard()

    application {
        val windowState = rememberWindowState(
            size = DpSize(width = 1400.dp, height = 900.dp)
        )

        var shiftPressed by remember { mutableStateOf(false) }
        // True when the top-bar search field has focus. Used to suppress
        // single-key shortcuts ('g', 'd', 'i') so the user can still type those
        // letters into the search box.
        val searchFocused = remember { mutableStateOf(false) }
        // FocusRequester for the invisible root Box (see the long comment by
        // the Box declaration). Declared here so the Window-level Escape and
        // click-outside handlers can transfer focus to it, dismissing any
        // focused TextField.
        val rootFocus = remember { FocusRequester() }
        // True while a PathCompletingTextField holds focus. Suppresses the
        // Tab → toggle-panels shortcut so Tab drives path completion instead.
        val pathFieldFocused = remember { mutableStateOf(false) }
        // Count of editable text fields currently focused (see LocalTextEntryActive).
        // Non-zero ⇒ the user is typing, so single-key shortcuts stand down.
        val textEntryActive = remember { mutableStateOf(0) }
        // ReelVaultApp registers its "group selected" action here, so the Window-
        // level key listener can invoke it on Cmd/Ctrl+G regardless of focus.
        val groupSelectedAction = remember { mutableStateOf<() -> Unit>({}) }
        // Same pattern for the Tab key panel-toggle.
        val togglePanelsAction = remember { mutableStateOf<() -> Unit>({}) }
        // …and for Cmd/Ctrl+A — "select all currently-visible videos".
        val selectAllAction = remember { mutableStateOf<() -> Unit>({}) }
        // …and for Cmd/Ctrl+D — "deselect everything".
        val deselectAllAction = remember { mutableStateOf<() -> Unit>({}) }
        // Actions for plain 'g' (grid mode), 'd' (detail mode), 'i' (cycle info
        // overlay). Bound via onPreviewKeyEvent so the search field's plain-key
        // input still works via the `searchFocused` gate above.
        val setGridModeAction = remember { mutableStateOf<() -> Unit>({}) }
        val setListModeAction = remember { mutableStateOf<() -> Unit>({}) }
        val setDetailModeAction = remember { mutableStateOf<() -> Unit>({}) }
        val setMapModeAction = remember { mutableStateOf<() -> Unit>({}) }
        val cycleInfoOverlayAction = remember { mutableStateOf<() -> Unit>({}) }
        // Space bar: toggle inline playback of the selected video.
        val spacebarAction = remember { mutableStateOf<() -> Unit>({}) }
        // Lightroom-style rating shortcut (digits 0..5). Carries the rating
        // value; the handler applies it to the current selection.
        val setRatingAction = remember { mutableStateOf<(Int) -> Unit>({ _ -> }) }
        // Lightroom-style colour-label shortcut (digits 6..9 + backtick).
        // Carries the colour raw value; "" clears.
        val setColorLabelAction = remember { mutableStateOf<(String) -> Unit>({ _ -> }) }
        // Arrow-key navigation in grid / list. Carries the direction; the
        // handler moves the active selection and syncs the detail panel.
        // Second arg: true when Shift is held → extend a range from the anchor
        // instead of moving the single selection.
        val moveSelectionAction = remember { mutableStateOf<(NavDirection, Boolean) -> Unit>({ _, _ -> }) }
        // Catalog + help shortcuts. The SwiftUI client gets these from its
        // native menu bar (⌘O / ⌘⇧W / ⌘?); the Compose client's File/Help menus
        // are in-window dropdowns with no key accelerators, so we drive them
        // from the Window-level key listener instead.
        val openCatalogAction = remember { mutableStateOf<() -> Unit>({}) }
        val closeCatalogAction = remember { mutableStateOf<() -> Unit>({}) }
        val showHelpAction = remember { mutableStateOf<() -> Unit>({}) }
        // Title reflects the currently-open catalog (lifted here so Window.title
        // recomposes when the catalog changes).
        var currentCatalog by remember { mutableStateOf(CatalogInfo.Closed) }
        val windowTitle = if (currentCatalog.isOpen) {
            "ReelVault — ${currentCatalog.name}"
        } else {
            "ReelVault"
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = windowTitle,
            icon = painterResource("icons/AppIcon.png"),
            // onPreviewKeyEvent fires BEFORE focused widgets consume the event,
            // so it works even when the search TextField is focused.
            onPreviewKeyEvent = { event ->
                // Track shift state for the rest of the UI.
                if (event.key == Key.ShiftLeft || event.key == Key.ShiftRight) {
                    shiftPressed = event.type == KeyEventType.KeyDown
                }
                // Cmd+G (macOS) / Ctrl+G (Windows/Linux) → group selected videos.
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.G &&
                    (event.isMetaPressed || event.isCtrlPressed)
                ) {
                    groupSelectedAction.value()
                    return@Window true // consume so default shortcuts don't also fire
                }
                // Tab → toggle both side panels (Lightroom-style).
                // Guard: when a PathCompletingTextField is focused, Tab must
                // drive its bash-style path completion — not collapse panels.
                // pathFieldFocused is set/cleared by the field's onFocusChanged.
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.Tab &&
                    !event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed &&
                    !pathFieldFocused.value
                ) {
                    togglePanelsAction.value()
                    return@Window true // consume so focus traversal doesn't also fire
                }
                // Escape → release focus from the top-bar search field so the
                // user can resume single-key shortcuts (and so the field stops
                // swallowing every subsequent keystroke). Only consume the
                // event when the field actually holds focus — otherwise let
                // dialogs and other components see Escape normally.
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.Escape &&
                    searchFocused.value
                ) {
                    rootFocus.requestFocusSafely()
                    return@Window true
                }
                // Single-letter shortcuts: only when no modifier is held AND no
                // text field is receiving the keystroke. The search box reports
                // via searchFocused; every other editable field bumps
                // textEntryActive (see Modifier.trackTextEntryFocus). Together
                // they enforce the key-responder rule: while the user types into
                // any field, 'g'/'l'/'m'/'d'/'i', space and the rating/colour
                // digits go to the field, not the view.
                if (event.type == KeyEventType.KeyDown &&
                    !event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed &&
                    !searchFocused.value && textEntryActive.value == 0
                ) {
                    when (event.key) {
                        Key.G -> { setGridModeAction.value(); return@Window true }
                        Key.L -> { setListModeAction.value(); return@Window true }
                        Key.D -> { setDetailModeAction.value(); return@Window true }
                        Key.M -> { setMapModeAction.value(); return@Window true }
                        Key.I -> { cycleInfoOverlayAction.value(); return@Window true }
                        Key.Spacebar -> { spacebarAction.value(); return@Window true }
                        // Lightroom-style rating shortcuts (number-row digits).
                        Key.Zero  -> { setRatingAction.value(0); return@Window true }
                        Key.One   -> { setRatingAction.value(1); return@Window true }
                        Key.Two   -> { setRatingAction.value(2); return@Window true }
                        Key.Three -> { setRatingAction.value(3); return@Window true }
                        Key.Four  -> { setRatingAction.value(4); return@Window true }
                        Key.Five  -> { setRatingAction.value(5); return@Window true }
                        // Lightroom-style colour-label shortcuts. Purple has
                        // no shortcut by design (right-click only).
                        Key.Six   -> { setColorLabelAction.value("red");    return@Window true }
                        Key.Seven -> { setColorLabelAction.value("yellow"); return@Window true }
                        Key.Eight -> { setColorLabelAction.value("green");  return@Window true }
                        Key.Nine  -> { setColorLabelAction.value("blue");   return@Window true }
                        // Backtick / grave clears the colour label. Universal
                        // fallback via the right-click "Set Color Label →
                        // None" menu for keyboards where this key is awkward.
                        Key.Grave -> { setColorLabelAction.value("");       return@Window true }
                        else -> Unit
                    }
                }
                false
            },
            // onKeyEvent fires AFTER focused widgets — so a focused TextField
            // (search bar, notes, keyword input) can still handle Cmd/Ctrl+A as
            // "select all text"; we only catch it when nothing else does.
            onKeyEvent = { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.A &&
                        (event.isMetaPressed || event.isCtrlPressed) &&
                        !event.isShiftPressed && !event.isAltPressed -> {
                        selectAllAction.value()
                        true
                    }
                    // Cmd/Ctrl+D — deselect every selected video. Same
                    // post-process placement as Cmd+A so a focused TextField
                    // gets first crack (it doesn't actually use Cmd+D, but the
                    // policy is "Window-level shortcuts never steal from a
                    // focused widget").
                    event.key == Key.D &&
                        (event.isMetaPressed || event.isCtrlPressed) &&
                        !event.isShiftPressed && !event.isAltPressed -> {
                        deselectAllAction.value()
                        true
                    }
                    // Cmd/Ctrl+O — Open Catalog. No text field uses it, so the
                    // post-focus placement is harmless and keeps the policy
                    // "Window shortcuts never steal from a focused widget".
                    event.key == Key.O &&
                        (event.isMetaPressed || event.isCtrlPressed) &&
                        !event.isShiftPressed && !event.isAltPressed -> {
                        openCatalogAction.value()
                        true
                    }
                    // Cmd/Ctrl+Shift+W — Close Catalog. Shift mirrors macOS's
                    // ⌘⇧W and dodges the bare ⌘W/Ctrl+W "close the window" reflex.
                    event.key == Key.W &&
                        (event.isMetaPressed || event.isCtrlPressed) &&
                        event.isShiftPressed && !event.isAltPressed -> {
                        closeCatalogAction.value()
                        true
                    }
                    // F1 — Help. The cross-platform help key; macOS also exposes
                    // ⌘? from its native menu bar.
                    event.key == Key.F1 -> {
                        showHelpAction.value()
                        true
                    }
                    // Arrow keys move the grid / list selection (Shift extends a
                    // range). Handled here (post-focus) so a focused multi-line
                    // field keeps arrows for caret movement; the !searchFocused
                    // guard covers the single-line search box, which wouldn't
                    // consume Up/Down.
                    !event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed &&
                        !searchFocused.value && textEntryActive.value == 0 &&
                        event.key in arrowKeys -> {
                        val extend = event.isShiftPressed
                        when (event.key) {
                            Key.DirectionUp -> moveSelectionAction.value(NavDirection.Up, extend)
                            Key.DirectionDown -> moveSelectionAction.value(NavDirection.Down, extend)
                            Key.DirectionLeft -> moveSelectionAction.value(NavDirection.Left, extend)
                            Key.DirectionRight -> moveSelectionAction.value(NavDirection.Right, extend)
                        }
                        true
                    }
                    else -> false
                }
            }
        ) {
            // Guarantee a valid Compose focus target at all times.
            //
            // Problem: AWT fires a keyTyped event for every key press, independent
            // of whether the paired keyPressed event was "consumed" by Compose's
            // onPreviewKeyEvent handler. Compose converts keyTyped into an internal
            // KeyDown event and routes it through FocusOwnerImpl.dispatchKeyEvent.
            // When a recomposition is in flight at that instant (common during a
            // library scan, because gRPC progress events arrive on the EDT and
            // trigger rapid UI updates), no composable may hold focus yet.
            // FocusOwnerImpl throws IllegalStateException("Event can't be processed
            // because we do not have an active focus target"), which on JDK 17+
            // propagates all the way up EventDispatchThread.pumpEvents and kills
            // the EDT — crashing the app.
            //
            // Fix: this invisible Box is focusable and claims focus exactly once
            // at window launch. Child composables (search bar, text fields, etc.)
            // can still take focus normally; when they release it the Box holds it
            // again as a neutral fallback. The focus system's invariant is never
            // violated regardless of recomposition timing.
            //
            // The same Box also handles click-outside-to-clear-focus: any tap that
            // isn't consumed by a child (button, TextField, etc.) reaches this
            // pointerInput and transfers focus back to the Box — so clicking
            // empty space dismisses the search field, matching macOS behavior.
            // rootFocus is declared at application scope so the Window-level
            // Escape handler can also use it.
            LaunchedEffect(Unit) {
                // requestFocus() must run after the first composition pass so the
                // node is actually attached to the owner. LaunchedEffect(Unit)
                // fires after the first frame — exactly the right moment.
                rootFocus.requestFocusSafely()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Reclaim focus whenever the whole subtree loses it (a child
                    // that had focus was removed — e.g. a panel collapsed, or the
                    // video surface released AWT focus). Without this, a key press
                    // arriving while no element is focused crashes Compose's key
                    // dispatch ("no active focus target"). `hasFocus` is true when
                    // this node OR any descendant is focused, so we only reclaim
                    // when focus is genuinely orphaned — never stealing it from a
                    // focused text field.
                    .onFocusChanged { if (!it.hasFocus) rootFocus.requestFocusSafely() }
                    .focusRequester(rootFocus)
                    .focusable()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { rootFocus.requestFocusSafely() })
                    }
            ) {
                CompositionLocalProvider(
                    LocalShiftPressed provides shiftPressed,
                    // Expose the AWT window for drag-out support (FileDragSource).
                    // `window` is the ComposeWindow (a JFrame) available in
                    // FrameWindowScope — the lambda body of Window { ... }.
                    LocalAppWindow provides window,
                    // Let PathCompletingTextField signal its focus state so
                    // onPreviewKeyEvent can suppress Tab → panel-toggle.
                    LocalPathFieldFocused provides pathFieldFocused,
                    // Let every editable text field signal focus so the window's
                    // single-key shortcuts stand down while the user types.
                    LocalTextEntryActive provides textEntryActive
                ) {
                    ReelVaultApp(
                        onRegisterGroupAction = { groupSelectedAction.value = it },
                        onRegisterTogglePanelsAction = { togglePanelsAction.value = it },
                        onRegisterSelectAllAction = { selectAllAction.value = it },
                        onRegisterDeselectAllAction = { deselectAllAction.value = it },
                        onRegisterSetGridMode = { setGridModeAction.value = it },
                        onRegisterSetListMode = { setListModeAction.value = it },
                        onRegisterSetDetailMode = { setDetailModeAction.value = it },
                        onRegisterSetMapMode = { setMapModeAction.value = it },
                        onRegisterCycleInfoOverlay = { cycleInfoOverlayAction.value = it },
                        onRegisterSpacebarAction = { spacebarAction.value = it },
                        onRegisterSetRatingAction = { setRatingAction.value = it },
                        onRegisterSetColorLabelAction = { setColorLabelAction.value = it },
                        onRegisterMoveSelection = { moveSelectionAction.value = it },
                        onRegisterOpenCatalog = { openCatalogAction.value = it },
                        onRegisterCloseCatalog = { closeCatalogAction.value = it },
                        onRegisterShowHelp = { showHelpAction.value = it },
                        onSearchFocusChanged = { searchFocused.value = it },
                        onCatalogChanged = { currentCatalog = it }
                    )
                }
            }
        }
    }
}

@Composable
fun ReelVaultApp(
    /** Called once to register the "group selected" action for the Cmd/Ctrl+G shortcut. */
    onRegisterGroupAction: (() -> Unit) -> Unit = {},
    /** Called once to register the "toggle panels" action for the Tab shortcut. */
    onRegisterTogglePanelsAction: (() -> Unit) -> Unit = {},
    /** Called once to register the "select all visible" action for the
     *  Cmd/Ctrl+A shortcut. */
    onRegisterSelectAllAction: (() -> Unit) -> Unit = {},
    /** Called once to register the "deselect everything" action for the
     *  Cmd/Ctrl+D shortcut. */
    onRegisterDeselectAllAction: (() -> Unit) -> Unit = {},
    /** Called once to register the "switch to grid" action for the 'g' shortcut. */
    onRegisterSetGridMode: (() -> Unit) -> Unit = {},
    /** Called once to register the "switch to list" action for the 'l' shortcut. */
    onRegisterSetListMode: (() -> Unit) -> Unit = {},
    /** Called once to register the "switch to detail" action for the 'd' shortcut. */
    onRegisterSetDetailMode: (() -> Unit) -> Unit = {},
    /** Called once to register the "switch to map" action for the 'm' shortcut. */
    onRegisterSetMapMode: (() -> Unit) -> Unit = {},
    /** Called once to register the "cycle info overlay" action for the 'i' shortcut. */
    onRegisterCycleInfoOverlay: (() -> Unit) -> Unit = {},
    /** Called once to register the space-bar play/pause action. */
    onRegisterSpacebarAction: (() -> Unit) -> Unit = {},
    /** Called once to register the digit-key rating action (0..5). */
    onRegisterSetRatingAction: ((Int) -> Unit) -> Unit = {},
    /** Called once to register the digit-key colour-label action. Receives
     *  the raw colour string ("" / red / yellow / green / blue). */
    onRegisterSetColorLabelAction: ((String) -> Unit) -> Unit = {},
    /** Called once to register the arrow-key grid/list navigation action. */
    onRegisterMoveSelection: ((NavDirection, Boolean) -> Unit) -> Unit = {},
    /** Called once to register the Open-Catalog action for the Cmd/Ctrl+O shortcut. */
    onRegisterOpenCatalog: (() -> Unit) -> Unit = {},
    /** Called once to register the Close-Catalog action for the Cmd/Ctrl+Shift+W shortcut. */
    onRegisterCloseCatalog: (() -> Unit) -> Unit = {},
    /** Called once to register the Help action for the F1 shortcut. */
    onRegisterShowHelp: (() -> Unit) -> Unit = {},
    /** Reports search-field focus state to the Window so it can suppress
     *  single-letter shortcuts while the user is typing. */
    onSearchFocusChanged: (Boolean) -> Unit = {},
    /** Notified whenever the open-catalog state changes, so the parent can
     *  update the Window title. */
    onCatalogChanged: (CatalogInfo) -> Unit = {}
) {
    val repository = remember { VideoRepository.getInstance() }
    val gridViewModel = remember { GridViewModel(repository) }
    val detailViewModel = remember { DetailViewModel(repository) }
    val launcher = remember { ServerLauncher() }
    val recents = remember { RecentCatalogs.Default }

    // Per-view-mode panel state — width + open/closed flag for each of
    // (Grid, List, Detail). Lightroom keeps these independent across
    // views (Library / Develop / Print), and we apply the same pattern.
    // Defaults are loaded from Java `Preferences` so widths persist
    // across launches; mutations write back via `setLeftPanelWidth`
    // / `setRightPanelWidth` / `setLeftPanelExpanded` etc.
    val leftPanelWidths = remember { mutableStateMapOf<ViewMode, Float>().also {
        ViewMode.values().forEach { m -> it[m] = PanelPrefs.loadWidth(PanelPrefs.Side.LEFT, m) }
    } }
    val rightPanelWidths = remember { mutableStateMapOf<ViewMode, Float>().also {
        ViewMode.values().forEach { m -> it[m] = PanelPrefs.loadWidth(PanelPrefs.Side.RIGHT, m) }
    } }
    val leftPanelExpandeds = remember { mutableStateMapOf<ViewMode, Boolean>().also {
        ViewMode.values().forEach { m -> it[m] = PanelPrefs.loadExpanded(PanelPrefs.Side.LEFT, m) }
    } }
    val rightPanelExpandeds = remember { mutableStateMapOf<ViewMode, Boolean>().also {
        ViewMode.values().forEach { m -> it[m] = PanelPrefs.loadExpanded(PanelPrefs.Side.RIGHT, m) }
    } }
    // Convenience accessors for the active view-mode live further down,
    // after `viewMode` itself is declared — Kotlin can't forward-reference.

    // Accent color scheme — persisted via Java Preferences.
    val uiPrefs = remember { java.util.prefs.Preferences.userRoot().node("com/reelvault/ui") }

    // Thumbnail size controls the minimum column width for the adaptive grid.
    // Smaller value → more columns when there's space; larger → fewer, bigger cards.
    // Persisted to the same uiPrefs node so the user's last size survives restarts.
    var thumbnailWidth by remember {
        mutableStateOf(uiPrefs.getFloat("thumbnailWidth", 220f).dp)
    }
    var accentScheme by remember {
        mutableStateOf(AccentScheme.fromString(uiPrefs.get("accentScheme", null)))
    }
    // Live-updates / file-watcher settings dialog visibility.
    var showWatchSettingsDialog by remember { mutableStateOf(false) }
    // Inline-playback / proxy-resolution preferences dialog visibility.
    var showPlaybackSettingsDialog by remember { mutableStateOf(false) }
    // Appearance (accent color scheme) dialog visibility.
    var showAppearanceDialog by remember { mutableStateOf(false) }
    // Camera-names editor dialog visibility (internal → marketing).
    var showCameraNamesDialog by remember { mutableStateOf(false) }
    var showLensNamesDialog by remember { mutableStateOf(false) }
    // Library-wide auto-tagging / detection settings (currently houses
    // the timelapse auto-tag toggle; future home for other library knobs).
    var showLibrarySettingsDialog by remember { mutableStateOf(false) }
    // Library removal confirmation. Non-null while the "Are you sure?" dialog is shown.
    var pendingRemoveLocation by remember { mutableStateOf<com.reelvault.data.models.LibraryLocation?>(null) }
    // Collection deletion confirmation. Non-null while the "Are you sure?" dialog is shown.
    var pendingDeleteCollection by remember { mutableStateOf<com.reelvault.data.models.Collection?>(null) }
    // Smart-collection name dialog.
    var showSmartCollectionDialog by remember { mutableStateOf(false) }
    var smartCollectionName by remember { mutableStateOf("") }
    // Map view (a top-level view, like grid/list/detail). When non-null,
    // `globalMapFocusLocation` makes the map open centred on this (lat, lon)
    // instead of framing all pins — set when the user taps a card's location
    // badge, then cleared by the map once applied. `mapSelectedVideoIds` are
    // the videos under the pin(s) the user has clicked, listed as cards in the
    // right panel.
    var globalMapFocusLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var mapSelectedVideoIds by remember { mutableStateOf<List<String>>(emptyList()) }
    // Non-null while the map's right-click "Name / Rename location" dialog is up,
    // carrying the pin the user right-clicked.
    var renameLocationPin by remember { mutableStateOf<com.reelvault.ui.components.MapPin?>(null) }
    // Location-picker state. `videoIdsForLocationPicker` non-null means the
    // dialog is open and operates on that set of video ids.
    var videoIdsForLocationPicker by remember { mutableStateOf<List<String>?>(null) }
    var initialLocationForPicker by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // Capture-date picker state — same pattern as the location picker.
    var videoIdsForDatePicker by remember { mutableStateOf<List<String>?>(null) }
    var initialTimestampForPicker by remember { mutableStateOf<Long?>(null) }

    // Add-library dialog visibility. Lifted here so both the top-bar
    // "add folder" button and the sidebar's '+' button can open the same
    // dialog.
    var showAddLibraryDialog by remember { mutableStateOf(false) }

    // Help dialog visibility.
    var showHelpDialog by remember { mutableStateOf(false) }

    // Update-available banner. Non-null when a newer GitHub release is found.
    // Dismissed by the user; rechecked every 24 h (and once at startup).
    var pendingUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }

    // Top-level view mode. GRID is the default catalog view; DETAIL is the
    // single-video loupe with in-app playback.
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }

    // Per-view-mode panel state accessors. The 4 maps above store every
    // view-mode's values; these `val`s + helper funcs read/write the
    // entry for the *current* viewMode and persist on every change.
    val leftPanelWidth = leftPanelWidths[viewMode] ?: PanelPrefs.DEFAULT_WIDTH
    val rightPanelWidth = rightPanelWidths[viewMode] ?: PanelPrefs.DEFAULT_WIDTH
    val leftPanelExpanded = leftPanelExpandeds[viewMode] ?: true
    val rightPanelExpanded = rightPanelExpandeds[viewMode] ?: true
    fun setLeftPanelWidth(w: Float) {
        val clamped = PanelPrefs.clamp(w)
        leftPanelWidths[viewMode] = clamped
        PanelPrefs.saveWidth(PanelPrefs.Side.LEFT, viewMode, clamped)
    }
    fun setRightPanelWidth(w: Float) {
        val clamped = PanelPrefs.clamp(w)
        rightPanelWidths[viewMode] = clamped
        PanelPrefs.saveWidth(PanelPrefs.Side.RIGHT, viewMode, clamped)
    }
    fun setLeftPanelExpanded(open: Boolean) {
        leftPanelExpandeds[viewMode] = open
        PanelPrefs.saveExpanded(PanelPrefs.Side.LEFT, viewMode, open)
    }
    fun setRightPanelExpanded(open: Boolean) {
        rightPanelExpandeds[viewMode] = open
        PanelPrefs.saveExpanded(PanelPrefs.Side.RIGHT, viewMode, open)
    }

    // Info overlay cycle in detail view ('i' key advances through states).
    var infoOverlay by remember { mutableStateOf(InfoOverlayState.NONE) }

    // Space bar in detail mode: each increment triggers a play/pause inside
    // DetailViewScreen without exposing its internal player state upward.
    var detailPlayToggle by remember { mutableStateOf(0) }

    // OpenCatalog dialog state. Shown automatically when the daemon has no
    // catalog open, or when the user picks File → Open.
    var showOpenCatalogDialog by remember { mutableStateOf(false) }
    var openDialogIsStartup by remember { mutableStateOf(false) }

    // Currently-open catalog from the server's perspective. Drives the title
    // and the File menu's enable state.
    var currentCatalog by remember { mutableStateOf(CatalogInfo.Closed) }
    // Propagate changes up so the Window title can recompose.
    LaunchedEffect(currentCatalog) { onCatalogChanged(currentCatalog) }

    // Register the keyboard shortcut handlers with the Window-level key listener.
    LaunchedEffect(gridViewModel) {
        onRegisterGroupAction { gridViewModel.groupSelectedVideos() }
        onRegisterSelectAllAction { gridViewModel.selectAllVisible() }
        onRegisterDeselectAllAction { gridViewModel.clearSelection() }
        onRegisterSpacebarAction {
            when (viewMode) {
                ViewMode.DETAIL -> {
                    // Delegate to DetailViewScreen via the toggle token.
                    detailPlayToggle++
                }
                ViewMode.GRID, ViewMode.LIST -> {
                    val playing = gridViewModel.playingVideoId.value
                    if (playing != null) {
                        // A video is playing — stop it.
                        gridViewModel.stopPlayback()
                    } else {
                        // Start playing the selected video (prefer proxy for oversize).
                        // Only trigger when exactly one video is selected so we don't
                        // accidentally start playback during multi-select.
                        val selectedId = gridViewModel.selectedVideoId.value
                        val selCount = gridViewModel.selectedVideoIds.value.size
                        if (selectedId != null && selCount <= 1) {
                            gridViewModel.playVideoPreferProxy(selectedId)
                        }
                    }
                }
                // No inline playback context on the map.
                ViewMode.MAP -> {}
            }
        }
        onRegisterSetRatingAction { rating ->
            gridViewModel.setRatingOnSelection(rating)
        }
        onRegisterSetColorLabelAction { label ->
            gridViewModel.setColorLabelOnSelection(label)
        }
        onRegisterMoveSelection { dir, extend ->
            // Arrow keys only navigate the grid / list, never the loupe.
            // Shift extends a range from the anchor; otherwise move the
            // single selection.
            if (viewMode == ViewMode.GRID || viewMode == ViewMode.LIST) {
                val moved = if (extend) {
                    gridViewModel.extendSelection(dir)
                } else {
                    gridViewModel.moveSelection(dir)
                }
                moved?.let {
                    detailViewModel.setCurrentVideo(it)
                    detailViewModel.loadMetadata(it.id)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        onRegisterTogglePanelsAction {
            // Read the live expanded-state from the state maps, not the
            // `leftPanelExpanded` / `rightPanelExpanded` locals. This lambda is
            // registered once (LaunchedEffect(Unit)) and capturing those locals
            // would freeze the first-composition snapshot — both panels default
            // to expanded — so `anyOpen` would stay true forever and Tab could
            // only ever close the panels, never reopen them. The state maps and
            // `viewMode` are stable remembered state, so reads here always see
            // the current values.
            val anyOpen = (leftPanelExpandeds[viewMode] ?: true) ||
                (rightPanelExpandeds[viewMode] ?: true)
            // If either is open, close both. If both are closed, open both.
            setLeftPanelExpanded(!anyOpen)
            setRightPanelExpanded(!anyOpen)
        }
    }
    LaunchedEffect(Unit) {
        onRegisterSetGridMode { viewMode = ViewMode.GRID }
        onRegisterSetListMode { viewMode = ViewMode.LIST }
        onRegisterSetDetailMode { viewMode = ViewMode.DETAIL }
        onRegisterSetMapMode {
            // Entering the map by shortcut frames all pins (no card-badge
            // focus), so drop any stale focus coordinate first.
            globalMapFocusLocation = null
            viewMode = ViewMode.MAP
        }
        onRegisterCycleInfoOverlay {
            infoOverlay = when (infoOverlay) {
                InfoOverlayState.NONE -> InfoOverlayState.CAMERA
                InfoOverlayState.CAMERA -> InfoOverlayState.FILE
                InfoOverlayState.FILE -> InfoOverlayState.NONE
            }
        }
    }
    // Refresh the map's pins from the current filter whenever the map becomes
    // the active view. Filter edits made while the map is up already refresh
    // videoLocations via the grid reload's debounced trigger.
    LaunchedEffect(viewMode) {
        // DETAIL pins the loupe's video even when a metadata edit filters it out;
        // returning to GRID/LIST/MAP drops a now-hidden selection so the inspector
        // doesn't strand it. The GridViewModel doesn't otherwise know the view mode.
        gridViewModel.onViewModeChanged(viewMode == ViewMode.DETAIL)
        if (viewMode == ViewMode.MAP) {
            gridViewModel.loadVideoLocationsFilteredAsync()
            // Named places drive the pin labels.
            gridViewModel.loadNamedLocationsAsync()
        }
    }

    val scope = rememberCoroutineScope()
    var connectionState by remember { mutableStateOf(ConnectionState.Connecting) }

    // Open the location picker on a set of videos, framed on `initial` (the
    // current location for an "update", or null to frame on all data for an
    // "add"). Awaits the location loads first so the picker's bbox framing sees
    // populated arrays (else it centres on Europe). Shared by the grid/list/map
    // right-click "Add/Update Location…" items and the detail panel.
    val openLocationPicker: (List<String>, Pair<Double, Double>?) -> Unit = { videoIds, initial ->
        scope.launch {
            gridViewModel.loadVideoLocationsAsync()
            gridViewModel.loadNamedLocationsAsync()
            videoIdsForLocationPicker = videoIds
            initialLocationForPicker = initial
        }
    }
    var errorMessage by remember { mutableStateOf("") }

    /** Load library data after a successful catalog open. */
    fun loadAfterCatalogOpened() {
        gridViewModel.loadVideos()
        gridViewModel.loadLibraryLocations()
        gridViewModel.loadTags()
        gridViewModel.loadCollections()
        gridViewModel.refreshMetadataFacets()
        // Per-catalog grid layout — the four top-of-card stat slots.
        gridViewModel.loadGridSettings()
        // Pre-load both location-related data sources so the global-map
        // and location-picker dialogs can open with the camera framed on
        // real data, not the global-view fallback.
        gridViewModel.loadVideoLocations()
        gridViewModel.loadNamedLocations()
        // Open the long-lived CatalogEvents subscription so the grid
        // refreshes when the server's file-watcher picks up new footage.
        // Cheap if live updates are disabled — the server sends a single
        // WATCHER_DISABLED greeting and idles the stream.
        gridViewModel.startCatalogEventStream()
    }

    /** Tell the daemon to switch to [path], persist it as a recent, refresh. */
    fun openCatalog(path: String) {
        scope.launch {
            val info = repository.openCatalog(path)
            if (info != null && info.isOpen) {
                recents.touch(info.path)
                currentCatalog = info
                showOpenCatalogDialog = false
                loadAfterCatalogOpened()
            } else {
                errorMessage = "Could not open catalog at $path"
                // Re-open the dialog so the user can pick again.
                showOpenCatalogDialog = true
            }
        }
    }

    /** Close the current catalog and prompt the user to open another. */
    fun closeCatalog() {
        scope.launch {
            gridViewModel.stopCatalogEventStream()
            repository.closeCatalog()
            currentCatalog = CatalogInfo.Closed
            gridViewModel.clearState()
            openDialogIsStartup = false
            showOpenCatalogDialog = true
        }
    }

    // Wire the catalog + help shortcuts to the Window-level key listener. Done
    // here (not with the view-mode shortcuts above) because closeCatalog() is a
    // local fun declared just above and can't be forward-referenced earlier.
    LaunchedEffect(Unit) {
        onRegisterOpenCatalog {
            openDialogIsStartup = false
            showOpenCatalogDialog = true
        }
        onRegisterCloseCatalog { closeCatalog() }
        onRegisterShowHelp { showHelpDialog = true }
    }

    /**
     * Try to connect to the backend. The flow:
     *   1. If a daemon is already listening on the default port, reuse it.
     *   2. Otherwise spawn one with no catalog mounted yet.
     *   3. Then ask the daemon what catalog it has open. If none, prompt.
     *   4. If the daemon's existing catalog matches our recents-head, just go.
     */
    fun attemptConnect() {
        scope.launch {
            connectionState = ConnectionState.Connecting

            // Step 1: probe the default port.
            val defaultPort = 50051
            val reachable = withContext(Dispatchers.IO) {
                launcher.isReachable("127.0.0.1", defaultPort)
            }

            // Step 2: if not reachable, spawn our own daemon.
            val port = if (reachable) {
                defaultPort
            } else {
                val listening = withContext(Dispatchers.IO) {
                    launcher.launch(preferredPort = defaultPort, dbPath = null)
                }
                if (listening == null) {
                    errorMessage = "Couldn't start the ReelVault backend. " +
                        "Set REELVAULT_CORE_BIN or build core with `cargo build`."
                    connectionState = ConnectionState.Failed
                    return@launch
                }
                listening.port
            }

            val ok = repository.connect(overridePort = port)
            if (!ok) {
                errorMessage = "Connected to port $port but the daemon didn't respond"
                connectionState = ConnectionState.Failed
                return@launch
            }
            connectionState = ConnectionState.Connected

            // Step 3: discover what catalog (if any) the daemon already has open.
            val existing = repository.getCurrentCatalog()
            if (existing.isOpen) {
                currentCatalog = existing
                recents.touch(existing.path)
                loadAfterCatalogOpened()
            } else {
                // Step 4: nothing open — pick one. Use the most-recent if it
                // still exists for one-click "resume", otherwise prompt.
                val head = recents.list().firstOrNull { java.io.File(it).exists() }
                if (head != null) {
                    openCatalog(head)
                } else {
                    openDialogIsStartup = true
                    showOpenCatalogDialog = true
                }
            }
        }
    }

    LaunchedEffect(Unit) { attemptConnect() }

    // Check for updates at startup and every 24 h. A null result (network
    // failure, pre-release repo, etc.) is silently ignored — never block
    // the user with an error banner over a background check.
    LaunchedEffect(Unit) {
        while (true) {
            val release = UpdateChecker.checkLatestRelease(
                AppVersion.GITHUB_OWNER, AppVersion.GITHUB_REPO
            )
            if (release != null && UpdateChecker.isNewer(release.version, AppVersion.CURRENT)) {
                pendingUpdate = release
            }
            delay(24L * 60 * 60 * 1_000)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            gridViewModel.onDestroy()
            detailViewModel.onDestroy()
            repository.disconnect()
            // Shut down any daemon we spawned ourselves.
            launcher.shutdown()
        }
    }

    // ReelVault is dark-mode only — light mode is intentionally not offered.
    ReelVaultTheme(accentScheme = accentScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (connectionState == ConnectionState.Connected) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top bar
                    val selectedIds = gridViewModel.selectedVideoIds.collectAsState()
                    ReelVaultTopBar(
                        gridViewModel = gridViewModel,
                        onGroupSelected = { gridViewModel.groupSelectedVideos() },
                        onConfigureWatcher = { showWatchSettingsDialog = true },
                        onConfigurePlayback = { showPlaybackSettingsDialog = true },
                        onConfigureCameraNames = { showCameraNamesDialog = true },
                        onConfigureLensNames = { showLensNamesDialog = true },
                        onConfigureLibrary = { showLibrarySettingsDialog = true },
                        onConfigureAppearance = { showAppearanceDialog = true },
                        onOpenCatalog = {
                            openDialogIsStartup = false
                            showOpenCatalogDialog = true
                        },
                        onCloseCatalog = { closeCatalog() },
                        onOpenRecent = { path -> openCatalog(path) },
                        catalogIsOpen = currentCatalog.isOpen,
                        catalogName = currentCatalog.name,
                        recents = recents.list(),
                        selectedCount = selectedIds.value.size,
                        onShowHelp = { showHelpDialog = true },
                        accentScheme = accentScheme,
                        proxyBanner = detailViewModel.proxyBanner.collectAsState().value,
                    )

                    // Horizontal border separating the top bar from the content
                    // below, matching the separator the macOS client draws under
                    // its top bar and the divider above the bottom bar.
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Scan status banner (during scan)
                    val scanStatus = gridViewModel.scanStatus.collectAsState()
                    val watcherBanner = gridViewModel.watcherBanner.collectAsState()
                    val effectiveScanText = scanStatus.value ?: watcherBanner.value
                    if (effectiveScanText != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReelVaultSpacing.Small),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                                Text(
                                    text = effectiveScanText ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Background post-index activity panel — proxy detection /
                    // grouping / camera-sensor lookups. Driven by POST_INDEX_*
                    // catalog events, so it surfaces watcher-triggered passes
                    // (which have no user scan banner) and explains why the
                    // core is busy + how far along it is.
                    val postIndex = gridViewModel.postIndexProgress.collectAsState()
                    postIndex.value?.let { pi ->
                        val phaseLabel = when (pi.phase) {
                            "grouping" -> "Grouping clips"
                            "proxies" -> "Detecting proxies"
                            "sensors" -> "Fetching camera data"
                            "tagging" -> "Tagging timelapses"
                            else -> "Post-indexing"
                        }
                        val countText =
                            if (pi.total > 0) "${pi.processed} / ${pi.total}" else "${pi.processed}"
                        val etaText = when {
                            pi.etaSeconds <= 0L -> ""
                            pi.etaSeconds < 60L -> "~${pi.etaSeconds}s left"
                            pi.etaSeconds < 3600L -> "~${pi.etaSeconds / 60L} min left"
                            else -> "~${pi.etaSeconds / 3600L}h ${(pi.etaSeconds % 3600L) / 60L}m left"
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReelVaultSpacing.Small)
                            ) {
                                // Single line: phase label (left) · progress bar
                                // (middle, greedy) · time estimate (right).
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                                    // Crossfade keyed on the phase label only, so a
                                    // phase change (e.g. proxies → tagging) fades
                                    // gently while the per-second count updates in
                                    // place.
                                    Crossfade(targetState = phaseLabel) { label ->
                                        Text(
                                            text = "$label ($countText)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(ReelVaultSpacing.Medium))
                                    if (pi.total > 0) {
                                        LinearProgressIndicator(
                                            progress = { (pi.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        // Total unknown (rare) — indeterminate bar.
                                        LinearProgressIndicator(modifier = Modifier.weight(1f))
                                    }
                                    if (etaText.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(ReelVaultSpacing.Medium))
                                        Text(
                                            text = etaText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1
                                        )
                                    }
                                }
                                // Optional second row: the live per-item detail.
                                if (pi.detail.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = pi.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Scan result banner (after scan)
                    val scanResult = gridViewModel.scanResult.collectAsState()
                    scanResult.value?.let { result ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (result.success) {
                                if (result.videosFound == 0) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                }
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReelVaultSpacing.Small),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = when {
                                            !result.success -> Icons.Default.Error
                                            result.videosFound == 0 -> Icons.Default.Warning
                                            else -> Icons.Default.CheckCircle
                                        },
                                        contentDescription = "Status",
                                        modifier = Modifier.size(20.dp),
                                        tint = if (result.success) {
                                            if (result.videosFound == 0) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            }
                                        } else {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                                    Text(
                                        text = result.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (result.success) {
                                            if (result.videosFound == 0) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            }
                                        } else {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        }
                                    )
                                }
                                com.reelvault.ui.components.Tooltip(text = "Dismiss this notification") {
                                    IconButton(
                                        onClick = { gridViewModel.clearScanResult() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Dismiss",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Update-available banner — shown when a newer GitHub release is detected.
                    pendingUpdate?.let { release ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ReelVaultSpacing.Medium,
                                             vertical = ReelVaultSpacing.Small),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Upgrade,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                                    Column {
                                        Text(
                                            text = "ReelVault ${release.version} is available",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            text = "You are running ${AppVersion.CURRENT}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                                        )
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(ReelVaultSpacing.Small),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    com.reelvault.ui.components.Tooltip(text = "Open the GitHub releases page to download ${release.version}") {
                                        TextButton(
                                            onClick = {
                                                com.reelvault.util.openUrl(release.releaseUrl)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Download", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    com.reelvault.ui.components.Tooltip(text = "Dismiss this update notification") {
                                        IconButton(
                                            onClick = { pendingUpdate = null },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Dismiss",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Active location-filter banner — sits above the
                    // panels so users always notice why the grid is
                    // narrowed, and can clear the filter in one click.
                    val activeFilter = gridViewModel.filterLocation.collectAsState().value
                    val totalCountForBanner = gridViewModel.totalCount.collectAsState().value
                    val namedLocsForBanner = gridViewModel.namedLocations.collectAsState().value
                    if (activeFilter != null) {
                        val (fLat, fLon, fRadius) = activeFilter
                        val matched = remember(fLat, fLon, namedLocsForBanner) {
                            com.reelvault.ui.screens.nearestNamedLocation(
                                fLat, fLon, namedLocsForBanner)
                        }
                        val identity = matched?.name
                            ?: "%.4f, %.4f".format(fLat, fLon)
                        val radiusLabel = if (fRadius == kotlin.math.floor(fRadius))
                            "%.0f".format(fRadius) else "%.1f".format(fRadius)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ReelVaultSpacing.Medium,
                                             vertical = ReelVaultSpacing.Small),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (matched != null) Icons.Default.Place
                                                  else Icons.Default.LocationOn,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Filtered to videos near $identity",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(
                                        text = "$totalCountForBanner match" +
                                            (if (totalCountForBanner == 1L) "" else "es") +
                                            " within $radiusLabel km",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                            .copy(alpha = 0.75f),
                                    )
                                }
                                com.reelvault.ui.components.Tooltip(
                                    text = "Clear the location filter and return to the full library."
                                ) {
                                    TextButton(
                                        onClick = {
                                            gridViewModel.setLocationFilter(null, null)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Show all videos",
                                             style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }

                    // Main content — takes remaining vertical space above the bottom bar.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        val libraryLocations = gridViewModel.libraryLocations.collectAsState()
                        val selectedLocations = gridViewModel.selectedLocationPaths.collectAsState()

                        // Left panel — in detail mode the slot shows the
                        // brightness/colour graphs (a per-video view), otherwise
                        // the library navigation panel or its collapsed strip.
                        if (leftPanelExpanded && viewMode == ViewMode.DETAIL) {
                            com.reelvault.ui.components.DetailGraphsPanel(
                                viewModel = gridViewModel,
                                onCollapse = { setLeftPanelExpanded(false) },
                                modifier = Modifier
                                    .width(leftPanelWidth.dp)
                                    .fillMaxHeight(),
                            )
                        } else if (leftPanelExpanded) {
                            com.reelvault.ui.components.LibraryPanel(
                                locations = libraryLocations.value,
                                selectedPaths = selectedLocations.value,
                                totalVideosAcrossLibrary = libraryLocations.value
                                    .sumOf { it.videoCount },
                                onSelect = { path, additive, range ->
                                    when {
                                        range -> gridViewModel.selectLocationRange(path)
                                        additive -> gridViewModel.toggleLocationFilter(path)
                                        else -> gridViewModel.setLocationFilter(path)
                                    }
                                },
                                onAddLocation = { showAddLibraryDialog = true },
                                onRemoveLocation = { loc -> pendingRemoveLocation = loc },
                                onRescan = { loc -> gridViewModel.rescanLibrary(loc.path) },
                                rescanningPaths = gridViewModel.rescanningPaths.collectAsState().value,
                                onCollapse = { setLeftPanelExpanded(false) },
                                collections = gridViewModel.collections.collectAsState().value,
                                selectedCollectionId = gridViewModel.selectedCollectionId.collectAsState().value,
                                onSelectCollection = { id -> gridViewModel.setCollection(id) },
                                onCreateCollection = { name ->
                                    gridViewModel.createCollection(name, isSmart = false)
                                },
                                onCreateSmartCollection = {
                                    smartCollectionName = ""
                                    showSmartCollectionDialog = true
                                },
                                onDeleteCollection = { col -> pendingDeleteCollection = col },
                                modifier = Modifier
                                    .width(leftPanelWidth.dp)
                                    .fillMaxHeight()
                            )

                            // Delete-collection confirmation dialog
                            pendingDeleteCollection?.let { col ->
                                AlertDialog(
                                    onDismissRequest = { pendingDeleteCollection = null },
                                    icon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    title = { Text("Delete collection?") },
                                    text = {
                                        Text(
                                            "\"${col.name}\" will be permanently deleted. " +
                                            "The videos in it will not be affected.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                gridViewModel.deleteCollection(col.id)
                                                pendingDeleteCollection = null
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            )
                                        ) { Text("Delete") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { pendingDeleteCollection = null }) {
                                            Text("Cancel")
                                        }
                                    }
                                )
                            }

                            // Smart-collection name dialog
                            if (showSmartCollectionDialog) {
                                AlertDialog(
                                    onDismissRequest = { showSmartCollectionDialog = false },
                                    title = { Text("Save as Smart Collection") },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                "This will capture the current filter settings " +
                                                "(camera, codec, rating, etc.) as a smart collection.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            TextField(
                                                value = smartCollectionName,
                                                onValueChange = { smartCollectionName = it },
                                                placeholder = { Text("Collection name") },
                                                singleLine = true,
                                                modifier = Modifier.trackTextEntryFocus(),
                                                colors = TextFieldDefaults.colors(
                                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                                                )
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                val name = smartCollectionName.trim()
                                                if (name.isNotEmpty()) {
                                                    gridViewModel.createCollection(
                                                        name,
                                                        isSmart = true,
                                                        filterJson = gridViewModel.buildSmartCollectionFilterJson()
                                                    )
                                                }
                                                showSmartCollectionDialog = false
                                            }
                                        ) { Text("Save") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showSmartCollectionDialog = false }) {
                                            Text("Cancel")
                                        }
                                    }
                                )
                            }

                            // Remove-library confirmation dialog
                            pendingRemoveLocation?.let { loc ->
                                val videoWord = if (loc.videoCount == 1L) "video" else "videos"
                                AlertDialog(
                                    onDismissRequest = { pendingRemoveLocation = null },
                                    icon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    title = { Text("Remove library location?") },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("\"${loc.path}\"")
                                            Text(
                                                "${loc.videoCount} $videoWord from this folder will be removed " +
                                                "from your catalog. The video files on disk will not be deleted.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                gridViewModel.removeLibraryLocation(loc.path)
                                                pendingRemoveLocation = null
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            )
                                        ) { Text("Remove") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { pendingRemoveLocation = null }) {
                                            Text("Cancel")
                                        }
                                    }
                                )
                            }
                        } else {
                            com.reelvault.ui.components.CollapsedPanelStrip(
                                expandIconLeft = false,  // arrow points right (toward expand)
                                tooltip = "Show library panel (Tab)",
                                onClick = { setLeftPanelExpanded(true) },
                                modifier = Modifier.fillMaxHeight()
                            )
                        }

                        // Drag handle on the left panel's inner edge.
                        // Only rendered when the panel is expanded — when
                        // collapsed the strip itself absorbs all clicks.
                        if (leftPanelExpanded) {
                            PanelResizeHandle(isLeftPanel = true) { dragDeltaPx ->
                                // Read the panel's *current* width from the
                                // state map fresh on each drag event rather
                                // than the captured `leftPanelWidth` val.
                                // Without this fresh read, fast drags lose
                                // events to a stale snapshot: every event
                                // computes `(stale_width + this_event's_delta)`
                                // and only the latest event wins, so the
                                // cursor sails ahead of the panel.
                                val current = leftPanelWidths[viewMode]
                                    ?: PanelPrefs.DEFAULT_WIDTH
                                setLeftPanelWidth(current + dragDeltaPx)
                            }
                        }

                        Divider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                        )

                        // Middle area — grid, list, or single-video loupe.
                        // Shared handler: tapping a card's location badge
                        // loads all video locations (if not yet loaded)
                        // and opens the global map focused on that video.
                        val onCardLocationClick: (Double, Double) -> Unit = { lat, lon ->
                            scope.launch {
                                gridViewModel.loadVideoLocationsFilteredAsync()
                                // Pre-select the videos sharing this exact
                                // coordinate so the map's right panel is
                                // populated the instant the view switches.
                                mapSelectedVideoIds = gridViewModel.videoLocations.value
                                    .filter {
                                        kotlin.math.abs(it.latitude - lat) < 1e-9 &&
                                            kotlin.math.abs(it.longitude - lon) < 1e-9
                                    }
                                    .map { it.id }
                                globalMapFocusLocation = lat to lon
                                viewMode = ViewMode.MAP
                            }
                        }

                        // Centre content column: the Library Filter bar pinned
                        // above the grid/list. Because this Column sits between
                        // the two panel dividers, the bar automatically stops at
                        // the side panels and tracks their resize/collapse.
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            // Slide the filter bar in/out as the loupe is
                            // entered or left, rather than snapping.
                            androidx.compose.animation.AnimatedVisibility(
                                visible = viewMode != ViewMode.DETAIL
                            ) {
                                com.reelvault.ui.components.LibraryFilterBar(
                                    viewModel = gridViewModel,
                                    onSearchFocusChanged = onSearchFocusChanged,
                                    // Map mode plots only located videos, so the
                                    // location presence filter is hidden (and
                                    // forced on) — every other filter still applies.
                                    hideLocationOption = viewMode == ViewMode.MAP,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            when (viewMode) {
                                ViewMode.GRID -> GridScreen(
                                    viewModel = gridViewModel,
                                    onVideoSelect = { video ->
                                        detailViewModel.setCurrentVideo(video)
                                        detailViewModel.loadMetadata(video.id)
                                    },
                                    thumbnailMinWidth = thumbnailWidth,
                                    onLocationClick = onCardLocationClick,
                                    onEditLocation = openLocationPicker,
                                    onClearLocation = { gridViewModel.clearVideoLocations(it) },
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                                ViewMode.LIST -> ListScreen(
                                    viewModel = gridViewModel,
                                    onVideoSelect = { video ->
                                        detailViewModel.setCurrentVideo(video)
                                        detailViewModel.loadMetadata(video.id)
                                    },
                                    // Same slider value the grid uses so a
                                    // list-mode card matches its grid-mode
                                    // counterpart in size.
                                    thumbnailHeight = thumbnailWidth,
                                    onLocationClick = onCardLocationClick,
                                    onEditLocation = openLocationPicker,
                                    onClearLocation = { gridViewModel.clearVideoLocations(it) },
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                                ViewMode.DETAIL -> DetailViewScreen(
                                    gridViewModel = gridViewModel,
                                    detailViewModel = detailViewModel,
                                    infoOverlay = infoOverlay,
                                    playToggle = detailPlayToggle,
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                                ViewMode.MAP -> {
                                    val mapLocations = gridViewModel.videoLocations.collectAsState()
                                    // Subscribe to named locations so pin labels
                                    // appear as soon as they load.
                                    val namedLocs = gridViewModel.namedLocations.collectAsState().value
                                    com.reelvault.ui.screens.MapScreen(
                                        locations = mapLocations.value,
                                        selectedVideoIds = mapSelectedVideoIds,
                                        onSelectionChange = { mapSelectedVideoIds = it },
                                        pinColor = MaterialTheme.colorScheme.primary,
                                        placeNameFor = { lat, lon ->
                                            if (namedLocs.isEmpty()) null
                                            else gridViewModel.nameForLocation(lat, lon)?.name
                                        },
                                        focusedLocation = globalMapFocusLocation,
                                        onRenameLocationRequest = { renameLocationPin = it },
                                        modifier = Modifier.weight(1f).fillMaxWidth()
                                    )
                                }
                            }
                        }

                        Divider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                        )

                        // Drag handle on the right panel's inner edge.
                        // For the right panel, a rightward drag should
                        // shrink the panel — `isLeftPanel = false`
                        // negates the delta sign internally.
                        if (rightPanelExpanded) {
                            PanelResizeHandle(isLeftPanel = false) { dragDeltaPx ->
                                // See the left-panel handler for the
                                // explanation — read the current width
                                // from the state map per-event so the
                                // panel keeps pace with a fast drag.
                                val current = rightPanelWidths[viewMode]
                                    ?: PanelPrefs.DEFAULT_WIDTH
                                setRightPanelWidth(current - dragDeltaPx)
                            }
                        }

                        // Right panel — in map mode, the videos at the selected
                        // location(s) as cards; otherwise the metadata inspector.
                        // Collapsed strip when hidden.
                        val openMapVideoInView = { video: com.reelvault.data.models.VideoSummary, mode: ViewMode ->
                            gridViewModel.selectVideo(video)
                            detailViewModel.setCurrentVideo(video)
                            detailViewModel.loadMetadata(video.id)
                            viewMode = mode
                        }
                        if (rightPanelExpanded && viewMode == ViewMode.MAP) {
                            val sel = mapSelectedVideoIds.toSet()
                            val mapPanelVideos = gridViewModel.geotaggedVideos.collectAsState().value
                                .filter { it.id in sel }
                            val mapLocLoading = gridViewModel.isLoadingVideoLocations
                                .collectAsState().value
                            // When the selected spot is a named place, show its
                            // name in the panel header instead of the generic
                            // "here" (all co-located videos share the spot, so
                            // the first one's coordinate resolves the name).
                            val mapLocationName = mapPanelVideos.firstOrNull()?.let {
                                gridViewModel.nameForLocation(it.gpsLatitude, it.gpsLongitude)?.name
                            }
                            // Resolver for the panel cards' "Location" slot. Keyed
                            // on namedLocations so cards relabel when a place is
                            // named/renamed.
                            val mapPanelNamedLocations = gridViewModel.namedLocations.collectAsState().value
                            val mapPanelPlaceNameFor: (Double, Double) -> String? =
                                remember(mapPanelNamedLocations) {
                                    { lat, lon ->
                                        if (mapPanelNamedLocations.isEmpty()) null
                                        else gridViewModel.nameForLocation(lat, lon)?.name
                                    }
                                }
                            com.reelvault.ui.screens.MapVideoListPanel(
                                videos = mapPanelVideos,
                                // Spinner while a clicked location's videos are
                                // still being resolved (and none are showing yet).
                                loading = sel.isNotEmpty() && mapPanelVideos.isEmpty() && mapLocLoading,
                                locationName = mapLocationName,
                                cardMinWidth = thumbnailWidth,
                                thumbnails = gridViewModel.thumbnails.collectAsState().value,
                                scrubFrames = gridViewModel.scrubFrames.collectAsState().value,
                                currentVideoId = gridViewModel.selectedVideoId.collectAsState().value,
                                topSlots = gridViewModel.topSlots.collectAsState().value,
                                onPickStatSlot = { i, k -> gridViewModel.updateGridTopSlot(i, k) },
                                placeNameFor = mapPanelPlaceNameFor,
                                onLoadThumbnail = { gridViewModel.loadThumbnail(it) },
                                onHoverEnter = { gridViewModel.loadScrubFrames(it) },
                                onCardClick = { openMapVideoInView(it, ViewMode.MAP) },
                                onOpenInGrid = { openMapVideoInView(it, ViewMode.GRID) },
                                onOpenInList = { openMapVideoInView(it, ViewMode.LIST) },
                                onOpenInDetail = { openMapVideoInView(it, ViewMode.DETAIL) },
                                onEditLocation = openLocationPicker,
                                onClearLocation = { gridViewModel.clearVideoLocations(it) },
                                onOpenAllInGrid = {
                                    gridViewModel.filterToVideosLocation(mapSelectedVideoIds)
                                    viewMode = ViewMode.GRID
                                },
                                onOpenAllInList = {
                                    gridViewModel.filterToVideosLocation(mapSelectedVideoIds)
                                    viewMode = ViewMode.LIST
                                },
                                onCollapse = { setRightPanelExpanded(false) },
                                modifier = Modifier
                                    .width(rightPanelWidth.dp)
                                    .fillMaxHeight()
                            )
                        } else if (rightPanelExpanded) {
                            DetailScreen(
                                viewModel = detailViewModel,
                                gridViewModel = gridViewModel,
                                viewMode = viewMode,
                                onCollapse = { setRightPanelExpanded(false) },
                                onEditLocation = openLocationPicker,
                                onEditCaptureDate = { videoIds, initialTs ->
                                    videoIdsForDatePicker = videoIds
                                    initialTimestampForPicker = initialTs
                                },
                                modifier = Modifier
                                    .width(rightPanelWidth.dp)
                                    .fillMaxHeight()
                            )
                        } else {
                            com.reelvault.ui.components.CollapsedPanelStrip(
                                expandIconLeft = true,  // arrow points left (toward expand)
                                tooltip = "Show details panel (Tab)",
                                onClick = { setRightPanelExpanded(true) },
                                modifier = Modifier.fillMaxHeight()
                            )
                        }
                    }

                    // Bottom bar — view-mode toggle (left), thumbnail-size slider
                    // (right). The sort controls now live in the Library Filter bar.
                    BottomBar(
                        viewMode = viewMode,
                        onViewModeChange = { newMode ->
                            // Switching to the map via the toggle frames all
                            // pins; only a card's location badge sets a focus.
                            if (newMode == ViewMode.MAP) globalMapFocusLocation = null
                            viewMode = newMode
                        },
                        thumbnailWidth = thumbnailWidth,
                        onThumbnailWidthChange = {
                            thumbnailWidth = it
                            uiPrefs.putFloat("thumbnailWidth", it.value)
                        },
                    )
                }
                // Help dialog
                if (showHelpDialog) {
                    HelpDialog(onDismiss = { showHelpDialog = false })
                }

                // Appearance (accent color scheme) dialog
                if (showAppearanceDialog) {
                    com.reelvault.ui.screens.AppearanceSettingsDialog(
                        currentScheme = accentScheme,
                        onSchemeChange = { scheme ->
                            accentScheme = scheme
                            uiPrefs.put("accentScheme", scheme.name)
                        },
                        onDismiss = { showAppearanceDialog = false }
                    )
                }

                // Live-updates / watcher preferences dialog
                if (showWatchSettingsDialog) {
                    com.reelvault.ui.screens.WatchSettingsDialog(
                        repository = repository,
                        onDismiss = { showWatchSettingsDialog = false }
                    )
                }

                // Playback + proxy resolution preferences dialog
                if (showPlaybackSettingsDialog) {
                    com.reelvault.ui.screens.PlaybackSettingsDialog(
                        repository = repository,
                        onDismiss = { showPlaybackSettingsDialog = false }
                    )
                }

                // Camera-names editor dialog (internal → marketing
                // mapping with user overrides).
                if (showCameraNamesDialog) {
                    com.reelvault.ui.screens.CameraNamesDialog(
                        repository = repository,
                        onDismiss = { showCameraNamesDialog = false }
                    )
                }

                if (showLensNamesDialog) {
                    com.reelvault.ui.screens.LensNamesDialog(
                        repository = repository,
                        onDismiss = { showLensNamesDialog = false }
                    )
                }

                // Library-wide auto-tagging / detection settings
                // (timelapse auto-tag, etc.).
                if (showLibrarySettingsDialog) {
                    com.reelvault.ui.screens.LibrarySettingsDialog(
                        repository = repository,
                        onDismiss = { showLibrarySettingsDialog = false }
                    )
                }

                // Proxy resolution picker. Observes the grid view-model:
                // a non-null `proxyCreationVideoId` means the user just
                // clicked Create proxy on a card. Look up the matching
                // VideoSummary so the dialog can show filename + greys
                // out presets larger than the source.
                val proxyTargetId = gridViewModel.proxyCreationVideoId.collectAsState().value
                val visibleVideos = gridViewModel.videos.collectAsState().value
                if (proxyTargetId != null) {
                    val source = visibleVideos.firstOrNull { it.id == proxyTargetId }
                    if (source != null) {
                        com.reelvault.ui.screens.ProxyResolutionDialog(
                            sourceVideo = source,
                            onConfirm = { h ->
                                gridViewModel.startProxyCreation(proxyTargetId, h)
                            },
                            onCancel = { gridViewModel.cancelProxyCreation() },
                        )
                    } else {
                        // The video disappeared from the visible page
                        // (e.g. user scrolled past it) — just dismiss
                        // the request instead of showing an empty dialog.
                        LaunchedEffect(proxyTargetId) { gridViewModel.cancelProxyCreation() }
                    }
                }

                // The global map is now a top-level view (ViewMode.MAP), not a
                // dialog — see the `when (viewMode)` block above.

                // Location-picker dialog — set/replace GPS on one or more videos.
                videoIdsForLocationPicker?.let { ids ->
                    val knownLocations = gridViewModel.videoLocations.collectAsState()
                    val namedPlaces = gridViewModel.namedLocations.collectAsState()
                    com.reelvault.ui.screens.LocationPickerDialog(
                        targetVideoIds = ids,
                        initialLocation = initialLocationForPicker,
                        existingLocations = knownLocations.value,
                        namedLocations = namedPlaces.value,
                        accentScheme = accentScheme,
                        onDismiss = {
                            videoIdsForLocationPicker = null
                            initialLocationForPicker = null
                        },
                        onApply = { lat, lon, writeToFile, name ->
                            // If the user typed (or kept) a name, upsert it
                            // first so subsequent UI refreshes can resolve
                            // the new GPS into a name immediately. Re-uses
                            // an existing named-location's id when the
                            // candidate is already within range of one.
                            if (name != null) {
                                val existing = com.reelvault.ui.screens.nearestNamedLocation(
                                    lat, lon, namedPlaces.value
                                )
                                gridViewModel.saveNamedLocation(
                                    id = existing?.id ?: "",
                                    name = name,
                                    latitude = lat,
                                    longitude = lon,
                                )
                            }
                            gridViewModel.setVideoLocations(ids, lat, lon, writeToFile) {
                                // Refresh the detail panel's metadata so the
                                // new GPS shows up immediately.
                                ids.firstOrNull()?.let { detailViewModel.loadMetadata(it) }
                            }
                            videoIdsForLocationPicker = null
                            initialLocationForPicker = null
                        }
                    )
                }

                // Map right-click "Name / Rename location" dialog.
                renameLocationPin?.let { pin ->
                    val named = gridViewModel.namedLocations.collectAsState().value
                    val existing = com.reelvault.ui.screens.nearestNamedLocation(
                        pin.latitude, pin.longitude, named)
                    com.reelvault.ui.screens.LocationNameDialog(
                        initialName = existing?.name ?: "",
                        accentScheme = accentScheme,
                        onDismiss = { renameLocationPin = null },
                        onSave = { newName ->
                            gridViewModel.saveNamedLocation(
                                id = existing?.id ?: "",
                                name = newName,
                                // Keep an existing place's centre; otherwise pin
                                // the new name to the exact spot clicked.
                                latitude = existing?.latitude ?: pin.latitude,
                                longitude = existing?.longitude ?: pin.longitude,
                                radiusMeters = existing?.radiusMeters ?: 250.0,
                            )
                            renameLocationPin = null
                        },
                    )
                }

                // Capture-date picker — set/replace creation time.
                videoIdsForDatePicker?.let { ids ->
                    // Filename of the single target (for filename-based date
                    // inference); null for multi-select so the infer controls hide.
                    val primaryDateFilename = if (ids.size == 1) {
                        val id = ids.first()
                        (gridViewModel.videos.value.firstOrNull { it.id == id }
                            ?: gridViewModel.selectedVideo.value?.takeIf { it.id == id })
                            ?.filename
                    } else null
                    com.reelvault.ui.screens.CaptureDateDialog(
                        targetVideoIds = ids,
                        initialTimestampMs = initialTimestampForPicker,
                        primaryFilename = primaryDateFilename,
                        onDismiss = {
                            videoIdsForDatePicker = null
                            initialTimestampForPicker = null
                        },
                        onApply = { ts, writeToFile ->
                            gridViewModel.setVideoCaptureDates(ids, ts, writeToFile) {
                                ids.firstOrNull()?.let { detailViewModel.loadMetadata(it) }
                            }
                            videoIdsForDatePicker = null
                            initialTimestampForPicker = null
                        }
                    )
                }

                // Add-library dialog — shared by the top-bar "add folder"
                // button and the LIBRARY sidebar's '+' button.
                if (showAddLibraryDialog) {
                    AddLibraryDialog(
                        onDismiss = { showAddLibraryDialog = false },
                        onConfirm = { paths, recursive, autoGroup, dateFormat, datePosition ->
                            gridViewModel.addLibraryAndScanMultiple(
                                paths = paths,
                                recursive = recursive,
                                autoGroup = autoGroup,
                                filenameDateFormat = dateFormat,
                                filenameDatePosition = datePosition
                            )
                            showAddLibraryDialog = false
                        }
                    )
                }

                // Open Catalog dialog — shown automatically when the daemon
                // has no catalog mounted, or when the user picks File → Open.
                if (showOpenCatalogDialog) {
                    OpenCatalogDialog(
                        onDismiss = { showOpenCatalogDialog = false },
                        onPick = { openCatalog(it) },
                        isStartup = openDialogIsStartup,
                        recents = recents,
                    )
                }
            } else if (connectionState == ConnectionState.Connecting) {
                // Friendly loading screen while we attempt to reach the backend.
                ConnectingScreen()
            } else {
                // Connection failed — error screen with retry.
                ConnectionErrorScreen(
                    errorMessage = errorMessage,
                    onRetry = { attemptConnect() }
                )
            }
        }
    }
}

/** Three-state connection lifecycle for the startup flow. */
private enum class ConnectionState { Connecting, Connected, Failed }

@Composable
fun ConnectingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(ReelVaultSpacing.Large))
            Text(
                text = "Connecting to ReelVault…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
            Text(
                text = "Reaching out to the backend on localhost:50051",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Help Dialog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full in-app help reference. Launched from the `?` button in the top bar.
 * Mirrors the macOS [HelpView] content: collapsible sections with icons,
 * markdown-rendered paragraphs, bullet lists, two-column tables, and numbered
 * steps.
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(680.dp),
        title = {
            Column {
                Text("ReelVault Help", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Your video catalog, explained",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                    HelpSection(icon = Icons.Default.VideoLibrary, title = "What is ReelVault?") {
                        HelpPara(
                            "ReelVault is a video catalog manager — think Adobe Lightroom, but " +
                            "built exclusively for video files. It organises large collections of footage " +
                            "so you can find, inspect, tag, and hand off clips to professional editors, " +
                            "without ReelVault ever modifying your original files."
                        )
                        HelpPara(
                            "ReelVault stores all metadata, tags, and settings in a small catalog file " +
                            "(.vrcat). Your video files stay exactly where they are on disk."
                        )
                    }

                    HelpSection(icon = Icons.Default.CheckCircle, title = "What ReelVault can do") {
                        HelpBullets(listOf(
                            "Browse hundreds of thousands of clips at 60 fps in a thumbnail grid",
                            "Extract and display codec, resolution, FPS, bitrate, duration, GPS, camera model, and more",
                            "Search instantly across filename, notes, and tags",
                            "Apply custom tags to any number of clips at once",
                            "Group related variants into stacks (e.g. 4K + proxy of the same shot)",
                            "Filter by camera, lens, codec, year, GPS radius, or library folder",
                            "Detect or generate lower-resolution proxies for oversize footage",
                            "Play clips inline using VLC (Linux/Windows) or native decoders (macOS)",
                            "Drag clips straight from the grid into DaVinci Resolve, Final Cut Pro, Premiere, and any app that accepts file drops",
                            "See geotagged clips on a world map; filter to a radius with one click",
                            "Watch library folders for new footage and update the catalog automatically",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Cancel, title = "What ReelVault cannot do") {
                        HelpBullets(listOf(
                            "Edit, trim, or color-grade video (open in DaVinci Resolve, Premiere, etc. instead)",
                            "Transcode or encode (use Handbrake, FFmpeg, or your NLE's export panel)",
                            "Sync libraries or manage cloud storage",
                            "Play video without a compatible codec — VLC is recommended on non-Apple platforms",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Flag, title = "Getting started") {
                        HelpStep(number = "1", heading = "Create or open a catalog") {
                            Text(
                                "A catalog is a small database file that stores all your metadata, tags, and settings. " +
                                "Use File → Open Catalog… (Ctrl+O) to create a new one or open an existing one. " +
                                "Your video files are never moved or modified.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        HelpStep(number = "2", heading = "Add a library location") {
                            Text(
                                "Click the folder+ icon in the top bar. You can add multiple folders in one session. " +
                                "Use \$YEAR in a path — for example /Volumes/Footage/\$YEAR/Raw — to import every " +
                                "matching year folder at once. ReelVault scans in the background and the grid fills " +
                                "as files are indexed.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        HelpStep(number = "3", heading = "Browse and inspect") {
                            Text(
                                "Click any thumbnail to select it and load its full metadata in the right panel. " +
                                "The panel shows codec, resolution, FPS, bitrate, GPS, camera model, notes, tags, and more.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    HelpSection(icon = Icons.Default.ViewModule, title = "Views") {
                        HelpTable(listOf(
                            "G — Grid" to "Adaptive thumbnail grid. Drag the slider in the bottom bar to resize cards.",
                            "L — List" to "Horizontal rows: thumbnail left, metadata columns right.",
                            "D — Detail" to "Full-window video player and inspector. Step through your library with ← / →.",
                            "M — Map" to "Geotagged clips on a world map. Click a pin to filter to that location.",
                        ))
                        HelpPara("Switch views with the segment control in the bottom bar, or press G, L, D, or M.")
                    }

                    HelpSection(icon = Icons.Default.TouchApp, title = "Selecting clips") {
                        HelpTable(listOf(
                            "Click" to "Select one clip",
                            "Shift-click" to "Extend the selection to include everything between the anchor and the clicked card",
                            "Ctrl-click" to "Add or remove individual clips from the selection",
                            "Ctrl+A" to "Select all currently-visible clips",
                            "Ctrl+D" to "Clear the selection",
                            "← ↑ → ↓" to "Navigate the grid one card at a time",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Layers, title = "Stacks") {
                        HelpPara(
                            "Stacks let a single card represent a group of related clips — useful for 4K originals " +
                            "paired with 1080p proxies, or multiple takes from the same setup."
                        )
                        HelpBullets(listOf(
                            "Select 2+ clips and press Ctrl+G (or click the layers icon in the top bar) to create a stack",
                            "Click the N× badge on a stack card to expand or collapse it inline",
                            "Right-click → Remove from stack: pulls just that clip out; the rest stay grouped",
                            "Right-click → Unstack: disbands the entire group so every clip stands alone",
                            "ReelVault auto-stacks matching variants during import (can be disabled per scan)",
                        ))
                    }

                    HelpSection(icon = Icons.Default.VideoSettings, title = "Proxies") {
                        HelpPara(
                            "A proxy is a lower-resolution stand-in stored alongside the original and linked " +
                            "automatically. Use them when source footage is too large to play inline."
                        )
                        HelpBullets(listOf(
                            "Videos above the inline-playback ceiling (configurable via the playback settings button) show a warning badge",
                            "Right-click → Create proxy… to generate one; choose a target height (720p, 1080p, …)",
                            "Proxies are auto-detected when they appear in the same folder after a rescan",
                            "The P×N badge on a card means N proxies are linked to that clip",
                            "In Detail view you can manually select which proxy to play",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Search, title = "Search & filters") {
                        HelpBullets(listOf(
                            "Search bar: live search across filename, notes, and tags",
                            "Filter dropdowns (Camera · Lens · Keyword · Codec · Year): stack multiple filters; click Clear to reset all",
                            "Map view (globe icon in top bar): click a pin to filter to that GPS radius",
                            "Library panel (left): click a folder to limit the grid to that location",
                            "Right-click → Go to Folder in Library: jumps the left panel to the containing folder",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Label, title = "Tags") {
                        HelpPara(
                            "Tags are catalog-only labels — they are not written into the video file. " +
                            "Add or remove tags from the right panel while one or more clips are selected, " +
                            "or filter the grid using the Keyword dropdown."
                        )
                    }

                    HelpSection(icon = Icons.Default.OpenInNew, title = "Hand off to an editor") {
                        HelpBullets(listOf(
                            "Drag one or more cards from the grid or list directly into DaVinci Resolve, Final Cut Pro, Premiere Pro, or any app that accepts file drops",
                            "Double-click a row in List view to open it with the system's default media player",
                        ))
                    }

                    HelpSection(icon = Icons.Default.FolderOpen, title = "Library management") {
                        HelpBullets(listOf(
                            "Add as many source folders as you like — each appears as a row in the left panel",
                            "Use \$YEAR in a path (e.g. /archive/\$YEAR/) to add an entire decade of year folders in one click",
                            "Hover over a library row in the left panel to reveal the ↺ rescan button — useful after moving files",
                            "Right-click or swipe left on a library row to remove it — your video files are not deleted",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Visibility, title = "Live updates") {
                        HelpPara(
                            "ReelVault can watch your library folders for new footage using the operating system's " +
                            "file-change notifications. Toggle via the Live pill in the top bar or from the settings panel."
                        )
                        HelpPara(
                            "When on, newly-added files appear in the grid within a few seconds of landing on disk. " +
                            "A short settle delay prevents half-written files from being indexed."
                        )
                    }

                    HelpSection(icon = Icons.Default.Keyboard, title = "Keyboard shortcuts") {
                        HelpTable(listOf(
                            "G" to "Grid view",
                            "L" to "List view",
                            "D" to "Detail / Catalog view",
                            "M" to "Map view",
                            "I" to "Cycle info overlay (Detail mode: none → camera → file → …)",
                            "Tab" to "Toggle both side panels",
                            "Space" to "Play / pause selected clip",
                            "0–5" to "Rate the selected clips (0 clears)",
                            "6–9" to "Color-label the selection (red / yellow / green / blue)",
                            "`" to "Clear the color label",
                            "Ctrl+G" to "Stack selected clips into a group",
                            "Ctrl+A" to "Select all currently-visible clips",
                            "Ctrl+D" to "Deselect all",
                            "Ctrl+O" to "Open Catalog…",
                            "Ctrl+Shift+W" to "Close Catalog",
                            "F1" to "Show this help",
                            "← ↑ → ↓" to "Navigate the grid",
                            "Escape" to "Clear search field focus",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Lightbulb, title = "Tips & tricks") {
                        HelpBullets(listOf(
                            "The thumbnail slider in the bottom bar rescales in real time — find the density that suits your display",
                            "Rescan a folder from the left panel without re-adding it — hover the row and click ↺",
                            "Press Tab to hide both panels and give the grid maximum screen space (Lightroom-style)",
                            "Hold Shift to select a range in the grid, then drag the whole selection into your editor",
                            "Using \$YEAR when adding a library (e.g. /footage/\$YEAR/) imports decade-scale archives in one click",
                            "Auto-stacking during import groups 4K + 1080p variants automatically — look for the N× badge",
                        ))
                    }

                    Spacer(modifier = Modifier.height(16.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// ── Help dialog primitives ────────────────────────────────────────────────────

@Composable
private fun HelpSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Clickable section header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

@Composable
private fun HelpPara(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun HelpBullets(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 1.dp)
                )
                Text(
                    item,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun HelpTable(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { (key, value) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(140.dp)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HelpStep(
    number: String,
    heading: String,
    body: @Composable () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(heading, style = MaterialTheme.typography.labelMedium)
            body()
        }
    }
}

@Composable
fun ReelVaultTopBar(
    gridViewModel: com.reelvault.viewmodel.GridViewModel,
    onGroupSelected: () -> Unit = {},
    /** Opens the watcher (live-updates) preferences dialog. */
    onConfigureWatcher: () -> Unit = {},
    /** Opens the playback & proxy-resolution preferences dialog. */
    onConfigurePlayback: () -> Unit = {},
    /** Opens the camera-names editor dialog. */
    onConfigureCameraNames: () -> Unit = {},
    /** Opens the lens-names editor dialog. */
    onConfigureLensNames: () -> Unit = {},
    /** Opens the library-wide auto-tagging/detection settings dialog. */
    onConfigureLibrary: () -> Unit = {},
    /** Opens the appearance (accent color scheme) dialog. */
    onConfigureAppearance: () -> Unit = {},
    onOpenCatalog: () -> Unit = {},
    onCloseCatalog: () -> Unit = {},
    onOpenRecent: (String) -> Unit = {},
    catalogIsOpen: Boolean = false,
    catalogName: String = "",
    recents: List<String> = emptyList(),
    selectedCount: Int = 0,
    /** Opens the full in-app help reference. */
    onShowHelp: () -> Unit = {},
    /** Drives which colour variant of the title-bar icon is shown. */
    accentScheme: AccentScheme = AccentScheme.Purple,
    /** When non-null, the detail player is showing a proxy — render the
     *  proxy-playback indicator. null hides it. */
    proxyBanner: com.reelvault.viewmodel.DetailViewModel.ProxyBanner? = null,
) {
    var showFileMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Only pad the trailing edge — the leading edge already
                    // gets Material3's title-slot inset, and adding 16dp on
                    // top of that pushes the brand mark visibly farther
                    // from the window edge than the SwiftUI client.
                    .padding(end = ReelVaultSpacing.Medium),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // The accent-tinted variant tracks the user's chosen
                    // colour scheme. The macOS Dock tile can't be swapped
                    // at runtime, but the in-app brand mark can.
                    Image(
                        painter = painterResource(
                            when (accentScheme) {
                                AccentScheme.Blue -> "icons/AppIcon-titlebar-blue.png"
                                AccentScheme.Purple -> "icons/AppIcon-titlebar-purple.png"
                            }
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        text = "ReelVault",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }

                Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))

                // File menu — Open / Close / Open Recent.
                Box {
                    com.reelvault.ui.components.Tooltip(
                        text = "Open a different catalog, close the current one, or pick from " +
                            "recent catalogs."
                    ) {
                        OutlinedButton(
                            onClick = { showFileMenu = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LibraryBooks,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (catalogIsOpen) catalogName else "No catalog",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    DropdownMenu(expanded = showFileMenu, onDismissRequest = { showFileMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open Catalog…") },
                            onClick = { onOpenCatalog(); showFileMenu = false },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Close Catalog") },
                            onClick = { onCloseCatalog(); showFileMenu = false },
                            enabled = catalogIsOpen,
                            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) }
                        )
                        if (recents.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                text = "Recent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = ReelVaultSpacing.Medium,
                                    vertical = ReelVaultSpacing.XSmall
                                )
                            )
                            recents.take(8).forEach { recent ->
                                val name = java.io.File(recent)
                                    .nameWithoutExtension
                                    .ifEmpty { java.io.File(recent).name }
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(name, style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                recent,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    },
                                    onClick = { onOpenRecent(recent); showFileMenu = false }
                                )
                            }
                        }
                    }
                }

                // Search field + filter dropdowns now live in the Library
                // Filter bar (LibraryFilterBar), below the top bar and inside
                // the centre content column.

                Spacer(modifier = Modifier.weight(1f))

                // Trailing controls, left → right: Live · Group · Map · Help ·
                // Settings. Kept in the same order as the SwiftUI client.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Proxy-playback indicator — relocated here from an overlay
                    // on the video itself so it never covers the frame. Visible
                    // only while the detail player is showing a proxy.
                    proxyBanner?.let { banner ->
                        com.reelvault.ui.components.Tooltip(
                            text = (banner.detail?.let { "Showing proxy: $it. " } ?: "") +
                                "The detail player is showing a proxy, not the master " +
                                "file. Pick a different proxy or revert to the master " +
                                "in the details panel."
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                color = Color(0xFF408888).copy(alpha = 0.85f),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = if (banner.selected) "Playing selected proxy"
                                            else "Playing proxy",
                                        fontSize = 11.sp,
                                        lineHeight = 11.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // Live-updates pill (leftmost). Green dot = watcher active;
                    // grey dot = paused. Clicking opens the watch-settings dialog.
                    val liveOn = gridViewModel.liveUpdatesEnabled.collectAsState().value
                    com.reelvault.ui.components.Tooltip(
                        text = if (liveOn)
                            "Live updates are on — ReelVault is watching your libraries " +
                                "for new and changed files and will add them automatically. " +
                                "Click to adjust."
                        else
                            "Live updates are off. Click to turn them on or adjust the " +
                                "watcher settings."
                    ) {
                        Surface(
                            onClick = onConfigureWatcher,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                androidx.compose.material3.MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                androidx.compose.foundation.Canvas(
                                    modifier = Modifier.size(8.dp)
                                ) {
                                    drawCircle(
                                        color = if (liveOn)
                                            androidx.compose.ui.graphics.Color(0xFF34C759)
                                        else
                                            androidx.compose.ui.graphics.Color.Gray
                                    )
                                }
                                Text(
                                    "Live",
                                    fontSize = 11.sp,
                                    lineHeight = 11.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Group Selected button: enabled when 2+ videos are multi-selected.
                    // Shows a small count badge to make the selection visible.
                    Box {
                        val groupTooltip = if (selectedCount >= 2) {
                            "Stack the $selectedCount selected videos into a group (Cmd/Ctrl+G). " +
                                "One representative will be shown in the grid; click the stack badge to expand."
                        } else {
                            "Shift-click or Cmd/Ctrl-click two or more videos in the grid to enable grouping."
                        }
                        com.reelvault.ui.components.Tooltip(text = groupTooltip) {
                            IconButton(
                                onClick = onGroupSelected,
                                enabled = selectedCount >= 2
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = if (selectedCount >= 2) {
                                        "Group $selectedCount selected videos"
                                    } else {
                                        "Shift+click to select videos to group"
                                    },
                                    tint = if (selectedCount >= 2) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    }
                                )
                            }
                        }
                        if (selectedCount > 0) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 4.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = selectedCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    // (The world map is now a top-level view — reachable from
                    // the view-mode toggle in the bottom bar, or the 'M'
                    // shortcut — so it no longer has a top-bar button.)

                    // Help button (kept as its own affordance — on macOS this
                    // lives in the system Help menu instead).
                    com.reelvault.ui.components.Tooltip(
                        text = "Open ReelVault Help — learn what ReelVault can do, " +
                            "keyboard shortcuts, and tips for new users."
                    ) {
                        IconButton(onClick = onShowHelp) {
                            Icon(
                                imageVector = Icons.Default.HelpOutline,
                                contentDescription = "Help"
                            )
                        }
                    }

                    // Settings gear (rightmost) — every preference collapsed
                    // into one menu so the top bar isn't a row of mystery
                    // glyphs. Playback, Library, and Appearance sit at the top
                    // level; the name-mapping editors live in a Names submenu.
                    // (Adding folders is done from the Library panel.)
                    Box {
                        var settingsOpen by remember { mutableStateOf(false) }
                        var namesOpen by remember { mutableStateOf(false) }
                        com.reelvault.ui.components.Tooltip(
                            text = "Settings — playback & proxies, library auto-tagging, " +
                                "appearance, and camera/lens name mappings."
                        ) {
                            IconButton(onClick = { settingsOpen = true }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings"
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = settingsOpen,
                            onDismissRequest = { settingsOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Playback & Proxies…") },
                                leadingIcon = { Icon(Icons.Default.PlayCircleOutline, contentDescription = null) },
                                onClick = { settingsOpen = false; onConfigurePlayback() }
                            )
                            DropdownMenuItem(
                                text = { Text("Library — Auto-Tagging…") },
                                leadingIcon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                                onClick = { settingsOpen = false; onConfigureLibrary() }
                            )
                            DropdownMenuItem(
                                text = { Text("Appearance…") },
                                leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                onClick = { settingsOpen = false; onConfigureAppearance() }
                            )
                            HorizontalDivider()
                            // "Names" submenu — opens a second menu to the side
                            // holding the camera- and lens-name editors.
                            Box {
                                DropdownMenuItem(
                                    text = { Text("Names") },
                                    leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) },
                                    trailingIcon = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                                    onClick = { namesOpen = true }
                                )
                                DropdownMenu(
                                    expanded = namesOpen,
                                    onDismissRequest = { namesOpen = false },
                                    offset = androidx.compose.ui.unit.DpOffset(x = 180.dp, y = 0.dp)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Camera Names…") },
                                        leadingIcon = { Icon(Icons.Default.Camera, contentDescription = null) },
                                        onClick = {
                                            namesOpen = false; settingsOpen = false; onConfigureCameraNames()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Lens Names…") },
                                        leadingIcon = { Icon(Icons.Default.Lens, contentDescription = null) },
                                        onClick = {
                                            namesOpen = false; settingsOpen = false; onConfigureLensNames()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = TopAppBarDefaults.topAppBarColors(
            // Window chrome — matches the macOS top bar (windowBackgroundColor),
            // the bottom bar, and the grid behind it.
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * Full-width bottom status/control bar.
 *
 * Layout (left → right):
 *   • [ViewModeToggle] — three-segment Catalog/Grid/List toggle (left cluster)
 *   • Thumbnail-size slider — only enabled in Grid or List mode (right cluster)
 *
 * Sort controls live in the Library Filter bar (top-right), not here.
 *
 * Height is fixed at 44 dp with a top divider line, matching the Lightroom
 * filmstrip bar aesthetic.
 */
@Composable
fun BottomBar(
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    thumbnailWidth: androidx.compose.ui.unit.Dp,
    onThumbnailWidthChange: (androidx.compose.ui.unit.Dp) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // Window chrome — matches the macOS bottom bar (windowBackgroundColor).
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top divider
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left cluster — view-mode toggle
                ViewModeToggle(current = viewMode, onChange = onViewModeChange)

                Spacer(modifier = Modifier.weight(1f))

                // Right cluster — thumbnail size slider (disabled in Catalog/Detail mode)
                val sliderEnabled = viewMode == ViewMode.GRID || viewMode == ViewMode.LIST
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ReelVaultSpacing.XSmall)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoSizeSelectSmall,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (sliderEnabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    com.reelvault.ui.components.Tooltip(
                        text = if (sliderEnabled)
                            "Drag to resize thumbnails. The grid automatically adjusts how many columns fit."
                        else
                            "Thumbnail size only applies in Grid or List mode."
                    ) {
                        Slider(
                            value = thumbnailWidth.value,
                            onValueChange = { onThumbnailWidthChange(it.dp) },
                            valueRange = 120f..400f,
                            enabled = sliderEnabled,
                            modifier = Modifier.width(140.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.PhotoSizeSelectLarge,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (sliderEnabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}

/**
 * Three-segment icon-only pill toggle: Grid (G) / List (L) / Detail (D).
 * The active segment has a filled primary background; the others are transparent.
 * Divider lines between segments give the segmented-control appearance.
 */
@Composable
fun ViewModeToggle(
    current: ViewMode,
    onChange: (ViewMode) -> Unit
) {
    val modes = listOf(
        Triple(ViewMode.GRID,   Icons.Default.GridView,          "Grid view (G)"),
        Triple(ViewMode.LIST,   Icons.Default.ViewList,          "List view (L)"),
        Triple(ViewMode.DETAIL, Icons.Default.PlayCircleOutline, "Detail view (D)"),
        Triple(ViewMode.MAP,    Icons.Default.Map,               "Map view (M)")
    )
    Surface(
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
        color = Color.Transparent
    ) {
        Row(modifier = Modifier.height(32.dp)) {
            modes.forEachIndexed { idx, (mode, icon, tooltip) ->
                if (idx > 0) {
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant))
                }
                com.reelvault.ui.components.Tooltip(text = tooltip) {
                    Box(
                        modifier = Modifier
                            .size(36.dp, 32.dp)
                            .background(
                                if (current == mode) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { onChange(mode) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (current == mode)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddLibraryDialog(
    onDismiss: () -> Unit,
    /**
     * dateFormat: empty disables the filename-date feature. Non-empty values
     * must be one of "MM-DD-YYYY", "DD-MM-YYYY", or "YYYY-MM-DD".
     * datePosition: "anywhere" | "beginning" | "end" (only consulted when
     * dateFormat is non-empty).
     * paths: all non-blank paths entered by the user (may be one or many).
     */
    onConfirm: (
        paths: List<String>,
        recursive: Boolean,
        autoGroup: Boolean,
        dateFormat: String,
        datePosition: String
    ) -> Unit
) {
    // Each entry in the list is the text in one path row.
    val pathEntries = remember { mutableStateListOf("") }
    var recursive by remember { mutableStateOf(true) }
    var autoGroup by remember { mutableStateOf(true) }
    var inferDate by remember { mutableStateOf(false) }
    var dateFormat by remember { mutableStateOf("YYYY-MM-DD") }
    var datePosition by remember { mutableStateOf("anywhere") }
    var dateFormatMenu by remember { mutableStateOf(false) }
    var datePositionMenu by remember { mutableStateOf(false) }
    var alwaysApplySettings by remember { mutableStateOf(false) }

    // Pre-populate with saved defaults (if any were saved with "always apply").
    val prefs = remember {
        java.util.prefs.Preferences.userRoot().node("com/reelvault/scanDefaults")
    }
    LaunchedEffect(Unit) {
        if (prefs.getBoolean("hasSavedDefaults", false)) {
            inferDate = prefs.getBoolean("inferDate", false)
            dateFormat = prefs.get("dateFormat", "YYYY-MM-DD")
            datePosition = prefs.get("datePosition", "anywhere")
        }
    }

    val hasAnyNonBlank = pathEntries.any { it.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Library Locations") },
        text = {
            Column {
                Text(
                    text = "Enter one or more directories containing videos. Use \$YEAR in a path " +
                        "(e.g. /Volumes/Media/\$YEAR/Raw) to add every matching year folder at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                // ── Path list ──────────────────────────────────────────────
                // Show at most ~4 rows before scrolling.
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(ReelVaultSpacing.Small)
                ) {
                    itemsIndexed(pathEntries) { idx, pathValue ->
                        // Client-side $YEAR preview: count how many year directories
                        // the user's path template would match on the local filesystem.
                        // The backend does the authoritative expansion; this is UX only.
                        val yearHint: String? = remember(pathValue) {
                            if (!pathValue.contains("\$YEAR")) return@remember null
                            val yearNow = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                            val matches = (1970..yearNow)
                                .map { y -> pathValue.replace("\$YEAR", y.toString()) }
                                .filter { java.io.File(it).isDirectory }
                            if (matches.isEmpty()) {
                                "No matching directories found on disk yet"
                            } else {
                                val example = matches.firstOrNull() ?: ""
                                "Will expand to ${matches.size} director${if (matches.size == 1) "y" else "ies"} " +
                                    "(e.g. $example)"
                            }
                        }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                com.reelvault.ui.components.Tooltip(
                                    text = "Full filesystem path to the folder containing your videos. " +
                                        "Press Tab to complete, type more to narrow the suggestions, or " +
                                        "pick a directory from the dropdown. " +
                                        "Use \$YEAR to expand to every matching year folder."
                                ) {
                                    com.reelvault.ui.components.PathCompletingTextField(
                                        value = pathValue,
                                        onValueChange = { pathEntries[idx] = it },
                                        placeholder = if (idx == 0) "/Users/you/Videos" else "Another path…",
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                // Remove button — only shown when there are multiple rows.
                                if (pathEntries.size > 1) {
                                    IconButton(
                                        onClick = { pathEntries.removeAt(idx) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove path",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (yearHint != null) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = yearHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                // "+  Add another path" button
                TextButton(
                    onClick = { pathEntries.add("") },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Add another path",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Medium))

                // ── Scan options ───────────────────────────────────────────
                com.reelvault.ui.components.Tooltip(
                    text = "When on, ReelVault walks into every subdirectory. " +
                        "When off, only files directly inside the chosen folder are indexed."
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = recursive,
                        onCheckedChange = { recursive = it }
                    )
                    Column {
                        Text(
                            text = "Scan subdirectories",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "When off, only video files directly in this folder are indexed.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                }

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                com.reelvault.ui.components.Tooltip(
                    text = "When on, videos that share a base filename, duration, and frame rate " +
                        "are automatically stacked together (e.g. 4K + 1080p exports of the same clip). " +
                        "You can always group/ungroup manually later."
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = autoGroup,
                        onCheckedChange = { autoGroup = it }
                    )
                    Column {
                        Text(
                            text = "Auto-group similar variants",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Stack videos that share a base name, duration, and frame rate (e.g. multiple resolutions of the same source).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                }

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                // Filename-based capture date inference.
                com.reelvault.ui.components.Tooltip(
                    text = "When on, ReelVault parses each video's filename for a date and " +
                        "uses it as the capture date if the file itself doesn't already have one. " +
                        "Useful for camera exports whose internal metadata lacks a capture time."
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = inferDate,
                        onCheckedChange = { inferDate = it }
                    )
                    Column {
                        Text(
                            text = "Infer capture date from filename",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Only applied when the file has no capture date in its metadata.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                }

                if (inferDate) {
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                    // Date format — full-width selector row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ReelVaultSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Format selector
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { dateFormatMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = "Date format",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = dateFormat,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = dateFormatMenu,
                                onDismissRequest = { dateFormatMenu = false }
                            ) {
                                listOf("MM-DD-YYYY", "DD-MM-YYYY", "YYYY-MM-DD").forEach { fmt ->
                                    DropdownMenuItem(
                                        text = { Text(fmt) },
                                        onClick = { dateFormat = fmt; dateFormatMenu = false }
                                    )
                                }
                            }
                        }

                        // Position selector — larger tap target, clearly labelled
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { datePositionMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = "Date position",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = datePosition,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = datePositionMenu,
                                onDismissRequest = { datePositionMenu = false }
                            ) {
                                listOf(
                                    "anywhere" to "Anywhere in filename",
                                    "beginning" to "Beginning of filename",
                                    "end" to "End of filename"
                                ).forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(label, style = MaterialTheme.typography.bodySmall)
                                                if (value == datePosition) {
                                                    Text(
                                                        "✓",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        },
                                        onClick = { datePosition = value; datePositionMenu = false }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                    // "Always apply" — persist these date settings as the default
                    // for future imports (both manual and automatic via file watcher).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = alwaysApplySettings,
                            onCheckedChange = { alwaysApplySettings = it }
                        )
                        Column {
                            Text(
                                text = "Always apply these settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Pre-fills this dialog and applies the same date rule when the " +
                                    "file watcher auto-indexes new files.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val nonBlankPaths = pathEntries.map { it.trim() }.filter { it.isNotBlank() }
                    if (nonBlankPaths.isNotEmpty()) {
                        // Persist date-rule defaults when "always apply" is ticked.
                        if (alwaysApplySettings && inferDate) {
                            prefs.putBoolean("hasSavedDefaults", true)
                            prefs.putBoolean("inferDate", true)
                            prefs.put("dateFormat", dateFormat)
                            prefs.put("datePosition", datePosition)
                        } else if (alwaysApplySettings && !inferDate) {
                            // User explicitly said "always apply" with date OFF —
                            // remember that preference too.
                            prefs.putBoolean("hasSavedDefaults", true)
                            prefs.putBoolean("inferDate", false)
                        }
                        onConfirm(
                            nonBlankPaths,
                            recursive,
                            autoGroup,
                            if (inferDate) dateFormat else "",
                            if (inferDate) datePosition else ""
                        )
                    }
                },
                enabled = hasAnyNonBlank
            ) {
                Text(if (pathEntries.count { it.isNotBlank() } > 1) "Add & Scan All" else "Add & Scan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ConnectionErrorScreen(
    errorMessage: String,
    onRetry: () -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(ReelVaultSpacing.Large)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Error",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(ReelVaultSpacing.Large))

            Text(
                text = "Connection Error",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(ReelVaultSpacing.Medium))

            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(ReelVaultSpacing.Large))

            Text(
                text = "Make sure the ReelVault backend is running:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "cd core && cargo run --bin reelvault-core",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(ReelVaultSpacing.Large))

            com.reelvault.ui.components.Tooltip(
                text = "Try connecting to the ReelVault backend daemon again. " +
                    "Make sure `reelvault-core` is running on localhost:50051."
            ) {
                Button(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                    Text("Retry")
                }
            }
        }
    }
}
