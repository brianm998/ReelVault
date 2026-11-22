package com.reelvault.viewmodel

/**
 * Recency + budget bookkeeping for a cache held in a StateFlow map. Stores no
 * values: [insert] returns the keys the caller must drop. Least recently
 * touched entries go first; the key just inserted is never evicted.
 */
class LruBudget(private val maxCount: Int, private val maxBytes: Long) {
    // Access-ordered: iteration starts at the least recently used key.
    private val costs = LinkedHashMap<String, Long>(16, 0.75f, true)
    var totalBytes = 0L
        private set
    val count: Int get() = costs.size

    @Synchronized fun touch(key: String) { costs[key] }

    fun contains(key: String): Boolean = synchronized(this) { costs.containsKey(key) }

    /** Keys in [protect] (on-screen cards) and [key] itself are never evicted. */
    @Synchronized fun insert(key: String, cost: Long, protect: Set<String> = emptySet()): List<String> {
        totalBytes -= costs.put(key, cost) ?: 0L
        totalBytes += cost
        val evicted = mutableListOf<String>()
        val it = costs.entries.iterator()
        while ((costs.size > maxCount || totalBytes > maxBytes) && it.hasNext()) {
            val e = it.next()
            if (e.key == key || e.key in protect) continue
            totalBytes -= e.value
            it.remove()
            evicted.add(e.key)
        }
        return evicted
    }

    @Synchronized fun clear() { costs.clear(); totalBytes = 0L }
}

/**
 * A cache map exposed through a StateFlow without copying on each change.
 * Writers mutate the shared concurrent [backing] map and emit a new O(1)
 * wrapper with a bumped [version]; equality is (same backing, same version), so
 * collectors see exactly one emission per change.
 */
class VersionedMap<V>(
    private val backing: java.util.concurrent.ConcurrentHashMap<String, V>,
    val version: Long,
) : Map<String, V> by backing {
    /** Insert [key] and drop [evict]; returns the next snapshot (O(1) + evictions). */
    fun with(key: String, value: V, evict: Collection<String>): VersionedMap<V> {
        backing[key] = value
        evict.forEach { backing.remove(it) }
        return VersionedMap(backing, version + 1)
    }

    fun cleared(): VersionedMap<V> {
        backing.clear()
        return VersionedMap(backing, version + 1)
    }

    override fun equals(other: Any?) =
        other is VersionedMap<*> && other.backing === backing && other.version == version
    override fun hashCode() = System.identityHashCode(backing) * 31 + version.hashCode()
    override fun toString() = "VersionedMap(v$version, size=$size)"

    companion object {
        fun <V> empty() = VersionedMap<V>(java.util.concurrent.ConcurrentHashMap(), 0)
    }
}
