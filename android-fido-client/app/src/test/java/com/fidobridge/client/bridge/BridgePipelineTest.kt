package com.fidobridge.client.bridge

import com.fidobridge.client.crypto.AesGcmCipher
import com.fidobridge.client.crypto.EncryptedMessage
import com.fidobridge.client.crypto.SessionKey
import com.fidobridge.client.ctap.CredentialStore
import com.fidobridge.client.ctap.Ctap2Processor
import com.fidobridge.client.ctap.GeneratedCredential
import com.fidobridge.client.ctap.KeyGenerator
import com.fidobridge.client.ctap.PublicKeyCoords
import com.fidobridge.client.ctap.Signer
import com.fidobridge.client.ctap.StoredCredential
import com.fidobridge.client.networking.FakeRelayTransport
import com.fidobridge.client.pairing.SessionKeyStore
import com.fidobridge.client.protocol.MessageCodec
import com.fidobridge.client.protocol.PlaintextEnvelope
import com.fidobridge.client.protocol.WireMessage
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
    private val key = SessionKey.fromBytes(ByteArray(32) { it.toByte() })
    private val cipher = AesGcmCipher(key)
    private val json = Json { ignoreUnknownKeys = false }

    private fun newPipeline(transport: FakeRelayTransport, store: SessionKeyStore): BridgePipeline {
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), FakeSigner())
        return BridgePipeline(store, "ws://localhost:8000/connection/websocket", processor, transportFactory = { _, _, _ -> transport })
    }

    private fun wire(plaintext: ByteArray): ByteArray {
        val sealed = cipher.encrypt(plaintext)
        return MessageCodec.encode(WireMessage(channelId, sealed.nonce, sealed.ciphertext, sealed.tag)).toByteArray()
    }

    private fun publishedEnvelope(transport: FakeRelayTransport): PlaintextEnvelope {
        val decoded = MessageCodec.decode(transport.published.last().decodeToString())
        val plaintext = cipher.decrypt(EncryptedMessage(decoded.nonce, decoded.ciphertext, decoded.tag))
        return json.decodeFromString(PlaintextEnvelope.serializer(), plaintext.decodeToString())
    }

    @Test
    fun `start connects and emits connected state`() = runBlocking {
        val transport = FakeRelayTransport()
        val pipeline = newPipeline(transport, FakeSessionKeyStore(key, channelId))

        pipeline.start()

        withTimeout(5000) { while (pipeline.state.value != BridgeState.Connected) delay(10) }
        assertEquals(BridgeState.Connected, pipeline.state.value)
        pipeline.stop()
    }

    @Test
    fun `inbound getAssertion signs and publishes assertionResult`() = runBlocking {
        val transport = FakeRelayTransport()
        val signer = FakeSigner()
        val store = FakeSessionKeyStore(key, channelId)
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), signer)
        val pipeline = BridgePipeline(store, "ws://localhost:8000/connection/websocket", processor, transportFactory = { _, _, _ -> transport })

        pipeline.start()
        withTimeout(5000) { while (pipeline.state.value != BridgeState.Connected) delay(10) }

        val request = getAssertionEnvelope("req-1")
        transport.simulatePublication(wire(request))

        withTimeout(5000) { while (transport.published.isEmpty()) yield() }

        val envelope = publishedEnvelope(transport)
        assertEquals("assertionResult", envelope.type)
        assertEquals("req-1", envelope.id)
        assertNotNull(signer.data)
        assertEquals("example.com", signer.rpId)
        pipeline.stop()
    }

    @Test
    fun `signer failure publishes operationDenied error`() = runBlocking {
        val transport = FakeRelayTransport()
        val store = FakeSessionKeyStore(key, channelId)
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), FakeSigner(fail = true))
        val pipeline = BridgePipeline(store, "ws://localhost:8000/connection/websocket", processor, transportFactory = { _, _, _ -> transport })

        pipeline.start()
        withTimeout(5000) { while (pipeline.state.value != BridgeState.Connected) delay(10) }

        transport.simulatePublication(wire(getAssertionEnvelope("req-2")))

        withTimeout(5000) { while (transport.published.isEmpty()) yield() }

        val envelope = publishedEnvelope(transport)
        assertEquals("error", envelope.type)
        val code = (envelope.payload["code"] as JsonPrimitive).content
        assertEquals(0x27, code.toInt())
        pipeline.stop()
    }

    @Test
    fun `not paired emits error state`() = runBlocking {
        val transport = FakeRelayTransport()
        val pipeline = newPipeline(transport, FakeSessionKeyStore(null, null))

        pipeline.start()

        withTimeout(5000) {
            while (pipeline.state.value !is BridgeState.Error) delay(10)
        }
        assertTrue(pipeline.state.value is BridgeState.Error)
    }

    @Test
    fun `GCM tag tamper emits security alert`() = runBlocking {
        val transport = FakeRelayTransport()
        val pipeline = newPipeline(transport, FakeSessionKeyStore(key, channelId))

        pipeline.start()
        withTimeout(5000) { while (pipeline.state.value != BridgeState.Connected) delay(10) }

        val good = MessageCodec.decode(wire(getAssertionEnvelope("req-3")).decodeToString())
        val tamperedTag = good.tag.copyOf().also { it[0] = (it[0] + 1).toByte() }
        transport.simulatePublication(
            MessageCodec.encode(WireMessage(good.channelId, good.nonce, good.ciphertext, tamperedTag)).toByteArray()
        )

        withTimeout(5000) { while (pipeline.state.value != BridgeState.SecurityAlert) delay(10) }
        assertEquals(BridgeState.SecurityAlert, pipeline.state.value)
        pipeline.stop()
    }

    private fun getAssertionEnvelope(id: String): ByteArray = (
        """{"version":2,"type":"getAssertion","id":"$id","payload":{""" +
            """"clientDataHash":"$CLIENT_DATA_HASH_B64","rpId":"example.com",""" +
            """"allowCredentials":["Y3JlZC0x"]}}"""
        ).toByteArray()

    companion object {
        private const val CLIENT_DATA_HASH_B64 = "ERERERERERERERERERERERERERERERERERERERERERE="
    }
}

private class FakeSessionKeyStore(
    private val storedKey: SessionKey?,
    private val storedChannelId: String?
) : SessionKeyStore {
    override fun save(key: SessionKey, channelId: String) = Unit
    override fun loadKey(): SessionKey? = storedKey
    override fun loadChannelId(): String? = storedChannelId
    override fun saveRelayToken(token: String?) = Unit
    override fun loadRelayToken(): String? = null
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