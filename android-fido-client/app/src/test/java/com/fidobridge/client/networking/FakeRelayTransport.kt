package com.fidobridge.client.networking

class FakeRelayTransport : RelayTransport {

    var listener: RelayTransport.Listener? = null
        private set

    val published = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
    var connected = false
        private set

    var publishFails = false

    override fun connect() {
        connected = true
        listener?.onConnected()
    }

    override fun disconnect() {
        connected = false
        listener?.onDisconnected(0, "closed")
    }

    override fun publish(data: ByteArray, onDone: (Throwable?) -> Unit) {
        published.add(data)
        onDone(if (publishFails) IllegalStateException("publish failed") else null)
    }

    override fun setListener(listener: RelayTransport.Listener) {
        this.listener = listener
    }

    fun simulatePublication(data: ByteArray) {
        listener?.onPublication(data)
    }

    fun simulateDisconnect(code: Int = 0, reason: String = "closed") {
        listener?.onDisconnected(code, reason)
    }
}
