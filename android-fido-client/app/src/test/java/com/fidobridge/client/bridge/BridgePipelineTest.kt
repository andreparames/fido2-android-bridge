package com.fidobridge.client.bridge

import com.fidobridge.client.crypto.NoiseSession
import com.fidobridge.client.ctap.CredentialStore
import com.fidobridge.client.ctap.Ctap2Processor
import com.fidobridge.client.ctap.GeneratedCredential
import com.fidobridge.client.ctap.KeyGenerator
import com.fidobridge.client.ctap.PublicKeyCoords
import com.fidobridge.client.ctap.Signer
import com.fidobridge.client.ctap.StoredCredential
import com.fidobridge.client.networking.FakeRelayTransport
import com.fidobridge.client.networking.TestRelayPeer
import com.fidobridge.client.pairing.FakeIdentityStore
import com.fidobridge.client.protocol.MessageCodec
import com.fidobridge.client.protocol.PlaintextEnvelope
import com.fidobridge.client.protocol.Protocol
import com.fidobridge.client.protocol.WireEnvelope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePipelineTest {

    private val channelId = "b".repeat(32)
    private val phonePrivate = ByteArray(32) { it.toByte() }
    private val daemonPrivate = ByteArray(32) { (it + 33).toByte() }
    private val daemonPublic = NoiseSession.staticPublicKey(daemonPrivate)
    private val json = Json { ignoreUnknownKeys = false }

    private fun pairedStore() = FakeIdentityStore(phonePrivate, daemonPublic, channelId)

    private fun newPipeline(
        transport: FakeRelayTransport,
        store: FakeIdentityStore,
        tracker: IntegrityFailureTracker = IntegrityFailureTracker()
    ): BridgePipeline {
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), FakeSigner())
        return BridgePipeline(
            store,
            "ws://localhost:8000/connection/websocket",
            processor,
            transportFactory = { _, _, _ -> transport },
            securityFailureTracker = tracker
        )
    }

    private suspend fun awaitConnected(pipeline: BridgePipeline) {
        withTimeout(5000) { while (pipeline.state.value != BridgeState.Connected) delay(10) }
    }

    private suspend fun awaitPublishedData(peer: TestRelayPeer): ByteArray {
        var data: ByteArray? = null
        withTimeout(5000) {
            while (data == null) {
                data = peer.decryptPublishedData().lastOrNull()
                if (data == null) yield()
            }
        }
        return data!!
    }

    @Test
    fun `start connects and emits connected state`() = runBlocking {
        val transport = FakeRelayTransport()
        val pipeline = newPipeline(transport, pairedStore())

        pipeline.start()
        awaitConnected(pipeline)
        assertEquals(BridgeState.Connected, pipeline.state.value)
        pipeline.stop()
    }

    @Test
    fun `inbound getAssertion signs and publishes assertionResult`() = runBlocking {
        val transport = FakeRelayTransport()
        val signer = FakeSigner()
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), signer)
        val pipeline = BridgePipeline(pairedStore(), "ws://localhost:8000/connection/websocket", processor, transportFactory = { _, _, _ -> transport })
        val daemon = TestRelayPeer(transport, channelId, daemonPrivate)

        pipeline.start()
        awaitConnected(pipeline)
        daemon.completeHandshake()

        daemon.sendRequest(getAssertionEnvelope("req-1"))

        val envelope = json.decodeFromString(PlaintextEnvelope.serializer(), awaitPublishedData(daemon).decodeToString())
        assertEquals("assertionResult", envelope.type)
        assertEquals("req-1", envelope.id)
        assertNotNull(signer.data)
        assertEquals("example.com", signer.rpId)
        pipeline.stop()
    }

    @Test
    fun `signer failure publishes operationDenied error`() = runBlocking {
        val transport = FakeRelayTransport()
        val signer = FakeSigner(fail = true)
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), signer)
        val pipeline = BridgePipeline(pairedStore(), "ws://localhost:8000/connection/websocket", processor, transportFactory = { _, _, _ -> transport })
        val daemon = TestRelayPeer(transport, channelId, daemonPrivate)

        pipeline.start()
        awaitConnected(pipeline)
        daemon.completeHandshake()

        daemon.sendRequest(getAssertionEnvelope("req-2"))

        val envelope = json.decodeFromString(PlaintextEnvelope.serializer(), awaitPublishedData(daemon).decodeToString())
        assertEquals("error", envelope.type)
        val code = (envelope.payload["code"] as JsonPrimitive).content
        assertEquals(0x27, code.toInt())
        pipeline.stop()
    }

    @Test
    fun `reconnect re-establishes the pipeline after a disconnect`() = runBlocking {
        val transport = FakeRelayTransport()
        val signer = FakeSigner()
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), signer)
        val pipeline = BridgePipeline(
            pairedStore(), "ws://localhost:8000/connection/websocket", processor,
            transportFactory = { _, _, _ -> transport }
        )

        pipeline.start()
        awaitConnected(pipeline)
        TestRelayPeer(transport, channelId, daemonPrivate).completeHandshake()

        transport.simulateDisconnect()
        withTimeout(5000) { while (pipeline.state.value != BridgeState.Disconnected) delay(10) }

        pipeline.reconnect()
        awaitConnected(pipeline)

        val daemon2 = TestRelayPeer(transport, channelId, daemonPrivate)
        daemon2.completeHandshake()
        daemon2.sendRequest(getAssertionEnvelope("req-after-reconnect"))

        val envelope = json.decodeFromString(PlaintextEnvelope.serializer(), awaitPublishedData(daemon2).decodeToString())
        assertEquals("assertionResult", envelope.type)
        assertEquals("req-after-reconnect", envelope.id)
        pipeline.stop()
    }

    @Test
    fun `not paired emits error state`() = runBlocking {
        val transport = FakeRelayTransport()
        val pipeline = newPipeline(transport, FakeIdentityStore(null, null, null))

        pipeline.start()

        withTimeout(5000) {
            while (pipeline.state.value !is BridgeState.Error) delay(10)
        }
        assertTrue(pipeline.state.value is BridgeState.Error)
    }

    @Test
    fun `single integrity failure stays quiet`() = runBlocking {
        val transport = FakeRelayTransport()
        val tracker = IntegrityFailureTracker(threshold = 2)
        val pipeline = newPipeline(transport, pairedStore(), tracker)
        val daemon = TestRelayPeer(transport, channelId, daemonPrivate)

        pipeline.start()
        awaitConnected(pipeline)
        daemon.completeHandshake()

        simulateTamper(transport, daemon, "req-blip")

        delay(100)
        assertEquals(BridgeState.Connected, pipeline.state.value)
        pipeline.stop()
    }

    @Test
    fun `repeated integrity failures within the window raise the security alert`() = runBlocking {
        val transport = FakeRelayTransport()
        val pipeline = newPipeline(transport, pairedStore())
        val daemon = TestRelayPeer(transport, channelId, daemonPrivate)

        pipeline.start()
        awaitConnected(pipeline)
        daemon.completeHandshake()

        repeat(3) { simulateTamper(transport, daemon, "req-$it") }

        withTimeout(5000) { while (pipeline.state.value != BridgeState.SecurityAlert) delay(10) }
        assertEquals(BridgeState.SecurityAlert, pipeline.state.value)
        pipeline.stop()
    }

    @Test
    fun `security alert is sticky across a reconnect`() = runBlocking {
        val transport = FakeRelayTransport()
        val tracker = IntegrityFailureTracker(threshold = 1)
        val pipeline = newPipeline(transport, pairedStore(), tracker)
        val daemon = TestRelayPeer(transport, channelId, daemonPrivate)

        pipeline.start()
        awaitConnected(pipeline)
        daemon.completeHandshake()
        simulateTamper(transport, daemon, "req-sticky")
        withTimeout(5000) { while (pipeline.state.value != BridgeState.SecurityAlert) delay(10) }

        transport.simulateDisconnect()
        transport.connect()

        delay(100)
        assertEquals(BridgeState.SecurityAlert, pipeline.state.value)
        pipeline.stop()
    }

    @Test
    fun `acknowledging the security alert resumes the normal state`() = runBlocking {
        val transport = FakeRelayTransport()
        val tracker = IntegrityFailureTracker(threshold = 1)
        val pipeline = newPipeline(transport, pairedStore(), tracker)
        val daemon = TestRelayPeer(transport, channelId, daemonPrivate)

        pipeline.start()
        awaitConnected(pipeline)
        daemon.completeHandshake()
        simulateTamper(transport, daemon, "req-ack")
        withTimeout(5000) { while (pipeline.state.value != BridgeState.SecurityAlert) delay(10) }

        pipeline.acknowledgeSecurityAlert()

        assertEquals(BridgeState.Connected, pipeline.state.value)
        pipeline.stop()
    }

    @Test
    fun `publish failures do not raise the security alert`() = runBlocking {
        val transport = FakeRelayTransport()
        transport.publishFails = true
        val pipeline = newPipeline(transport, pairedStore())
        val daemon = TestRelayPeer(transport, channelId, daemonPrivate)

        pipeline.start()
        awaitConnected(pipeline)
        daemon.completeHandshake()

        repeat(3) { daemon.sendRequest(getAssertionEnvelope("req-pub-$it")) }

        delay(200)
        assertEquals(BridgeState.Connected, pipeline.state.value)
        pipeline.stop()
    }

    @Test
    fun `reconnect preserves an active security alert`() = runBlocking {
        val transport = FakeRelayTransport()
        val tracker = IntegrityFailureTracker(threshold = 1)
        val pipeline = newPipeline(transport, pairedStore(), tracker)
        val daemon = TestRelayPeer(transport, channelId, daemonPrivate)

        pipeline.start()
        awaitConnected(pipeline)
        daemon.completeHandshake()
        simulateTamper(transport, daemon, "req-preserve")
        withTimeout(5000) { while (pipeline.state.value != BridgeState.SecurityAlert) delay(10) }

        pipeline.reconnect()

        delay(100)
        assertEquals(BridgeState.SecurityAlert, pipeline.state.value)
        pipeline.stop()
    }

    private fun simulateTamper(transport: FakeRelayTransport, daemon: TestRelayPeer, id: String) {
        val ciphertext = daemon.encryptForPhone(getAssertionEnvelope(id))
        val tampered = ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        transport.simulatePublication(
            MessageCodec.encode(WireEnvelope(channelId, Protocol.KIND_DATA, tampered)).toByteArray()
        )
    }

    private fun getAssertionEnvelope(id: String): ByteArray = (
        """{"version":3,"type":"getAssertion","id":"$id","payload":{""" +
            """"clientDataHash":"$CLIENT_DATA_HASH_B64","rpId":"example.com",""" +
            """"allowCredentials":["Y3JlZC0x"]}}"""
        ).toByteArray()

    companion object {
        private const val CLIENT_DATA_HASH_B64 = "ERERERERERERERERERERERERERERERERERERERERERE="
    }
}

private class FakeCredentialStore : CredentialStore {
    val credentials = mutableListOf<StoredCredential>()

    init {
        credentials.add(
            StoredCredential(
                rpId = "example.com",
                alias = "pre-seeded",
                credentialId = "cred-1".toByteArray(),
                publicKey = PublicKeyCoords(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { (it + 33).toByte() }),
                userHandle = "user-1".toByteArray()
            )
        )
    }

    override fun findForRpId(rpId: String) = credentials.filter { it.rpId == rpId }
    override fun findByCredentialId(rpId: String, credentialId: ByteArray) =
        credentials.firstOrNull { it.rpId == rpId && it.credentialId.contentEquals(credentialId) }
    override fun add(credential: StoredCredential) {
        credentials.add(credential)
    }

    override fun clear(): Boolean {
        credentials.clear()
        return true
    }
}

private class FakeKeyGenerator : KeyGenerator {
    var counter = 0
    override fun generate(): GeneratedCredential {
        counter++
        return GeneratedCredential(
            alias = "alias-$counter",
            credentialId = ByteArray(16) { counter.toByte() },
            publicKey = PublicKeyCoords(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { (it + 33).toByte() })
        )
    }
}

private class FakeSigner(var fail: Boolean = false) : Signer {
    var rpId: String? = null
    var alias: String? = null
    var data: ByteArray? = null

    override fun sign(rpId: String, keyAlias: String, data: ByteArray, onResult: (Result<ByteArray>) -> Unit) {
        this.rpId = rpId
        this.alias = keyAlias
        this.data = data
        if (fail) {
            onResult(Result.failure(Exception("denied")))
        } else {
            onResult(Result.success(ByteArray(32) { 0x77.toByte() }))
        }
    }
}