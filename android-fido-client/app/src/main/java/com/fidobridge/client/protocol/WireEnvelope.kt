package com.fidobridge.client.protocol

data class WireEnvelope(
    val channelId: String,
    val kind: String,
    val payload: ByteArray
)