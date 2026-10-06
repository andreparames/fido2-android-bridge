package com.fidobridge.client.networking

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Fans diagnostic entries out to two destinations:
 *
 * 1. The local [DiagnosticLogStore] — **always**, and never dropped, so logs
 *    survive a relay outage and can be exported. Writes are serialized on
 *    [writer] so they never run on the caller's (often main) thread and local
 *    persistence failures can never break the authentication/security path.
 * 2. The relay log channel via [RelayLogPublisher] — only when [relayEnabled],
 *    which is `true` for debug builds and `false` for release/prod builds.
 *
 * [stop] refuses new writes and drains everything already queued, so a caller
 * (e.g. app reset) can clear the store afterwards without a late write
 * recreating it. [start] re-enables logging for a reconnect.
 */
class DiagnosticLogSink(
    private val store: DiagnosticLogStore,
    private val publisher: RelayLogPublisher,
    private val relayEnabled: Boolean,
    private val writer: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostic-log-writer").apply { isDaemon = true }
    }
) {

    @Volatile
    private var accepting = true

    fun start(channelId: String, relayUrl: String, relayToken: String?) {
        accepting = true
        if (!relayEnabled) return
        runCatching { publisher.start(channelId, relayUrl, relayToken) }
    }

    fun log(message: String) {
        if (!accepting) return
        val entry = DiagnosticLogEntry.now(message)
        writer.execute { runCatching { store.append(entry) } }
        if (relayEnabled) {
            runCatching {
                publisher.publish(DiagnosticLogEntry.encode(entry).toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * Refuses further writes and waits for every already-queued write to finish
     * before tearing down the relay, so nothing can be appended after the caller
     * clears the store.
     */
    fun stop() {
        accepting = false
        drain()
        if (relayEnabled) runCatching { publisher.stop() }
    }

    private fun drain() {
        val drained = CountDownLatch(1)
        runCatching { writer.execute { drained.countDown() } }
        try {
            drained.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
