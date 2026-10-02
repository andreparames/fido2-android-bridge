package com.fidobridge.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserMessageBusTest {

    @Test
    fun `post publishes a message and clear removes it`() {
        val bus = UserMessageBus()

        assertNull(bus.message.value)
        bus.post("boom")
        assertEquals("boom", bus.message.value)
        bus.clear()
        assertNull(bus.message.value)
    }
}