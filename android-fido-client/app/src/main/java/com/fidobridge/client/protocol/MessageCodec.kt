package com.fidobridge.client.protocol

import com.fidobridge.client.crypto.AesGcmCipher
import com.fidobridge.client.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object MessageCodec {

    class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val json = Json { ignoreUnknownKeys = false }

    private val channelIdRegex = Regex("^[0-9a-f]{32}$")

    fun encode(message: WireMessage): String {
        val dto = WireMessageDto(
            channelId = message.channelId,
            nonce = Base64.encodeStandard(message.nonce),
            ciphertext = Base64.encodeStandard(message.ciphertext),
            tag = Base64.encodeStandard(message.tag)
        )
        return json.encodeToString(dto)
    }

    fun decode(raw: String): WireMessage {
        val dto = try {
            json.decodeFromString<WireMessageDto>(raw)
        } catch (e: SerializationException) {
            throw DecodeException("Malformed wire message: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw DecodeException("Malformed wire message: ${e.message}", e)
        }

        if (!channelIdRegex.matches(dto.channelId)) {
            throw DecodeException("channel_id must be 32 lowercase hex chars")
        }

        val nonce = decodeField(dto.nonce, "nonce")
        val ciphertext = decodeField(dto.ciphertext, "ciphertext")
        val tag = decodeField(dto.tag, "tag")

        if (nonce.size != AesGcmCipher.NONCE_BYTES) {
            throw DecodeException("nonce must be ${AesGcmCipher.NONCE_BYTES} bytes")
        }
        if (tag.size != AesGcmCipher.TAG_BYTES) {
            throw DecodeException("tag must be ${AesGcmCipher.TAG_BYTES} bytes")
        }

        return WireMessage(channelId = dto.channelId, nonce = nonce, ciphertext = ciphertext, tag = tag)
    }

    private fun decodeField(value: String, name: String): ByteArray {
        return try {
            Base64.decodeStandard(value)
        } catch (e: IllegalArgumentException) {
            throw DecodeException("Invalid base64 in $name: ${e.message}", e)
        }
    }

    @Serializable
    private data class WireMessageDto(
        @SerialName("channel_id") val channelId: String,
        val nonce: String,
        val ciphertext: String,
        val tag: String
    )
}
