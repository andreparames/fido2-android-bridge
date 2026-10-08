package com.fidobridge.client.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayModeDetectorTest {

    private fun detect(url: String) = RelayModeDetector.detect(url)

    @Test
    fun `managed host is managed`() {
        assertEquals(RelayMode.MANAGED, detect("wss://relay.gatebridge.app/connection/websocket"))
        assertEquals(RelayMode.MANAGED, detect("https://relay.gatebridge.app/connection/websocket"))
    }

    @Test
    fun `hostname matching is case insensitive`() {
        assertEquals(RelayMode.MANAGED, detect("WSS://RELAY.GATEBRIDGE.APP/CONNECTION/WEBSOCKET"))
        assertEquals(RelayMode.MANAGED, detect("wss://Relay.Gatebridge.App/connection/websocket"))
    }

    @Test
    fun `port path and query do not affect the host match`() {
        assertEquals(RelayMode.MANAGED, detect("wss://relay.gatebridge.app:9000/connection/websocket"))
        assertEquals(RelayMode.MANAGED, detect("wss://relay.gatebridge.app:443/custom?token=x"))
        assertEquals(RelayMode.MANAGED, detect("ws://relay.gatebridge.app"))
    }

    @Test
    fun `userinfo does not confuse the host match`() {
        assertEquals(RelayMode.MANAGED, detect("wss://user:pass@relay.gatebridge.app/connection/websocket"))
    }

    @Test
    fun `trailing dot resolves to the same host`() {
        assertEquals(RelayMode.MANAGED, detect("wss://relay.gatebridge.app./connection/websocket"))
    }

    @Test
    fun `prefixes and suffixes of the managed host are classic`() {
        assertEquals(RelayMode.CLASSIC, detect("wss://evil-relay.gatebridge.app/connection/websocket"))
        assertEquals(RelayMode.CLASSIC, detect("wss://notrelay.gatebridge.app/connection/websocket"))
        assertEquals(RelayMode.CLASSIC, detect("wss://relay.gatebridge.app.evil.com/connection/websocket"))
        assertEquals(RelayMode.CLASSIC, detect("wss://relay.gatebridge.app.evil/connection/websocket"))
        assertEquals(RelayMode.CLASSIC, detect("wss://xrelay.gatebridge.app/connection/websocket"))
    }

    @Test
    fun `classic hosts and ip addresses are classic`() {
        assertEquals(RelayMode.CLASSIC, detect("wss://localhost:9000/connection/websocket"))
        assertEquals(RelayMode.CLASSIC, detect("ws://127.0.0.1:9000/connection/websocket"))
        assertEquals(RelayMode.CLASSIC, detect("wss://relay.example.com/connection/websocket"))
    }

    @Test
    fun `unsupported schemes and malformed urls are classic`() {
        assertEquals(RelayMode.CLASSIC, detect("ftp://relay.gatebridge.app"))
        assertEquals(RelayMode.CLASSIC, detect("relay.gatebridge.app"))
        assertEquals(RelayMode.CLASSIC, detect(""))
        assertEquals(RelayMode.CLASSIC, detect("   "))
        assertEquals(RelayMode.CLASSIC, detect("not-a-url"))
        assertEquals(RelayMode.CLASSIC, detect("wss:///connection/websocket"))
    }
}