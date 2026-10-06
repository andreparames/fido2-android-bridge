package com.fidobridge.client.networking

/**
 * Fans diagnostic entries out to two destinations:
 *
 * 1. The local [DiagnosticLogStore] — **always**, and never dropped, so logs
 *    survive a relay outage and can be exported.
 * 2. The relay log channel via [RelayLogPublisher] — only when [relayEnabled],
 *    which is `true` for debug builds and `false` for release/prod builds.
 */
class DiagnosticLogSink(
    private val store: DiagnosticLogStore,
    private val publisher: RelayLogPublisher,
    private val relayEnabled: Boolean
) {

    fun start(channelId: String, relayUrl: String, relayToken: String?) {
        if (!relayEnabled) return
        publisher.start(channelId, relayUrl, relayToken)
    }

    fun log(message: String) {
        val entry = DiagnosticLogEntry.now(message)
        store.append(entry)
        if (relayEnabled) {
            publisher.publish(DiagnosticLogEntry.encode(entry).toByteArray(Charsets.UTF_8))
        }
    }

    fun stop() {
        if (relayEnabled) publisher.stop()
    }
}
