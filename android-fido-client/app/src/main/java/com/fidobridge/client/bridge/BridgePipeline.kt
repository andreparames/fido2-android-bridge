package com.fidobridge.client.bridge

import android.util.Log
import com.fidobridge.client.billing.SubscriptionRepository
import com.fidobridge.client.ctap.Ctap2Processor
import com.fidobridge.client.networking.DiagnosticLogSink
import com.fidobridge.client.networking.RelayClient
import com.fidobridge.client.networking.RelayTransport
import com.fidobridge.client.pairing.IdentityStore
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
    private val identityStore: IdentityStore,
    private val relayUrl: String,
    private val processor: Ctap2Processor,
    private val transportFactory: (endpoint: String, channel: String, relayToken: String?) -> RelayTransport,
    private val logSink: DiagnosticLogSink? = null,
    private val defaultRelayToken: String = "",
    private val subscriptionRepository: SubscriptionRepository,
    private val securityFailureTracker: IntegrityFailureTracker = IntegrityFailureTracker()
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<BridgeState>(BridgeState.Disconnected)
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    private var client: RelayClient? = null
    private var inboundJob: Job? = null
    private var statusJob: Job? = null
    private var alertJob: Job? = null
    private var disconnectJob: Job? = null

    fun start() {
        startInternal(preserveSecurityAlert = false)
    }

    /**
     * Tears the pipeline down and re-establishes the relay connection from
     * scratch. Used to recover a dead connection without restarting the app.
     */
    fun reconnect() {
        val preserveSecurityAlert = _state.value == BridgeState.SecurityAlert
        stop()
        startInternal(preserveSecurityAlert = preserveSecurityAlert)
    }

    private fun startInternal(preserveSecurityAlert: Boolean) {
        if (client != null) return
        if (!subscriptionRepository.entitlement.value.isEntitled) {
            return fail(SUBSCRIPTION_REQUIRED)
        }
        if (!preserveSecurityAlert) {
            securityFailureTracker.reset()
        }
        val phonePrivate = identityStore.loadPhoneStaticPrivate()
        val daemonPublic = identityStore.loadDaemonStaticPublic()
        val channelId = identityStore.loadChannelId()
        val relayToken = identityStore.loadRelayToken()?.takeIf { it.isNotEmpty() }
            ?: defaultRelayToken.takeIf { it.isNotEmpty() }
        Log.i(TAG, "pipeline.start phoneKey=${phonePrivate != null} daemonKey=${daemonPublic != null} channelId=$channelId relay=$relayUrl")
        if (phonePrivate == null) return fail("not paired: missing phone static key")
        if (daemonPublic == null) return fail("not paired: missing daemon static key")
        if (channelId == null) return fail("not paired: missing channel")
        if (preserveSecurityAlert) {
            _state.value = BridgeState.SecurityAlert
        }
        logSink?.start(channelId, relayUrl, relayToken)
        logSink?.log("pipeline.start channel=$channelId")

        val transport = transportFactory(relayUrl, Protocol.relayChannel(channelId), relayToken)
        val relay = RelayClient(transport, channelId, phonePrivate, daemonPublic)
        client = relay

        inboundJob = scope.launch {
            relay.inbound.collect { plaintext ->
                if (_state.value == BridgeState.SecurityAlert) {
                    logSink?.log("inbound request dropped: security alert active")
                    return@collect
                }
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
                if (_state.value == BridgeState.SecurityAlert) return@collect
                _state.value = mapConnectionState(connectionState)
            }
        }

        alertJob = scope.launch {
            relay.securityAlerts.collect { alert ->
                Log.w(TAG, "SECURITY ALERT: $alert")
                logSink?.log("SECURITY ALERT: $alert")
                if (alert == RelayClient.SecurityAlert.NOISE_AUTHENTICATION_FAILURE &&
                    securityFailureTracker.record()
                ) {
                    Log.w(TAG, "integrity failure threshold reached; pausing approvals")
                    logSink?.log("integrity failure threshold reached; pausing approvals")
                    _state.value = BridgeState.SecurityAlert
                }
            }
        }

        disconnectJob = scope.launch {
            relay.disconnections.collect {
                Log.w(TAG, "relay disconnection")
                logSink?.log("relay disconnection")
                if (_state.value == BridgeState.SecurityAlert) return@collect
                _state.value = BridgeState.Disconnected
            }
        }

        // Entitlement is session-latched (LatchingSubscriptionRepository): once
        // this pipeline starts entitled it is never torn down for a transient
        // Play state; the server subscribe proxy remains authoritative.
        Log.i(TAG, "pipeline connecting to ${Protocol.relayChannel(channelId)}")
        relay.connect()
    }

    fun stop() {
        inboundJob?.cancel()
        statusJob?.cancel()
        alertJob?.cancel()
        disconnectJob?.cancel()
        client?.close()
        client = null
        logSink?.stop()
        _state.value = BridgeState.Disconnected
    }

    /**
     * Dismisses a raised [BridgeState.SecurityAlert] and resumes normal
     * operation. The message-corruption counter is reset, so a fresh healthy
     * stretch does not re-trigger the alert.
     */
    fun acknowledgeSecurityAlert() {
        securityFailureTracker.reset()
        _state.value = mapConnectionState(client?.state?.value)
    }

    private fun mapConnectionState(connectionState: RelayClient.ConnectionState?): BridgeState = when (connectionState) {
        RelayClient.ConnectionState.CONNECTED -> BridgeState.Connected
        RelayClient.ConnectionState.CONNECTING -> BridgeState.Connecting
        RelayClient.ConnectionState.DISCONNECTED -> BridgeState.Disconnected
        null -> BridgeState.Disconnected
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
private const val SUBSCRIPTION_REQUIRED = "subscription required"