package com.fidobridge.client.billing

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Session-latched view of [delegate]: once the entitlement is `ENTITLED` it is
 * never revoked for the lifetime of the app process. Transient Play states
 * (a `PENDING` purchase callback, `BILLING_UNAVAILABLE`, `ERROR`) must not knock
 * a legitimately entitled user offline mid-session.
 *
 * The latch is in-memory only, so a cold start re-evaluates from Play. It is
 * not the authorization boundary: the managed relay's Centrifugo subscribe
 * proxy remains authoritative (billing.md §6.1, managed-relay.md §4.3/§5).
 */
class LatchingSubscriptionRepository(
    private val delegate: SubscriptionRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : SubscriptionRepository {

    private val _entitlement = MutableStateFlow(delegate.entitlement.value)
    override val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    init {
        scope.launch {
            delegate.entitlement.collect { accept(it) }
        }
    }

    // Accept upgrades and pre-entitlement transitions; never regress an
    // already-ENTITLED state.
    private fun accept(incoming: Entitlement) {
        if (incoming.isEntitled || !_entitlement.value.isEntitled) {
            _entitlement.value = incoming
        }
    }

    /**
     * Refreshes the delegate and publishes its resolved state before returning,
     * so callers that `await refresh()` observe an app-facing resolved
     * entitlement rather than the async collector's lagging value.
     */
    override suspend fun refresh() {
        delegate.refresh()
        accept(delegate.entitlement.value)
    }

    override suspend fun queryProducts(): Result<List<SubscriptionProduct>> =
        delegate.queryProducts()

    override suspend fun launchPurchase(activity: Activity, productId: String): Result<Unit> =
        delegate.launchPurchase(activity, productId)

    override suspend fun restorePurchases() = delegate.restorePurchases()

    override fun markEntitledForInvite(inviteCode: String) =
        delegate.markEntitledForInvite(inviteCode)
}
