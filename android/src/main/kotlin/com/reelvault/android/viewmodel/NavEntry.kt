// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.viewmodel

/**
 * The grid "source" filters that back/forward restores. iOS uses a single
 * mutually-exclusive `LibrarySection`, but Android's sidebar lets a collection,
 * a keyword tag, and a library-location path be co-active (the daemon
 * AND-combines them), so we capture all three to round-trip losslessly.
 *
 * Facet filters (rating / colour / geo / search / attributes) are deliberately
 * NOT captured — iOS omits them from `NavState` too, so back/forward restores
 * the browse *path*, not every transient filter.
 */
data class NavSource(
    val collectionId: String?,
    val tagId: String,
    val locationPath: String,
)

/**
 * Which browse destination a recorded location lives on. Only these three are
 * recorded; Connection / Settings / LocalMedia / OfflineLibrary are outside the
 * browse area and never enter the history.
 */
enum class NavRoute { GRID, DETAIL, MAP }

/**
 * One recorded browse location for the back/forward history — the Android
 * analogue of iOS `NavState`. Equality (data class) drives the dedup + restore
 * matching, so [viewMode] is normalised to "" for non-GRID routes (it is only
 * meaningful for the grid/list toggle): a Detail/Map entry must compare equal
 * regardless of whatever grid/list mode happens to be active behind it.
 */
data class NavEntry(
    val route: NavRoute,
    val source: NavSource,
    val viewMode: String,   // "grid" | "list" — honoured only when route == GRID
    val videoId: String?,   // detail target; null off Detail
)
