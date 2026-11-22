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

    @Synchronized fun insert(key: String, cost: Long): List<String> {
        totalBytes -= costs.put(key, cost) ?: 0L
        totalBytes += cost
        val evicted = mutableListOf<String>()
        val it = costs.entries.iterator()
        while ((costs.size > maxCount || totalBytes > maxBytes) && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            totalBytes -= e.value
            it.remove()
            evicted.add(e.key)
        }
        return evicted
    }

    @Synchronized fun clear() { costs.clear(); totalBytes = 0L }
}
