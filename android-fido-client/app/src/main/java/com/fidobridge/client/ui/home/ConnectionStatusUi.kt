package com.fidobridge.client.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.fidobridge.client.bridge.BridgeState
import com.fidobridge.client.ui.theme.SemanticColors

enum class ConnectionStatusKind { CONNECTED, CONNECTING, DISCONNECTED, ERROR, SECURITY_ALERT }

data class ConnectionStatusUi(
    val kind: ConnectionStatusKind,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val showReconnect: Boolean = false,
    val showAcknowledge: Boolean = false
)

fun connectionStatus(state: BridgeState): ConnectionStatusUi = when (state) {
    BridgeState.Connected -> ConnectionStatusUi(
        kind = ConnectionStatusKind.CONNECTED,
        title = "Waiting for WebAuthn requests",
        subtitle = "Requests are approved with your fingerprint",
        icon = Icons.Outlined.CheckCircle
    )

    BridgeState.Connecting -> ConnectionStatusUi(
        kind = ConnectionStatusKind.CONNECTING,
        title = "Connecting…",
        icon = Icons.Outlined.Sync
    )

    BridgeState.Disconnected -> ConnectionStatusUi(
        kind = ConnectionStatusKind.DISCONNECTED,
        title = "Not connected",
        icon = Icons.Outlined.LinkOff,
        showReconnect = true
    )

    is BridgeState.Error -> ConnectionStatusUi(
        kind = ConnectionStatusKind.ERROR,
        title = state.message,
        icon = Icons.Outlined.ErrorOutline,
        showReconnect = true
    )

    BridgeState.SecurityAlert -> ConnectionStatusUi(
        kind = ConnectionStatusKind.SECURITY_ALERT,
        title = "Approvals are paused",
        subtitle = "Corrupted messages were received on the relay connection. " +
            "This can happen with network interference or a relay problem.",
        icon = Icons.Outlined.Shield,
        showReconnect = true,
        showAcknowledge = true
    )
}

fun statusColor(kind: ConnectionStatusKind, colors: SemanticColors): Color = when (kind) {
    ConnectionStatusKind.CONNECTED -> colors.success
    ConnectionStatusKind.CONNECTING -> colors.warning
    ConnectionStatusKind.DISCONNECTED -> colors.neutral
    ConnectionStatusKind.ERROR -> colors.danger
    ConnectionStatusKind.SECURITY_ALERT -> colors.danger
}