package com.fidobridge.client.pairing

interface IdentityStore {
    fun savePairing(phoneStaticPrivate: ByteArray, daemonStaticPublic: ByteArray, channelId: String)
    fun loadPhoneStaticPrivate(): ByteArray?
    fun loadDaemonStaticPublic(): ByteArray?
    fun loadChannelId(): String?
    fun saveRelayToken(token: String?)
    fun loadRelayToken(): String?

    val isPaired: Boolean
        get() = loadPhoneStaticPrivate() != null &&
            loadDaemonStaticPublic() != null &&
            loadChannelId() != null
}