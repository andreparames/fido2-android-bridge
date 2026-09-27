package com.fidobridge.client.protocol

data class WireMessage(
    val channelId: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray
)
