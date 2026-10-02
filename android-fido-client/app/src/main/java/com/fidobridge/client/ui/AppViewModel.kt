package com.fidobridge.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fidobridge.client.bridge.BridgePipeline
import com.fidobridge.client.bridge.BridgeState
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
    private val userMessageBus: UserMessageBus
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
        pipeline.stop()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { appResetManager.reset() }
            if (!ok) {
                userMessageBus.post("Reset incomplete — some data may remain. Try again.")
            }
            onComplete(ok)
        }
    }
}