package com.fidobridge.client.ui.home

import androidx.compose.ui.graphics.Color
import com.fidobridge.client.bridge.BridgeState
import com.fidobridge.client.ui.theme.SemanticColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusUiTest {

    private val colors = SemanticColors(
        success = Color.Red,
        onSuccess = Color.Red,
        successContainer = Color.Red,
        warning = Color.Red,
        onWarning = Color.Red,
        warningContainer = Color.Red,
        neutral = Color.Red,
        onNeutral = Color.Red,
        neutralContainer = Color.Red,
        danger = Color.Red,
        onDanger = Color.Red,
        dangerContainer = Color.Red
    )

    @Test
    fun `connected shows waiting state with no actions`() {
        val status = connectionStatus(BridgeState.Connected)

        assertEquals(ConnectionStatusKind.CONNECTED, status.kind)
        assertEquals("Waiting for WebAuthn requests", status.title)
        assertFalse(status.showReconnect)
        assertFalse(status.showAcknowledge)
    }

    @Test
    fun `disconnected offers reconnect`() {
        val status = connectionStatus(BridgeState.Disconnected)

        assertEquals(ConnectionStatusKind.DISCONNECTED, status.kind)
        assertEquals("Not connected", status.title)
        assertTrue(status.showReconnect)
        assertFalse(status.showAcknowledge)
    }

    @Test
    fun `error surfaces the message and offers reconnect`() {
        val status = connectionStatus(BridgeState.Error("boom"))

        assertEquals(ConnectionStatusKind.ERROR, status.kind)
        assertEquals("boom", status.title)
        assertTrue(status.showReconnect)
    }

    @Test
    fun `security alert uses neutral language and offers both actions`() {
        val status = connectionStatus(BridgeState.SecurityAlert)

        assertEquals(ConnectionStatusKind.SECURITY_ALERT, status.kind)
        assertTrue(status.showReconnect)
        assertTrue(status.showAcknowledge)

        val subtitle = status.subtitle!!
        assertTrue(subtitle.contains("Corrupted messages"))
        assertFalse(subtitle.contains("attack"))
        assertFalse(subtitle.contains("tamper"))
        assertFalse(subtitle.contains("compromised"))
    }

    @Test
    fun `every state maps to the expected status color`() {
        assertEquals(colors.success, statusColor(ConnectionStatusKind.CONNECTED, colors))
        assertEquals(colors.warning, statusColor(ConnectionStatusKind.CONNECTING, colors))
        assertEquals(colors.neutral, statusColor(ConnectionStatusKind.DISCONNECTED, colors))
        assertEquals(colors.danger, statusColor(ConnectionStatusKind.ERROR, colors))
        assertEquals(colors.danger, statusColor(ConnectionStatusKind.SECURITY_ALERT, colors))
    }
}