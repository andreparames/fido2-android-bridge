package com.fidobridge.client.ui

import com.fidobridge.client.billing.EntitlementStatus

enum class AppSurface { MAIN, LOADING, SUBSCRIBE }

/**
 * Which top-level surface to show given the flavor's billing requirement and the
 * current entitlement. oss (`!playBillingRequired`) never shows the subscription
 * gate (billing.md §5.2/§5.4).
 */
fun surfaceFor(playBillingRequired: Boolean, status: EntitlementStatus): AppSurface = when {
    !playBillingRequired -> AppSurface.MAIN
    status == EntitlementStatus.LOADING -> AppSurface.LOADING
    status == EntitlementStatus.ENTITLED -> AppSurface.MAIN
    else -> AppSurface.SUBSCRIBE
}
