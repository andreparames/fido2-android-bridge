package com.fidobridge.client.networking

import android.util.Log
import com.fidobridge.client.crypto.AesGcmCipher
import com.fidobridge.client.crypto.EncryptedMessage
import com.fidobridge.client.protocol.Ctap2Status
import com.fidobridge.client.protocol.MessageCodec
import com.fidobridge.client.protocol.PlaintextEnvelope
import com.fidobridge.client.protocol.Protocol
import com.fidobridge.client.protocol.WireMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RelayClient(
    private val transport: RelayTransport,
    private val channelId: String,
    private val cipher: AesGcmCipher,
    private val replayCache: ReplayCache = ReplayCache(),
    private val json: Json = Json { ignoreUnknownKeys = false }
) {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    private val inboundChannel = Channel<ByteArray>(Channel.BUFFERED)
    val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val alertChannel = Channel<Unit>(Channel.BUFFERED)
    val securityAlerts: Flow<Unit> = alertChannel.receiveAsFlow()

    private val disconnectChannel = Channel<Unit>(Channel.BUFFERED)
    val disconnections: Flow<Unit> = disconnectChannel.receiveAsFlow()

    private val sentIds = ReplayCache()

    init {
        transport.setListener(object : RelayTransport.Listener {
            override fun onPublication(data: ByteArray) = handlePublication(data)

            override fun onConnected() {
                _state.value = ConnectionState.CONNECTED
            }

            override fun onDisconnected(code: Int, reason: String) {
                _state.value = ConnectionState.DISCONNECTED
                disconnectChannel.trySend(Unit)
            }
        })
    }

    fun connect() {
        _state.value = ConnectionState.CONNECTING
        transport.connect()
    }

    fun send(payload: ByteArray): Boolean {
        if (_state.value != ConnectionState.CONNECTED) return false
        val sealed = cipher.encrypt(payload)
        val wire = WireMessage(channelId, sealed.nonce, sealed.ciphertext, sealed.tag)
        val encoded = MessageCodec.encode(wire).toByteArray()
        recordSentId(payload)
        transport.publish(encoded) { error ->
            if (error != null) alertChannel.trySend(Unit)
        }
        return true
    }

    private fun recordSentId(plaintext: ByteArray) {
        val id = try {
            json.decodeFromString(PlaintextEnvelope.serializer(), plaintext.decodeToString()).id
        } catch (e: Exception) {
            return
        }
        if (id.isNotBlank()) sentIds.isReplay(id)
    }

    fun close() {
        transport.disconnect()
        _state.value = ConnectionState.DISCONNECTED
    }

    private fun handlePublication(data: ByteArray) {
        Log.d(TAG, "handlePublication len=${data.size}")
        val wire = try {
            MessageCodec.decode(data.decodeToString())
        } catch (e: Exception) {
            Log.w(TAG, "publication dropped: bad wire (${e.message})")
            alertChannel.trySend(Unit)
            return
        }

        if (wire.channelId != channelId) {
            Log.w(TAG, "publication dropped: channel mismatch")
            alertChannel.trySend(Unit)
            return
        }

        val plaintext = try {
            cipher.decrypt(EncryptedMessage(wire.nonce, wire.ciphertext, wire.tag))
        } catch (e: AesGcmCipher.TagMismatchException) {
            Log.w(TAG, "publication dropped: GCM tag failure")
            alertChannel.trySend(Unit)
            return
        }

        val envelope = try {
            json.decodeFromString(PlaintextEnvelope.serializer(), plaintext.decodeToString())
        } catch (e: Exception) {
            Log.w(TAG, "publication dropped: bad envelope (${e.message})")
            alertChannel.trySend(Unit)
            return
        }

        // Skip our own published message echoed back by the relay (Centrifugo
        // publishes back to all subscribers, including the publisher).
        if (envelope.id.isNotBlank() && sentIds.contains(envelope.id)) {
            Log.d(TAG, "skipping own echo id=${envelope.id}")
            return
        }

        if (envelope.id.isNotBlank() && replayCache.isReplay(envelope.id)) {
            Log.w(TAG, "publication is replay id=${envelope.id}; answering operation denied")
            sendError(envelope.id, Ctap2Status.CTAP2_ERR_OPERATION_DENIED)
            return
        }

        Log.d(TAG, "publication queued type=${envelope.type} id=${envelope.id}")
        inboundChannel.trySend(plaintext)
    }

    private fun sendError(id: String, code: Int) {
        val payload = buildJsonObject {
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive("operation denied"))
        }
        val envelope = PlaintextEnvelope(version = Protocol.VERSION, type = TYPE_ERROR, id = id, payload = payload)
        send(json.encodeToString(PlaintextEnvelope.serializer(), envelope).toByteArray())
    }

    companion object {
        private const val TYPE_ERROR = "error"
        private const val TAG = "FidoBridge"
    }
}
