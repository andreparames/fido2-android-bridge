package com.fidobridge.client.networking

import android.util.Log
import com.fidobridge.client.protocol.Protocol
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort transport that publishes diagnostic entries to the relay log
 * channel (`fidobridge:log:<channel_id>`). Kept behind this interface so the
 * sink can be unit-tested without a live Centrifugo connection.
 */
interface RelayLogPublisher {
    fun start(channelId: String, relayUrl: String, relayToken: String?)
    fun publish(payload: ByteArray)
    fun stop()
}

@Singleton
class CentrifugoLogPublisher @Inject constructor() : RelayLogPublisher {

    private var client: Client? = null
    private var sub: Subscription? = null

    override fun start(channelId: String, relayUrl: String, relayToken: String?) {
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
        sub = c.newSubscription(
            "${Protocol.RELAY_CHANNEL_PREFIX}log:$channelId",
            object : SubscriptionEventListener() {}
        )
        c.connect()
    }

    override fun publish(payload: ByteArray) {
        val s = sub
        if (s == null) {
            Log.w(TAG, "diagnostic log not connected, dropping")
            return
        }
        s.publish(payload, object : ResultCallback<PublishResult> {
            override fun onDone(e: Throwable?, result: PublishResult?) {
                if (e != null) Log.w(TAG, "diagnostic publish failed: ${e.message}")
            }
        })
    }

    override fun stop() {
        client?.disconnect()
        client = null
        sub = null
    }

    companion object {
        private const val TAG = "FidoBridgeLog"
    }
}
