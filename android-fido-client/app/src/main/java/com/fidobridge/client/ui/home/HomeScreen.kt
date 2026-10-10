package com.fidobridge.client.ui.home

import androidx.annotation.PluralsRes
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fidobridge.client.R
import com.fidobridge.client.bridge.BridgeState
import com.fidobridge.client.ui.AppViewModel
import com.fidobridge.client.ui.components.ScreenScaffold
import com.fidobridge.client.ui.components.StatusMessage
import com.fidobridge.client.ui.components.StatusMessageTone
import com.fidobridge.client.ui.components.heading
import com.fidobridge.client.ui.model.RequestOutcome
import com.fidobridge.client.ui.model.RequestRecord
import com.fidobridge.client.ui.model.RequestType
import com.fidobridge.client.ui.theme.FidoBridgeTheme
import com.fidobridge.client.ui.theme.SemanticColors
import com.fidobridge.client.ui.theme.semanticColors
import kotlinx.coroutines.delay

/** Time after which a pending request is likely to have timed out (matches the daemon's 30s). */
private const val PENDING_TIMEOUT_MS = 30_000L

/** How often the pending-staleness clock refreshes (≤ this after the timeout). */
private const val PENDING_REFRESH_MS = 5_000L

object HomeTags {
    const val STATUS_BANNER = "home_status_banner"
    const val SECURITY_BANNER = "home_status_security"
    const val EMPTY = "home_empty"
    const val CLEAR_LOG = "home_clear_log"
    const val CLEAR_LOG_CONFIRM = "home_clear_confirm"
    const val EXPORT = "home_export"
    const val RESET = "home_reset"
    const val RESET_CONFIRM = "home_reset_confirm"
    fun row(id: String) = "home_row_$id"
}

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onResetConfirmed: () -> Unit
) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val bridgeState by viewModel.bridgeState.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(PENDING_REFRESH_MS)
            now = System.currentTimeMillis()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) viewModel.exportDiagnostics(uri)
    }

    HomeContent(
        requests = requests,
        bridgeState = bridgeState,
        now = now,
        onReconnect = viewModel::reconnect,
        onAcknowledge = viewModel::acknowledgeSecurityAlert,
        onClearLog = viewModel::clearLog,
        onExportLogs = {
            viewModel.checkDiagnosticsAvailable { available ->
                if (available) {
                    exportLauncher.launch("gatebridge-diagnostics-${System.currentTimeMillis()}.zip")
                } else {
                    viewModel.notifyNoDiagnostics()
                }
            }
        },
        onReset = { viewModel.reset { success -> if (success) onResetConfirmed() } }
    )
}

@Composable
internal fun HomeContent(
    requests: List<RequestRecord>,
    bridgeState: BridgeState,
    now: Long,
    onReconnect: () -> Unit,
    onAcknowledge: () -> Unit,
    onClearLog: () -> Unit,
    onExportLogs: () -> Unit,
    onReset: () -> Unit
) {
    val colors = semanticColors()
    var showResetDialog by remember { mutableStateOf(false) }
    var showClearLogDialog by remember { mutableStateOf(false) }

    ScreenScaffold(scrollable = false) {
        ConnectionStatusBanner(
            state = bridgeState,
            colors = colors,
            onReconnect = onReconnect,
            onAcknowledge = onAcknowledge
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        RequestListHeader(
            requests = requests,
            colors = colors,
            onClear = { showClearLogDialog = true }
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
                    RequestRow(record = record, colors = colors, now = now)
                }
            }
        }

        DiagnosticsSection(onExportClick = onExportLogs)

        ResetSection(onResetClick = { showResetDialog = true })
    }

    if (showClearLogDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogDialog = false },
            title = { Text(stringResource(R.string.home_clear_log_title)) },
            text = { Text(stringResource(R.string.home_clear_log_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearLogDialog = false
                        onClearLog()
                    },
                    modifier = Modifier.testTag(HomeTags.CLEAR_LOG_CONFIRM)
                ) {
                    Text(stringResource(R.string.home_clear_log))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogDialog = false }) {
                    Text(stringResource(R.string.home_cancel))
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.home_reset_title)) },
            text = { Text(stringResource(R.string.home_reset_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        onReset()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag(HomeTags.RESET_CONFIRM)
                ) {
                    Text(stringResource(R.string.home_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.home_cancel))
                }
            }
        )
    }
}

@Composable
private fun pluralString(quantity: Int, @PluralsRes id: Int): String {
    val resources = LocalContext.current.resources
    return remember(quantity, id) { resources.getQuantityString(id, quantity, quantity) }
}

@Composable
private fun ConnectionStatusBanner(
    state: BridgeState,
    colors: SemanticColors,
    onReconnect: () -> Unit,
    onAcknowledge: () -> Unit
) {
    val status = connectionStatus(state)
    val title = when (status.kind) {
        ConnectionStatusKind.CONNECTED -> stringResource(R.string.home_status_waiting)
        ConnectionStatusKind.CONNECTING -> stringResource(R.string.home_connecting)
        ConnectionStatusKind.DISCONNECTED -> stringResource(R.string.home_not_connected)
        ConnectionStatusKind.ERROR -> status.title
        ConnectionStatusKind.SECURITY_ALERT ->
            stringResource(R.string.home_security_alert_title)
    }
    val subtitle = when (status.kind) {
        ConnectionStatusKind.CONNECTED ->
            stringResource(R.string.home_status_waiting_subtitle)
        ConnectionStatusKind.SECURITY_ALERT ->
            stringResource(R.string.home_security_alert_subtitle)
        else -> status.subtitle
    }
    val reconnectEnabled = state !is BridgeState.Connecting
    val reconnectLabel = stringResource(R.string.home_reconnect)
    val acknowledgeLabel = stringResource(R.string.home_acknowledge)

    if (status.kind == ConnectionStatusKind.SECURITY_ALERT) {
        StatusMessage(
            title = title,
            text = subtitle.orEmpty(),
            tone = StatusMessageTone.ERROR,
            testTag = HomeTags.SECURITY_BANNER
        ) {
            val content = MaterialTheme.colorScheme.onErrorContainer
            val actionColors = ButtonDefaults.textButtonColors(contentColor = content)
            if (status.showAcknowledge) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onAcknowledge, colors = actionColors) {
                    Text(acknowledgeLabel)
                }
            }
            if (status.showReconnect) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onReconnect, enabled = reconnectEnabled, colors = actionColors) {
                    Text(reconnectLabel)
                }
            }
        }
        return
    }

    val dotColor = statusColor(status.kind, colors)
    val pulsing =
        status.kind == ConnectionStatusKind.CONNECTING || status.kind == ConnectionStatusKind.CONNECTED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HomeTags.STATUS_BANNER),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color = dotColor, pulsing = pulsing)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (status.showReconnect) {
            TextButton(onClick = onReconnect, enabled = reconnectEnabled) {
                Text(reconnectLabel)
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val context = LocalContext.current
    val reduceMotion = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val animatedAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000), RepeatMode.Reverse),
        label = "status-alpha"
    )
    val alpha = if (pulsing && !reduceMotion) animatedAlpha else 1f
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(color.copy(alpha = alpha), CircleShape)
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
            text = stringResource(R.string.home_recent_requests),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .heading()
        )
        if (pendingCount > 1) {
            val waiting = pendingCount - 1
            Text(
                text = pluralString(waiting, R.plurals.home_more_waiting),
                style = MaterialTheme.typography.labelMedium,
                color = colors.warning,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        TextButton(
            onClick = onClear,
            enabled = requests.isNotEmpty(),
            modifier = Modifier.testTag(HomeTags.CLEAR_LOG)
        ) {
            Text(stringResource(R.string.home_clear_log))
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(HomeTags.EMPTY),
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
        Text(
            stringResource(R.string.home_no_requests),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.home_no_requests_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RequestRow(record: RequestRecord, colors: SemanticColors, now: Long) {
    val pending = record.outcome == RequestOutcome.PENDING
    val description = stringResource(
        R.string.home_request_row_desc,
        requestTypeLabel(record.type),
        record.rpId,
        requestOutcomeLabel(record.outcome).lowercase(),
        formatRelativeTime(record.timestamp, now)
    )
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
            .testTag(HomeTags.row(record.id))
            .clearAndSetSemantics {
                contentDescription = description
                if (pending) liveRegion = LiveRegionMode.Polite
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
                        if (now - record.timestamp > PENDING_TIMEOUT_MS) {
                            stringResource(R.string.home_may_have_timed_out)
                        } else {
                            stringResource(R.string.home_waiting_approval)
                        }
                    } else {
                        "${requestTypeLabel(record.type)} · ${formatRelativeTime(record.timestamp, now)}"
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
private fun DiagnosticsSection(onExportClick: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.home_diagnostics),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    OutlinedButton(
        onClick = onExportClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HomeTags.EXPORT)
    ) {
        Text(stringResource(R.string.home_export_logs))
    }
}

@Composable
private fun ResetSection(onResetClick: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.home_danger_zone),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    OutlinedButton(
        onClick = onResetClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HomeTags.RESET),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        )
    ) {
        Text(stringResource(R.string.home_reset_app))
    }
}

private fun previewRequest(
    id: String,
    type: RequestType,
    rpId: String,
    outcm: RequestOutcome,
    timestamp: Long = System.currentTimeMillis()
) = RequestRecord(
    id = id,
    type = type,
    rpId = rpId,
    timestamp = timestamp,
    outcome = outcm
)

private val noAction: () -> Unit = {}

@Preview(name = "Connected (empty)", showBackground = true, widthDp = 411, heightDp = 800, locale = "en")
@Composable
private fun HomePreviewConnectedEmpty() {
    FidoBridgeTheme {
        HomeContent(
            requests = emptyList(),
            bridgeState = BridgeState.Connected,
            now = System.currentTimeMillis(),
            onReconnect = noAction,
            onAcknowledge = noAction,
            onClearLog = noAction,
            onExportLogs = noAction,
            onReset = noAction
        )
    }
}

@Preview(name = "Disconnected", showBackground = true, widthDp = 411, heightDp = 800, locale = "en")
@Composable
private fun HomePreviewDisconnected() {
    FidoBridgeTheme {
        HomeContent(
            requests = listOf(
                previewRequest("r1", RequestType.SIGN_IN, "example.com", RequestOutcome.ACCEPTED)
            ),
            bridgeState = BridgeState.Disconnected,
            now = System.currentTimeMillis(),
            onReconnect = noAction,
            onAcknowledge = noAction,
            onClearLog = noAction,
            onExportLogs = noAction,
            onReset = noAction
        )
    }
}

@Preview(name = "Security alert", showBackground = true, widthDp = 411, heightDp = 800, locale = "en")
@Composable
private fun HomePreviewSecurityAlert() {
    FidoBridgeTheme {
        HomeContent(
            requests = emptyList(),
            bridgeState = BridgeState.SecurityAlert,
            now = System.currentTimeMillis(),
            onReconnect = noAction,
            onAcknowledge = noAction,
            onClearLog = noAction,
            onExportLogs = noAction,
            onReset = noAction
        )
    }
}

@Preview(name = "Pending requests", showBackground = true, widthDp = 411, heightDp = 800, locale = "en")
@Composable
private fun HomePreviewPending() {
    val now = System.currentTimeMillis()
    FidoBridgeTheme {
        HomeContent(
            requests = listOf(
                previewRequest(
                    "r1", RequestType.SIGN_IN, "example.com", RequestOutcome.PENDING, timestamp = now - 10_000
                ),
                previewRequest(
                    "r2", RequestType.REGISTER, "shop.example.org", RequestOutcome.PENDING, timestamp = now - 60_000
                )
            ),
            bridgeState = BridgeState.Connected,
            now = now,
            onReconnect = noAction,
            onAcknowledge = noAction,
            onClearLog = noAction,
            onExportLogs = noAction,
            onReset = noAction
        )
    }
}