package com.fidobridge.client.security

import com.fidobridge.client.ctap.GeneratedCredential
import com.fidobridge.client.ctap.KeyGenerator
import com.fidobridge.client.ctap.PublicKeyCoords
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class KeystoreKeyGenerator @Inject constructor(
    private val keystoreManager: KeystoreManager
) : KeyGenerator {

    private val counter = AtomicInteger()

    override fun generate(): GeneratedCredential {
        val alias = "fido-cred-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
        val keyPair = keystoreManager.getOrCreateSigningKey(alias)
        val ecPublic = keyPair.public as ECPublicKey
        val coords = PublicKeyCoords(
            x = toFixed32(ecPublic.w.affineX),
            y = toFixed32(ecPublic.w.affineY)
        )
        val credentialId = MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded)
        return GeneratedCredential(alias = alias, credentialId = credentialId, publicKey = coords)
    }

    private fun toFixed32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size >= 32) {
            bytes.copyOfRange(bytes.size - 32, bytes.size)
        } else {
            ByteArray(32 - bytes.size) { 0 } + bytes
        }
    }
}