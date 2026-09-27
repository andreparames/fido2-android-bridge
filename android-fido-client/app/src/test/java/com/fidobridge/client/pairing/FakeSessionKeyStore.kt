package com.fidobridge.client.pairing

import com.fidobridge.client.crypto.SessionKey

class FakeSessionKeyStore : SessionKeyStore {
    private var storedKey: SessionKey? = null
    private var storedChannelId: String? = null

    override fun save(key: SessionKey, channelId: String) {
        storedKey = key
        storedChannelId = channelId
    }

    override fun loadKey(): SessionKey? = storedKey

    override fun loadChannelId(): String? = storedChannelId
}
