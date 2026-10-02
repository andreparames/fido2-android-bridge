package com.fidobridge.client.pairing

import android.util.Log
import com.fidobridge.client.ctap.CredentialStore
import com.fidobridge.client.security.KeystoreManager
import com.fidobridge.client.ui.model.RequestLog

/**
 * Wipes all app state so the phone can be paired again: hardware signing keys,
 * the pairing identity, stored credentials, and the request history.
 */
class AppResetManager(
    private val identityStore: IdentityStore,
    private val credentialStore: CredentialStore,
    private val keystoreManager: KeystoreManager,
    private val requestLog: RequestLog
) {

    /**
     * Clears every store even if an earlier step fails, so a partial failure
     * does not leave half of the state behind. Returns false when any step did
     * not complete durably (keystore deletion or a failed preference commit).
     */
    fun reset(): Boolean {
        val keysDeleted = try {
            keystoreManager.deleteAllSigningKeys()
            true
        } catch (e: Exception) {
            Log.w(TAG, "keystore cleanup failed: ${e.message}")
            false
        }
        val identityCleared = identityStore.clear()
        val credentialsCleared = credentialStore.clear()
        requestLog.clear()
        return keysDeleted && identityCleared && credentialsCleared
    }

    companion object {
        private const val TAG = "FidoBridge"
    }
}