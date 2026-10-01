package com.fidobridge.client.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseSessionTest {

    private val phonePrivate = ByteArray(32) { it.toByte() }
    private val daemonPrivate = ByteArray(32) { (it + 1).toByte() }
    private val daemonPublic = NoiseSession.staticPublicKey(daemonPrivate)

    private fun handshake(): Pair<NoiseSession, TestResponderSession> {
        val phone = NoiseSession.create(phonePrivate, daemonPublic)
        val daemon = TestResponderSession(daemonPrivate)
        val ik2 = daemon.receiveIk1(phone.createIk1())
        phone.receiveIk2(ik2)
        return phone to daemon
    }

    @Test
    fun `static key generation returns 32 bytes and derives public deterministically`() {
        val priv = NoiseSession.generateStaticKey()
        assertEquals(32, priv.size)
        assertEquals(32, NoiseSession.staticPublicKey(priv).size)
        assertArrayEquals(NoiseSession.staticPublicKey(priv), NoiseSession.staticPublicKey(priv))
    }

    @Test
    fun `create validates key sizes`() {
        assertThrows(IllegalArgumentException::class.java) {
            NoiseSession.create(ByteArray(16), daemonPublic)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoiseSession.create(phonePrivate, ByteArray(16))
        }
    }

    @Test
    fun `ik1 is 96 bytes and ik2 is 48 bytes`() {
        // Cross-peer fixture: message sizes are pinned (PROTOCOL.md §7).
        val phone = NoiseSession.create(phonePrivate, daemonPublic)
        val daemon = TestResponderSession(daemonPrivate)
        val ik1 = phone.createIk1()
        assertEquals(96, ik1.size)
        val ik2 = daemon.receiveIk1(ik1)
        assertEquals(48, ik2.size)
    }

    @Test
    fun `handshake round trips transport both directions`() {
        val (phone, daemon) = handshake()
        assertTrue(phone.isReady)

        assertArrayEquals(daemon.decrypt(phone.encrypt("request".toByteArray())), "request".toByteArray())
        assertArrayEquals(phone.decrypt(daemon.encrypt("response".toByteArray())), "response".toByteArray())
    }

    @Test
    fun `tampered ciphertext raises AuthenticationException`() {
        val (phone, daemon) = handshake()
        val ciphertext = daemon.encrypt("secret".toByteArray())
        val tampered = ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows(NoiseSession.AuthenticationException::class.java) {
            phone.decrypt(tampered)
        }
    }

    @Test
    fun `replayed ciphertext raises AuthenticationException`() {
        val (phone, daemon) = handshake()
        val ciphertext = daemon.encrypt("secret".toByteArray())
        phone.decrypt(ciphertext)
        assertThrows(NoiseSession.AuthenticationException::class.java) {
            phone.decrypt(ciphertext)
        }
    }

    @Test
    fun `wrong daemon static key fails the handshake`() {
        val wrongDaemon = TestResponderSession(ByteArray(32) { (it + 9).toByte() })
        val phone = NoiseSession.create(phonePrivate, daemonPublic)
        val ik1 = phone.createIk1()

        // The responder cannot authenticate a handshake bound to a different
        // daemon static key.
        assertThrows(NoiseSession.AuthenticationException::class.java) {
            wrongDaemon.receiveIk1(ik1)
        }
    }

    @Test
    fun `tampered ik2 fails the handshake on the phone side`() {
        val phone = NoiseSession.create(phonePrivate, daemonPublic)
        val daemon = TestResponderSession(daemonPrivate)
        val ik1 = phone.createIk1()
        val ik2 = daemon.receiveIk1(ik1)
        val tampered = ik2.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertThrows(NoiseSession.AuthenticationException::class.java) {
            phone.receiveIk2(tampered)
        }
    }

    @Test
    fun `encrypt and decrypt before handshake throw`() {
        val phone = NoiseSession.create(phonePrivate, daemonPublic)
        assertThrows(IllegalStateException::class.java) {
            phone.encrypt(ByteArray(1))
        }
        assertThrows(IllegalStateException::class.java) {
            phone.decrypt(ByteArray(1))
        }
        assertFalse(phone.isReady)
    }
}