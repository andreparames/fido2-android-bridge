package com.fidobridge.client.pairing

sealed class PairingException(message: String) : Exception(message)

class MalformedPairingUriException(message: String) : PairingException(message)

class VersionMismatchException : PairingException("Protocol version mismatch")

class SubscriptionRequiredException :
    PairingException("A Gatebridge subscription is required to use the managed relay")

class ChannelActivationException(cause: Throwable) :
    PairingException(cause.message ?: "Relay channel activation failed")
