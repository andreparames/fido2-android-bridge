package com.fidobridge.client.crypto

data class EncryptedMessage(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray
)
