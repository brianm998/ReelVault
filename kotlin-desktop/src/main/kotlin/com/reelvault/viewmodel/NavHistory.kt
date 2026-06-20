// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.viewmodel

import com.reelvault.ViewMode
import com.reelvault.data.models.AttributeFilterState
import com.reelvault.data.models.LibraryFilterMode
import com.reelvault.data.models.MetadataColumn
import com.reelvault.data.models.OrientationFilterState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One recorded browse location for the back/forward history — the Compose-desktop
 * analogue of the iOS client's `NavState`: the active view mode, the grid "source"
 * filters, and the selected video.
 *
 * iOS captures a single mutually-exclusive `LibrarySection`, but the desktop
 * sidebar (like macOS) lets a collection, a keyword tag, and one *or more*
 * library-location paths be co-active (the daemon AND-combines them), so we
 * capture all three to round-trip losslessly.
 *
 * Library filter state (search / rating / colour / attributes / metadata
 * columns) is also captured so back/forward restores the exact filter the user
 * had at each browse location. Equality (data class) drives the dedup + restore
 * matching.
 *
 * This is a standalone copy, NOT shared with the Android client — the two
 * desktop/Android ViewModels are independent.
 */
data class NavState(
    val viewMode: ViewMode,
    val locationPaths: List<String>,
    val collectionId: String?,
    val tagId: String,
    val videoId: String?,
    // Library filter state — including the active editor mode so the filter bar
    // shows the right panel (Text / Attribute / Metadata / Clear) on restore.
    val libraryFilterMode: LibraryFilterMode,
    val searchQuery: String,
    val filterMinRating: Int,
    val filterColorLabel: String,
    val filterHasLocation: AttributeFilterState,
    val filterHasKeywords: AttributeFilterState,
    val filterHasProxies: AttributeFilterState,
    val filterFullResolution: AttributeFilterState,
    val filterHasAudio: AttributeFilterState,
    val filterOrientation: OrientationFilterState,
    val metadataColumns: List<MetadataColumn>,
)

/**
 * Browser-style session navigation history — a port of the iOS `NavigationHistory`
 * (RootSplitView.swift).
 *
 * [record] dedups against the cursor (so re-recording the state a back/forward
 * restore produces is a no-op — the chevrons don't pollute the history) and
 * truncates any forward tail before appending (diverging after a back starts a
 * fresh branch). [goBack]/[goForward] only move the cursor and return the entry
 * to restore. [clear] wipes it when the open catalog changes.
 *
 * Plain object (no Compose deps): held by `remember {}` in the app composable so
 * it lives for the window's session. Unit-testable.
 */
class NavHistory {
    private val stack = mutableListOf<NavState>()
    private var index = -1

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()
    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    /** Push a settled browse location. Identical semantics to iOS `record()`. */
    fun record(e: NavState) {
        if (index >= 0 && stack[index] == e) return            // dedup (absorbs restores)
        while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)  // truncate forward
        stack.add(e)
        index = stack.lastIndex
        refresh()
    }

    fun goBack(): NavState? {
        if (index <= 0) return null
        index--; refresh(); return stack[index]
    }

    fun goForward(): NavState? {
        if (index >= stack.lastIndex) return null
        index++; refresh(); return stack[index]
    }

    fun clear() {
        stack.clear(); index = -1; refresh()
    }

    private fun refresh() {
        _canGoBack.value = index > 0
        _canGoForward.value = index < stack.lastIndex
    }
}
