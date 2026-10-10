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

    @Test
    fun `clearIf only removes when the message still matches`() {
        val bus = UserMessageBus()
        bus.post("one")
        bus.post("two")

        bus.clearIf("one")

        assertEquals("two", bus.message.value)

        bus.clearIf("two")
        assertNull(bus.message.value)
    }
}