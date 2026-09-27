package com.fidobridge.client.ctap

import com.fidobridge.client.util.Base64
import com.upokecenter.cbor.CBORObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ctap2ProcessorTest {

    private val clientDataHash = ByteArray(32) { 0x11.toByte() }
    private val clientDataHashB64 = Base64.encodeStandard(clientDataHash)
    private val fixedSignature = ByteArray(32) { 0x77.toByte() }

    private fun request(type: String, id: String, payload: String): ByteArray =
        """{"version":1,"type":"$type","id":"$id","payload":$payload}""".toByteArray()

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
    fun `rpId mismatch returns operation denied`() {
        val store = FakeCredentialStore()
        val keyGen = FakeKeyGenerator()
        val processor = Ctap2Processor(store, keyGen, FakeSigner())
        val generated = keyGen.generate()
        store.add(StoredCredential("example.com", generated.alias, generated.credentialId, generated.publicKey))

        val response = processSync(
            processor,
            request("getAssertion", "req-3", """{"clientDataHash":"$clientDataHashB64","rpId":"evil.com"}""")
        )

        assertEquals(39, errorCode(response))
    }

    @Test
    fun `unknown allowCredential returns operation denied`() {
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

        assertEquals(39, errorCode(response))
    }

    @Test
    fun `empty allowCredentials with multiple credentials returns operation denied`() {
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

        assertEquals(39, errorCode(response))
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

    private class FakeCredentialStore : CredentialStore {
        val credentials = mutableListOf<StoredCredential>()

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
