package com.fidobridge.client.crypto

import com.fidobridge.client.protocol.Protocol
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.HandshakeState
import javax.crypto.BadPaddingException

/**
 * Test-only Noise IK responder (daemon role). Mirrors the Python daemon's
 * `NoiseResponderSession` so the app can be tested against a local peer with
 * the same cross-peer protocol (PROTOCOL.md §7).
 */
class TestResponderSession(private val staticPrivate: ByteArray) {

    private val aad = ByteArray(0)
    private val handshake = HandshakeState(Protocol.NOISE_PROTOCOL_NAME, HandshakeState.RESPONDER)
    private var sender: CipherState? = null
    private var receiver: CipherState? = null

    init {
        handshake.setPrologue(Protocol.NOISE_PROLOGUE, 0, Protocol.NOISE_PROLOGUE.size)
        handshake.getLocalKeyPair().setPrivateKey(staticPrivate, 0)
        handshake.start()
    }

    fun receiveIk1(message: ByteArray): ByteArray {
        try {
            handshake.readMessage(message, 0, message.size, ByteArray(0), 0)
        } catch (e: BadPaddingException) {
            throw NoiseSession.AuthenticationException("ik1 authentication failed", e)
        }
        val reply = ByteArray(64)
        val len = handshake.writeMessage(reply, 0, ByteArray(0), 0, 0)
        val pair = handshake.split()
        sender = pair.getSender()
        receiver = pair.getReceiver()
        handshake.destroy()
        return reply.copyOf(len)
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = sender ?: error("handshake not complete")
        val out = ByteArray(plaintext.size + TAG_LENGTH)
        val len = cipher.encryptWithAd(aad, plaintext, 0, out, 0, plaintext.size)
        return out.copyOf(len)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val cipher = receiver ?: error("handshake not complete")
        val out = ByteArray(ciphertext.size)
        val len = try {
            cipher.decryptWithAd(aad, ciphertext, 0, out, 0, ciphertext.size)
        } catch (e: BadPaddingException) {
            throw NoiseSession.AuthenticationException("transport authentication failed", e)
        }
        return out.copyOf(len)
    }

    companion object {
        private const val TAG_LENGTH = 16
    }
}