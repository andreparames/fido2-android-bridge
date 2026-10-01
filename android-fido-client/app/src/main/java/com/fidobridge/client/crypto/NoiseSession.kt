package com.fidobridge.client.crypto

import com.fidobridge.client.protocol.Protocol
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import javax.crypto.BadPaddingException

/**
 * Noise IK transport (initiator role, PROTOCOL.md §6).
 *
 * The phone is the initiator of `Noise_IK_25519_AESGCM_SHA256`: it sends `ik1`,
 * the daemon (responder) replies `ik2`, then both `split()` into per-direction
 * transport cipher states. Fresh ephemeral keys are generated per handshake
 * (perfect forward secrecy) and the transport rejects replayed/reordered frames
 * via its 64-bit per-direction nonce counters.
 */
class NoiseSession private constructor(private val handshake: HandshakeState) {

    class AuthenticationException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val aad = ByteArray(0)
    private var sender: CipherState? = null
    private var receiver: CipherState? = null

    val isReady: Boolean get() = sender != null

    fun createIk1(): ByteArray {
        val message = ByteArray(MAX_HANDSHAKE_MESSAGE)
        val len = handshake.writeMessage(message, 0, ByteArray(0), 0, 0)
        return message.copyOf(len)
    }

    fun receiveIk2(message: ByteArray) {
        if (sender != null) throw IllegalStateException("handshake already complete")
        try {
            handshake.readMessage(message, 0, message.size, ByteArray(0), 0)
        } catch (e: BadPaddingException) {
            throw AuthenticationException("ik2 authentication failed", e)
        }
        val pair: CipherStatePair = handshake.split()
        sender = pair.getSender()
        receiver = pair.getReceiver()
        handshake.destroy()
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = sender ?: throw IllegalStateException("handshake not complete")
        val ciphertext = ByteArray(plaintext.size + MAC_LENGTH)
        val len = cipher.encryptWithAd(aad, plaintext, 0, ciphertext, 0, plaintext.size)
        return ciphertext.copyOf(len)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val cipher = receiver ?: throw IllegalStateException("handshake not complete")
        val plaintext = ByteArray(ciphertext.size)
        val len = try {
            cipher.decryptWithAd(aad, ciphertext, 0, plaintext, 0, ciphertext.size)
        } catch (e: BadPaddingException) {
            throw AuthenticationException("transport authentication failed", e)
        }
        return plaintext.copyOf(len)
    }

    fun destroy() {
        sender?.destroy()
        receiver?.destroy()
        sender = null
        receiver = null
        handshake.destroy()
    }

    companion object {
        const val STATIC_KEY_BYTES = Protocol.STATIC_KEY_BYTES

        private const val MAC_LENGTH = 16
        private const val MAX_HANDSHAKE_MESSAGE = 256

        fun generateStaticKey(): ByteArray {
            val dh = Noise.createDH("25519")
            dh.generateKeyPair()
            val privateKey = ByteArray(dh.getPrivateKeyLength())
            dh.getPrivateKey(privateKey, 0)
            dh.clearKey()
            return privateKey
        }

        fun staticPublicKey(privateKey: ByteArray): ByteArray {
            require(privateKey.size == STATIC_KEY_BYTES) { "static private key must be $STATIC_KEY_BYTES bytes" }
            val dh = Noise.createDH("25519")
            dh.setPrivateKey(privateKey, 0)
            val publicKey = ByteArray(dh.getPublicKeyLength())
            dh.getPublicKey(publicKey, 0)
            dh.clearKey()
            return publicKey
        }

        fun create(staticPrivate: ByteArray, daemonStaticPublic: ByteArray): NoiseSession {
            require(staticPrivate.size == STATIC_KEY_BYTES) { "static private key must be $STATIC_KEY_BYTES bytes" }
            require(daemonStaticPublic.size == STATIC_KEY_BYTES) { "daemon static public key must be $STATIC_KEY_BYTES bytes" }
            val handshake = HandshakeState(Protocol.NOISE_PROTOCOL_NAME, HandshakeState.INITIATOR)
            handshake.setPrologue(Protocol.NOISE_PROLOGUE, 0, Protocol.NOISE_PROLOGUE.size)
            handshake.getLocalKeyPair().setPrivateKey(staticPrivate, 0)
            handshake.getRemotePublicKey().setPublicKey(daemonStaticPublic, 0)
            handshake.start()
            return NoiseSession(handshake)
        }
    }
}