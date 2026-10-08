package com.fidobridge.client.networking

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One diagnostic log line. Serialized as `{"ts":<millis>,"message":"..."}`, the
 * same shape published to the relay log channel and appended to the local
 * `diagnostics.jsonl` file.
 */
@Serializable
data class DiagnosticLogEntry(val ts: Long, val message: String) {
    companion object {
        private val json = Json

        fun now(message: String): DiagnosticLogEntry =
            DiagnosticLogEntry(System.currentTimeMillis(), message)

        fun encode(entry: DiagnosticLogEntry): String =
            json.encodeToString(serializer(), entry)

        fun decode(line: String): DiagnosticLogEntry? =
            try {
                json.decodeFromString(serializer(), line)
            } catch (e: Exception) {
                null
            }
    }
}
