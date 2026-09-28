package com.fidobridge.client.pairing

import com.fidobridge.client.crypto.SessionKey
import com.fidobridge.client.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRepositoryTest {

    private val channel = "0123456789abcdef0123456789abcdef"
    private val key = SessionKey.fromBytes(ByteArray(32) { it.toByte() })
    private val keyEncoded = Base64.encodeUrl(key.bytes)
    private val expectedChannelId = "3eb1bd439947eb762998e566ccc2e099"

    private fun store() = FakeSessionKeyStore()

    private fun repo() = PairingRepository(store())

    private fun uri(
        channel: String = this.channel,
        key: String = this.keyEncoded,
        token: String? = null,
        v: String? = null
    ): String = "fidobridge://pair?channel=$channel&key=$key" +
        (token?.let { "&token=$it" } ?: "") +
        (v?.let { "&v=$it" } ?: "")

    @Test
    fun `valid uri parses into pairing info with derived channel_id`() {
        val result = repo().parseUri(uri())

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals(channel, info.channel)
        assertEquals(expectedChannelId, info.channelId)
        assertArrayEquals(key.bytes, info.key.bytes)
    }

    @Test
    fun `missing channel is rejected`() {
        val result = repo().parseUri("fidobridge://pair?key=$keyEncoded")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedPairingUriException)
    }

    @Test
    fun `missing key is rejected`() {
        val result = repo().parseUri("fidobridge://pair?channel=$channel")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedPairingUriException)
    }

    @Test
    fun `non-base64url key is rejected`() {
        val result = repo().parseUri(uri(key = "!!!!not-base64!!!!"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedPairingUriException)
    }

    @Test
    fun `wrong key length is rejected`() {
        val shortKey = Base64.encodeUrl(ByteArray(16) { it.toByte() })

        val result = repo().parseUri(uri(key = shortKey))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedPairingUriException)
    }

    @Test
    fun `uppercase channel hex is rejected`() {
        val result = repo().parseUri(uri(channel = channel.uppercase()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedPairingUriException)
    }

    @Test
    fun `absent version defaults to 1`() {
        val result = repo().parseUri(uri(v = null))

        assertTrue(result.isSuccess)
    }

    @Test
    fun `mismatched version is rejected with VersionMismatchException`() {
        val result = repo().parseUri(uri(v = "99"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VersionMismatchException)
    }

    @Test
    fun `unknown parameter is rejected`() {
        val result = repo().parseUri(uri() + "&foo=bar")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedPairingUriException)
    }

    @Test
    fun `duplicate parameter is rejected`() {
        val result = repo().parseUri(uri() + "&channel=$channel")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedPairingUriException)
    }

    @Test
    fun `store round-trips the key and channel id`() {
        val sessionKeyStore = store()
        val repository = PairingRepository(sessionKeyStore)
        val info = repository.parseUri(uri()).getOrThrow()

        repository.pair(info)

        assertArrayEquals(key.bytes, sessionKeyStore.loadKey()!!.bytes)
        assertEquals(info.channelId, sessionKeyStore.loadChannelId())
    }

    @Test
    fun `isPaired reflects persisted state`() {
        val sessionKeyStore = store()
        val repository = PairingRepository(sessionKeyStore)

        assertFalse(repository.isPaired)

        val info = repository.parseUri(uri()).getOrThrow()
        repository.pair(info)

        assertTrue(repository.isPaired)
    }

    @Test
    fun `token is parsed from uri`() {
        val result = repo().parseUri(uri(token = "my-jwt-token"))

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals("my-jwt-token", info.relayToken)
    }

    @Test
    fun `token defaults to null when absent`() {
        val result = repo().parseUri(uri())

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals(null, info.relayToken)
    }

    @Test
    fun `empty token is treated as absent`() {
        val result = repo().parseUri(uri(token = ""))

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals(null, info.relayToken)
    }

    @Test
    fun `store round-trips the relay token`() {
        val sessionKeyStore = store()
        val repository = PairingRepository(sessionKeyStore)
        val info = repository.parseUri(uri(token = "test-token")).getOrThrow()

        repository.pair(info)

        assertEquals("test-token", sessionKeyStore.loadRelayToken())
    }

    @Test
    fun `store clears relay token when absent`() {
        val sessionKeyStore = store()
        val repository = PairingRepository(sessionKeyStore)

        // First pair with a token
        val withToken = repository.parseUri(uri(token = "old-token")).getOrThrow()
        repository.pair(withToken)
        assertEquals("old-token", sessionKeyStore.loadRelayToken())

        // Then pair without a token
        val withoutToken = repository.parseUri(uri()).getOrThrow()
        repository.pair(withoutToken)
        assertEquals(null, sessionKeyStore.loadRelayToken())
    }
}
