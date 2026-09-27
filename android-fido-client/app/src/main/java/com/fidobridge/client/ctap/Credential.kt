package com.fidobridge.client.ctap

data class PublicKeyCoords(val x: ByteArray, val y: ByteArray)

data class StoredCredential(
    val rpId: String,
    val alias: String,
    val credentialId: ByteArray,
    val publicKey: PublicKeyCoords,
    val userHandle: ByteArray? = null
)

data class GeneratedCredential(
    val alias: String,
    val credentialId: ByteArray,
    val publicKey: PublicKeyCoords
)

fun interface KeyGenerator {
    fun generate(): GeneratedCredential
}

interface CredentialStore {
    fun findForRpId(rpId: String): List<StoredCredential>
    fun findByCredentialId(rpId: String, credentialId: ByteArray): StoredCredential?
    fun add(credential: StoredCredential)
}

fun interface Signer {
    fun sign(rpId: String, keyAlias: String, data: ByteArray, onResult: (Result<ByteArray>) -> Unit)
}
