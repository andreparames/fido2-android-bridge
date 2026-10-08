package com.fidobridge.client.pairing

import com.fidobridge.client.billing.EntitlementBackend
import com.fidobridge.client.billing.SubscriptionRepository
import com.fidobridge.client.relay.RelayMode
import com.fidobridge.client.relay.RelayModeDetector

/**
 * Gates pairing behind managed-relay entitlement and server-side channel
 * activation (MANAGED_RELAY_PLAN §3, §6.2).
 *
 * Classic relay (any host other than the managed host) is never gated and never
 * touches the Gatebridge API. For the managed relay, pairing requires an active
 * entitlement and a successful `activate(subscription channel)` before the
 * pairing material is persisted.
 */
class ManagedPairingGate(
    relayUrl: String,
    private val subscriptionRepository: SubscriptionRepository,
    private val entitlementBackend: EntitlementBackend
) {
    val mode: RelayMode = RelayModeDetector.detect(relayUrl)

    val isManaged: Boolean get() = mode == RelayMode.MANAGED

    suspend fun authorize(info: PairingInfo): Result<Unit> {
        if (!isManaged) return Result.success(Unit)
        if (!subscriptionRepository.entitlement.value.isEntitled) {
            return Result.failure(SubscriptionRequiredException())
        }
        return entitlementBackend.activate(info.channelId).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(ChannelActivationException(it)) }
        )
    }
}
