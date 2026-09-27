package com.fidobridge.client.pairing

sealed class PairingException(message: String) : Exception(message)

class MalformedPairingUriException(message: String) : PairingException(message)

class VersionMismatchException : PairingException("Protocol version mismatch")
