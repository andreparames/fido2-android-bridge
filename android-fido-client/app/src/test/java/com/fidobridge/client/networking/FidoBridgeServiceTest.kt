package com.fidobridge.client.networking

import com.fidobridge.client.bridge.BridgeState
import org.junit.Assert.assertEquals
import org.junit.Test

class FidoBridgeServiceTest {

    @Test
    fun `notification text maps every bridge state`() {
        assertEquals("Waiting for WebAuthn requests", bridgeNotificationText(BridgeState.Connected))
        assertEquals("Connecting…", bridgeNotificationText(BridgeState.Connecting))
        assertEquals("Not connected", bridgeNotificationText(BridgeState.Disconnected))
        assertEquals("Security alert — approvals paused", bridgeNotificationText(BridgeState.SecurityAlert))
        assertEquals("boom", bridgeNotificationText(BridgeState.Error("boom")))
    }
}