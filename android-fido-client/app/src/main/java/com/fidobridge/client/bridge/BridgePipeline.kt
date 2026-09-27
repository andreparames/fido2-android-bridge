package com.fidobridge.client.bridge

import com.fidobridge.client.crypto.AesGcmCipher
import com.fidobridge.client.ctap.Ctap2Processor
import com.fidobridge.client.networking.RelayClient
import com.fidobridge.client.networking.RelayTransport
import com.fidobridge.client.pairing.SessionKeyStore
import com.fidobridge.client.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BridgePipeline(
    private val sessionKeyStore: SessionKeyStore,
    private val relayUrl: String,
    private val processor: Ctap2Processor,
    private val transportFactory: (endpoint: String, channel: String) -> RelayTransport
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<BridgeState>(BridgeState.Disconnected)
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    private var client: RelayClient? = null
    private var inboundJob: Job? = null
    private var statusJob: Job? = null

    fun start() {
        if (client != null) return
        val key = sessionKeyStore.loadKey() ?: return fail("not paired: missing session key")
        val channelId = sessionKeyStore.loadChannelId() ?: return fail("not paired: missing channel")

        val cipher = AesGcmCipher(key)
        val transport = transportFactory(relayUrl, Protocol.relayChannel(channelId))
        val relay = RelayClient(transport, channelId, cipher)
        client = relay

        inboundJob = scope.launch {
            relay.inbound.collect { plaintext ->
                processor.process(plaintext) { result ->
                    result.onSuccess { response -> relay.send(response) }
                }
            }
        }

        statusJob = scope.launch {
            relay.state.collect { connectionState ->
                _state.value = when (connectionState) {
                    RelayClient.ConnectionState.DISCONNECTED -> BridgeState.Disconnected
                    RelayClient.ConnectionState.CONNECTING -> BridgeState.Connecting
                    RelayClient.ConnectionState.CONNECTED -> BridgeState.Connected
                }
            }
        }

        scope.launch {
            relay.securityAlerts.collect {
                _state.value = BridgeState.SecurityAlert
            }
        }

        scope.launch {
            relay.disconnections.collect {
                _state.value = BridgeState.Disconnected
            }
        }

        relay.connect()
    }

    fun stop() {
        inboundJob?.cancel()
        statusJob?.cancel()
        client?.close()
        client = null
        _state.value = BridgeState.Disconnected
    }

    private fun fail(reason: String): Unit {
        _state.value = BridgeState.Error(reason)
    }
}

sealed interface BridgeState {
    data object Disconnected : BridgeState
    data object Connecting : BridgeState
    data object Connected : BridgeState
    data object SecurityAlert : BridgeState
    data class Error(val message: String) : BridgeState
}