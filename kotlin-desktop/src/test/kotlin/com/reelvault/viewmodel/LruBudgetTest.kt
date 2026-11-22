package com.reelvault.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LruBudgetTest {
    @Test fun countBoundNeverExceeded() {
        val lru = LruBudget(3, Long.MAX_VALUE)
        for (i in 0 until 20) {
            lru.insert("k$i", 1)
            assertTrue(lru.count <= 3)
        }
    }

    @Test fun byteBoundNeverExceeded() {
        val lru = LruBudget(1000, 100)
        for (i in 0 until 50) {
            lru.insert("k$i", 30)
            assertTrue(lru.totalBytes <= 100)
        }
    }

    @Test fun evictsLeastRecentlyTouchedFirst() {
        val lru = LruBudget(3, Long.MAX_VALUE)
        lru.insert("a", 1); lru.insert("b", 1); lru.insert("c", 1)
        lru.touch("a")
        assertEquals(listOf("b"), lru.insert("d", 1))
    }

    @Test fun newestKeyNeverEvictedEvenIfOversized() {
        val lru = LruBudget(10, 10)
        lru.insert("a", 5)
        val ev = lru.insert("big", 50)
        assertEquals(listOf("a"), ev)
        assertEquals(1, lru.count)
    }

    @Test fun reinsertReplacesCost() {
        val lru = LruBudget(10, 100)
        lru.insert("a", 40); lru.insert("a", 10)
        assertEquals(10L, lru.totalBytes)
        assertEquals(1, lru.count)
    }

    @Test fun clearResets() {
        val lru = LruBudget(2, 100)
        lru.insert("a", 5); lru.clear()
        assertEquals(0, lru.count)
        assertEquals(0L, lru.totalBytes)
    }

    @Test fun versionedMapSharesBackingAndBumpsVersion() {
        val m0 = VersionedMap.empty<String>()
        val m1 = m0.with("a", "x", emptyList())
        val m2 = m1.with("b", "y", listOf("a"))
        assertNotEquals(m1, m2)
        assertFalse(m2.containsKey("a"))
        assertEquals("y", m2["b"])
        assertEquals(0, m2.cleared().size)
    }
}
