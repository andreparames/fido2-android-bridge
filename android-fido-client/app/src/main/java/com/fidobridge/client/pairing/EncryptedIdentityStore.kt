package com.fidobridge.client.pairing

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fidobridge.client.util.Base64

class EncryptedIdentityStore(context: Context) : IdentityStore {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "fidobridge_identity",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun savePairing(phoneStaticPrivate: ByteArray, daemonStaticPublic: ByteArray, channelId: String) {
        prefs.edit()
            .putString(PHONE_PRIVATE_FIELD, Base64.encodeStandard(phoneStaticPrivate))
            .putString(DAEMON_PUBLIC_FIELD, Base64.encodeStandard(daemonStaticPublic))
            .putString(CHANNEL_ID_FIELD, channelId)
            .apply()
    }

    override fun loadPhoneStaticPrivate(): ByteArray? =
        prefs.getString(PHONE_PRIVATE_FIELD, null)?.let(::decode)

    override fun loadDaemonStaticPublic(): ByteArray? =
        prefs.getString(DAEMON_PUBLIC_FIELD, null)?.let(::decode)

    override fun loadChannelId(): String? = prefs.getString(CHANNEL_ID_FIELD, null)

    override fun saveRelayToken(token: String?) {
        if (token.isNullOrEmpty()) {
            prefs.edit().remove(RELAY_TOKEN_FIELD).apply()
        } else {
            prefs.edit().putString(RELAY_TOKEN_FIELD, token).apply()
        }
    }

    override fun loadRelayToken(): String? = prefs.getString(RELAY_TOKEN_FIELD, null)

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun decode(value: String): ByteArray? = try {
        Base64.decodeStandard(value)
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val PHONE_PRIVATE_FIELD = "phone_static_private"
        private const val DAEMON_PUBLIC_FIELD = "daemon_static_public"
        private const val CHANNEL_ID_FIELD = "channel_id"
        private const val RELAY_TOKEN_FIELD = "relay_token"
    }
}