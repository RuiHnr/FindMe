package com.ruirui.findme.crypto

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class X3DHTest {

    @Test
    fun testAliceAndBobDeriveSameMasterSecret() = runTest {
        // Use the REAL crypto adapter for this integration test
        val crypto = CryptoKotlinAdapter()

        // 1. Generate real X25519 keys for both parties
        // Note: For X3DH, ALL keys involved in calculateDhAgreement MUST be X25519 keys.
        // If your Identity Key is Ed25519 (for signing), you should generate a parallel 
        // X25519 Identity Key specifically for the X3DH handshake, or implement XEdDSA.
        val aliceIdentity = crypto.generateX25519KeyPair()
        val aliceBase = crypto.generateX25519KeyPair() // Ephemeral

        val bobIdentity = crypto.generateX25519KeyPair()
        val bobSignedPreKey = crypto.generateX25519KeyPair()
        val bobOneTimePreKey = crypto.generateX25519KeyPair()

        // 2. Alice computes the shared secret (as the initiator)
        val aliceMasterSecret = initX3DH(
            crypto = crypto,
            aliceIdentityKey = aliceIdentity,
            aliceBaseKey = aliceBase,
            bobIdentityKey = bobIdentity.publicKey,
            bobSignedPreKey = bobSignedPreKey.publicKey,
            bobOneTimePreKey = bobOneTimePreKey.publicKey
        )

        // 3. Bob computes the shared secret (as the receiver)
        val bobMasterSecret = receiveX3DHForTest(
            crypto = crypto,
            bobIdentityKey = bobIdentity,
            bobSignedPreKey = bobSignedPreKey,
            bobOneTimePreKey = bobOneTimePreKey,
            aliceIdentityKey = aliceIdentity.publicKey,
            aliceBaseKey = aliceBase.publicKey
        )

        // 4. ASSERT: The entire point of Diffie-Hellman - they must perfectly match!
        assertTrue(
            aliceMasterSecret.contentEquals(bobMasterSecret),
            "Master secrets do not match! The real Diffie-Hellman math failed."
        )
    }

    // Bob's perspective of X3DH. Placed here as a helper to keep production clean.
    private suspend fun receiveX3DHForTest(
        crypto: Crypto,
        bobIdentityKey: KeyPair,
        bobSignedPreKey: KeyPair,
        bobOneTimePreKey: KeyPair?,
        aliceIdentityKey: ByteArray,
        aliceBaseKey: ByteArray
    ): ByteArray {
        val dh1 = crypto.calculateDhAgreement(bobSignedPreKey.privateKey, aliceIdentityKey)
        val dh2 = crypto.calculateDhAgreement(bobIdentityKey.privateKey, aliceBaseKey)
        val dh3 = crypto.calculateDhAgreement(bobSignedPreKey.privateKey, aliceBaseKey)

        var sharedSecret = dh1 + dh2 + dh3
        if (bobOneTimePreKey != null) {
            val dh4 = crypto.calculateDhAgreement(bobOneTimePreKey.privateKey, aliceBaseKey)
            sharedSecret += dh4
        }

        return crypto.hkdf(
            ikm = sharedSecret,
            salt = ByteArray(32),
            info = "FindMe-X3DH".encodeToByteArray(),
            outLength = 32
        )
    }
}