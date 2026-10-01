package com.fidobridge.client.ui

import androidx.lifecycle.ViewModel
import com.fidobridge.client.bridge.BridgePipeline
import com.fidobridge.client.bridge.BridgeState
import com.fidobridge.client.pairing.AppResetManager
import com.fidobridge.client.pairing.PairingRepository
import com.fidobridge.client.ui.model.RequestLog
import com.fidobridge.client.ui.model.RequestRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class AppViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val requestLog: RequestLog,
    private val pipeline: BridgePipeline,
    private val appResetManager: AppResetManager
) : ViewModel() {

    val isPaired: Boolean = pairingRepository.isPaired

    val requests: StateFlow<List<RequestRecord>> = requestLog.records

    val bridgeState: StateFlow<BridgeState> = pipeline.state

    fun clearLog() = requestLog.clear()

    fun restoreRequests(records: List<RequestRecord>) = requestLog.replace(records)

    fun acknowledgeSecurityAlert() = pipeline.acknowledgeSecurityAlert()

    fun reconnect() = pipeline.reconnect()

    fun reset() {
        pipeline.stop()
        appResetManager.reset()
    }
}