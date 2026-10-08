package com.fidobridge.client.billing

import javax.inject.Inject

/**
 * Classic/OSS has no managed-relay backend: [com.fidobridge.client.pairing.ManagedPairingGate]
 * short-circuits for [com.fidobridge.client.relay.RelayMode.CLASSIC], so channel
 * activation is never invoked. This exists only to satisfy DI and never fails.
 */
class NoOpEntitlementBackend @Inject constructor() : EntitlementBackend {
    override suspend fun activate(channel: String): Result<ActivateResult> =
        Result.success(ActivateResult(channel = channel, status = "active"))
}
