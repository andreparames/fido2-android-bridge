package com.fidobridge.client.networking

import java.io.OutputStream
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogSinkTest {

    private val direct = Executor { it.run() }

    private class RecordingStore : DiagnosticLogStore {
        val entries = mutableListOf<DiagnosticLogEntry>()
        override fun append(entry: DiagnosticLogEntry) {
            entries += entry
        }
        override fun readAll(): String = ""
        override fun exportZipTo(output: OutputStream) = Unit
        override fun clear(): Boolean = true
        override fun sizeBytes(): Long = entries.size.toLong()
    }

    private class ThrowingStore : DiagnosticLogStore {
        override fun append(entry: DiagnosticLogEntry) = throw java.io.IOException("disk full")
        override fun readAll(): String = ""
        override fun exportZipTo(output: OutputStream) = Unit
        override fun clear(): Boolean = true
        override fun sizeBytes(): Long = 0
    }

    private class RecordingPublisher : RelayLogPublisher {
        val published = mutableListOf<ByteArray>()
        var started = false
        var stopped = false
        var lastArgs: Triple<String, String, String?>? = null
        override fun start(channelId: String, relayUrl: String, relayToken: String?) {
            started = true
            lastArgs = Triple(channelId, relayUrl, relayToken)
        }
        override fun publish(payload: ByteArray) {
            published += payload
        }
        override fun stop() {
            stopped = true
        }
    }

    @Test
    fun `log persists locally and skips the relay when disabled`() {
        val store = RecordingStore()
        val publisher = RecordingPublisher()
        val sink = DiagnosticLogSink(store, publisher, relayEnabled = false, writer = direct)

        sink.log("hello")

        assertEquals(listOf("hello"), store.entries.map { it.message })
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `log persists locally and publishes when enabled`() {
        val store = RecordingStore()
        val publisher = RecordingPublisher()
        val sink = DiagnosticLogSink(store, publisher, relayEnabled = true, writer = direct)

        sink.log("hello")

        assertEquals(listOf("hello"), store.entries.map { it.message })
        assertEquals("hello", DiagnosticLogEntry.decode(publisher.published.single().toString(Charsets.UTF_8))?.message)
    }

    @Test
    fun `log persists locally even when the relay publisher was never started`() {
        val store = RecordingStore()
        val publisher = RecordingPublisher()
        val sink = DiagnosticLogSink(store, publisher, relayEnabled = true, writer = direct)

        sink.log("offline")

        assertEquals(listOf("offline"), store.entries.map { it.message })
    }

    @Test
    fun `a local storage failure never propagates and the relay still publishes`() {
        val publisher = RecordingPublisher()
        val sink = DiagnosticLogSink(ThrowingStore(), publisher, relayEnabled = true, writer = direct)

        sink.log("boom") // must not throw

        assertEquals(1, publisher.published.size)
    }

    @Test
    fun `stop drains queued writes`() {
        val store = RecordingStore()
        val publisher = RecordingPublisher()
        // Default single-thread executor: writes are queued off the caller thread.
        val sink = DiagnosticLogSink(store, publisher, relayEnabled = true)

        repeat(200) { sink.log("m$it") }
        sink.stop()

        assertEquals(200, store.entries.size)
        assertTrue(publisher.stopped)
    }

    @Test
    fun `start only connects the relay when enabled`() {
        val disabledPublisher = RecordingPublisher()
        DiagnosticLogSink(RecordingStore(), disabledPublisher, relayEnabled = false, writer = direct)
            .start("c".repeat(32), "wss://relay", "tok")
        assertFalse(disabledPublisher.started)

        val enabledPublisher = RecordingPublisher()
        DiagnosticLogSink(RecordingStore(), enabledPublisher, relayEnabled = true, writer = direct)
            .start("c".repeat(32), "wss://relay", "tok")
        assertTrue(enabledPublisher.started)
        assertEquals(Triple("c".repeat(32), "wss://relay", "tok"), enabledPublisher.lastArgs)
    }

    @Test
    fun `stop only tears the relay down when enabled`() {
        val disabledPublisher = RecordingPublisher()
        DiagnosticLogSink(RecordingStore(), disabledPublisher, relayEnabled = false, writer = direct).stop()
        assertFalse(disabledPublisher.stopped)

        val enabledPublisher = RecordingPublisher()
        DiagnosticLogSink(RecordingStore(), enabledPublisher, relayEnabled = true, writer = direct).stop()
        assertTrue(enabledPublisher.stopped)
    }
}
