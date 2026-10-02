package com.fidobridge.client.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fidobridge.client.bridge.BridgeState
import com.fidobridge.client.ui.AppViewModel
import com.fidobridge.client.ui.model.RequestOutcome
import com.fidobridge.client.ui.model.RequestRecord
import com.fidobridge.client.ui.theme.SemanticColors
import com.fidobridge.client.ui.theme.semanticColors
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onResetConfirmed: () -> Unit
) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val bridgeState by viewModel.bridgeState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val colors = semanticColors()
    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .padding(16.dp)
            ) {
                ConnectionStatusBanner(
                    state = bridgeState,
                    colors = colors,
                    onReconnect = viewModel::reconnect,
                    onAcknowledge = viewModel::acknowledgeSecurityAlert
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                RequestListHeader(
                    requests = requests,
                    colors = colors,
                    onClear = {
                        val snapshot = requests
                        viewModel.clearLog()
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Request history cleared",
                                actionLabel = "Undo",
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.restoreRequests(snapshot)
                            }
                        }
                    }
                )

                if (requests.isEmpty()) {
                    EmptyState(modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(requests, key = { it.id }) { record ->
                            RequestRow(record = record, colors = colors)
                        }
                    }
                }

                ResetSection(onResetClick = { showResetDialog = true })
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset app?") },
            text = { Text("This erases your pairing key and all stored credentials. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        viewModel.reset { success ->
                            if (success) onResetConfirmed()
                        }
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ConnectionStatusBanner(
    state: BridgeState,
    colors: SemanticColors,
    onReconnect: () -> Unit,
    onAcknowledge: () -> Unit
) {
    val status = connectionStatus(state)

    if (status.kind == ConnectionStatusKind.SECURITY_ALERT) {
        SecurityAlertBanner(status, colors, onReconnect, onAcknowledge)
        return
    }

    val dotColor = statusColor(status.kind, colors)
    val pulsing =
        status.kind == ConnectionStatusKind.CONNECTING || status.kind == ConnectionStatusKind.CONNECTED

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color = dotColor, pulsing = pulsing, contentDescription = status.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(status.title, style = MaterialTheme.typography.titleMedium)
            if (status.subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    status.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (status.showReconnect) {
            TextButton(onClick = onReconnect) { Text("Reconnect") }
        }
    }
}

@Composable
private fun SecurityAlertBanner(
    status: ConnectionStatusUi,
    colors: SemanticColors,
    onReconnect: () -> Unit,
    onAcknowledge: () -> Unit
) {
    Surface(
        color = colors.dangerContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    status.icon,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    status.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onDanger
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                status.subtitle.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onDanger
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End) {
                if (status.showAcknowledge) {
                    TextButton(onClick = onAcknowledge) {
                        Text("Acknowledge", color = colors.onDanger)
                    }
                }
                if (status.showReconnect) {
                    TextButton(onClick = onReconnect) {
                        Text("Reconnect", color = colors.onDanger)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean, contentDescription: String) {
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000), RepeatMode.Reverse),
        label = "status-alpha"
    )
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(color.copy(alpha = if (pulsing) alpha else 1f), CircleShape)
            .semantics { this.contentDescription = contentDescription }
    )
}

@Composable
private fun RequestListHeader(
    requests: List<RequestRecord>,
    colors: SemanticColors,
    onClear: () -> Unit
) {
    val pendingCount = requests.count { it.outcome == RequestOutcome.PENDING }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Recent requests",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        if (pendingCount > 1) {
            Text(
                "${pendingCount - 1} more waiting",
                style = MaterialTheme.typography.labelMedium,
                color = colors.warning,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        TextButton(onClick = onClear, enabled = requests.isNotEmpty()) {
            Text("Clear")
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Inbox,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text("No requests yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "When your computer asks to sign in, the request will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RequestRow(record: RequestRecord, colors: SemanticColors) {
    val pending = record.outcome == RequestOutcome.PENDING
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (pending) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (pending) BorderStroke(1.dp, colors.warning) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription =
                    "${requestTypeLabel(record.type)} request from ${record.rpId}, " +
                        "${requestOutcomeLabel(record.outcome).lowercase()}, ${formatRelativeTime(record.timestamp)}"
            }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    requestTypeIcon(record.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.rpId,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (pending) Int.MAX_VALUE else 1,
                    overflow = if (pending) TextOverflow.Clip else TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (pending) {
                        "Waiting for your approval"
                    } else {
                        "${requestTypeLabel(record.type)} · ${formatRelativeTime(record.timestamp)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            OutcomeBadge(outcome = record.outcome, colors = colors)
        }
    }
}

@Composable
private fun OutcomeBadge(outcome: RequestOutcome, colors: SemanticColors) {
    val (background, content) = when (outcome) {
        RequestOutcome.ACCEPTED -> colors.success to colors.onSuccess
        RequestOutcome.REJECTED -> colors.danger to colors.onDanger
        RequestOutcome.PENDING -> colors.warning to colors.onWarning
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = requestOutcomeLabel(outcome) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            requestOutcomeIcon(outcome),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(requestOutcomeLabel(outcome), style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
private fun ResetSection(onResetClick: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text(
        "Danger zone",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    OutlinedButton(
        onClick = onResetClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        )
    ) {
        Text("Reset app")
    }
}