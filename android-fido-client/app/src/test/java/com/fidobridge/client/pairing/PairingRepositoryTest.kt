package com.fidobridge.client.pairing

import com.fidobridge.client.crypto.NoiseSession
import com.fidobridge.client.protocol.Protocol
import com.fidobridge.client.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRepositoryTest {

    private val channel = "0123456789abcdef0123456789abcdef"
    private val daemonPublic = NoiseSession.staticPublicKey(ByteArray(32) { (it + 1).toByte() })
    private val pubkeyEncoded = Base64.encodeUrl(daemonPublic)
    private val expectedChannelId = "3eb1bd439947eb762998e566ccc2e099"

    private fun store() = FakeIdentityStore()

    private fun repo() = PairingRepository(store())

    private fun uri(
        channel: String = this.channel,
        pubkey: String = this.pubkeyEncoded,
        token: String? = null,
        v: String? = null
    ): String = "fidobridge://pair?channel=$channel&pubkey=$pubkey" +
        (token?.let { "&token=$it" } ?: "") +
        (v?.let { "&v=$it" } ?: "")

    @Test
    fun `valid uri parses into pairing info with derived channel_id`() {
        val result = repo().parseUri(uri())

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals(channel, info.channel)
        assertEquals(expectedChannelId, info.channelId)
        assertArrayEquals(daemonPublic, info.daemonStaticPublic)
    }

    @Test
    fun `missing channel is rejected`() {
        val result = repo().parseUri("fidobridge://pair?pubkey=$pubkeyEncoded")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedPairingUriException)
    }

    @Test
    fun `missing pubkey is rejected`() {
        val result = repo().parseUri("fidobridge://pair?channel=$channel")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedPairingUriException)
    }

    @Test
    fun `legacy key param is rejected`() {
        // v2 URIs carrying `key=` are not accepted in v3.
        val result = repo().parseUri("fidobridge://pair?channel=$channel&key=$pubkeyEncoded")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedPairingUriException)
    }

    @Test
    fun `non-base64url pubkey is rejected`() {
        val result = repo().parseUri(uri(pubkey = "!!!!not-base64!!!!"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedPairingUriException)
    }

    @Test
    fun `wrong pubkey length is rejected`() {
        val shortKey = Base64.encodeUrl(ByteArray(16) { it.toByte() })

        val result = repo().parseUri(uri(pubkey = shortKey))

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
    fun `absent version defaults to current`() {
        val result = repo().parseUri(uri(v = null))

        assertTrue(result.isSuccess)
        assertEquals(Protocol.VERSION, 3)
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
    fun `pair generates and stores the phone static key`() {
        val identityStore = store()
        val repository = PairingRepository(identityStore)
        val info = repository.parseUri(uri()).getOrThrow()

        repository.pair(info)

        assertNotNull(identityStore.loadPhoneStaticPrivate())
        assertEquals(NoiseSession.STATIC_KEY_BYTES, identityStore.loadPhoneStaticPrivate()!!.size)
        assertArrayEquals(daemonPublic, identityStore.loadDaemonStaticPublic())
        assertEquals(info.channelId, identityStore.loadChannelId())
    }

    @Test
    fun `isPaired reflects persisted state`() {
        val identityStore = store()
        val repository = PairingRepository(identityStore)

        assertFalse(repository.isPaired)

        val info = repository.parseUri(uri()).getOrThrow()
        repository.pair(info)

        assertTrue(repository.isPaired)
    }

    @Test
    fun `token is parsed from uri`() {
        val result = repo().parseUri(uri(token = "my-jwt-token"))

        assertTrue(result.isSuccess)
        assertEquals("my-jwt-token", result.getOrThrow().relayToken)
    }

    @Test
    fun `token defaults to null when absent`() {
        val result = repo().parseUri(uri())

        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrThrow().relayToken)
    }

    @Test
    fun `empty token is treated as absent`() {
        val result = repo().parseUri(uri(token = ""))

        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrThrow().relayToken)
    }

    @Test
    fun `store round-trips the relay token`() {
        val identityStore = store()
        val repository = PairingRepository(identityStore)
        val info = repository.parseUri(uri(token = "test-token")).getOrThrow()

        repository.pair(info)

        assertEquals("test-token", identityStore.loadRelayToken())
    }

    @Test
    fun `store clears relay token when absent`() {
        val identityStore = store()
        val repository = PairingRepository(identityStore)

        repository.pair(repository.parseUri(uri(token = "old-token")).getOrThrow())
        assertEquals("old-token", identityStore.loadRelayToken())

        repository.pair(repository.parseUri(uri()).getOrThrow())
        assertEquals(null, identityStore.loadRelayToken())
    }
}