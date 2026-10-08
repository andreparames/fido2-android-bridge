package com.fidobridge.client.billing

object ProductIds {
    const val MONTHLY = "gatebridge_individual_monthly"
    const val YEARLY = "gatebridge_individual_yearly"
    const val TRIAL_PERIOD = "P1M"
    val ALL = setOf(MONTHLY, YEARLY)

    fun isLegal(productId: String): Boolean = productId in ALL
}
