package com.fidobridge.client.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PlaintextEnvelope(
    val version: Int,
    val type: String,
    val id: String,
    val payload: JsonObject
)
