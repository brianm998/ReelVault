// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.sync

import com.reelvault.data.models.SmartCollectionFilters
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartFilterRewriteTest {
    private val src = mapOf("s1" to "Travel", "s2" to "Family", "s3" to "Only-src")
    private val dst = mapOf("d1" to "Travel", "d2" to "Family")

    @Test fun remapsByName() {
        val out = rewriteSmartFilterTagIds("{\"tagIds\":[\"s1\",\"s2\"],\"minRating\":1}", src, dst)
        assertEquals(listOf("d1", "d2"), SmartCollectionFilters.fromJson(out).tagIds)
        assertEquals(1, SmartCollectionFilters.fromJson(out).minRating)
    }

    @Test fun dropsNamesMissingOnDestination() {
        val out = rewriteSmartFilterTagIds("{\"tagIds\":[\"s1\",\"s3\"]}", src, dst)
        assertEquals(listOf("d1"), SmartCollectionFilters.fromJson(out).tagIds)
    }

    @Test fun dropsUnknownSourceIds() {
        val out = rewriteSmartFilterTagIds("{\"tagIds\":[\"zzz\"]}", src, dst)
        assertEquals("{\"tagIds\":[]}", out)
    }

    @Test fun blankInputUnchanged() {
        assertEquals("", rewriteSmartFilterTagIds("", src, dst))
        assertEquals("  ", rewriteSmartFilterTagIds("  ", src, dst))
    }

    @Test fun noTagIdsKeyUnchanged() {
        val j = "{\"minRating\":2}"
        assertEquals(j, rewriteSmartFilterTagIds(j, src, dst))
    }

    @Test fun emptyArrayStaysEmpty() {
        assertEquals("{\"tagIds\":[]}", rewriteSmartFilterTagIds("{\"tagIds\":[]}", src, dst))
    }

    @Test fun roundTripsThroughToJson() {
        val j = SmartCollectionFilters(tagIds = listOf("s2"), searchQuery = "q").toJson()
        val f = SmartCollectionFilters.fromJson(rewriteSmartFilterTagIds(j, src, dst))
        assertEquals(listOf("d2"), f.tagIds)
        assertEquals("q", f.searchQuery)
    }
}
