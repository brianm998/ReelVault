// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Browser-style session history of browse locations — a verbatim port of the
 * iOS `NavigationHistory` (RootSplitView.swift).
 *
 * [record] dedups against the cursor (so re-recording the state a back/forward
 * restore produces is a no-op) and truncates any forward tail before appending
 * (diverging after a back starts a fresh branch). [goBack]/[goForward] only move
 * the cursor and return the entry to restore.
 *
 * Plain object, not a `ViewModel`: it is held by the activity-scoped
 * [GridViewModel] so it survives configuration changes and is wiped together
 * with the session in `resetForNewSession()`. Unit-testable (no Android deps).
 */
class NavHistory {
    private val stack = mutableListOf<NavEntry>()
    private var index = -1

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()
    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    /** Push a settled browse location. Identical semantics to iOS `record()`. */
    fun record(e: NavEntry) {
        if (index >= 0 && stack[index] == e) return            // dedup (absorbs restores)
        while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)  // truncate forward
        stack.add(e)
        index = stack.lastIndex
        refresh()
    }

    fun goBack(): NavEntry? {
        if (index <= 0) return null
        index--; refresh(); return stack[index]
    }

    fun goForward(): NavEntry? {
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
