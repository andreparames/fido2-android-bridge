package com.fidobridge.client.pairing

import com.fidobridge.client.crypto.SessionKey
import com.fidobridge.client.protocol.Protocol
import com.fidobridge.client.util.Base64
import java.security.MessageDigest

class PairingRepository(private val sessionKeyStore: SessionKeyStore) {

    val isPaired: Boolean get() = sessionKeyStore.isPaired

    fun parseUri(uri: String): Result<PairingInfo> {
        return try {
            Result.success(parseInternal(uri))
        } catch (e: PairingException) {
            Result.failure(e)
        }
    }

    fun pair(info: PairingInfo): Result<Unit> {
        return try {
            sessionKeyStore.save(info.key, info.channelId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseInternal(uri: String): PairingInfo {
        val prefix = "${Protocol.SCHEME}://${Protocol.PAIR_HOST}?"
        if (!uri.startsWith(prefix)) {
            throw MalformedPairingUriException("Not a fidobridge pairing URI")
        }

        val query = uri.substring(prefix.length)
        if (query.isEmpty()) {
            throw MalformedPairingUriException("Missing parameters")
        }

        val values = parseParams(query)

        if (values.containsKey("v")) {
            val v = values.getValue("v")
            if (v != Protocol.VERSION.toString()) {
                throw VersionMismatchException()
            }
            values.remove("v")
        }

        val unknown = values.keys - ALLOWED_PARAMS
        if (unknown.isNotEmpty()) {
            throw MalformedPairingUriException("Unknown parameter: ${unknown.first()}")
        }

        val channel = values["channel"] ?: throw MalformedPairingUriException("Missing channel")
        val keyEncoded = values["key"] ?: throw MalformedPairingUriException("Missing key")

        if (!channelRegex.matches(channel)) {
            throw MalformedPairingUriException("channel must be 32 lowercase hex chars")
        }

        val keyBytes = try {
            Base64.decodeUrl(keyEncoded)
        } catch (e: IllegalArgumentException) {
            throw MalformedPairingUriException("Invalid key encoding")
        }
        if (keyBytes.size != SessionKey.KEY_BYTES) {
            throw MalformedPairingUriException("key must be ${SessionKey.KEY_BYTES} bytes")
        }

        return PairingInfo(
            channel = channel,
            channelId = deriveChannelId(channel),
            key = SessionKey.fromBytes(keyBytes)
        )
    }

    private fun parseParams(query: String): MutableMap<String, String> {
        val values = mutableMapOf<String, String>()
        for (param in query.split("&")) {
            val idx = param.indexOf('=')
            if (idx <= 0) {
                throw MalformedPairingUriException("Malformed parameter: $param")
            }
            val key = param.substring(0, idx)
            val value = param.substring(idx + 1)
            if (values.containsKey(key)) {
                throw MalformedPairingUriException("Duplicate parameter: $key")
            }
            values[key] = value
        }
        return values
    }

    private fun deriveChannelId(channel: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(channel.toByteArray(Charsets.UTF_8))
        val first16 = digest.copyOfRange(0, 16)
        return first16.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    companion object {
        private val channelRegex = Regex("^[0-9a-f]{32}$")
        private val ALLOWED_PARAMS = setOf("channel", "key")
    }
}
