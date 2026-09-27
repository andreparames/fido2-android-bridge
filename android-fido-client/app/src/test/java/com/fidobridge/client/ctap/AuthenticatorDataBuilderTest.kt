package com.fidobridge.client.ctap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class AuthenticatorDataBuilderTest {

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `assertion data has rpIdHash, 0x05 flags, and zero sign count`() {
        val data = AuthenticatorDataBuilder.buildAssertion("example.com")

        assertEquals(37, data.size)
        assertArrayEquals(sha256("example.com"), data.copyOfRange(0, 32))
        assertEquals(0x05, data[32].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), data.copyOfRange(33, 37))
    }

    @Test
    fun `makeCredential data has 0x45 flags, zeroed AAGUID, and credential id`() {
        val credentialId = byteArrayOf(9, 8, 7, 6, 5)
        val coseKey = byteArrayOf(0x01, 0x02, 0x03)
        val data = AuthenticatorDataBuilder.buildMakeCredential("example.com", credentialId, coseKey)

        assertEquals(0x45, data[32].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), data.copyOfRange(33, 37))

        val aaguid = data.copyOfRange(37, 53)
        assertArrayEquals(ByteArray(16), aaguid)

        val credIdLen = ((data[53].toInt() and 0xff) shl 8) or (data[54].toInt() and 0xff)
        assertEquals(credentialId.size, credIdLen)

        val storedCredId = data.copyOfRange(55, 55 + credentialId.size)
        assertArrayEquals(credentialId, storedCredId)

        val storedCose = data.copyOfRange(55 + credentialId.size, data.size)
        assertArrayEquals(coseKey, storedCose)
    }
}
