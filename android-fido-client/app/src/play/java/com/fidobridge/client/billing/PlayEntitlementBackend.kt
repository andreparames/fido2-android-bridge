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
 * Managed-relay channel activation via the Gatebridge backend
 * (MANAGED_RELAY_PLAN §4.1–4.2).
 *
 * Turns the current Play purchase into a short-lived session, then activates
 * channel `C` so the Centrifugo subscribe proxy will admit it. Fails closed:
 * any missing purchase proof, non-2xx response, or unexpected body is a failure
 * and the subscription is never trusted client-side alone.
 */
@Singleton
class PlayEntitlementBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionRepository: SubscriptionRepository,
    private val playSubscriptionRepository: PlayBillingSubscriptionRepository
) : EntitlementBackend {

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun activate(channel: String): Result<ActivateResult> =
        withContext(Dispatchers.IO) {
            if (!CHANNEL_REGEX.matches(channel)) {
                return@withContext Result.failure(
                    IllegalArgumentException("channel must be 32 lowercase hex chars")
                )
            }
            val entitlement = subscriptionRepository.entitlement.value
            val productId = entitlement.productId
            val purchaseToken = entitlement.purchaseToken
            if (!entitlement.isEntitled || productId == null || purchaseToken == null) {
                return@withContext Result.failure(
                    IllegalStateException("no verified Play purchase available for activation")
                )
            }
            runCatching {
                val session = createSession(productId, purchaseToken)
                val result = activateChannel(session.token, channel)
                playSubscriptionRepository.applyServerTrial(session.isTrial)
                result
            }
        }

    private fun createSession(productId: String, purchaseToken: String): PlaySession {
        val body = buildJsonObject {
            put("productId", productId)
            put("purchaseToken", purchaseToken)
            put("packageName", context.packageName)
        }
        val root = json.parseToJsonElement(
            post("$BASE_URL/v1/play/session", body.toString(), sessionToken = null)
        ).jsonObject
        val entitled = root["entitled"]?.jsonPrimitive?.booleanOrNull == true
        val token = root["sessionToken"]?.jsonPrimitive?.contentOrNull
        val isTrial = root["isTrial"]?.jsonPrimitive?.booleanOrNull == true
        if (!entitled || token.isNullOrEmpty()) {
            throw IllegalStateException("play session denied: ${root["reason"]?.jsonPrimitive?.contentOrNull}")
        }
        return PlaySession(token, isTrial)
    }

    private data class PlaySession(val token: String, val isTrial: Boolean)

    private fun activateChannel(sessionToken: String, channel: String): ActivateResult {
        val body = buildJsonObject { put("channel", channel) }
        val root = json.parseToJsonElement(
            post("$BASE_URL/v1/channels/activate", body.toString(), sessionToken)
        ).jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        if (status != "active") {
            throw IllegalStateException("channel activation rejected: ${status ?: "unknown"}")
        }
        val activated = root["channel"]?.jsonPrimitive?.contentOrNull ?: channel
        return ActivateResult(channel = activated, status = status)
    }

    private fun post(url: String, body: String, sessionToken: String?): String {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(mediaType))
            .header("Accept", "application/json")
        if (sessionToken != null) {
            builder.header("Authorization", "Bearer $sessionToken")
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("gatebridge api returned ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        private val BASE_URL = BuildConfig.GATEBRIDGE_API_URL.trimEnd('/')
        private val CHANNEL_REGEX = Regex("^[0-9a-f]{32}$")
    }
}
