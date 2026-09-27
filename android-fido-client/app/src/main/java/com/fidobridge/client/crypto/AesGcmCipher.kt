package com.fidobridge.client.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class AesGcmCipher(private val key: SessionKey) {

    class TagMismatchException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val secureRandom = SecureRandom()

    fun encrypt(plaintext: ByteArray): EncryptedMessage {
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key.toSecretKey(), GCMParameterSpec(TAG_BITS, nonce))
        val output = cipher.doFinal(plaintext)

        val tagStart = output.size - TAG_BYTES
        val ciphertext = output.copyOfRange(0, tagStart)
        val tag = output.copyOfRange(tagStart, output.size)
        return EncryptedMessage(nonce = nonce, ciphertext = ciphertext, tag = tag)
    }

    fun decrypt(message: EncryptedMessage): ByteArray {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key.toSecretKey(),
                GCMParameterSpec(TAG_BITS, message.nonce)
            )
            cipher.doFinal(message.ciphertext + message.tag)
        } catch (e: AEADBadTagException) {
            throw TagMismatchException("GCM authentication failed", e)
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * 8
    }
}
