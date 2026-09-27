package com.fidobridge.client.util

object Base64 {
    fun encodeStandard(bytes: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

    fun decodeStandard(s: String): ByteArray =
        java.util.Base64.getDecoder().decode(pad(s))

    fun encodeUrl(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeUrl(s: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(pad(s))

    private fun pad(s: String): String =
        if (s.length % 4 == 0) s else s.padEnd((s.length / 4 + 1) * 4, '=')
}
