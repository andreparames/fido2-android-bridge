package com.fidobridge.client.protocol

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

    fun encode(envelope: WireEnvelope): String {
        val dto = WireEnvelopeDto(
            channelId = envelope.channelId,
            kind = envelope.kind,
            payload = Base64.encodeStandard(envelope.payload)
        )
        return json.encodeToString(dto)
    }

    fun decode(raw: String): WireEnvelope {
        val dto = try {
            json.decodeFromString<WireEnvelopeDto>(raw)
        } catch (e: SerializationException) {
            throw DecodeException("Malformed wire envelope: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw DecodeException("Malformed wire envelope: ${e.message}", e)
        }

        if (!channelIdRegex.matches(dto.channelId)) {
            throw DecodeException("channel_id must be 32 lowercase hex chars")
        }
        if (dto.kind !in Protocol.ENVELOPE_KINDS) {
            throw DecodeException("kind must be one of ${Protocol.ENVELOPE_KINDS}")
        }

        val payload = try {
            Base64.decodeStandard(dto.payload)
        } catch (e: IllegalArgumentException) {
            throw DecodeException("Invalid base64 in payload: ${e.message}", e)
        }

        return WireEnvelope(channelId = dto.channelId, kind = dto.kind, payload = payload)
    }

    @Serializable
    private data class WireEnvelopeDto(
        @SerialName("channel_id") val channelId: String,
        val kind: String,
        val payload: String
    )
}