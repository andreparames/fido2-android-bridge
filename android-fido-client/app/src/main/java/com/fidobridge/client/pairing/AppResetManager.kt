package com.fidobridge.client.pairing

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

    fun reset() {
        keystoreManager.deleteAllSigningKeys()
        identityStore.clear()
        credentialStore.clear()
        requestLog.clear()
    }
}