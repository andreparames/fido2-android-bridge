package com.fidobridge.client.networking

import com.fidobridge.client.crypto.NoiseSession
import com.fidobridge.client.protocol.MessageCodec
import com.fidobridge.client.protocol.Protocol
import com.fidobridge.client.protocol.WireEnvelope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelayClientTest {

    private val channelId = "a".repeat(32)
    private val phonePrivate = ByteArray(32) { it.toByte() }
    private val daemonPrivate = ByteArray(32) { (it + 1).toByte() }
    private val daemonPublic = NoiseSession.staticPublicKey(daemonPrivate)

    private fun envelope(type: String, id: String): ByteArray =
        """{"version":3,"type":"$type","id":"$id","payload":{}}""".toByteArray()

    private fun relayClient(transport: FakeRelayTransport) =
        RelayClient(transport, channelId, phonePrivate, daemonPublic)

    private fun peer(transport: FakeRelayTransport) =
        TestRelayPeer(transport, channelId, daemonPrivate)

    private fun dataEnvelopes(transport: FakeRelayTransport): List<WireEnvelope> =
        transport.published
            .mapNotNull { try { MessageCodec.decode(it.decodeToString()) } catch (e: Exception) { null } }
            .filter { it.kind == Protocol.KIND_DATA }

    @Test
    fun `connect publishes an ik1 handshake message`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        client.connect()

        val ik1 = transport.published
            .mapNotNull { try { MessageCodec.decode(it.decodeToString()) } catch (e: Exception) { null } }
            .first { it.kind == Protocol.KIND_IK1 }
        assertEquals(96, ik1.payload.size) // pinned by PROTOCOL.md §7
    }

    @Test
    fun `send encrypts before publishing`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        val daemon = peer(transport)
        client.connect()
        daemon.completeHandshake()

        val plaintext = "super-secret".toByteArray()
        val sent = client.send(plaintext)

        assertEquals(true, sent)
        val envelope = dataEnvelopes(transport).single()
        assertFalse(envelope.payload.decodeToString().contains("super-secret"))
        assertArrayEquals(plaintext, daemon.decryptPublishedData().single())
    }

    @Test
    fun `inbound publication is decrypted and emitted`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        val daemon = peer(transport)
        client.connect()
        daemon.completeHandshake()

        val payload = envelope("ping", "id-1")
        daemon.sendRequest(payload)

        val received = withTimeout(5000) { client.inbound.first() }
        assertArrayEquals(payload, received)
    }

    @Test
    fun `tag failure drops message and raises security alert`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        val daemon = peer(transport)
        client.connect()
        daemon.completeHandshake()

        val ciphertext = daemon.encryptForPhone(envelope("ping", "id-1"))
        val tampered = ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        transport.simulatePublication(
            MessageCodec.encode(WireEnvelope(channelId, Protocol.KIND_DATA, tampered)).toByteArray()
        )

        val alert = withTimeout(5000) { client.securityAlerts.first() }
        assertNotNull(alert)
        val inbound = withTimeoutOrNull(1000) { client.inbound.first() }
        assertNull(inbound)
    }

    @Test
    fun `replayed frame is rejected by the transport nonce`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        val daemon = peer(transport)
        client.connect()
        daemon.completeHandshake()

        val payload = envelope("getAssertion", "id-1")
        val ciphertext = daemon.encryptForPhone(payload)
        transport.simulatePublication(
            MessageCodec.encode(WireEnvelope(channelId, Protocol.KIND_DATA, ciphertext)).toByteArray()
        )
        assertArrayEquals(payload, withTimeout(5000) { client.inbound.first() })

        // Replaying the same frame fails authentication (Noise nonce counter).
        transport.simulatePublication(
            MessageCodec.encode(WireEnvelope(channelId, Protocol.KIND_DATA, ciphertext)).toByteArray()
        )
        assertNotNull(withTimeout(5000) { client.securityAlerts.first() })
    }

    @Test
    fun `channel_id mismatch raises security alert`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        client.connect()

        transport.simulatePublication(
            MessageCodec.encode(WireEnvelope("b".repeat(32), Protocol.KIND_DATA, ByteArray(0))).toByteArray()
        )

        assertNotNull(withTimeout(5000) { client.securityAlerts.first() })
    }

    @Test
    fun `own published echo is skipped without re-emitting`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        val daemon = peer(transport)
        client.connect()
        daemon.completeHandshake()

        val received = mutableListOf<ByteArray>()
        val collector = launch { client.inbound.collect { received.add(it) } }

        client.send(envelope("assertionResult", "id-echo"))

        // Centrifugo echoes the phone's own publication back with exact bytes.
        val echo = transport.published.last()
        transport.simulatePublication(echo)

        yield()
        assertEquals(0, received.size)
        collector.cancel()
    }

    @Test
    fun `disconnect emits a disconnection and updates state`() = runBlocking {
        val transport = FakeRelayTransport()
        val client = relayClient(transport)
        client.connect()

        assertEquals(RelayClient.ConnectionState.CONNECTED, client.state.value)

        transport.simulateDisconnect()

        assertNotNull(withTimeout(5000) { client.disconnections.first() })
        assertEquals(RelayClient.ConnectionState.DISCONNECTED, client.state.value)
    }
}