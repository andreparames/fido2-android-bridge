package com.fidobridge.client.networking

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
import com.fidobridge.client.BuildConfig

class CentrifugoTransport(
    private val endpoint: String,
    private val channel: String
) : RelayTransport {

    private var listener: RelayTransport.Listener? = null

    private val token: String = BuildConfig.RELAY_TOKEN

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
            listener?.onConnected()
        }

        override fun onDisconnected(client: Client, event: DisconnectedEvent) {
            listener?.onDisconnected(event.code, event.reason)
        }
    })

    private var subscription: Subscription? = null

    override fun connect() {
        if (subscription == null) {
            subscription = client.newSubscription(channel, object : SubscriptionEventListener() {
                override fun onPublication(sub: Subscription, event: PublicationEvent) {
                    listener?.onPublication(event.data)
                }
            })
        }
        subscription?.subscribe()
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
    }
}
