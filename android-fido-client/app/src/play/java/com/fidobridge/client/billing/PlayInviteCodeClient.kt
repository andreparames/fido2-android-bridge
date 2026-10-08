package com.fidobridge.client.billing

import android.content.Context
import com.fidobridge.client.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Redeems an invite code against `POST /v1/play/session` and grants the
 * in-memory entitlement (playstore/plans/invite-code.md §6.4). Never logs the
 * code or the returned session token.
 */
@Singleton
class PlayInviteCodeClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionRepository: SubscriptionRepository
) : InviteCodeClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun submitInviteCode(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!InviteCodeValidator.isValid(code)) {
            return@withContext Result.failure(IllegalArgumentException("invalid invite code"))
        }
        runCatching {
            val body = buildJsonObject { put("inviteCode", code) }
            val request = Request.Builder()
                .url("${BuildConfig.GATEBRIDGE_API_URL.trimEnd('/')}/v1/play/session")
                .post(body.toString().toRequestBody(mediaType))
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("invite session failed: ${response.code}")
                }
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val entitled = root["entitled"]?.jsonPrimitive?.booleanOrNull == true
                val token = root["sessionToken"]?.jsonPrimitive?.contentOrNull
                if (!entitled || token.isNullOrEmpty()) {
                    throw IllegalStateException(
                        "invite session denied: ${root["reason"]?.jsonPrimitive?.contentOrNull}"
                    )
                }
            }
            subscriptionRepository.markEntitledForInvite(code)
        }
    }
}
