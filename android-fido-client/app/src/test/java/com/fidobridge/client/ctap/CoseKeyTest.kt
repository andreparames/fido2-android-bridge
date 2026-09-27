package com.fidobridge.client.ctap

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class CoseKeyTest {

    @Test
    fun `cose key matches expected byte layout`() {
        val x = ByteArray(32) { (it + 1).toByte() }
        val y = ByteArray(32) { (it + 33).toByte() }

        val actual = CoseKey.encode(PublicKeyCoords(x, y))

        assertArrayEquals(expectedCose(x, y), actual)
    }

    private fun expectedCose(x: ByteArray, y: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xA5)                 // map(5)
        out.write(byteArrayOf(0x01, 0x02)) // 1: 2   (kty: EC2)
        out.write(byteArrayOf(0x03, 0x26)) // 3: -7  (alg: ES256)
        out.write(byteArrayOf(0x20, 0x01)) // -1: 1  (crv: P-256)
        out.write(byteArrayOf(0x21, 0x58, 0x20)) // -2: bstr(32) x
        out.write(x)
        out.write(byteArrayOf(0x22, 0x58, 0x20)) // -3: bstr(32) y
        out.write(y)
        return out.toByteArray()
    }
}
