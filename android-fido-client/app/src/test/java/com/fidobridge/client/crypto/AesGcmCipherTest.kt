package com.fidobridge.client.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AesGcmCipherTest {

    private val key = SessionKey.fromBytes(ByteArray(32) { it.toByte() })
    private val cipher = AesGcmCipher(key)

    @Test
    fun `encrypt produces 12-byte nonce, non-empty ciphertext, 16-byte tag`() {
        val plaintext = "hello fido bridge".toByteArray()

        val sealed = cipher.encrypt(plaintext)

        assertEquals(12, sealed.nonce.size)
        assertEquals(16, sealed.tag.size)
        assertTrue(sealed.ciphertext.isNotEmpty())
    }

    @Test
    fun `decrypt of encrypt returns original plaintext`() {
        val plaintext = "round trip payload".toByteArray()

        val sealed = cipher.encrypt(plaintext)
        val decrypted = cipher.decrypt(sealed)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypting same plaintext twice yields different ciphertext`() {
        val plaintext = "same plaintext".toByteArray()

        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun `tampered ciphertext throws TagMismatchException`() {
        val sealed = cipher.encrypt("secret".toByteArray())
        val tampered = sealed.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertThrows(AesGcmCipher.TagMismatchException::class.java) {
            cipher.decrypt(sealed.copy(ciphertext = tampered))
        }
    }

    @Test
    fun `tampered tag throws TagMismatchException`() {
        val sealed = cipher.encrypt("secret".toByteArray())
        val tampered = sealed.tag.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertThrows(AesGcmCipher.TagMismatchException::class.java) {
            cipher.decrypt(sealed.copy(tag = tampered))
        }
    }

    @Test
    fun `decrypt with wrong key throws`() {
        val otherKey = SessionKey.fromBytes(ByteArray(32) { (it + 1).toByte() })
        val otherCipher = AesGcmCipher(otherKey)
        val sealed = cipher.encrypt("secret".toByteArray())

        assertThrows(AesGcmCipher.TagMismatchException::class.java) {
            otherCipher.decrypt(sealed)
        }
    }
}
