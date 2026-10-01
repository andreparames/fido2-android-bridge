package com.fidobridge.client.harness

import com.fidobridge.client.crypto.NoiseSession
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
        val daemonPublicB64 = props.getProperty("daemon_static_public_b64")
            ?: System.getenv("FIDO2_DAEMON_PUBLIC_B64")
            ?: throw IllegalStateException("FIDO2_DAEMON_PUBLIC_B64 is required for the harness")
        val phoneStaticPrivate = props.getProperty("phone_static_private_b64")
            ?.let { Base64.decodeStandard(it) }
            ?: System.getenv("FIDO2_PHONE_STATIC_PRIVATE_B64")?.let { Base64.decodeStandard(it) }
            ?: NoiseSession.generateStaticKey()
        return Config(
            channelId = deriveChannelId(channelHex),
            daemonStaticPublic = Base64.decodeStandard(daemonPublicB64),
            phoneStaticPrivate = phoneStaticPrivate,
            relayUrl = props.getProperty("relay_url")
                ?: System.getenv("FIDO2_RELAY_URL")
                ?: "wss://gary.andreparames.com:8000/connection/websocket",
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
        val daemonStaticPublic: ByteArray,
        val phoneStaticPrivate: ByteArray,
        val relayUrl: String,
        val timeoutSeconds: Long
    )
}