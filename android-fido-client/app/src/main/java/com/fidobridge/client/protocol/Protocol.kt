package com.fidobridge.client.protocol

object Protocol {
    const val VERSION = 3
    const val SCHEME = "fidobridge"
    const val PAIR_HOST = "pair"
    const val RELAY_CHANNEL_PREFIX = "fidobridge."

    // Noise IK transport (PROTOCOL.md §1, §6): the phone is the initiator.
    const val NOISE_PROTOCOL_NAME = "Noise_IK_25519_AESGCM_SHA256"
    val NOISE_PROLOGUE: ByteArray = "FIDO2_BRIDGE_V2".encodeToByteArray()
    const val STATIC_KEY_BYTES = 32

    // Wire envelope kinds (PROTOCOL.md §3).
    const val KIND_IK1 = "ik1"
    const val KIND_IK2 = "ik2"
    const val KIND_DATA = "data"
    val ENVELOPE_KINDS: Set<String> = setOf(KIND_IK1, KIND_IK2, KIND_DATA)

    fun relayChannel(channelId: String): String = "$RELAY_CHANNEL_PREFIX$channelId"
}