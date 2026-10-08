package com.fidobridge.client.billing

import javax.inject.Inject

/**
 * oss/classic has no invite-code entry point; this exists only to satisfy DI.
 * Always fails, and is never reached because the UI button is gated by
 * `PLAY_BILLING_REQUIRED`.
 */
class NoOpInviteCodeClient @Inject constructor() : InviteCodeClient {
    override suspend fun submitInviteCode(code: String): Result<Unit> =
        Result.failure(IllegalStateException("invite codes are unavailable on this build"))
}
