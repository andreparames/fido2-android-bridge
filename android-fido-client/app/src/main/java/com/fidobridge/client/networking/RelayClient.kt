package com.fidobridge.client.networking

import android.util.Log
import com.fidobridge.client.crypto.NoiseSession
import com.fidobridge.client.protocol.MessageCodec
import com.fidobridge.client.protocol.PlaintextEnvelope
import com.fidobridge.client.protocol.Protocol
import com.fidobridge.client.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Relay client for the phone (Noise IK initiator, PROTOCOL.md §3/§6).
 *
 * On connect it publishes an `ik1` handshake message (retrying until it goes
 * out); on `ik2` it completes the handshake and `split()`s into transport
 * cipher states. `data` envelopes carry Noise ciphertext: inbound frames are
 * decrypted and emitted on [inbound]; outbound frames are encrypted by [send].
 * Any Noise authentication failure raises a security alert.
 */
class RelayClient(
    private val transport: RelayTransport,
    private val channelId: String,
    private val phoneStaticPrivate: ByteArray,
    private val daemonStaticPublic: ByteArray,
    private val json: Json = Json { ignoreUnknownKeys = false }
) {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    /** Categories of relay problems; only [NOISE_AUTHENTICATION_FAILURE] counts
     * as an integrity failure for the approval-pause threshold. */
    enum class SecurityAlert { NOISE_AUTHENTICATION_FAILURE, OTHER }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val inboundChannel = Channel<ByteArray>(Channel.BUFFERED)
    val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val alertChannel = Channel<SecurityAlert>(Channel.BUFFERED)
    val securityAlerts: Flow<SecurityAlert> = alertChannel.receiveAsFlow()

    private val disconnectChannel = Channel<Unit>(Channel.BUFFERED)
    val disconnections: Flow<Unit> = disconnectChannel.receiveAsFlow()

    private val sentWireById = ConcurrentHashMap<String, String>()

    private var session: NoiseSession? = null
    private var handshakeJob: Job? = null

    init {
        transport.setListener(object : RelayTransport.Listener {
            override fun onPublication(data: ByteArray) = handlePublication(data)

            override fun onConnected() {
                _state.value = ConnectionState.CONNECTED
                startHandshake()
            }

            override fun onDisconnected(code: Int, reason: String) {
                teardownSession()
                _state.value = ConnectionState.DISCONNECTED
                disconnectChannel.trySend(Unit)
            }
        })
    }

    fun connect() {
        _state.value = ConnectionState.CONNECTING
        transport.connect()
    }

    private fun startHandshake() {
        val noise = NoiseSession.create(phoneStaticPrivate, daemonStaticPublic)
        session = noise
        val encoded = MessageCodec.encode(
            WireEnvelope(channelId, Protocol.KIND_IK1, noise.createIk1())
        ).toByteArray()
        // Publish ik1 immediately, then retry if the handshake stalls (e.g. the
        // relay echoed it before the subscription was confirmed).
        publishIk1(encoded)
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            var attempt = 1
            while (noise === session && !noise.isReady && attempt < HANDSHAKE_ATTEMPTS) {
                delay(HANDSHAKE_RETRY_MS)
                if (noise.isReady) break
                publishIk1(encoded)
                attempt++
            }
        }
    }

    private fun publishIk1(encoded: ByteArray) {
        transport.publish(encoded) { error ->
            if (error != null) Log.w(TAG, "ik1 publish failed: ${error.message}")
        }
    }

    fun send(payload: ByteArray): Boolean {
        val noise = session ?: return false
        if (_state.value != ConnectionState.CONNECTED || !noise.isReady) return false
        val ciphertext = try {
            noise.encrypt(payload)
        } catch (e: Exception) {
            Log.w(TAG, "send failed to encrypt: ${e.message}")
            alertChannel.trySend(SecurityAlert.OTHER)
            return false
        }
        val encoded = MessageCodec.encode(WireEnvelope(channelId, Protocol.KIND_DATA, ciphertext)).toByteArray()
        val id = try {
            json.decodeFromString(PlaintextEnvelope.serializer(), payload.decodeToString()).id
        } catch (e: Exception) {
            null
        }
        if (!id.isNullOrBlank()) sentWireById[id] = encoded.decodeToString()
        transport.publish(encoded) { error ->
            if (error != null) alertChannel.trySend(SecurityAlert.OTHER)
        }
        return true
    }

    fun close() {
        teardownSession()
        transport.disconnect()
        _state.value = ConnectionState.DISCONNECTED
    }

    private fun teardownSession() {
        handshakeJob?.cancel()
        handshakeJob = null
        session?.destroy()
        session = null
        sentWireById.clear()
    }

    private fun handlePublication(data: ByteArray) {
        Log.d(TAG, "handlePublication len=${data.size}")
        val envelope = try {
            MessageCodec.decode(data.decodeToString())
        } catch (e: Exception) {
            Log.w(TAG, "publication dropped: bad wire (${e.message})")
            alertChannel.trySend(SecurityAlert.OTHER)
            return
        }

        if (envelope.channelId != channelId) {
            Log.w(TAG, "publication dropped: channel mismatch")
            alertChannel.trySend(SecurityAlert.OTHER)
            return
        }

        when (envelope.kind) {
            Protocol.KIND_IK1 -> {
                // The phone (initiator) only ever publishes ik1; any ik1 we
                // receive is our own relay echo.
                Log.d(TAG, "skipping own ik1 echo")
            }

            Protocol.KIND_IK2 -> {
                val noise = session ?: return
                if (noise.isReady) return
                try {
                    noise.receiveIk2(envelope.payload)
                    Log.d(TAG, "handshake complete")
                } catch (e: NoiseSession.AuthenticationException) {
                    Log.w(TAG, "publication dropped: ik2 authentication failed")
                    alertChannel.trySend(SecurityAlert.NOISE_AUTHENTICATION_FAILURE)
                }
            }

            Protocol.KIND_DATA -> handleData(envelope, data.decodeToString())
        }
    }

    private fun handleData(envelope: WireEnvelope, raw: String) {
        val noise = session ?: return
        // Skip our own relay echo BEFORE decrypting: our own publications are
        // sealed with the sender key and cannot be opened with the receiver key.
        if (sentWireById.containsValue(raw)) {
            Log.d(TAG, "skipping own echo")
            return
        }
        val plaintext = try {
            noise.decrypt(envelope.payload)
        } catch (e: NoiseSession.AuthenticationException) {
            Log.w(TAG, "publication dropped: Noise authentication failed")
            alertChannel.trySend(SecurityAlert.NOISE_AUTHENTICATION_FAILURE)
            return
        }

        val plain = try {
            json.decodeFromString(PlaintextEnvelope.serializer(), plaintext.decodeToString())
        } catch (e: Exception) {
            Log.w(TAG, "publication dropped: bad envelope (${e.message})")
            alertChannel.trySend(SecurityAlert.OTHER)
            return
        }

        Log.d(TAG, "publication queued type=${plain.type} id=${plain.id}")
        inboundChannel.trySend(plaintext)
    }

    companion object {
        private const val HANDSHAKE_ATTEMPTS = 10
        private const val HANDSHAKE_RETRY_MS = 300L
        private const val TAG = "FidoBridge"
    }
}