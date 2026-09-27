package com.fidobridge.client.harness

import com.fidobridge.client.util.Base64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties

object HarnessConfig {

    private const val CONFIG_FILE = "fido2.config"

    fun isEnabled(): Boolean {
        if (System.getenv("FIDO2_HARNESS") == "1") return true
        val file = configFile()
        if (!file.exists()) return false
        val props = Properties().apply { load(file.inputStream()) }
        return props.getProperty("enabled", "false") == "true"
    }

    fun fromEnv(): Config {
        val props = loadProperties()
        val channelHex = props.getProperty("channel_id")
            ?: System.getenv("FIDO2_CHANNEL_ID")
            ?: randomHex(16)
        val sessionKeyB64 = props.getProperty("session_key_b64")
            ?: System.getenv("FIDO2_SESSION_KEY_B64")
            ?: run {
                val key = ByteArray(32)
                SecureRandom().nextBytes(key)
                java.util.Base64.getEncoder().withoutPadding().encodeToString(key)
            }
        return Config(
            channelId = deriveChannelId(channelHex),
            sessionKeyB64 = sessionKeyB64,
            relayUrl = props.getProperty("relay_url")
                ?: System.getenv("FIDO2_RELAY_URL")
                ?: "ws://localhost:8000/connection/websocket",
            timeoutSeconds = props.getProperty("timeout_seconds")?.toLongOrNull()
                ?: System.getenv("FIDO2_HARNESS_TIMEOUT")?.toLongOrNull()
                ?: 180L
        )
    }

    private fun loadProperties(): Properties {
        val file = configFile()
        if (!file.exists()) return Properties()
        return Properties().apply { load(file.inputStream()) }
    }

    private fun configFile(): File {
        val path = System.getProperty(CONFIG_FILE) ?: System.getenv("FIDO2_CONFIG_FILE")
            ?: "/tmp/fido2_harness.properties"
        return File(path)
    }

    fun deriveChannelId(channelHex: String): String =
        MessageDigest.getInstance("SHA-256").digest(channelHex.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 16)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun randomHex(byteLen: Int): String {
        val bytes = ByteArray(byteLen)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    data class Config(
        val channelId: String,
        val sessionKeyB64: String,
        val relayUrl: String,
        val timeoutSeconds: Long
    ) {
        val sessionKeyBytes: ByteArray get() = Base64.decodeStandard(sessionKeyB64)
    }
}