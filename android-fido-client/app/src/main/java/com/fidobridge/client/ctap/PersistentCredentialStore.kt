package com.fidobridge.client.ctap

import android.content.SharedPreferences
import com.fidobridge.client.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class PersistentCredentialStore(
    private val prefs: SharedPreferences
) : CredentialStore {

    @Synchronized
    override fun findForRpId(rpId: String): List<StoredCredential> =
        loadAll().filter { it.rpId == rpId }

    @Synchronized
    override fun findByCredentialId(rpId: String, credentialId: ByteArray): StoredCredential? =
        loadAll().firstOrNull { it.rpId == rpId && it.credentialId.contentEquals(credentialId) }

    @Synchronized
    override fun add(credential: StoredCredential) {
        val all = loadAll().toMutableList()
        all.add(credential)
        prefs.edit().putString(KEY, encode(all)).apply()
    }

    @Synchronized
    override fun clear(): Boolean = prefs.edit().remove(KEY).commit()

    private fun loadAll(): List<StoredCredential> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            decode(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encode(credentials: List<StoredCredential>): String =
        json.encodeToString(ListSerializer(CredentialDto.serializer()), credentials.map { it.toDto() })

    private fun decode(raw: String): List<StoredCredential> =
        json.decodeFromString(ListSerializer(CredentialDto.serializer()), raw).map { it.toDomain() }

    companion object {
        private const val KEY = "credentials"
        private val json = Json { ignoreUnknownKeys = false }

        @Serializable
        private data class CredentialDto(
            val rpId: String,
            val alias: String,
            val credentialId: String,
            val publicKeyX: String,
            val publicKeyY: String,
            val userHandle: String? = null
        )

        private fun StoredCredential.toDto() = CredentialDto(
            rpId = rpId,
            alias = alias,
            credentialId = Base64.encodeStandard(credentialId),
            publicKeyX = Base64.encodeStandard(publicKey.x),
            publicKeyY = Base64.encodeStandard(publicKey.y),
            userHandle = userHandle?.let { Base64.encodeStandard(it) }
        )

        private fun CredentialDto.toDomain() = StoredCredential(
            rpId = rpId,
            alias = alias,
            credentialId = Base64.decodeStandard(credentialId),
            publicKey = PublicKeyCoords(
                x = Base64.decodeStandard(publicKeyX),
                y = Base64.decodeStandard(publicKeyY)
            ),
            userHandle = userHandle?.let { Base64.decodeStandard(it) }
        )
    }
}