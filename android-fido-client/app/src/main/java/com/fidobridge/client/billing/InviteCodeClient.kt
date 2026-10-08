package com.fidobridge.client.billing

/**
 * Redeems an 8-digit invite code for a managed-relay entitlement session
 * (playstore/plans/invite-code.md). Play-only; oss binds a failing stub and
 * never shows the entry point.
 */
interface InviteCodeClient {
    suspend fun submitInviteCode(code: String): Result<Unit>
}
