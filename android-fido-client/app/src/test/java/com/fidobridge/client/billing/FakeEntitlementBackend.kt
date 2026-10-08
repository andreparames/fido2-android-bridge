package com.fidobridge.client.billing

/** JVM test stand-in for the Gatebridge activate API. Records activated channels. */
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
