package com.ruirui.findme.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoKotlinAdapterTest {

    private val crypto = CryptoKotlinAdapter()

    @Test
    fun testGenerateAndVerifyEd25519Signature() = runTest {
        // 1. Generate KeyPair
        val keyPair = crypto.generateEd25519KeyPair()
        val message = "Hello FindMe!".encodeToByteArray()

        // 2. Sign
        val signature = crypto.sign(keyPair.privateKey, message)

        // 3. Verify
        val isValid = crypto.verify(keyPair.publicKey, message, signature)
        assertTrue(isValid, "Signature should be valid for the correct public key and message")

        // 4. Verify Failure on tampered message
        val tamperedMessage = "Hello FindMe?".encodeToByteArray()
        val isInvalid = crypto.verify(keyPair.publicKey, tamperedMessage, signature)
        assertFalse(isInvalid, "Signature should be invalid for a tampered message")
    }

    @Test
    fun testX25519DiffieHellmanAgreement() = runTest {
        // 1. Generate keys for Alice and Bob
        val aliceKeys = crypto.generateX25519KeyPair()
        val bobKeys = crypto.generateX25519KeyPair()

        // 2. Compute shared secrets
        val aliceSharedSecret = crypto.calculateDhAgreement(aliceKeys.privateKey, bobKeys.publicKey)
        val bobSharedSecret = crypto.calculateDhAgreement(bobKeys.privateKey, aliceKeys.publicKey)

        // 3. Assert they match
        assertEquals(32, aliceSharedSecret.size, "X25519 shared secret should be 32 bytes")
        assertTrue(
            aliceSharedSecret.contentEquals(bobSharedSecret),
            "Alice and Bob should compute the exact same shared secret"
        )
    }

    @Test
    fun testAesGcmEncryptionAndDecryption() = runTest {
        // In the double ratchet, message keys are 32 bytes (256-bit)
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "Top Secret Location Data".encodeToByteArray()
        val associatedData = "Header(N=1,PN=0)".encodeToByteArray()

        // 1. Encrypt
        val ciphertext = crypto.encryptAesGcm(key, plaintext, associatedData)

        // 2. Decrypt
        val decrypted = crypto.decryptAesGcm(key, ciphertext, associatedData)

        // 3. Assert
        assertTrue(
            plaintext.contentEquals(decrypted),
            "Decrypted plaintext should match original"
        )
    }

    @Test
    fun testHkdfDerivation() = runTest {
        val ikm = "initial-keying-material".encodeToByteArray()
        val salt = ByteArray(32)
        val info = "FindMe-Test".encodeToByteArray()

        val derived1 = crypto.hkdf(ikm, salt, info, 32)
        val derived2 = crypto.hkdf(ikm, salt, info, 32)

        assertEquals(32, derived1.size)
        assertTrue(derived1.contentEquals(derived2), "HKDF should be deterministic")
    }
}
