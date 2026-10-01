package com.fidobridge.client.harness

import com.fidobridge.client.ctap.CredentialStore
import com.fidobridge.client.ctap.GeneratedCredential
import com.fidobridge.client.ctap.KeyGenerator
import com.fidobridge.client.ctap.PublicKeyCoords
import com.fidobridge.client.ctap.Signer
import com.fidobridge.client.ctap.StoredCredential

class FakeCredentialStore : CredentialStore {
    val credentials = mutableListOf<StoredCredential>()

    init {
        credentials.add(
            StoredCredential(
                rpId = "example.com",
                alias = "pre-seeded",
                credentialId = "cred-1".toByteArray(),
                publicKey = PublicKeyCoords(
                    x = ByteArray(32) { (it + 1).toByte() },
                    y = ByteArray(32) { (it + 33).toByte() }
                ),
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

    override fun clear() {
        credentials.clear()
    }
}

class FakeKeyGenerator : KeyGenerator {
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

class FakeSigner : Signer {
    var fail = false

    override fun sign(rpId: String, keyAlias: String, data: ByteArray, onResult: (Result<ByteArray>) -> Unit) {
        if (fail) {
            onResult(Result.failure(Exception("denied")))
        } else {
            onResult(Result.success(ByteArray(32) { 0x77.toByte() }))
        }
    }
}