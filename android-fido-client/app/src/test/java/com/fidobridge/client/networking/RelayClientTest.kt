package com.fidobridge.client.networking

import com.fidobridge.client.crypto.AesGcmCipher
import com.fidobridge.client.crypto.EncryptedMessage
import com.fidobridge.client.crypto.SessionKey
import com.fidobridge.client.protocol.MessageCodec
import com.fidobridge.client.protocol.WireMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelayClientTest {

    private val channelId = "a".repeat(32)
    private val cipher = AesGcmCipher(SessionKey.fromBytes(ByteArray(32) { it.toByte() }))

    private fun envelope(type: String, id: String): ByteArray =
        """{"version":1,"type":"$type","id":"$id","payload":{}}""".toByteArray()

    private fun wire(plaintext: ByteArray, channelId: String = this.channelId): ByteArray {
        val sealed = cipher.encrypt(plaintext)
        return MessageCodec.encode(WireMessage(channelId, sealed.nonce, sealed.ciphertext, sealed.tag)).toByteArray()
    }

    private fun relayClient(transport: FakeRelayTransport) =
        RelayClient(transport, channelId, cipher)

    @Test
    fun `send encrypts before publishing`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        client.connect()

        val plaintext = "super-secret".toByteArray()
        val sent = client.send(plaintext)

        assertEquals(true, sent)
        assertEquals(1, transport.published.size)

        val raw = transport.published.first().decodeToString()
        assertFalse(raw.contains("super-secret"))

        val decoded = MessageCodec.decode(raw)
        val decrypted = cipher.decrypt(EncryptedMessage(decoded.nonce, decoded.ciphertext, decoded.tag))
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `inbound publication is decrypted and emitted`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        client.connect()

        val payload = envelope("ping", "id-1")
        transport.simulatePublication(wire(payload))

        val received = withTimeout(5000) { client.inbound.first() }
        assertArrayEquals(payload, received)
    }

    @Test
    fun `tag failure drops message and raises security alert`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        client.connect()

        val good = MessageCodec.decode(wire(envelope("ping", "id-1")).decodeToString())
        val tamperedTag = good.tag.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val bad = MessageCodec.encode(WireMessage(good.channelId, good.nonce, good.ciphertext, tamperedTag))

        transport.simulatePublication(bad.toByteArray())

        val alert = withTimeout(5000) { client.securityAlerts.first() }
        assertNotNull(alert)
        val inbound = withTimeoutOrNull(1000) { client.inbound.first() }
        assertNull(inbound)
    }

    @Test
    fun `channel_id mismatch raises security alert`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        client.connect()

        transport.simulatePublication(wire(envelope("ping", "id-1"), channelId = "b".repeat(32)))

        val alert = withTimeout(5000) { client.securityAlerts.first() }
        assertNotNull(alert)
    }

    @Test
    fun `replay is dropped and answered with operation denied`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        val received = mutableListOf<ByteArray>()
        val collector = launch { client.inbound.collect { received.add(it) } }

        client.connect()

        val payload = envelope("getAssertion", "id-1")
        transport.simulatePublication(wire(payload))
        transport.simulatePublication(wire(payload))

        withTimeout(5000) { while (received.isEmpty()) yield() }
        assertEquals(1, received.size)
        assertArrayEquals(payload, received[0])

        withTimeout(5000) { while (transport.published.isEmpty()) yield() }
        val rawError = transport.published.first().decodeToString()
        val decoded = MessageCodec.decode(rawError)
        val decrypted = cipher.decrypt(EncryptedMessage(decoded.nonce, decoded.ciphertext, decoded.tag))
        val errorEnvelope = Json.parseToJsonElement(decrypted.decodeToString()).jsonObject

        assertEquals("error", errorEnvelope["type"]!!.jsonPrimitive.content)
        assertEquals(39, errorEnvelope["payload"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        collector.cancel()
    }

    @Test
    fun `disconnect emits a disconnection and updates state`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        client.connect()

        assertEquals(RelayClient.ConnectionState.CONNECTED, client.state.value)

        transport.simulateDisconnect()

        val disconnect = withTimeout(5000) { client.disconnections.first() }
        assertNotNull(disconnect)
        assertEquals(RelayClient.ConnectionState.DISCONNECTED, client.state.value)
    }
}
