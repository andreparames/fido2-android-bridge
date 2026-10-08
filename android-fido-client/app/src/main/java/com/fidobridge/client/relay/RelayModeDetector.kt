package com.fidobridge.client.relay

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

    private val schemed = Regex("^(ws://|wss://|http://|https://)", RegexOption.IGNORE_CASE)

    fun detect(relayUrl: String): RelayMode {
        val host = hostOf(relayUrl) ?: return RelayMode.CLASSIC
        return if (host == MANAGED_HOST) RelayMode.MANAGED else RelayMode.CLASSIC
    }

    /** Extracts the lowercase hostname, or null if the URL has no parseable host. */
    fun hostOf(relayUrl: String): String? {
        val trimmed = relayUrl.trim()
        if (trimmed.isEmpty()) return null
        if (!schemed.containsMatchIn(trimmed)) return null
        val authority = trimmed.substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (authority.isEmpty()) return null
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return null
        val host = when {
            hostPort.startsWith("[") -> hostPort.substringAfter('[').substringBefore(']')
            else -> hostPort.substringBefore(':')
        }
        return host.lowercase().trimEnd('.').ifEmpty { null }
    }
}
