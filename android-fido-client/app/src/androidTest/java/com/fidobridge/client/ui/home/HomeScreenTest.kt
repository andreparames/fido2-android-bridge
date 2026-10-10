package com.fidobridge.client.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fidobridge.client.bridge.BridgeState
import com.fidobridge.client.ui.model.RequestOutcome
import com.fidobridge.client.ui.model.RequestRecord
import com.fidobridge.client.ui.model.RequestType
import com.fidobridge.client.ui.theme.FidoBridgeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun record(
        id: String = "r1",
        type: RequestType = RequestType.SIGN_IN,
        rpId: String = "example.com",
        outcome: RequestOutcome = RequestOutcome.ACCEPTED,
        timestamp: Long = System.currentTimeMillis()
    ) = RequestRecord(id = id, type = type, rpId = rpId, timestamp = timestamp, outcome = outcome)

    private fun content(
        requests: List<RequestRecord> = emptyList(),
        bridgeState: BridgeState = BridgeState.Connected,
        now: Long = System.currentTimeMillis(),
        onClearLog: () -> Unit = {},
        onReset: () -> Unit = {}
    ) {
        composeRule.setContent {
            FidoBridgeTheme {
                HomeContent(
                    requests = requests,
                    bridgeState = bridgeState,
                    now = now,
                    onReconnect = {},
                    onAcknowledge = {},
                    onClearLog = onClearLog,
                    onExportLogs = {},
                    onReset = onReset
                )
            }
        }
    }

    @Test
    fun connectedEmptyStateShowsWaitingMessage() {
        content()

        composeRule.onNodeWithTag(HomeTags.STATUS_BANNER).assertExists()
        composeRule.onNodeWithText("Waiting for WebAuthn requests").assertExists()
        composeRule.onNodeWithTag(HomeTags.EMPTY).assertExists()
        composeRule.onNodeWithText("No requests yet").assertExists()
    }

    @Test
    fun disconnectedOffersReconnect() {
        content(bridgeState = BridgeState.Disconnected)

        composeRule.onNodeWithText("Not connected").assertExists()
        composeRule.onNodeWithText("Reconnect").assertExists()
    }

    @Test
    fun connectingHidesReconnectWhileInFlight() {
        content(bridgeState = BridgeState.Connecting)

        composeRule.onNodeWithText("Connecting…").assertExists()
        composeRule.onAllNodesWithText("Reconnect").assertCountEquals(0)
    }

    @Test
    fun securityAlertIsAnnouncedWithBothActions() {
        content(bridgeState = BridgeState.SecurityAlert)

        composeRule.onNodeWithTag(HomeTags.SECURITY_BANNER).assertExists()
        composeRule.onNodeWithText("Approvals are paused").assertExists()
        composeRule.onNodeWithText("Acknowledge").assertExists()
        composeRule.onNodeWithText("Reconnect").assertExists()
    }

    @Test
    fun freshPendingRequestShowsWaitingAndStaleOneTimesOut() {
        val now = System.currentTimeMillis()
        content(
            requests = listOf(
                record(id = "fresh", outcome = RequestOutcome.PENDING, timestamp = now - 5_000),
                record(id = "stale", outcome = RequestOutcome.PENDING, timestamp = now - 61_000)
            ),
            now = now
        )

        composeRule.onNodeWithText("Waiting for your approval").assertExists()
        composeRule.onNodeWithText("May have timed out").assertExists()
    }

    @Test
    fun clearLogRequiresConfirmation() {
        var cleared = false
        content(requests = listOf(record()), onClearLog = { cleared = true })

        composeRule.onNodeWithTag(HomeTags.CLEAR_LOG).performClick()
        composeRule.onNodeWithText("Clear request log?").assertExists()
        composeRule.onNodeWithTag(HomeTags.CLEAR_LOG_CONFIRM).performClick()

        assertTrue(cleared)
    }

    @Test
    fun resetRequiresDestructiveConfirmation() {
        var reset = false
        content(onReset = { reset = true })

        composeRule.onNodeWithTag(HomeTags.RESET).performClick()
        composeRule.onNodeWithText("Reset app?").assertExists()
        composeRule.onNodeWithTag(HomeTags.RESET_CONFIRM).performClick()

        assertTrue(reset)
    }
}