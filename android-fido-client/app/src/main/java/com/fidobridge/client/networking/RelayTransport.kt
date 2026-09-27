package com.fidobridge.client.networking

interface RelayTransport {

    fun connect()

    fun disconnect()

    fun publish(data: ByteArray, onDone: (Throwable?) -> Unit)

    fun setListener(listener: Listener)

    interface Listener {
        fun onPublication(data: ByteArray)
        fun onConnected()
        fun onDisconnected(code: Int, reason: String)
    }
}
