package com.fidobridge.client.ctap

import com.fidobridge.client.protocol.Protocol
import com.fidobridge.client.ui.model.InMemoryRequestLog
import com.fidobridge.client.ui.model.RequestOutcome
import com.fidobridge.client.ui.model.RequestType
import com.fidobridge.client.util.Base64
import com.upokecenter.cbor.CBORObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ctap2ProcessorTest {

    private val clientDataHash = ByteArray(32) { 0x11.toByte() }
    private val clientDataHashB64 = Base64.encodeStandard(clientDataHash)
    private val fixedSignature = ByteArray(32) { 0x77.toByte() }

    private fun request(type: String, id: String, payload: String): ByteArray =
        """{"version":${Protocol.VERSION},"type":"$type","id":"$id","payload":$payload}""".toByteArray()

    private fun processSync(processor: Ctap2Processor, request: ByteArray): ByteArray {
        var result: Result<ByteArray>? = null
        processor.process(request) { result = it }
        return result!!.getOrThrow()
    }

    private fun parseEnvelope(bytes: ByteArray) =
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun errorCode(bytes: ByteArray): Int =
        parseEnvelope(bytes)["payload"]!!.jsonObject["code"]!!.jsonPrimitive.int

    @Test
    fun `getAssertion with matching credential returns assertion result`() {
        val store = FakeCredentialStore()
        val keyGen = FakeKeyGenerator()
        val signer = FakeSigner()
        val processor = Ctap2Processor(store, keyGen, signer)

        val generated = keyGen.generate()
        store.add(
            StoredCredential("example.com", generated.alias, generated.credentialId, generated.publicKey, "user1".toByteArray())
        )

        val response = processSync(
            processor,
            request("getAssertion", "req-1", """{"clientDataHash":"$clientDataHashB64","rpId":"example.com"}""")
        )
        val env = parseEnvelope(response)

        assertEquals("assertionResult", env["type"]!!.jsonPrimitive.content)
        val payload = env["payload"]!!.jsonObject
        assertEquals(Base64.encodeStandard(generated.credentialId), payload["credentialId"]!!.jsonPrimitive.content)
        assertEquals(Base64.encodeStandard(fixedSignature), payload["signature"]!!.jsonPrimitive.content)
        assertEquals(Base64.encodeStandard("user1".toByteArray()), payload["userHandle"]!!.jsonPrimitive.content)

        val authData = Base64.decodeStandard(payload["authenticatorData"]!!.jsonPrimitive.content)
        assertEquals(37, authData.size)
        assertEquals(0x05, authData[32].toInt() and 0xff)

        assertArrayEquals(authData + clientDataHash, signer.data)
        assertEquals("example.com", signer.rpId)
        assertEquals(generated.alias, signer.alias)
    }

    @Test
    fun `makeCredential returns result with fmt none attestation object`() {
        val store = FakeCredentialStore()
        val keyGen = FakeKeyGenerator()
        val signer = FakeSigner()
        val processor = Ctap2Processor(store, keyGen, signer)

        val userId = Base64.encodeStandard("user-1".toByteArray())
        val response = processSync(
            processor,
            request(
                "makeCredential", "req-2",
                """{"clientDataHash":"$clientDataHashB64","rpId":"example.com","user":{"id":"$userId","name":"alice","displayName":"Alice"}}"""
            )
        )
        val env = parseEnvelope(response)

        assertEquals("makeCredentialResult", env["type"]!!.jsonPrimitive.content)
        val payload = env["payload"]!!.jsonObject

        val authData = Base64.decodeStandard(payload["authenticatorData"]!!.jsonPrimitive.content)
        assertEquals(0x45, authData[32].toInt() and 0xff)

        val attestationObject = CBORObject.DecodeFromBytes(Base64.decodeStandard(payload["attestationObject"]!!.jsonPrimitive.content))
        assertEquals("none", attestationObject.get("fmt").AsString())
        assertEquals(0, attestationObject.get("attStmt").size())

        assertEquals(1, store.credentials.size)
        assertEquals("example.com", store.credentials[0].rpId)
        assertArrayEquals("user-1".toByteArray(), store.credentials[0].userHandle!!)

        assertArrayEquals(authData + clientDataHash, signer.data)
    }

    @Test
    fun `rpId mismatch returns no credentials`() {
        val store = FakeCredentialStore()
        val keyGen = FakeKeyGenerator()
        val processor = Ctap2Processor(store, keyGen, FakeSigner())
        val generated = keyGen.generate()
        store.add(StoredCredential("example.com", generated.alias, generated.credentialId, generated.publicKey))

        val response = processSync(
            processor,
            request("getAssertion", "req-3", """{"clientDataHash":"$clientDataHashB64","rpId":"evil.com"}""")
        )

        assertEquals(46, errorCode(response))
    }

    @Test
    fun `unknown allowCredential returns no credentials`() {
        val store = FakeCredentialStore()
        val keyGen = FakeKeyGenerator()
        val processor = Ctap2Processor(store, keyGen, FakeSigner())
        val generated = keyGen.generate()
        store.add(StoredCredential("example.com", generated.alias, generated.credentialId, generated.publicKey))

        val unknown = Base64.encodeStandard(byteArrayOf(1, 2, 3, 4))
        val response = processSync(
            processor,
            request(
                "getAssertion", "req-4",
                """{"clientDataHash":"$clientDataHashB64","rpId":"example.com","allowCredentials":["$unknown"]}"""
            )
        )

        assertEquals(46, errorCode(response))
    }

    @Test
    fun `empty allowCredentials with multiple credentials returns no credentials`() {
        val store = FakeCredentialStore()
        val keyGen = FakeKeyGenerator()
        val processor = Ctap2Processor(store, keyGen, FakeSigner())
        repeat(2) {
            val g = keyGen.generate()
            store.add(StoredCredential("example.com", g.alias, g.credentialId, g.publicKey))
        }

        val response = processSync(
            processor,
            request("getAssertion", "req-5", """{"clientDataHash":"$clientDataHashB64","rpId":"example.com"}""")
        )

        assertEquals(46, errorCode(response))
    }

    @Test
    fun `pubKeyCredParams without ES256 returns invalid option`() {
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), FakeSigner())
        val userId = Base64.encodeStandard("user-1".toByteArray())

        val response = processSync(
            processor,
            request(
                "makeCredential", "req-6",
                """{"clientDataHash":"$clientDataHashB64","rpId":"example.com","user":{"id":"$userId","name":"a","displayName":"A"},"pubKeyCredParams":[{"alg":-8}]}"""
            )
        )

        assertEquals(38, errorCode(response))
    }

    @Test
    fun `excludeCredentials match returns operation denied`() {
        val store = FakeCredentialStore()
        val keyGen = FakeKeyGenerator()
        val processor = Ctap2Processor(store, keyGen, FakeSigner())
        val generated = keyGen.generate()
        store.add(StoredCredential("example.com", generated.alias, generated.credentialId, generated.publicKey))

        val excluded = Base64.encodeStandard(generated.credentialId)
        val userId = Base64.encodeStandard("user-1".toByteArray())
        val response = processSync(
            processor,
            request(
                "makeCredential", "req-7",
                """{"clientDataHash":"$clientDataHashB64","rpId":"example.com","user":{"id":"$userId","name":"a","displayName":"A"},"excludeCredentials":["$excluded"]}"""
            )
        )

        assertEquals(39, errorCode(response))
    }

    @Test
    fun `dummy rp makeCredential probe is answered but not persisted`() {
        val store = FakeCredentialStore()
        val keyGen = FakeKeyGenerator()
        val processor = Ctap2Processor(store, keyGen, FakeSigner())

        val userId = Base64.encodeStandard("dummy".toByteArray())
        val response = processSync(
            processor,
            request(
                "makeCredential", "req-dummy",
                """{"clientDataHash":"$clientDataHashB64","rpId":".dummy","user":{"id":"$userId","name":"dummy","displayName":"dummy"}}"""
            )
        )
        val env = parseEnvelope(response)

        assertEquals("makeCredentialResult", env["type"]!!.jsonPrimitive.content)
        assertEquals(0, store.credentials.size)
    }

    @Test
    fun `successful getAssertion is recorded as accepted sign-in`() {
        val store = FakeCredentialStore()
        val keyGen = FakeKeyGenerator()
        val log = InMemoryRequestLog()
        val processor = Ctap2Processor(store, keyGen, FakeSigner(), log)
        val generated = keyGen.generate()
        store.add(StoredCredential("example.com", generated.alias, generated.credentialId, generated.publicKey))

        processSync(
            processor,
            request("getAssertion", "req-accept", """{"clientDataHash":"$clientDataHashB64","rpId":"example.com"}""")
        )

        val record = log.records.value.single()
        assertEquals("req-accept", record.id)
        assertEquals(RequestType.SIGN_IN, record.type)
        assertEquals("example.com", record.rpId)
        assertEquals(RequestOutcome.ACCEPTED, record.outcome)
    }

    @Test
    fun `getAssertion with denied signer is recorded as rejected`() {
        val store = FakeCredentialStore()
        val keyGen = FakeKeyGenerator()
        val signer = FakeSigner()
        signer.fail = true
        val log = InMemoryRequestLog()
        val processor = Ctap2Processor(store, keyGen, signer, log)
        val generated = keyGen.generate()
        store.add(StoredCredential("example.com", generated.alias, generated.credentialId, generated.publicKey))

        processSync(
            processor,
            request("getAssertion", "req-deny", """{"clientDataHash":"$clientDataHashB64","rpId":"example.com"}""")
        )

        assertEquals(RequestOutcome.REJECTED, log.records.value.single().outcome)
    }

    @Test
    fun `getAssertion with no matching credential is recorded as rejected`() {
        val log = InMemoryRequestLog()
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), FakeSigner(), log)

        processSync(
            processor,
            request("getAssertion", "req-none", """{"clientDataHash":"$clientDataHashB64","rpId":"nobody.com"}""")
        )

        assertEquals(RequestOutcome.REJECTED, log.records.value.single().outcome)
    }

    @Test
    fun `successful makeCredential is recorded as accepted register`() {
        val log = InMemoryRequestLog()
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), FakeSigner(), log)
        val userId = Base64.encodeStandard("user-1".toByteArray())

        processSync(
            processor,
            request(
                "makeCredential", "req-reg",
                """{"clientDataHash":"$clientDataHashB64","rpId":"example.com","user":{"id":"$userId","name":"alice","displayName":"Alice"}}"""
            )
        )

        val record = log.records.value.single()
        assertEquals(RequestType.REGISTER, record.type)
        assertEquals(RequestOutcome.ACCEPTED, record.outcome)
    }

    @Test
    fun `dummy rp probe is recorded as browser check`() {
        val log = InMemoryRequestLog()
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), FakeSigner(), log)
        val userId = Base64.encodeStandard("dummy".toByteArray())

        processSync(
            processor,
            request(
                "makeCredential", "req-probe",
                """{"clientDataHash":"$clientDataHashB64","rpId":".dummy","user":{"id":"$userId","name":"dummy","displayName":"dummy"}}"""
            )
        )

        val record = log.records.value.single()
        assertEquals(RequestType.BROWSER_CHECK, record.type)
        assertEquals(RequestOutcome.ACCEPTED, record.outcome)
    }

    @Test
    fun `ping is not recorded in the request log`() {
        val log = InMemoryRequestLog()
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), FakeSigner(), log)

        processSync(processor, request("ping", "req-ping", """{}"""))

        assertTrue(log.records.value.isEmpty())
    }

    private class FakeCredentialStore : CredentialStore {
        val credentials = mutableListOf<StoredCredential>()

        override fun findForRpId(rpId: String) = credentials.filter { it.rpId == rpId }

        override fun findByCredentialId(rpId: String, credentialId: ByteArray) =
            credentials.firstOrNull { it.rpId == rpId && it.credentialId.contentEquals(credentialId) }

        override fun add(credential: StoredCredential) {
            credentials.add(credential)
        }

        override fun clear() {
            credentials.clear()
        }
    }

    private class FakeKeyGenerator : KeyGenerator {
        var counter = 0

        override fun generate(): GeneratedCredential {
            counter++
            return GeneratedCredential(
                alias = "alias-$counter",
                credentialId = ByteArray(16) { counter.toByte() },
                publicKey = PublicKeyCoords(
                    x = ByteArray(32) { (it + 1).toByte() },
                    y = ByteArray(32) { (it + 33).toByte() }
                )
            )
        }
    }

    private class FakeSigner : Signer {
        var rpId: String? = null
        var alias: String? = null
        var data: ByteArray? = null
        var fail = false

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
}
