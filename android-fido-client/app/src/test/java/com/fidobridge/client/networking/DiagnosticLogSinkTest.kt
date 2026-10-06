package com.fidobridge.client.networking

import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogSinkTest {

    private class RecordingStore : DiagnosticLogStore {
        val entries = mutableListOf<DiagnosticLogEntry>()
        override fun append(entry: DiagnosticLogEntry) {
            entries += entry
        }
        override fun readAll(): String = ""
        override fun exportZipTo(output: OutputStream) = Unit
        override fun clear() = Unit
        override fun sizeBytes(): Long = entries.size.toLong()
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
        val sink = DiagnosticLogSink(store, publisher, relayEnabled = false)

        sink.log("hello")

        assertEquals(listOf("hello"), store.entries.map { it.message })
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `log persists locally and publishes when enabled`() {
        val store = RecordingStore()
        val publisher = RecordingPublisher()
        val sink = DiagnosticLogSink(store, publisher, relayEnabled = true)

        sink.log("hello")

        assertEquals(listOf("hello"), store.entries.map { it.message })
        assertEquals("hello", DiagnosticLogEntry.decode(publisher.published.single().toString(Charsets.UTF_8))?.message)
    }

    @Test
    fun `log persists locally even when the relay publisher was never started`() {
        val store = RecordingStore()
        val publisher = RecordingPublisher()
        val sink = DiagnosticLogSink(store, publisher, relayEnabled = true)

        sink.log("offline")

        assertEquals(listOf("offline"), store.entries.map { it.message })
    }

    @Test
    fun `start only connects the relay when enabled`() {
        val disabledPublisher = RecordingPublisher()
        DiagnosticLogSink(RecordingStore(), disabledPublisher, relayEnabled = false)
            .start("c".repeat(32), "wss://relay", "tok")
        assertFalse(disabledPublisher.started)

        val enabledPublisher = RecordingPublisher()
        DiagnosticLogSink(RecordingStore(), enabledPublisher, relayEnabled = true)
            .start("c".repeat(32), "wss://relay", "tok")
        assertTrue(enabledPublisher.started)
        assertEquals(Triple("c".repeat(32), "wss://relay", "tok"), enabledPublisher.lastArgs)
    }

    @Test
    fun `stop only tears the relay down when enabled`() {
        val disabledPublisher = RecordingPublisher()
        DiagnosticLogSink(RecordingStore(), disabledPublisher, relayEnabled = false).stop()
        assertFalse(disabledPublisher.stopped)

        val enabledPublisher = RecordingPublisher()
        DiagnosticLogSink(RecordingStore(), enabledPublisher, relayEnabled = true).stop()
        assertTrue(enabledPublisher.stopped)
    }
}
