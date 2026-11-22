// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.viewmodel

import com.reelvault.ViewMode
import com.reelvault.data.models.AttributeFilterState
import com.reelvault.data.models.LibraryFilterMode
import com.reelvault.data.models.OrientationFilterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavHistoryTest {
    private fun state(tag: String = "", video: String? = null, search: String = "") = NavState(
        viewMode = ViewMode.GRID,
        locationPaths = emptyList(),
        collectionId = null,
        tagId = tag,
        videoId = video,
        libraryFilterMode = LibraryFilterMode.Text,
        searchQuery = search,
        filterMinRating = 0,
        filterColorLabel = "",
        filterHasLocation = AttributeFilterState.Any,
        filterHasKeywords = AttributeFilterState.Any,
        filterHasProxies = AttributeFilterState.Any,
        filterFullResolution = AttributeFilterState.Any,
        filterHasAudio = AttributeFilterState.Any,
        filterOrientation = OrientationFilterState.Any,
        metadataColumns = emptyList(),
    )

    @Test fun emptyHistoryCannotMove() {
        val h = NavHistory()
        assertFalse(h.canGoBack.value)
        assertFalse(h.canGoForward.value)
        assertNull(h.goBack())
        assertNull(h.goForward())
    }

    @Test fun singleEntryCannotGoBack() {
        val h = NavHistory()
        h.record(state("a"))
        assertFalse(h.canGoBack.value)
        assertNull(h.goBack())
    }

    @Test fun recordThenBackReturnsPreviousEntry() {
        val h = NavHistory()
        h.record(state("a")); h.record(state("b"))
        assertTrue(h.canGoBack.value)
        assertEquals(state("a"), h.goBack())
        assertFalse(h.canGoBack.value)
        assertTrue(h.canGoForward.value)
    }

    @Test fun forwardRestoresAfterBack() {
        val h = NavHistory()
        h.record(state("a")); h.record(state("b"))
        h.goBack()
        assertEquals(state("b"), h.goForward())
        assertFalse(h.canGoForward.value)
        assertNull(h.goForward())
    }

    @Test fun duplicateRecordIsIgnored() {
        val h = NavHistory()
        h.record(state("a")); h.record(state("a"))
        assertFalse(h.canGoBack.value)
    }

    @Test fun reRecordingRestoredStateIsNoOp() {
        val h = NavHistory()
        h.record(state("a")); h.record(state("b"))
        val restored = h.goBack()!!
        h.record(restored)
        assertTrue(h.canGoForward.value)
        assertEquals(state("b"), h.goForward())
    }

    @Test fun divergingAfterBackTruncatesForwardTail() {
        val h = NavHistory()
        h.record(state("a")); h.record(state("b")); h.record(state("c"))
        h.goBack(); h.goBack()
        h.record(state("d"))
        assertFalse(h.canGoForward.value)
        assertEquals(state("a"), h.goBack())
    }

    @Test fun filterChangeCountsAsDistinctEntry() {
        val h = NavHistory()
        h.record(state("a")); h.record(state("a", search = "beach"))
        assertEquals(state("a"), h.goBack())
    }

    @Test fun clearResetsEverything() {
        val h = NavHistory()
        h.record(state("a")); h.record(state("b"))
        h.clear()
        assertFalse(h.canGoBack.value)
        assertFalse(h.canGoForward.value)
        assertNull(h.goBack())
    }
}
