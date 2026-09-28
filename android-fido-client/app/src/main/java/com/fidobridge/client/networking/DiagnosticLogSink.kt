package com.fidobridge.client.networking

import android.util.Log
import io.github.centrifugal.centrifuge.Client
import io.github.centrifugal.centrifuge.ConnectionTokenEvent
import io.github.centrifugal.centrifuge.ConnectionTokenGetter
import io.github.centrifugal.centrifuge.ConnectedEvent
import io.github.centrifugal.centrifuge.EventListener
import io.github.centrifugal.centrifuge.Options
import io.github.centrifugal.centrifuge.PublishResult
import io.github.centrifugal.centrifuge.ResultCallback
import io.github.centrifugal.centrifuge.Subscription
import io.github.centrifugal.centrifuge.SubscriptionEventListener
import io.github.centrifugal.centrifuge.TokenCallback
import io.github.centrifugal.centrifuge.UnauthorizedException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticLogSink @Inject constructor() {

    @Serializable
    private data class Entry(val ts: Long, val message: String)

    private val json = Json
    private var client: Client? = null
    private var sub: Subscription? = null

    fun start(channelId: String, relayUrl: String, relayToken: String?) {
        if (client != null) return
        val token = relayToken ?: ""
        val opts = Options().apply {
            this.token = token
            tokenGetter = object : ConnectionTokenGetter() {
                override fun getConnectionToken(event: ConnectionTokenEvent, cb: TokenCallback) {
                    if (token.isEmpty()) cb.Done(UnauthorizedException(), "") else cb.Done(null, token)
                }
            }
        }
        val c = Client(relayUrl, opts, object : EventListener() {
            override fun onConnected(client: Client, event: ConnectedEvent) {
                Log.i(TAG, "diagnostic log sink connected")
                sub?.subscribe()
            }
        })
        client = c
        sub = c.newSubscription("fidobridge.log.$channelId", object : SubscriptionEventListener() {})
        c.connect()
    }

    fun log(message: String) {
        val s = sub
        if (s == null) {
            Log.w(TAG, "diagnostic log not connected, dropping: $message")
            return
        }
        val payload = json.encodeToString(Entry(System.currentTimeMillis(), message))
        s.publish(payload.toByteArray(), object : ResultCallback<PublishResult> {
            override fun onDone(e: Throwable?, result: PublishResult?) {
                if (e != null) Log.w(TAG, "diagnostic publish failed: ${e.message}")
            }
        })
    }

    fun stop() {
        client?.disconnect()
        client = null
        sub = null
    }

    companion object {
        private const val TAG = "FidoBridgeLog"
    }
}