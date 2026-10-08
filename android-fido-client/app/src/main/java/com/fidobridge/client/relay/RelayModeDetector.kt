package com.fidobridge.client.relay

import java.net.URI

enum class RelayMode {
    MANAGED,
    CLASSIC
}

/**
 * Managed relay is selected purely by hostname (see MANAGED_RELAY_PLAN §2):
 * the configured relay URL's host must equal [MANAGED_HOST] exactly. Any other
 * host — including look-alikes such as `evil-relay.gatebridge.app` or
 * `relay.gatebridge.app.evil` — is classic. Invalid URLs are classic.
 */
object RelayModeDetector {
    const val MANAGED_HOST = "relay.gatebridge.app"

    private val ALLOWED_SCHEMES = setOf("ws", "wss", "http", "https")

    fun detect(relayUrl: String): RelayMode =
        if (hostOf(relayUrl) == MANAGED_HOST) RelayMode.MANAGED else RelayMode.CLASSIC

    /** Extracts the lowercase hostname, or null if the URL has no parseable host. */
    fun hostOf(relayUrl: String): String? {
        val uri = runCatching { URI(relayUrl.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) return null
        // IPv6 literals come back bracketed; they can never match MANAGED_HOST.
        val host = uri.host ?: return null
        return host.lowercase().trimEnd('.').ifEmpty { null }
    }
}
