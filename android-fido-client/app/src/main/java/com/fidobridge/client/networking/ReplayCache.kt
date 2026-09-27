package com.fidobridge.client.networking

class ReplayCache(private val capacity: Int = 512) {

    private val seen = object : LinkedHashMap<String, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>?): Boolean =
            size > capacity
    }

    @Synchronized
    fun isReplay(id: String): Boolean {
        if (seen.containsKey(id)) return true
        seen[id] = Unit
        return false
    }
}
