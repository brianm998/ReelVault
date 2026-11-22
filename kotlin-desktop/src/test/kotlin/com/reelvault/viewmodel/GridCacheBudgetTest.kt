package com.reelvault.viewmodel

import com.reelvault.data.remote.ChannelFactory
import com.reelvault.data.repository.VideoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Cache-level checks: the published maps (not just the LRU bookkeeping) stay in budget. */
class GridCacheBudgetTest {
    private fun vm(): GridViewModel {
        val factory = Proxy.newProxyInstance(
            ChannelFactory::class.java.classLoader, arrayOf(ChannelFactory::class.java),
        ) { _, _, _ -> throw UnsupportedOperationException() } as ChannelFactory
        return GridViewModel(VideoRepository(factory))
    }

    @Test fun thumbnailsMapNeverExceedsBudget() {
        val vm = vm()
        // Budget is 4000 entries; insert well past it with tiny payloads.
        for (i in 0 until 4300) {
            vm.storeThumbnail("v$i", ByteArray(1))
            assertTrue(vm.thumbnails.value.size <= 4000)
        }
        assertEquals(4000, vm.thumbnails.value.size)
        // Oldest evicted from the map itself, newest kept.
        assertFalse(vm.thumbnails.value.containsKey("v0"))
        assertTrue(vm.thumbnails.value.containsKey("v4299"))
    }

    @Test fun scrubFramesMapNeverExceedsBudget() {
        val vm = vm()
        for (i in 0 until 400) {
            vm.storeScrubFrames("v$i", listOf(ByteArray(1), null))
            assertTrue(vm.scrubFrames.value.size <= 150)
        }
        assertEquals(150, vm.scrubFrames.value.size)
        assertFalse(vm.scrubFrames.value.containsKey("v0"))
    }

    @Test fun visibleCardsAreNeverEvicted() {
        val vm = vm()
        vm.storeThumbnail("visible", ByteArray(1))
        vm.cardAppeared("visible")
        for (i in 0 until 5000) vm.storeThumbnail("v$i", ByteArray(1))
        assertTrue(vm.thumbnails.value.containsKey("visible"))
        assertTrue(vm.thumbnails.value.size <= 4001)
        // Once off screen it becomes evictable again.
        vm.cardDisappeared("visible")
        for (i in 0 until 4100) vm.storeThumbnail("w$i", ByteArray(1))
        assertFalse(vm.thumbnails.value.containsKey("visible"))
    }

    @Test fun lruSkipsProtectedKeys() {
        val lru = LruBudget(2, Long.MAX_VALUE)
        lru.insert("a", 1); lru.insert("b", 1)
        assertEquals(listOf("b"), lru.insert("c", 1, setOf("a")))
        assertTrue(lru.contains("a")); assertFalse(lru.contains("b"))
    }

    @Test fun versionedMapWithRemovesEvictedKeys() {
        val m0 = VersionedMap.empty<Int>()
        val m1 = m0.with("a", 1, emptyList())
        val m2 = m1.with("b", 2, listOf("a"))
        assertFalse(m2.containsKey("a"))
        assertTrue(m2.containsKey("b"))
        assertTrue(m2.version > m1.version)
    }
}
