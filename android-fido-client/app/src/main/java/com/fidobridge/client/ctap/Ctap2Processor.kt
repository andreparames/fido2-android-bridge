package com.fidobridge.client.ctap

import com.fidobridge.client.protocol.Ctap2Status
import com.fidobridge.client.protocol.PlaintextEnvelope
import com.fidobridge.client.protocol.Protocol
import com.fidobridge.client.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class Ctap2Processor(
    private val credentialStore: CredentialStore,
    private val keyGenerator: KeyGenerator,
    private val signer: Signer,
    private val json: Json = Json { ignoreUnknownKeys = false }
) {

    fun process(plaintext: ByteArray, onResult: (Result<ByteArray>) -> Unit) {
        val envelope = try {
            json.decodeFromString(PlaintextEnvelope.serializer(), plaintext.decodeToString())
        } catch (e: Exception) {
            onResult(Result.success(buildError("", Ctap2Status.CTAP2_ERR_CMD_NOT_SUPPORTED)))
            return
        }

        if (envelope.version != Protocol.VERSION) {
            onResult(Result.success(buildError(envelope.id, Ctap2Status.VERSION_MISMATCH)))
            return
        }

        when (envelope.type) {
            TYPE_GET_ASSERTION -> handleGetAssertion(envelope, onResult)
            TYPE_MAKE_CREDENTIAL -> handleMakeCredential(envelope, onResult)
            TYPE_PING -> onResult(Result.success(buildPing(envelope.id)))
            else -> onResult(Result.success(buildError(envelope.id, Ctap2Status.CTAP2_ERR_CMD_NOT_SUPPORTED)))
        }
    }

    private fun handleGetAssertion(envelope: PlaintextEnvelope, onResult: (Result<ByteArray>) -> Unit) {
        val payload = try {
            json.decodeFromJsonElement<GetAssertionPayload>(envelope.payload)
        } catch (e: Exception) {
            onResult(Result.success(buildError(envelope.id, Ctap2Status.CTAP2_ERR_CMD_NOT_SUPPORTED)))
            return
        }

        val clientDataHash = decodeHash(payload.clientDataHash)
            ?: return onResult(Result.success(buildError(envelope.id, Ctap2Status.CTAP2_ERR_CMD_NOT_SUPPORTED)))

        val registered = credentialStore.findForRpId(payload.rpId)
        val chosen = resolveCredential(payload.rpId, registered, payload.allowCredentials)
            ?: return onResult(Result.success(buildError(envelope.id, Ctap2Status.CTAP2_ERR_OPERATION_DENIED)))

        val authData = AuthenticatorDataBuilder.buildAssertion(payload.rpId)
        val dataToSign = authData + clientDataHash

        signer.sign(payload.rpId, chosen.alias, dataToSign) { signResult ->
            signResult.fold(
                onSuccess = { signature ->
                    val result = AssertionResultPayload(
                        credentialId = Base64.encodeStandard(chosen.credentialId),
                        authenticatorData = Base64.encodeStandard(authData),
                        signature = Base64.encodeStandard(signature),
                        userHandle = chosen.userHandle?.let { Base64.encodeStandard(it) }
                    )
                    onResult(Result.success(buildResponse(envelope.id, TYPE_ASSERTION_RESULT, result)))
                },
                onFailure = {
                    onResult(Result.success(buildError(envelope.id, Ctap2Status.CTAP2_ERR_OPERATION_DENIED)))
                }
            )
        }
    }

    private fun handleMakeCredential(envelope: PlaintextEnvelope, onResult: (Result<ByteArray>) -> Unit) {
        val payload = try {
            json.decodeFromJsonElement<MakeCredentialPayload>(envelope.payload)
        } catch (e: Exception) {
            onResult(Result.success(buildError(envelope.id, Ctap2Status.CTAP2_ERR_CMD_NOT_SUPPORTED)))
            return
        }

        if (!payload.pubKeyCredParams.any { it.alg == ALG_ES256 }) {
            onResult(Result.success(buildError(envelope.id, Ctap2Status.CTAP2_ERR_INVALID_OPTION)))
            return
        }

        val excluded = payload.excludeCredentials.mapNotNull { decodeCredentialId(it) }
        if (excluded.any { credentialStore.findByCredentialId(payload.rpId, it) != null }) {
            onResult(Result.success(buildError(envelope.id, Ctap2Status.CTAP2_ERR_OPERATION_DENIED)))
            return
        }

        val clientDataHash = decodeHash(payload.clientDataHash)
            ?: return onResult(Result.success(buildError(envelope.id, Ctap2Status.CTAP2_ERR_CMD_NOT_SUPPORTED)))

        val generated = keyGenerator.generate()
        val userHandle = decodeCredentialId(payload.user.id)
        credentialStore.add(
            StoredCredential(
                rpId = payload.rpId,
                alias = generated.alias,
                credentialId = generated.credentialId,
                publicKey = generated.publicKey,
                userHandle = userHandle
            )
        )

        val cosePublicKey = CoseKey.encode(generated.publicKey)
        val authData = AuthenticatorDataBuilder.buildMakeCredential(payload.rpId, generated.credentialId, cosePublicKey)
        val dataToSign = authData + clientDataHash

        signer.sign(payload.rpId, generated.alias, dataToSign) { signResult ->
            signResult.fold(
                onSuccess = { signature ->
                    val result = MakeCredentialResultPayload(
                        credentialId = Base64.encodeStandard(generated.credentialId),
                        authenticatorData = Base64.encodeStandard(authData),
                        attestationObject = Base64.encodeStandard(AttestationObjectBuilder.build(authData)),
                        signature = Base64.encodeStandard(signature)
                    )
                    onResult(Result.success(buildResponse(envelope.id, TYPE_MAKE_CREDENTIAL_RESULT, result)))
                },
                onFailure = {
                    onResult(Result.success(buildError(envelope.id, Ctap2Status.CTAP2_ERR_OPERATION_DENIED)))
                }
            )
        }
    }

    private fun resolveCredential(
        rpId: String,
        registered: List<StoredCredential>,
        allowCredentials: List<String>
    ): StoredCredential? {
        if (registered.isEmpty()) return null

        if (allowCredentials.isEmpty()) {
            return if (registered.size == 1) registered.first() else null
        }

        val allowed = allowCredentials.mapNotNull { decodeCredentialId(it) }
        return allowed.firstNotNullOfOrNull { cid ->
            registered.firstOrNull { it.credentialId.contentEquals(cid) }
        }
    }

    private fun decodeCredentialId(value: String): ByteArray? {
        return try {
            Base64.decodeStandard(value)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun decodeHash(value: String): ByteArray? {
        val decoded = decodeCredentialId(value) ?: return null
        return if (decoded.size == CLIENT_DATA_HASH_BYTES) decoded else null
    }

    private inline fun <reified T> buildResponse(id: String, type: String, payload: T): ByteArray {
        val envelope = buildJsonObject {
            put("version", JsonPrimitive(Protocol.VERSION))
            put("type", JsonPrimitive(type))
            put("id", JsonPrimitive(id))
            put("payload", json.encodeToJsonElement(payload))
        }
        return json.encodeToString(JsonObject.serializer(), envelope).toByteArray()
    }

    private fun buildError(id: String, code: Int): ByteArray {
        val envelope = buildJsonObject {
            put("version", JsonPrimitive(Protocol.VERSION))
            put("type", JsonPrimitive(TYPE_ERROR))
            put("id", JsonPrimitive(id))
            put("payload", buildJsonObject {
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive("operation denied"))
            })
        }
        return json.encodeToString(JsonObject.serializer(), envelope).toByteArray()
    }

    private fun buildPing(id: String): ByteArray {
        val envelope = buildJsonObject {
            put("version", JsonPrimitive(Protocol.VERSION))
            put("type", JsonPrimitive(TYPE_PING))
            put("id", JsonPrimitive(id))
            put("payload", buildJsonObject { put("ts", JsonPrimitive(System.currentTimeMillis())) })
        }
        return json.encodeToString(JsonObject.serializer(), envelope).toByteArray()
    }

    companion object {
        private const val TYPE_GET_ASSERTION = "getAssertion"
        private const val TYPE_MAKE_CREDENTIAL = "makeCredential"
        private const val TYPE_ASSERTION_RESULT = "assertionResult"
        private const val TYPE_MAKE_CREDENTIAL_RESULT = "makeCredentialResult"
        private const val TYPE_ERROR = "error"
        private const val TYPE_PING = "ping"
        private const val ALG_ES256 = -7
        private const val CLIENT_DATA_HASH_BYTES = 32
    }
}
