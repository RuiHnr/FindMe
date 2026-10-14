package com.ruirui.orbit.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SafetyNumberHelperTest {

    @Test
    fun testSafetyNumberSymmetry() = runTest {
        val aliceId = "alice_uuid_123"
        val aliceKey = "AlicePublicDHKeyBase64_111"
        val bobId = "bob_uuid_456"
        val bobKey = "BobPublicDHKeyBase64_222"

        // Alice computes safety number
        val aliceView = SafetyNumberHelper.computeSafetyNumber(
            aliceId = aliceId,
            aliceIdentityPubKeyDh = aliceKey,
            bobId = bobId,
            bobIdentityPubKeyDh = bobKey
        )

        // Bob computes safety number (caller perspective swapped)
        val bobView = SafetyNumberHelper.computeSafetyNumber(
            aliceId = bobId,
            aliceIdentityPubKeyDh = bobKey,
            bobId = aliceId,
            bobIdentityPubKeyDh = aliceKey
        )

        assertEquals(aliceView, bobView, "Safety numbers must be identical regardless of who computes it")
    }

    @Test
    fun testSafetyNumberFormatting() = runTest {
        val number = SafetyNumberHelper.computeSafetyNumber(
            aliceId = "alice",
            aliceIdentityPubKeyDh = "keyA",
            bobId = "bob",
            bobIdentityPubKeyDh = "keyB"
        )

        val blocks = number.split(" ")
        assertEquals(12, blocks.size, "Safety number must consist of 12 digit blocks")
        blocks.forEach { block ->
            assertEquals(5, block.length, "Each block must be exactly 5 digits")
            block.forEach { char ->
                assertTrue(char.isDigit(), "Each character in safety number must be a digit")
            }
        }
    }

    @Test
    fun testSafetyNumberSensitivityToKeyChanges() = runTest {
        val baseNumber = SafetyNumberHelper.computeSafetyNumber(
            aliceId = "alice",
            aliceIdentityPubKeyDh = "original_key_dh",
            bobId = "bob",
            bobIdentityPubKeyDh = "bob_key_dh"
        )

        val swappedKeyNumber = SafetyNumberHelper.computeSafetyNumber(
            aliceId = "alice",
            aliceIdentityPubKeyDh = "attacker_swapped_key_dh",
            bobId = "bob",
            bobIdentityPubKeyDh = "bob_key_dh"
        )

        assertNotEquals(baseNumber, swappedKeyNumber, "Safety number must change when a public key changes")
    }
}
