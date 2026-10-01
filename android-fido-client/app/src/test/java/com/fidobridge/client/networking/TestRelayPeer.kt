package com.fidobridge.client.networking

import com.fidobridge.client.crypto.TestResponderSession
import com.fidobridge.client.protocol.MessageCodec
import com.fidobridge.client.protocol.Protocol
import com.fidobridge.client.protocol.WireEnvelope

/**
 * Test-only daemon peer that drives a [FakeRelayTransport] through the Noise
 * handshake and exchanges encrypted `data` messages, mirroring the real Python
 * daemon (PROTOCOL.md §6).
 */
class TestRelayPeer(
    private val transport: FakeRelayTransport,
    private val channelId: String,
    daemonStaticPrivate: ByteArray
) {
    private val responder = TestResponderSession(daemonStaticPrivate)
    private var decryptedIndex = 0

    fun completeHandshake() {
        val ik1 = transport.published
            .mapNotNull { try { MessageCodec.decode(it.decodeToString()) } catch (e: Exception) { null } }
            .lastOrNull { it.kind == Protocol.KIND_IK1 }
            ?: error("no ik1 published by the phone")
        val ik2 = responder.receiveIk1(ik1.payload)
        transport.simulatePublication(
            MessageCodec.encode(WireEnvelope(channelId, Protocol.KIND_IK2, ik2)).toByteArray()
        )
    }

    fun sendRequest(plaintext: ByteArray) {
        val ciphertext = responder.encrypt(plaintext)
        transport.simulatePublication(
            MessageCodec.encode(WireEnvelope(channelId, Protocol.KIND_DATA, ciphertext)).toByteArray()
        )
    }

    /** Decrypt every new `data` envelope the phone has published since the last call. */
    fun decryptPublishedData(): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        val published = transport.published
        while (decryptedIndex < published.size) {
            val raw = published[decryptedIndex]
            decryptedIndex++
            try {
                val envelope = MessageCodec.decode(raw.decodeToString())
                if (envelope.kind == Protocol.KIND_DATA) {
                    result.add(responder.decrypt(envelope.payload))
                }
            } catch (e: Exception) {
                // Skip non-data envelopes or frames we can no longer authenticate.
            }
        }
        return result
    }

    fun encryptForPhone(plaintext: ByteArray): ByteArray = responder.encrypt(plaintext)
}