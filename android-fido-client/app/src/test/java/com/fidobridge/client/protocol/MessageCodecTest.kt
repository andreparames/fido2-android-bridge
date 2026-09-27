package com.fidobridge.client.protocol

import com.fidobridge.client.crypto.AesGcmCipher
import com.fidobridge.client.crypto.EncryptedMessage
import com.fidobridge.client.crypto.SessionKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageCodecTest {

    private val channelId = "a".repeat(32)

    private fun sample() = WireMessage(
        channelId = channelId,
        nonce = ByteArray(12) { it.toByte() },
        ciphertext = byteArrayOf(1, 2, 3, 4, 5),
        tag = ByteArray(16) { (it + 1).toByte() }
    )

    @Test
    fun `encode then decode returns an equal message`() {
        val message = sample()

        val decoded = MessageCodec.decode(MessageCodec.encode(message))

        assertEquals(message.channelId, decoded.channelId)
        assertArrayEquals(message.nonce, decoded.nonce)
        assertArrayEquals(message.ciphertext, decoded.ciphertext)
        assertArrayEquals(message.tag, decoded.tag)
    }

    @Test
    fun `missing nonce field throws DecodeException`() {
        val raw = """{"channel_id":"$channelId","ciphertext":"AQIDBAU","tag":"${"B".repeat(22)}"}"""

        assertThrows(MessageCodec.DecodeException::class.java) {
            MessageCodec.decode(raw)
        }
    }

    @Test
    fun `missing tag field throws DecodeException`() {
        val raw = """{"channel_id":"$channelId","nonce":"${"A".repeat(16)}","ciphertext":"AQIDBAU"}"""

        assertThrows(MessageCodec.DecodeException::class.java) {
            MessageCodec.decode(raw)
        }
    }

    @Test
    fun `malformed base64 field throws DecodeException`() {
        val raw = """{"channel_id":"$channelId","nonce":"!!!!","ciphertext":"AQIDBAU","tag":"${"B".repeat(22)}"}"""

        assertThrows(MessageCodec.DecodeException::class.java) {
            MessageCodec.decode(raw)
        }
    }

    @Test
    fun `wrong nonce byte length throws DecodeException`() {
        val raw = """{"channel_id":"$channelId","nonce":"AQID","ciphertext":"AQIDBAU","tag":"${"B".repeat(22)}"}"""

        assertThrows(MessageCodec.DecodeException::class.java) {
            MessageCodec.decode(raw)
        }
    }

    @Test
    fun `round trip with real cipher output`() {
        val key = SessionKey.fromBytes(ByteArray(32) { it.toByte() })
        val cipher = AesGcmCipher(key)
        val plaintext = "integration".toByteArray()
        val sealed = cipher.encrypt(plaintext)
        val message = WireMessage(
            channelId = channelId,
            nonce = sealed.nonce,
            ciphertext = sealed.ciphertext,
            tag = sealed.tag
        )

        val decoded = MessageCodec.decode(MessageCodec.encode(message))
        val decrypted = cipher.decrypt(
            EncryptedMessage(decoded.nonce, decoded.ciphertext, decoded.tag)
        )

        assertArrayEquals(plaintext, decrypted)
    }
}
