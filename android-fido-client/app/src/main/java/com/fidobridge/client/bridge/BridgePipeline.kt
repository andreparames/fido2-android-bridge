package com.fidobridge.client.bridge

import android.util.Log
import com.fidobridge.client.crypto.AesGcmCipher
import com.fidobridge.client.ctap.Ctap2Processor
import com.fidobridge.client.networking.DiagnosticLogSink
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
    private val transportFactory: (endpoint: String, channel: String, relayToken: String?) -> RelayTransport,
    private val logSink: DiagnosticLogSink? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<BridgeState>(BridgeState.Disconnected)
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    private var client: RelayClient? = null
    private var inboundJob: Job? = null
    private var statusJob: Job? = null

    fun start() {
        if (client != null) return
        val key = sessionKeyStore.loadKey()
        val channelId = sessionKeyStore.loadChannelId()
        val relayToken = sessionKeyStore.loadRelayToken()
        Log.i(TAG, "pipeline.start key=${key != null} channelId=$channelId relay=$relayUrl")
        if (key == null) return fail("not paired: missing session key")
        if (channelId == null) return fail("not paired: missing channel")
        logSink?.start(channelId, relayUrl, relayToken)
        logSink?.log("pipeline.start channel=$channelId")

        val cipher = AesGcmCipher(key)
        val transport = transportFactory(relayUrl, Protocol.relayChannel(channelId), relayToken)
        val relay = RelayClient(transport, channelId, cipher)
        client = relay

        inboundJob = scope.launch {
            relay.inbound.collect { plaintext ->
                logSink?.log("inbound publication received")
                try {
                    processor.process(plaintext) { result ->
                        result.onSuccess { response -> relay.send(response) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "processor failure: ${e::class.simpleName}: ${e.message}")
                    logSink?.log("processor failure: ${e::class.simpleName}: ${e.message}")
                }
            }
        }

        statusJob = scope.launch {
            relay.state.collect { connectionState ->
                Log.i(TAG, "relay state -> $connectionState")
                logSink?.log("relay state -> $connectionState")
                _state.value = when (connectionState) {
                    RelayClient.ConnectionState.DISCONNECTED -> BridgeState.Disconnected
                    RelayClient.ConnectionState.CONNECTING -> BridgeState.Connecting
                    RelayClient.ConnectionState.CONNECTED -> BridgeState.Connected
                }
            }
        }

        scope.launch {
            relay.securityAlerts.collect {
                Log.w(TAG, "SECURITY ALERT: GCM integrity failure")
                logSink?.log("SECURITY ALERT: GCM integrity failure")
                _state.value = BridgeState.SecurityAlert
            }
        }

        scope.launch {
            relay.disconnections.collect {
                Log.w(TAG, "relay disconnection")
                logSink?.log("relay disconnection")
                _state.value = BridgeState.Disconnected
            }
        }

        Log.i(TAG, "pipeline connecting to ${Protocol.relayChannel(channelId)}")
        relay.connect()
    }

    fun stop() {
        inboundJob?.cancel()
        statusJob?.cancel()
        client?.close()
        client = null
        logSink?.stop()
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

private const val TAG = "FidoBridge"