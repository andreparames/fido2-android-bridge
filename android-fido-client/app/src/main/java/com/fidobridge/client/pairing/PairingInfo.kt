package com.fidobridge.client.pairing

data class PairingInfo(
    val channel: String,
    val channelId: String,
    val daemonStaticPublic: ByteArray,
    val relayToken: String? = null
)