package com.fidobridge.client.protocol

object Protocol {
    const val VERSION = 1
    const val SCHEME = "fidobridge"
    const val PAIR_HOST = "pair"
    const val RELAY_CHANNEL_PREFIX = "fidobridge."

    fun relayChannel(channelId: String): String = "$RELAY_CHANNEL_PREFIX$channelId"
}
