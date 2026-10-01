package com.fidobridge.client.protocol

import com.fidobridge.client.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageCodecTest {

    private val channelId = "a".repeat(32)

    private fun sample(payload: ByteArray = byteArrayOf(1, 2, 3, 4, 5)) = WireEnvelope(
        channelId = channelId,
        kind = Protocol.KIND_IK1,
        payload = payload
    )

    @Test
    fun `encode then decode returns an equal message`() {
        val envelope = sample()

        val decoded = MessageCodec.decode(MessageCodec.encode(envelope))

        assertEquals(envelope.channelId, decoded.channelId)
        assertEquals(envelope.kind, decoded.kind)
        assertArrayEquals(envelope.payload, decoded.payload)
    }

    @Test
    fun `missing kind field throws DecodeException`() {
        val raw = """{"channel_id":"$channelId","payload":"AQIDBAU"}"""

        assertThrows(MessageCodec.DecodeException::class.java) {
            MessageCodec.decode(raw)
        }
    }

    @Test
    fun `missing payload field throws DecodeException`() {
        val raw = """{"channel_id":"$channelId","kind":"ik1"}"""

        assertThrows(MessageCodec.DecodeException::class.java) {
            MessageCodec.decode(raw)
        }
    }

    @Test
    fun `unknown kind is rejected`() {
        val raw = """{"channel_id":"$channelId","kind":"bogus","payload":"AQIDBAU"}"""

        assertThrows(MessageCodec.DecodeException::class.java) {
            MessageCodec.decode(raw)
        }
    }

    @Test
    fun `malformed base64 payload throws DecodeException`() {
        val raw = """{"channel_id":"$channelId","kind":"data","payload":"!!!!"}"""

        assertThrows(MessageCodec.DecodeException::class.java) {
            MessageCodec.decode(raw)
        }
    }

    @Test
    fun `malformed channel id throws DecodeException`() {
        for (bad in listOf("", "ABC", "a".repeat(31), "z".repeat(32))) {
            val raw = """{"channel_id":"$bad","kind":"ik1","payload":"AQIDBAU"}"""
            assertThrows(MessageCodec.DecodeException::class.java) {
                MessageCodec.decode(raw)
            }
        }
    }

    @Test
    fun `round trip with real noise cipher output`() {
        val payload = "integration".toByteArray()
        val envelope = WireEnvelope(channelId, Protocol.KIND_DATA, payload)

        val decoded = MessageCodec.decode(MessageCodec.encode(envelope))
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `payload uses unpadded standard base64`() {
        val envelope = sample(byteArrayOf(0x00, 0x01, 0x7f))
        val json = MessageCodec.encode(envelope)
        val payloadField = """\"payload\":\"([^\"]*)\"""".toRegex().find(json)!!.groupValues[1]
        assertEquals(false, payloadField.contains("="))
        assertArrayEquals(envelope.payload, Base64.decodeStandard(payloadField))
    }
}