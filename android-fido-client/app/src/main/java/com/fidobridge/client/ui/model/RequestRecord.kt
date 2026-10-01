package com.fidobridge.client.ui.model

enum class RequestType { SIGN_IN, REGISTER, BROWSER_CHECK }

enum class RequestOutcome { PENDING, ACCEPTED, REJECTED }

data class RequestRecord(
    val id: String,
    val type: RequestType,
    val rpId: String,
    val timestamp: Long,
    val outcome: RequestOutcome
)