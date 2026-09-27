package com.fidobridge.client.ctap

import kotlinx.serialization.Serializable

@Serializable
data class Option(val up: Boolean = true, val uv: Boolean = true)

@Serializable
data class GetAssertionPayload(
    val clientDataHash: String,
    val rpId: String,
    val allowCredentials: List<String> = emptyList(),
    val option: Option = Option()
)

@Serializable
data class UserInfo(
    val id: String,
    val name: String,
    val displayName: String
)

@Serializable
data class AlgParam(val alg: Int)

@Serializable
data class MakeCredentialPayload(
    val clientDataHash: String,
    val rpId: String,
    val user: UserInfo,
    val pubKeyCredParams: List<AlgParam> = listOf(AlgParam(-7)),
    val excludeCredentials: List<String> = emptyList()
)

@Serializable
data class AssertionResultPayload(
    val credentialId: String,
    val authenticatorData: String,
    val signature: String,
    val userHandle: String? = null
)

@Serializable
data class MakeCredentialResultPayload(
    val credentialId: String,
    val authenticatorData: String,
    val attestationObject: String,
    val signature: String
)
