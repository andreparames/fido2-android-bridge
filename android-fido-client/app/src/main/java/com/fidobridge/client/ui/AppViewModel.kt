package com.fidobridge.client.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fidobridge.client.bridge.BridgePipeline
import com.fidobridge.client.bridge.BridgeState
import com.fidobridge.client.networking.DiagnosticLogStore
import com.fidobridge.client.networking.DiagnosticsExporter
import com.fidobridge.client.pairing.AppResetManager
import com.fidobridge.client.pairing.PairingRepository
import com.fidobridge.client.ui.model.RequestLog
import com.fidobridge.client.ui.model.RequestRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class AppViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val requestLog: RequestLog,
    private val pipeline: BridgePipeline,
    private val appResetManager: AppResetManager,
    private val userMessageBus: UserMessageBus,
    private val diagnosticLogStore: DiagnosticLogStore,
    private val diagnosticsExporter: DiagnosticsExporter
) : ViewModel() {

    val isPaired: Boolean = pairingRepository.isPaired

    val requests: StateFlow<List<RequestRecord>> = requestLog.records

    val bridgeState: StateFlow<BridgeState> = pipeline.state

    val userMessage: StateFlow<String?> = userMessageBus.message

    fun dismissUserMessage() = userMessageBus.clear()

    fun clearLog() = requestLog.clear()

    fun acknowledgeSecurityAlert() = pipeline.acknowledgeSecurityAlert()

    fun reconnect() = pipeline.reconnect()

    fun reset(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            // Stop the pipeline first (drains queued diagnostics off-main) so the
            // store can be cleared without a late write recreating it.
            val ok = withContext(Dispatchers.IO) {
                pipeline.stop()
                appResetManager.reset()
            }
            if (!ok) {
                userMessageBus.post("Reset incomplete — some data may remain. Try again.")
            }
            onComplete(ok)
        }
    }

    /** Reports, off the main thread, whether there are logs to export. */
    fun checkDiagnosticsAvailable(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) { diagnosticLogStore.sizeBytes() > 0 }
            onResult(available)
        }
    }

    fun notifyNoDiagnostics() = userMessageBus.post("No logs to export yet.")

    fun exportDiagnostics(uri: Uri, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { diagnosticsExporter.exportTo(uri) }
            userMessageBus.post(if (ok) "Diagnostics exported." else "Could not export diagnostics.")
            onComplete(ok)
        }
    }
}