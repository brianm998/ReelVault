// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.viewmodel

import com.reelvault.data.models.AttributeFilterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavHistoryTest {
    private fun entry(
        tag: String = "",
        route: NavRoute = NavRoute.GRID,
        video: String? = null,
        search: String = "",
    ) = NavEntry(
        route = route,
        source = NavSource(collectionId = null, tagId = tag, locationPath = ""),
        viewMode = "grid",
        videoId = video,
        searchQuery = search,
    )

    @Test fun emptyHistoryCannotMove() {
        val h = NavHistory()
        assertFalse(h.canGoBack.value)
        assertFalse(h.canGoForward.value)
        assertNull(h.goBack())
        assertNull(h.goForward())
    }

    @Test fun recordThenBackReturnsPreviousEntry() {
        val h = NavHistory()
        h.record(entry("a")); h.record(entry("b"))
        assertTrue(h.canGoBack.value)
        assertEquals(entry("a"), h.goBack())
        assertTrue(h.canGoForward.value)
        assertFalse(h.canGoBack.value)
    }

    @Test fun forwardRestoresAfterBack() {
        val h = NavHistory()
        h.record(entry("a")); h.record(entry("b"))
        h.goBack()
        assertEquals(entry("b"), h.goForward())
        assertNull(h.goForward())
    }

    @Test fun duplicateRecordIsIgnored() {
        val h = NavHistory()
        h.record(entry("a")); h.record(entry("a"))
        assertFalse(h.canGoBack.value)
    }

    @Test fun reRecordingRestoredEntryKeepsForwardTail() {
        val h = NavHistory()
        h.record(entry("a")); h.record(entry("b"))
        h.record(h.goBack()!!)
        assertTrue(h.canGoForward.value)
    }

    @Test fun divergingAfterBackTruncatesForwardTail() {
        val h = NavHistory()
        h.record(entry("a")); h.record(entry("b")); h.record(entry("c"))
        h.goBack(); h.goBack()
        h.record(entry("d"))
        assertFalse(h.canGoForward.value)
        assertEquals(entry("a"), h.goBack())
    }

    @Test fun routeAndFilterDistinguishEntries() {
        assertNotEquals(entry("a"), entry("a", route = NavRoute.DETAIL, video = "v"))
        assertNotEquals(entry("a"), entry("a", search = "x"))
        assertNotEquals(
            entry("a"),
            entry("a").copy(filterHasAudio = AttributeFilterState.Yes),
        )
    }

    @Test fun clearResetsEverything() {
        val h = NavHistory()
        h.record(entry("a")); h.record(entry("b"))
        h.clear()
        assertFalse(h.canGoBack.value)
        assertFalse(h.canGoForward.value)
        assertNull(h.goBack())
    }
}
