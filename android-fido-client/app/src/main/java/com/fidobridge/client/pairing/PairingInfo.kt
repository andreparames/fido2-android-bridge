package com.fidobridge.client.pairing

import com.fidobridge.client.crypto.SessionKey

data class PairingInfo(
    val channel: String,
    val channelId: String,
    val key: SessionKey
)
