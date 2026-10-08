package com.fidobridge.client.billing

/** JVM/dev stand-in until the Gatebridge API is wired for real purchases. */
class FakeEntitlementBackend(
    private val activateResult: Result<ActivateResult> = Result.success(
        ActivateResult(channel = "", status = "active")
    )
) : EntitlementBackend {

    val calls = mutableListOf<String>()

    override suspend fun activate(channel: String): Result<ActivateResult> {
        calls += channel
        return activateResult.map { it.copy(channel = channel) }
    }
}
