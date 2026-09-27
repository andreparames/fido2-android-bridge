package com.fidobridge.client.ctap

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object AuthenticatorDataBuilder {

    const val FLAG_UP = 0x01
    const val FLAG_UV = 0x04
    const val FLAG_AT = 0x40

    fun rpIdHash(rpId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray(Charsets.UTF_8))

    fun buildAssertion(rpId: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(rpIdHash(rpId))
        out.write(FLAG_UP or FLAG_UV)
        out.write(byteArrayOf(0, 0, 0, 0))
        return out.toByteArray()
    }

    fun buildMakeCredential(rpId: String, credentialId: ByteArray, cosePublicKey: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(rpIdHash(rpId))
        out.write(FLAG_UP or FLAG_UV or FLAG_AT)
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(ByteArray(16)) // AAGUID: zeroed for fmt=none
        out.write(credentialId.size shr 8)
        out.write(credentialId.size and 0xff)
        out.write(credentialId)
        out.write(cosePublicKey)
        return out.toByteArray()
    }
}
