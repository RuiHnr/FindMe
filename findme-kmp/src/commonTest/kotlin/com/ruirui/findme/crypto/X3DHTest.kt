package com.ruirui.findme.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

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
            aliceIdentityPrivateKey = aliceIdentity.privateKey,
            aliceBasePrivateKey = aliceBase.privateKey,
            bobIdentityKey = bobIdentity.publicKey,
            bobSignedPreKey = bobSignedPreKey.publicKey,
            bobOneTimePreKey = bobOneTimePreKey.publicKey
        )

        // 3. Bob computes the shared secret (as the receiver)
        val bobMasterSecret = receiveX3DH(
            crypto = crypto,
            bobIdentityPrivateKey = bobIdentity.privateKey,
            bobSignedPreKeyPrivate = bobSignedPreKey.privateKey,
            bobOneTimePreKeyPrivate = bobOneTimePreKey.privateKey,
            aliceIdentityPublicKey = aliceIdentity.publicKey,
            aliceBasePublicKey = aliceBase.publicKey
        )

        // 4. ASSERT: The entire point of Diffie-Hellman - they must perfectly match!
        assertTrue(
            aliceMasterSecret.contentEquals(bobMasterSecret),
            "Master secrets do not match! The real Diffie-Hellman math failed."
        )
    }
}