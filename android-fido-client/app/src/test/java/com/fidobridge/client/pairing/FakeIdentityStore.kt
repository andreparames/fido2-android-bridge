package com.fidobridge.client.pairing

class FakeIdentityStore(
    private var storedPhonePrivate: ByteArray? = null,
    private var storedDaemonPublic: ByteArray? = null,
    private var storedChannelId: String? = null,
    private var storedRelayToken: String? = null
) : IdentityStore {

    override fun savePairing(phoneStaticPrivate: ByteArray, daemonStaticPublic: ByteArray, channelId: String) {
        storedPhonePrivate = phoneStaticPrivate
        storedDaemonPublic = daemonStaticPublic
        storedChannelId = channelId
    }

    override fun loadPhoneStaticPrivate(): ByteArray? = storedPhonePrivate
    override fun loadDaemonStaticPublic(): ByteArray? = storedDaemonPublic
    override fun loadChannelId(): String? = storedChannelId

    override fun saveRelayToken(token: String?) {
        storedRelayToken = token
    }

    override fun loadRelayToken(): String? = storedRelayToken

    override fun clear(): Boolean {
        storedPhonePrivate = null
        storedDaemonPublic = null
        storedChannelId = null
        storedRelayToken = null
        return true
    }
}