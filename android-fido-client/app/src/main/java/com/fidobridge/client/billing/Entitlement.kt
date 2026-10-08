package com.fidobridge.client.billing

data class Entitlement(
    val status: EntitlementStatus,
    val productId: String? = null,
    val isTrial: Boolean = false,
    val expiryEpochMs: Long? = null,
    val purchaseToken: String? = null
) {
    val isEntitled: Boolean get() = status == EntitlementStatus.ENTITLED

    companion object {
        val Loading = Entitlement(EntitlementStatus.LOADING)
        val NotEntitled = Entitlement(EntitlementStatus.NOT_ENTITLED)
        val BillingUnavailable = Entitlement(EntitlementStatus.BILLING_UNAVAILABLE)
        val Error = Entitlement(EntitlementStatus.ERROR)
        val Entitled = Entitlement(EntitlementStatus.ENTITLED)
    }
}
