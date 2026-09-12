package com.ruirui.findme.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class DoubleRatchetSessionTest {

    private suspend fun setupSessions(): Pair<DoubleRatchetSession, DoubleRatchetSession> {
        val crypto = CryptoKotlinAdapter()
        val sharedSecret = ByteArray(32) { 1 } // Dummy X3DH master secret
        val bobPreKey = crypto.generateX25519KeyPair()

        val aliceSession = DoubleRatchetSession.initAlice(
            sharedSecret = sharedSecret,
            bobSignedPreKeyPub = bobPreKey.publicKey,
            crypto = crypto
        )

        val bobSession = DoubleRatchetSession.initBob(
            sharedSecret = sharedSecret,
            bobRatchetKeyPair = bobPreKey,
            crypto = crypto
        )

        return Pair(aliceSession, bobSession)
    }

    @Test
    fun testPingPongMessaging() = runTest {
        val (alice, bob) = setupSessions()

        // Alice to Bob
        val msg1 = alice.encrypt("Hello Bob".encodeToByteArray())
        val dec1 = bob.decrypt(msg1)
        assertEquals("Hello Bob", dec1.decodeToString())

        // Bob to Alice
        val msg2 = bob.encrypt("Hello Alice".encodeToByteArray())
        val dec2 = alice.decrypt(msg2)
        assertEquals("Hello Alice", dec2.decodeToString())

        // Alice to Bob again
        val msg3 = alice.encrypt("How are you?".encodeToByteArray())
        val dec3 = bob.decrypt(msg3)
        assertEquals("How are you?", dec3.decodeToString())
    }

    @Test
    fun testMultipleMessagesSameChain() = runTest {
        val (alice, bob) = setupSessions()

        val msg1 = alice.encrypt("Message 1".encodeToByteArray())
        val msg2 = alice.encrypt("Message 2".encodeToByteArray())
        val msg3 = alice.encrypt("Message 3".encodeToByteArray())

        assertEquals("Message 1", bob.decrypt(msg1).decodeToString())
        assertEquals("Message 2", bob.decrypt(msg2).decodeToString())
        assertEquals("Message 3", bob.decrypt(msg3).decodeToString())
    }

    @Test
    fun testOutOfOrderMessages() = runTest {
        val (alice, bob) = setupSessions()

        val msg1 = alice.encrypt("First".encodeToByteArray())
        val msg2 = alice.encrypt("Second".encodeToByteArray())
        val msg3 = alice.encrypt("Third".encodeToByteArray())

        // Bob receives out of order: 3, 1, 2
        assertEquals("Third", bob.decrypt(msg3).decodeToString())
        assertEquals("First", bob.decrypt(msg1).decodeToString())
        assertEquals("Second", bob.decrypt(msg2).decodeToString())
    }

    @Test
    fun testDroppedMessageAndRatchetTurn() = runTest {
        val (alice, bob) = setupSessions()

        val msg1 = alice.encrypt("Msg 1".encodeToByteArray())
        val msg2 = alice.encrypt("Msg 2".encodeToByteArray())
        val msg3 = alice.encrypt("Msg 3".encodeToByteArray())

        // Bob receives 1 and 3 (2 is dropped/delayed)
        assertEquals("Msg 1", bob.decrypt(msg1).decodeToString())
        assertEquals("Msg 3", bob.decrypt(msg3).decodeToString())

        // Bob replies, causing a DH Ratchet turn on Alice's side
        val bobReply = bob.encrypt("Bob Reply".encodeToByteArray())
        assertEquals("Bob Reply", alice.decrypt(bobReply).decodeToString())

        // Bob finally receives message 2 late, after he has already ratcheted
        // It should be successfully decrypted from the skippedMessageKeys buffer
        assertEquals("Msg 2", bob.decrypt(msg2).decodeToString())
    }

    @Test
    fun testMaxSkipExceededThrowsException() = runTest {
        val (alice, bob) = setupSessions()

        val validMsg = alice.encrypt("Valid".encodeToByteArray())
        
        // Forge a message that skips more than MAX_SKIP
        val forgedMsg = validMsg.copy(msgNumber = DoubleRatchetSession.MAX_SKIP + 5)

        assertFailsWith<IllegalArgumentException> {
            bob.decrypt(forgedMsg)
        }
    }
}
