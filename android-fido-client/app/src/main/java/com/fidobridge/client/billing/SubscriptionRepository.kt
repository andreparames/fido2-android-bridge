package com.fidobridge.client.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

interface SubscriptionRepository {
    val entitlement: StateFlow<Entitlement>
    suspend fun refresh()
    suspend fun queryProducts(): Result<List<SubscriptionProduct>>
    suspend fun launchPurchase(activity: Activity, productId: String): Result<Unit>
    suspend fun restorePurchases()

    /** Applies an invite-code grant (in-memory only; never persisted). */
    fun markEntitledForInvite(inviteCode: String)
}
