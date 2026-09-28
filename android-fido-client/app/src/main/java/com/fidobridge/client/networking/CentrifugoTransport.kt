package com.fidobridge.client.networking

import android.util.Log
import io.github.centrifugal.centrifuge.Client
import io.github.centrifugal.centrifuge.ConnectedEvent
import io.github.centrifugal.centrifuge.ConnectionTokenEvent
import io.github.centrifugal.centrifuge.ConnectionTokenGetter
import io.github.centrifugal.centrifuge.DisconnectedEvent
import io.github.centrifugal.centrifuge.EventListener
import io.github.centrifugal.centrifuge.Options
import io.github.centrifugal.centrifuge.PublicationEvent
import io.github.centrifugal.centrifuge.PublishResult
import io.github.centrifugal.centrifuge.ResultCallback
import io.github.centrifugal.centrifuge.Subscription
import io.github.centrifugal.centrifuge.SubscriptionEventListener
import io.github.centrifugal.centrifuge.TokenCallback
import io.github.centrifugal.centrifuge.UnauthorizedException
import kotlinx.serialization.json.Json

class CentrifugoTransport(
    private val endpoint: String,
    private val channel: String,
    relayToken: String? = null
) : RelayTransport {

    private var listener: RelayTransport.Listener? = null

    private val token: String = relayToken ?: ""

    private val options = Options().apply {
        name = CLIENT_NAME
        token = this@CentrifugoTransport.token
        tokenGetter = object : ConnectionTokenGetter() {
            override fun getConnectionToken(event: ConnectionTokenEvent, cb: TokenCallback) {
                val current = this@CentrifugoTransport.token
                if (current.isEmpty()) {
                    cb.Done(UnauthorizedException(), "")
                } else {
                    cb.Done(null, current)
                }
            }
        }
    }

    private val client = Client(endpoint, options, object : EventListener() {
        override fun onConnected(client: Client, event: ConnectedEvent) {
            Log.i(TAG, "onConnected endpoint=$endpoint")
            subscription?.subscribe()
            listener?.onConnected()
        }

        override fun onDisconnected(client: Client, event: DisconnectedEvent) {
            Log.w(TAG, "onDisconnected code=${event.code} reason=${event.reason}")
            listener?.onDisconnected(event.code, event.reason)
        }
    })

    private var subscription: Subscription? = null

    override fun connect() {
        Log.i(TAG, "transport.connect endpoint=$endpoint channel=$channel")
        if (subscription == null) {
            subscription = client.newSubscription(channel, object : SubscriptionEventListener() {
                override fun onPublication(sub: Subscription, event: PublicationEvent) {
                    val raw = event.data.decodeToString()
                    val wire = try {
                        json.decodeFromString<String>(raw)
                    } catch (e: Exception) {
                        raw
                    }
                    listener?.onPublication(wire.toByteArray())
                }
            })
        }
        client.connect()
    }

    override fun disconnect() {
        subscription?.unsubscribe()
        client.disconnect()
    }

    override fun publish(data: ByteArray, onDone: (Throwable?) -> Unit) {
        val sub = subscription
        if (sub == null) {
            onDone(IllegalStateException("relay not subscribed"))
            return
        }
        sub.publish(data, object : ResultCallback<PublishResult> {
            override fun onDone(e: Throwable?, result: PublishResult?) {
                onDone(e)
            }
        })
    }

    override fun setListener(listener: RelayTransport.Listener) {
        this.listener = listener
    }

    companion object {
        private const val CLIENT_NAME = "fidobridge-android"
        private const val TAG = "FidoBridge"
        private val json = Json
    }
}
