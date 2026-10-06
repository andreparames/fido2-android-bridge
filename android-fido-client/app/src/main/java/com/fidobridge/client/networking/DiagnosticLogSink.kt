package com.fidobridge.client.networking

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Fans diagnostic entries out to two destinations:
 *
 * 1. The local [DiagnosticLogStore] — **always**, and never dropped, so logs
 *    survive a relay outage and can be exported. Writes are serialized on
 *    [writer] so they never run on the caller's (often main) thread and local
 *    persistence failures can never break the authentication/security path.
 * 2. The relay log channel via [RelayLogPublisher] — only when [relayEnabled],
 *    which is `true` for debug builds and `false` for release/prod builds.
 */
class DiagnosticLogSink(
    private val store: DiagnosticLogStore,
    private val publisher: RelayLogPublisher,
    private val relayEnabled: Boolean,
    private val writer: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostic-log-writer").apply { isDaemon = true }
    }
) {

    fun start(channelId: String, relayUrl: String, relayToken: String?) {
        if (!relayEnabled) return
        runCatching { publisher.start(channelId, relayUrl, relayToken) }
    }

    fun log(message: String) {
        val entry = DiagnosticLogEntry.now(message)
        writer.execute { runCatching { store.append(entry) } }
        if (relayEnabled) {
            runCatching {
                publisher.publish(DiagnosticLogEntry.encode(entry).toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** Drains queued local writes (best effort) before tearing down the relay. */
    fun stop() {
        val drained = CountDownLatch(1)
        runCatching { writer.execute { drained.countDown() } }
        runCatching { drained.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        if (relayEnabled) runCatching { publisher.stop() }
    }

    companion object {
        private const val DRAIN_TIMEOUT_SECONDS = 2L
    }
}
