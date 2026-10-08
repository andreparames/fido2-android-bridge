package com.fidobridge.client.billing

/** Play purchase → channel activate on the Gatebridge backend (managed relay). */
interface EntitlementBackend {
    suspend fun activate(channel: String): Result<ActivateResult>
}

data class ActivateResult(
    val channel: String,
    val status: String
)
