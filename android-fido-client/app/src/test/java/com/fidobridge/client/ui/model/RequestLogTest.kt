package com.fidobridge.client.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLogTest {

    private fun requestLog(maxEntries: Int = InMemoryRequestLog.DEFAULT_MAX_ENTRIES) =
        InMemoryRequestLog(maxEntries)

    @Test
    fun `record adds a pending request`() {
        val log = requestLog()

        log.record("id-1", RequestType.SIGN_IN, "example.com")

        assertEquals(1, log.records.value.size)
        val record = log.records.value.first()
        assertEquals("id-1", record.id)
        assertEquals(RequestType.SIGN_IN, record.type)
        assertEquals("example.com", record.rpId)
        assertEquals(RequestOutcome.PENDING, record.outcome)
        assertTrue(record.timestamp > 0)
    }

    @Test
    fun `records are newest first`() {
        val log = requestLog()

        log.record("id-1", RequestType.SIGN_IN, "a.com")
        log.record("id-2", RequestType.REGISTER, "b.com")

        val records = log.records.value
        assertEquals(listOf("id-2", "id-1"), records.map { it.id })
        assertEquals(RequestType.REGISTER, records[0].type)
        assertEquals(RequestType.SIGN_IN, records[1].type)
    }

    @Test
    fun `record with an existing id is not duplicated`() {
        val log = requestLog()

        log.record("id-1", RequestType.SIGN_IN, "a.com")
        log.record("id-1", RequestType.SIGN_IN, "a.com")

        assertEquals(1, log.records.value.size)
    }

    @Test
    fun `markAccepted updates the pending record`() {
        val log = requestLog()
        log.record("id-1", RequestType.SIGN_IN, "a.com")

        log.markAccepted("id-1")

        assertEquals(RequestOutcome.ACCEPTED, log.records.value.single().outcome)
    }

    @Test
    fun `markRejected updates the pending record`() {
        val log = requestLog()
        log.record("id-1", RequestType.REGISTER, "a.com")

        log.markRejected("id-1")

        assertEquals(RequestOutcome.REJECTED, log.records.value.single().outcome)
    }

    @Test
    fun `marking an unknown id is a no-op`() {
        val log = requestLog()
        log.record("id-1", RequestType.SIGN_IN, "a.com")

        log.markAccepted("unknown")

        assertEquals(RequestOutcome.PENDING, log.records.value.single().outcome)
    }

    @Test
    fun `clear empties the log`() {
        val log = requestLog()
        log.record("id-1", RequestType.SIGN_IN, "a.com")
        log.record("id-2", RequestType.REGISTER, "b.com")

        log.clear()

        assertTrue(log.records.value.isEmpty())
    }

    @Test
    fun `log is capped and keeps the newest entries`() {
        val log = requestLog(maxEntries = 3)

        log.record("id-1", RequestType.SIGN_IN, "a.com")
        log.record("id-2", RequestType.SIGN_IN, "b.com")
        log.record("id-3", RequestType.REGISTER, "c.com")
        log.record("id-4", RequestType.SIGN_IN, "d.com")
        log.record("id-5", RequestType.REGISTER, "e.com")

        val records = log.records.value
        assertEquals(3, records.size)
        assertEquals(listOf("id-5", "id-4", "id-3"), records.map { it.id })
    }

    @Test
    fun `no-op log ignores all operations`() {
        val log = NoOpRequestLog

        log.record("id-1", RequestType.SIGN_IN, "a.com")
        log.markAccepted("id-1")
        log.markRejected("id-1")
        log.clear()

        assertTrue(log.records.value.isEmpty())
    }
}