package com.fidobridge.client.billing

class FakeInviteCodeClient : InviteCodeClient {
    var result: Result<Unit> = Result.success(Unit)
    val codes = mutableListOf<String>()

    override suspend fun submitInviteCode(code: String): Result<Unit> {
        codes += code
        return result
    }
}
