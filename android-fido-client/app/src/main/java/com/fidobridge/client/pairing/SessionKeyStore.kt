package com.fidobridge.client.pairing

import com.fidobridge.client.crypto.SessionKey

interface SessionKeyStore {
    fun save(key: SessionKey, channelId: String)
    fun loadKey(): SessionKey?
    fun loadChannelId(): String?
    val isPaired: Boolean get() = loadKey() != null
}
