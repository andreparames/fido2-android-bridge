package com.fidobridge.client.crypto

import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class SessionKey private constructor(val bytes: ByteArray) {

    init {
        require(bytes.size == KEY_BYTES) { "AES-256 session key must be $KEY_BYTES bytes" }
    }

    fun toSecretKey(): SecretKey = SecretKeySpec(bytes, "AES")

    companion object {
        const val KEY_BYTES = 32

        fun fromBytes(bytes: ByteArray): SessionKey = SessionKey(bytes.copyOf())

        fun generate(): SessionKey {
            val bytes = ByteArray(KEY_BYTES)
            SecureRandom().nextBytes(bytes)
            return SessionKey(bytes)
        }
    }
}
