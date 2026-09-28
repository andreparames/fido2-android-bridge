package com.fidobridge.client.pairing

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fidobridge.client.crypto.SessionKey
import com.fidobridge.client.util.Base64

class EncryptedSessionKeyStore(context: Context) : SessionKeyStore {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "fidobridge_session",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun save(key: SessionKey, channelId: String) {
        prefs.edit()
            .putString(KEY_FIELD, Base64.encodeStandard(key.bytes))
            .putString(CHANNEL_ID_FIELD, channelId)
            .apply()
    }

    override fun loadKey(): SessionKey? {
        val encoded = prefs.getString(KEY_FIELD, null) ?: return null
        return try {
            SessionKey.fromBytes(Base64.decodeStandard(encoded))
        } catch (e: Exception) {
            null
        }
    }

    override fun loadChannelId(): String? = prefs.getString(CHANNEL_ID_FIELD, null)

    override fun saveRelayToken(token: String?) {
        if (token.isNullOrEmpty()) {
            prefs.edit().remove(RELAY_TOKEN_FIELD).apply()
        } else {
            prefs.edit().putString(RELAY_TOKEN_FIELD, token).apply()
        }
    }

    override fun loadRelayToken(): String? = prefs.getString(RELAY_TOKEN_FIELD, null)

    companion object {
        private const val KEY_FIELD = "session_key"
        private const val CHANNEL_ID_FIELD = "channel_id"
        private const val RELAY_TOKEN_FIELD = "relay_token"
    }
}
