package com.fidobridge.client.networking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayCacheTest {

    @Test
    fun `first occurrence is not a replay`() {
        val cache = ReplayCache()

        assertFalse(cache.isReplay("id-1"))
    }

    @Test
    fun `second occurrence is a replay`() {
        val cache = ReplayCache()
        cache.isReplay("id-1")

        assertTrue(cache.isReplay("id-1"))
    }

    @Test
    fun `contains checks membership without recording`() {
        val cache = ReplayCache()

        assertFalse(cache.contains("id-1"))
        cache.isReplay("id-1")

        assertTrue(cache.contains("id-1"))
        assertFalse(cache.contains("id-2"))
    }

    @Test
    fun `evicts least recently used entry past capacity`() {
        val cache = ReplayCache(2)
        cache.isReplay("a")
        cache.isReplay("b")
        cache.isReplay("c")

        assertTrue(cache.isReplay("b"))
        assertTrue(cache.isReplay("c"))
        assertFalse(cache.isReplay("a"))
    }
}
